#!/usr/bin/env bash
set -euo pipefail

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required but was not found in PATH." >&2
  exit 1
fi

python3 - "$@" <<'PY'
import argparse
import collections
import datetime as dt
import os
import re
import sys
import tempfile
import xml.sax
from xml.sax.handler import ContentHandler, feature_external_ges, feature_external_pes
from xml.sax.saxutils import XMLGenerator
from xml.sax.xmlreader import AttributesImpl

try:
    from xml.sax import SAXNotRecognizedException, SAXNotSupportedException
except ImportError:  # pragma: no cover
    SAXNotRecognizedException = SAXNotSupportedException = Exception


DEFAULT_AUTO_PARENT = "/asx:abap/asx:values"
DEFAULT_AUTO_ITEM = "item"

BOOLEAN_RE = re.compile(r"^(?:true|false)$", re.I)
INTEGER_RE = re.compile(r"^[+-]?\d+$")
DECIMAL_RE = re.compile(r"^[+-]?\d+\.\d+$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DATETIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}[T ][0-9:.+-]+Z?$")
UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


def normalize_path(value):
    value = value.strip()
    if not value:
        raise argparse.ArgumentTypeError("XML path cannot be empty")
    if not value.startswith("/"):
        value = "/" + value
    return value.rstrip("/")


def path_parts(path):
    return [part for part in path.strip("/").split("/") if part]


def is_auto_target_path(path, parent_path, item_name):
    current_parts = path_parts(path)
    parent_parts = path_parts(parent_path)
    return (
        len(current_parts) == len(parent_parts) + 2
        and current_parts[: len(parent_parts)] == parent_parts
        and current_parts[-1] == item_name
    )


def set_safe_feature(parser, feature, value):
    try:
        parser.setFeature(feature, value)
    except (SAXNotRecognizedException, SAXNotSupportedException):
        pass


def encode_letters(number):
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    result = []
    while number:
        number, remainder = divmod(number - 1, len(alphabet))
        result.append(alphabet[remainder])
    return "".join(reversed(result or ["A"]))


class SyntheticValues:
    def __init__(self):
        self.safe_by_original = collections.defaultdict(dict)
        self.generated_values = collections.defaultdict(set)

    def make(self, field_key, original):
        normalized = " ".join(original.split())
        if not normalized:
            return ""

        safe_values = self.safe_by_original[field_key]
        if normalized in safe_values:
            return safe_values[normalized]

        number = len(safe_values) + 1
        candidate = self.make_candidate(normalized, number)

        # Avoid accidentally reproducing an original value and avoid collisions
        # between different source values in the same field whenever possible.
        generated = self.generated_values[field_key]
        attempts = 0
        while (candidate == normalized or candidate in generated) and attempts < 100:
            number += 1
            candidate = self.make_candidate(normalized, number)
            attempts += 1
        generated.add(candidate)
        safe_values[normalized] = candidate
        return candidate

    @staticmethod
    def make_candidate(original, number):
        if UUID_RE.match(original):
            return f"00000000-0000-4000-8000-{number:012x}"[-36:]

        if DATETIME_RE.match(original):
            moment = dt.datetime(2000, 1, 1) + dt.timedelta(seconds=number)
            value = moment.strftime("%Y-%m-%dT%H:%M:%SZ")
            if len(original) == len(value):
                return value
            return (value + "0" * len(original))[: len(original)]

        if DATE_RE.match(original):
            return (dt.date(2000, 1, 1) + dt.timedelta(days=number)).isoformat()

        if BOOLEAN_RE.match(original):
            return "false" if number % 2 else "true"

        if INTEGER_RE.match(original):
            width = len(original.lstrip("+-"))
            modulus = 10 ** width
            value = (number * 104729 + 314159) % modulus
            return str(value).zfill(width)

        if DECIMAL_RE.match(original):
            unsigned = original.lstrip("+-")
            integer_part, fraction_part = unsigned.split(".", 1)
            integer_width = len(integer_part)
            fraction_width = len(fraction_part)
            integer_value = (number * 7919 + 2718) % (10 ** integer_width)
            fraction_value = (number * 104729 + 1618) % (10 ** fraction_width)
            return (
                str(integer_value).zfill(integer_width)
                + "."
                + str(fraction_value).zfill(fraction_width)
            )

        width = len(original)
        token = "SAMPLE" + encode_letters(number)
        if width <= len(token):
            return token[-width:]
        return token + "X" * (width - len(token))


class SafeSampleWriter(ContentHandler):
    def __init__(self, output, args):
        super().__init__()
        self.args = args
        self.generator = XMLGenerator(output, encoding="utf-8")
        self.path_stack = []
        self.output_frames = []
        self.skip_depth = 0
        self.target_paths = set(args.path)
        self.auto_discovery = not args.path
        self.stats = collections.OrderedDict()
        self.synthetic = SyntheticValues()
        self.text_values_replaced = 0
        self.attribute_values_replaced = 0
        self.namespace_values_replaced = 0

    def startDocument(self):
        self.generator.startDocument()

    def endDocument(self):
        self.generator.endDocument()

    def current_path(self):
        return "/" + "/".join(self.path_stack)

    def is_target(self, path):
        if path in self.target_paths:
            return True
        if self.auto_discovery and is_auto_target_path(
            path, self.args.auto_parent, self.args.auto_item
        ):
            self.target_paths.add(path)
            return True
        return False

    def startElement(self, name, attrs):
        self.path_stack.append(name)

        if self.skip_depth:
            self.skip_depth += 1
            return

        self.flush_text()
        path = self.current_path()

        if self.is_target(path):
            stats = self.stats.setdefault(path, {"seen": 0, "kept": 0})
            stats["seen"] += 1
            if stats["kept"] >= self.args.items_per_collection:
                self.skip_depth = 1
                return
            stats["kept"] += 1

        safe_attrs = {}
        for attr_name in attrs.getNames():
            original = attrs.getValue(attr_name)
            if attr_name == "xmlns" or attr_name.startswith("xmlns:"):
                prefix = attr_name.split(":", 1)[1] if ":" in attr_name else "default"
                safe_attrs[attr_name] = f"urn:sanitized:{prefix}"
                self.namespace_values_replaced += 1
            else:
                field_key = f"attribute:{path}@{attr_name}"
                safe_attrs[attr_name] = self.synthetic.make(field_key, original)
                if original:
                    self.attribute_values_replaced += 1

        self.generator.startElement(name, AttributesImpl(safe_attrs))
        self.output_frames.append({"path": path, "text": []})

    def characters(self, content):
        if self.skip_depth or not self.output_frames:
            return
        self.output_frames[-1]["text"].append(content)

    def flush_text(self):
        if not self.output_frames:
            return
        frame = self.output_frames[-1]
        if not frame["text"]:
            return
        original = "".join(frame["text"])
        frame["text"].clear()
        if not original.strip():
            return
        safe_value = self.synthetic.make(f"text:{frame['path']}", original)
        self.generator.characters(safe_value)
        self.text_values_replaced += 1

    def endElement(self, name):
        if self.skip_depth:
            self.skip_depth -= 1
            self.path_stack.pop()
            return

        self.flush_text()
        self.generator.endElement(name)
        self.output_frames.pop()
        self.path_stack.pop()


def make_parser(handler):
    parser = xml.sax.make_parser()
    set_safe_feature(parser, feature_external_ges, False)
    set_safe_feature(parser, feature_external_pes, False)
    parser.setContentHandler(handler)
    return parser


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=(
            "Create a small, shareable XML sample while replacing all source "
            "text and attribute values with synthetic values."
        )
    )
    parser.add_argument(
        "--items-per-collection",
        type=int,
        default=3,
        help="Keep the first N records in every matched collection. Default: 3.",
    )
    parser.add_argument(
        "--path",
        action="append",
        type=normalize_path,
        default=[],
        help=(
            "Absolute record path to sample. Can be repeated. If omitted, paths "
            "are discovered as --auto-parent/*/--auto-item."
        ),
    )
    parser.add_argument(
        "--auto-parent",
        type=normalize_path,
        default=DEFAULT_AUTO_PARENT,
        help=f"Automatic collection parent. Default: {DEFAULT_AUTO_PARENT}.",
    )
    parser.add_argument(
        "--auto-item",
        default=DEFAULT_AUTO_ITEM,
        help=f"Automatic record tag. Default: {DEFAULT_AUTO_ITEM}.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite the output file if it already exists.",
    )
    parser.add_argument("input_xml", help="Source XML file that stays inside the trusted network.")
    parser.add_argument("output_xml", help="Sanitized XML sample safe to inspect and test.")
    args = parser.parse_args(argv)

    if args.items_per_collection < 1:
        parser.error("--items-per-collection must be a positive integer")
    if not args.auto_item or "/" in args.auto_item:
        parser.error("--auto-item must be a single XML tag name")
    if not os.path.isfile(args.input_xml):
        parser.error(f"Input file does not exist: {args.input_xml}")

    input_path = os.path.realpath(args.input_xml)
    output_path = os.path.realpath(args.output_xml)
    if input_path == output_path:
        parser.error("Input and output XML paths must be different")
    if os.path.exists(args.output_xml) and not args.force:
        parser.error(f"Output file already exists: {args.output_xml}. Use --force to overwrite.")
    output_parent = os.path.dirname(os.path.abspath(args.output_xml)) or "."
    if not os.path.isdir(output_parent):
        parser.error(f"Output directory does not exist: {output_parent}")

    return args


def main(argv):
    args = parse_args(argv)
    output_parent = os.path.dirname(os.path.abspath(args.output_xml)) or "."
    fd, temporary_path = tempfile.mkstemp(
        prefix=".safe-xml-", suffix=".tmp", dir=output_parent
    )

    try:
        with os.fdopen(fd, "wb") as output:
            handler = SafeSampleWriter(output, args)
            make_parser(handler).parse(args.input_xml)

        if not handler.stats:
            if args.path:
                expected = ", ".join(args.path)
                raise RuntimeError(f"No records found for path(s): {expected}")
            raise RuntimeError(
                "No automatic record paths found. Expected paths like "
                f"{args.auto_parent}/*/{args.auto_item}"
            )

        os.replace(temporary_path, args.output_xml)
    except Exception:
        try:
            os.unlink(temporary_path)
        except FileNotFoundError:
            pass
        raise

    print("XML_SAFE_SAMPLE_V1", file=sys.stderr)
    print(f"output_xml: {args.output_xml}", file=sys.stderr)
    print(f"output_size_bytes: {os.path.getsize(args.output_xml)}", file=sys.stderr)
    print(f"text_values_replaced: {handler.text_values_replaced}", file=sys.stderr)
    print(f"attribute_values_replaced: {handler.attribute_values_replaced}", file=sys.stderr)
    print(f"namespace_values_replaced: {handler.namespace_values_replaced}", file=sys.stderr)
    print("sampled_paths:", file=sys.stderr)
    for path, stats in handler.stats.items():
        print(
            f"  - {path} seen={stats['seen']} kept={stats['kept']}",
            file=sys.stderr,
        )


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (RuntimeError, OSError, xml.sax.SAXException) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
PY
