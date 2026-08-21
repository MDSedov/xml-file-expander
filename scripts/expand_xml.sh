#!/usr/bin/env bash
set -euo pipefail

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required but was not found in PATH." >&2
  exit 1
fi

python3 - "$@" <<'PY'
import argparse
import collections
import io
import os
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
import xml.sax
from xml.sax.handler import ContentHandler, feature_external_ges, feature_external_pes
from xml.sax.saxutils import XMLGenerator

try:
    from xml.sax import SAXNotRecognizedException, SAXNotSupportedException
except ImportError:  # pragma: no cover
    SAXNotRecognizedException = SAXNotSupportedException = Exception


DEFAULT_AUTO_PARENT = "/asx:abap/asx:values"
DEFAULT_AUTO_ITEM = "item"


def fmt_int(value):
    return f"{value:,}"


def fmt_bytes(value):
    if value >= 1024 ** 3:
        return f"{fmt_int(value)} bytes ({value / (1024 ** 3):.3f} GiB)"
    if value >= 1024 ** 2:
        return f"{fmt_int(value)} bytes ({value / (1024 ** 2):.1f} MiB)"
    return f"{fmt_int(value)} bytes"


def parse_size(value):
    raw = str(value).strip()
    match = re.match(r"^(\d+(?:\.\d+)?)\s*([kmgt]?i?b?|[kmgt])?$", raw, re.I)
    if not match:
        raise argparse.ArgumentTypeError(f"Invalid size: {value}")

    number = float(match.group(1))
    unit = (match.group(2) or "b").lower()
    multipliers = {
        "b": 1,
        "": 1,
        "k": 1000,
        "kb": 1000,
        "m": 1000 ** 2,
        "mb": 1000 ** 2,
        "g": 1000 ** 3,
        "gb": 1000 ** 3,
        "t": 1000 ** 4,
        "tb": 1000 ** 4,
        "kib": 1024,
        "mib": 1024 ** 2,
        "gib": 1024 ** 3,
        "tib": 1024 ** 4,
    }
    if unit not in multipliers:
        raise argparse.ArgumentTypeError(f"Invalid size unit: {unit}")
    return int(number * multipliers[unit])


def normalize_path(path):
    path = path.strip()
    if not path:
        raise argparse.ArgumentTypeError("XML path cannot be empty")
    if not path.startswith("/"):
        path = "/" + path
    return path.rstrip("/")


def path_parts(path):
    return [part for part in path.strip("/").split("/") if part]


def path_from_stack(stack):
    return "/" + "/".join(stack)


def is_auto_target_path(path, parent_path, item_name):
    if not parent_path:
        return False

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


class CountingWriter:
    def __init__(self, sink=None, encoding="utf-8"):
        self.sink = sink
        self.encoding = encoding
        self.bytes = 0

    def write(self, value):
        data = value.encode(self.encoding) if isinstance(value, str) else value
        self.bytes += len(data)
        if self.sink is not None:
            self.sink.write(data)

    def flush(self):
        if self.sink is not None:
            self.sink.flush()


class ExpansionPreflight(ContentHandler):
    def __init__(self, target_paths, auto_parent, auto_item, encoding):
        super().__init__()
        self.target_paths = set(target_paths)
        self.auto_parent = auto_parent
        self.auto_item = auto_item
        self.auto_discovery = not target_paths
        self.stack = []
        self.writer = CountingWriter(encoding=encoding)
        self.generator = XMLGenerator(self.writer, encoding=encoding)
        self.target_counts = collections.OrderedDict((path, 0) for path in target_paths)
        self.target_bytes = collections.OrderedDict((path, 0) for path in target_paths)
        self.total_target_bytes = 0
        self.buffer_generator = None
        self.buffer_io = None
        self.buffer_depth = 0
        self.buffer_path = None

    def should_capture_path(self, path):
        if path in self.target_paths:
            return True

        if self.auto_discovery and is_auto_target_path(path, self.auto_parent, self.auto_item):
            self.target_paths.add(path)
            self.target_counts[path] = 0
            self.target_bytes[path] = 0
            return True

        return False

    def startDocument(self):
        self.generator.startDocument()

    def endDocument(self):
        self.generator.endDocument()

    def startElement(self, name, attrs):
        self.stack.append(name)
        current_path = path_from_stack(self.stack)

        self.generator.startElement(name, attrs)

        if self.buffer_generator is not None:
            self.buffer_generator.startElement(name, attrs)
            self.buffer_depth += 1
            return

        if self.should_capture_path(current_path):
            self.buffer_io = io.StringIO()
            self.buffer_generator = XMLGenerator(self.buffer_io, encoding="utf-8")
            self.buffer_generator.startElement(name, attrs)
            self.buffer_depth = 1
            self.buffer_path = current_path

    def characters(self, content):
        self.generator.characters(content)
        if self.buffer_generator is not None:
            self.buffer_generator.characters(content)

    def endElement(self, name):
        if self.buffer_generator is not None:
            self.buffer_generator.endElement(name)
            self.buffer_depth -= 1
            if self.buffer_depth == 0:
                fragment = self.buffer_io.getvalue()
                fragment_bytes = len(fragment.encode("utf-8"))
                self.target_counts[self.buffer_path] += 1
                self.target_bytes[self.buffer_path] += fragment_bytes
                self.total_target_bytes += fragment_bytes
                self.buffer_generator = None
                self.buffer_io = None
                self.buffer_path = None

        self.generator.endElement(name)
        self.stack.pop()


class XmlExpander(ContentHandler):
    def __init__(self, args, plan, output_handle):
        super().__init__()
        self.args = args
        self.plan = plan
        self.stack = []
        self.writer = CountingWriter(output_handle, encoding=args.encoding)
        self.generator = XMLGenerator(self.writer, encoding=args.encoding)
        self.target_paths = set(plan["target_paths"])
        self.buffer_generator = None
        self.buffer_io = None
        self.buffer_depth = 0
        self.buffer_path = None
        self.duplicates_written = 0
        self.duplicated_bytes = 0
        self.residual_bytes_written_by_path = collections.Counter()
        self.mutated_fields = 0
        self.duplicate_serial = 0

    def startDocument(self):
        self.generator.startDocument()

    def endDocument(self):
        self.generator.endDocument()

    def startElement(self, name, attrs):
        self.stack.append(name)
        current_path = path_from_stack(self.stack)

        if self.buffer_generator is not None:
            self.buffer_generator.startElement(name, attrs)
            self.buffer_depth += 1
            return

        if current_path in self.target_paths:
            self.buffer_io = io.StringIO()
            self.buffer_generator = XMLGenerator(self.buffer_io, encoding="utf-8")
            self.buffer_generator.startElement(name, attrs)
            self.buffer_depth = 1
            self.buffer_path = current_path
            return

        self.generator.startElement(name, attrs)

    def characters(self, content):
        if self.buffer_generator is not None:
            self.buffer_generator.characters(content)
            return
        self.generator.characters(content)

    def endElement(self, name):
        if self.buffer_generator is not None:
            self.buffer_generator.endElement(name)
            self.buffer_depth -= 1

            if self.buffer_depth == 0:
                fragment = self.buffer_io.getvalue()
                self.writer.write(fragment)
                self.write_duplicates(fragment)
                self.buffer_generator = None
                self.buffer_io = None
                self.buffer_path = None

            self.stack.pop()
            return

        self.generator.endElement(name)
        self.stack.pop()

    def write_duplicates(self, fragment):
        copies = self.plan["full_extra_copies"]
        fragment_bytes = len(fragment.encode("utf-8"))

        if self.plan["fixed_extra_copies"] is not None:
            copies = self.plan["fixed_extra_copies"]
            add_residual = False
        else:
            path_residual_limit = self.plan["residual_extra_bytes_by_path"].get(self.buffer_path, 0)
            add_residual = self.residual_bytes_written_by_path[self.buffer_path] < path_residual_limit

        for _ in range(copies):
            self.write_one_duplicate(fragment)

        if add_residual:
            self.write_one_duplicate(fragment)
            self.residual_bytes_written_by_path[self.buffer_path] += fragment_bytes

    def write_one_duplicate(self, fragment):
        self.duplicate_serial += 1
        duplicate, changed = make_duplicate_fragment(
            fragment,
            self.duplicate_serial,
            self.args.unique_field,
        )
        self.writer.write(duplicate)
        self.duplicates_written += 1
        self.duplicated_bytes += len(duplicate.encode("utf-8"))
        self.mutated_fields += changed


def local_name(tag):
    if "}" in tag:
        return tag.rsplit("}", 1)[1]
    if ":" in tag:
        return tag.rsplit(":", 1)[1]
    return tag


def child_matches(child, expected):
    return child.tag == expected or local_name(child.tag) == expected


def find_relative_elements(root, field_path):
    parts = [part for part in field_path.strip("/").split("/") if part]
    if not parts:
        return []

    current = [root]
    for part in parts:
        next_items = []
        for item in current:
            next_items.extend(child for child in list(item) if child_matches(child, part))
        current = next_items
        if not current:
            break
    return current


def mutate_value(value, serial):
    value = "" if value is None else value
    suffix = f"DUP{serial:07d}"

    if "@" in value and " " not in value:
        local, domain = value.split("@", 1)
        if local and domain:
            return f"{local}.dup{serial:07d}@{domain}"

    number_match = re.match(r"^(.*?)(\d+)$", value)
    if number_match:
        prefix, digits = number_match.groups()
        modulus = 10 ** len(digits)
        new_number = (int(digits) + serial * 104729) % modulus
        return prefix + str(new_number).zfill(len(digits))

    if value:
        return f"{value}_{suffix}"
    return suffix


def make_duplicate_fragment(fragment, serial, unique_fields):
    if not unique_fields:
        return fragment, 0

    try:
        root = ET.fromstring(fragment)
    except ET.ParseError as exc:
        raise RuntimeError(f"Cannot mutate duplicate XML fragment: {exc}") from exc

    changed = 0
    for field_path in unique_fields:
        for element in find_relative_elements(root, field_path):
            element.text = mutate_value(element.text, serial)
            changed += 1

    return ET.tostring(root, encoding="unicode", short_empty_elements=False), changed


def make_parser(handler):
    parser = xml.sax.make_parser()
    set_safe_feature(parser, feature_external_ges, False)
    set_safe_feature(parser, feature_external_pes, False)
    parser.setContentHandler(handler)
    return parser


def distribute_residual_bytes(residual_bytes, target_bytes):
    if residual_bytes <= 0:
        return collections.OrderedDict((path, 0) for path in target_bytes)

    total_bytes = sum(target_bytes.values())
    if total_bytes <= 0:
        return collections.OrderedDict((path, 0) for path in target_bytes)

    distribution = collections.OrderedDict()
    remainders = []
    allocated = 0

    for path, path_bytes in target_bytes.items():
        exact = residual_bytes * path_bytes / total_bytes
        whole = int(exact)
        distribution[path] = whole
        allocated += whole
        remainders.append((exact - whole, path))

    remaining = residual_bytes - allocated
    for _, path in sorted(remainders, reverse=True)[:remaining]:
        distribution[path] += 1

    return distribution


def build_plan(args):
    preflight = ExpansionPreflight(
        args.path,
        args.auto_parent,
        args.auto_item,
        args.encoding,
    )
    make_parser(preflight).parse(args.input_xml)

    if preflight.total_target_bytes <= 0:
        if args.path:
            joined = ", ".join(args.path)
            raise RuntimeError(f"No target XML records found for path(s): {joined}")
        raise RuntimeError(
            "No auto target XML records found. Expected paths like "
            f"{args.auto_parent}/*/{args.auto_item}"
        )

    original_bytes = preflight.writer.bytes
    if args.fixed_extra_copies is not None:
        full_extra_copies = args.fixed_extra_copies
        residual_extra_bytes = 0
        estimated_output_bytes = original_bytes + preflight.total_target_bytes * full_extra_copies
    else:
        extra_needed = max(0, args.target_size - original_bytes)
        full_extra_copies = extra_needed // preflight.total_target_bytes
        residual_extra_bytes = extra_needed - full_extra_copies * preflight.total_target_bytes
        estimated_output_bytes = original_bytes + full_extra_copies * preflight.total_target_bytes + residual_extra_bytes

    residual_extra_bytes_by_path = distribute_residual_bytes(
        int(residual_extra_bytes),
        preflight.target_bytes,
    )

    return {
        "original_bytes": original_bytes,
        "target_paths": list(preflight.target_counts.keys()),
        "target_counts": preflight.target_counts,
        "target_bytes": preflight.target_bytes,
        "total_target_bytes": preflight.total_target_bytes,
        "fixed_extra_copies": args.fixed_extra_copies,
        "full_extra_copies": int(full_extra_copies),
        "residual_extra_bytes": int(residual_extra_bytes),
        "residual_extra_bytes_by_path": residual_extra_bytes_by_path,
        "estimated_output_bytes": int(estimated_output_bytes),
    }


def print_plan(args, plan):
    print("XML_EXPAND_PLAN_V1", file=sys.stderr)
    print(f"input_xml: {args.input_xml}", file=sys.stderr)
    print(f"original_serialized_size: {fmt_bytes(plan['original_bytes'])}", file=sys.stderr)
    print(f"target_size: {fmt_bytes(args.target_size)}", file=sys.stderr)
    print(f"repeatable_record_bytes: {fmt_bytes(plan['total_target_bytes'])}", file=sys.stderr)
    print(f"full_extra_copies_per_record: {plan['full_extra_copies']}", file=sys.stderr)
    print(f"residual_extra_bytes: {fmt_bytes(plan['residual_extra_bytes'])}", file=sys.stderr)
    print(f"estimated_output_size: {fmt_bytes(plan['estimated_output_bytes'])}", file=sys.stderr)
    if args.path:
        print("target_mode: explicit --path", file=sys.stderr)
    else:
        print(f"target_mode: auto {args.auto_parent}/*/{args.auto_item}", file=sys.stderr)
    print("target_paths:", file=sys.stderr)
    for path in plan["target_paths"]:
        bytes_share = plan["target_bytes"][path] / plan["total_target_bytes"] * 100
        residual_for_path = plan["residual_extra_bytes_by_path"].get(path, 0)
        print(
            f"  - {path} count={fmt_int(plan['target_counts'][path])} "
            f"bytes={fmt_bytes(plan['target_bytes'][path])} "
            f"share={bytes_share:.2f}% "
            f"residual={fmt_bytes(residual_for_path)}",
            file=sys.stderr,
        )
    if args.unique_field:
        print("unique_fields:", file=sys.stderr)
        for field in args.unique_field:
            print(f"  - {field}", file=sys.stderr)


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Expand a large XML file by duplicating selected repeated records."
    )
    parser.add_argument(
        "--target-size",
        default="1.5GiB",
        type=parse_size,
        help="Target output size for automatic mode. Examples: 1.5GiB, 1536MiB, 1600MB.",
    )
    parser.add_argument(
        "--path",
        action="append",
        type=normalize_path,
        default=[],
        help=(
            "Absolute XML path to duplicate. Can be repeated. "
            "If omitted, paths are discovered as --auto-parent/*/--auto-item."
        ),
    )
    parser.add_argument(
        "--auto-parent",
        type=normalize_path,
        default=DEFAULT_AUTO_PARENT,
        help=(
            "Parent XML path used for automatic entity discovery. "
            f"Default: {DEFAULT_AUTO_PARENT}."
        ),
    )
    parser.add_argument(
        "--auto-item",
        default=DEFAULT_AUTO_ITEM,
        help=f"Item tag name used for automatic entity discovery. Default: {DEFAULT_AUTO_ITEM}.",
    )
    parser.add_argument(
        "--fixed-extra-copies",
        type=int,
        default=None,
        help="Add exactly N duplicates for every matched record instead of using --target-size.",
    )
    parser.add_argument(
        "--unique-field",
        action="append",
        default=[],
        help="Relative field path inside duplicated records whose text should be changed.",
    )
    parser.add_argument(
        "--encoding",
        default="utf-8",
        help="Output encoding. Default: utf-8.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only print the expansion plan; do not write the output XML.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite output file if it already exists.",
    )
    parser.add_argument("input_xml", help="Source XML file.")
    parser.add_argument("output_xml", nargs="?", help="Expanded XML file.")
    args = parser.parse_args(argv)

    if not args.auto_item or "/" in args.auto_item:
        parser.error("--auto-item must be a single XML tag name")

    if args.fixed_extra_copies is not None and args.fixed_extra_copies < 0:
        parser.error("--fixed-extra-copies must be zero or greater")

    if not os.path.isfile(args.input_xml):
        parser.error(f"Input file does not exist: {args.input_xml}")

    if not args.dry_run:
        if not args.output_xml:
            parser.error("output_xml is required unless --dry-run is used")
        if os.path.realpath(args.input_xml) == os.path.realpath(args.output_xml):
            parser.error("Input and output XML paths must be different")
        if os.path.exists(args.output_xml) and not args.force:
            parser.error(f"Output file already exists: {args.output_xml}. Use --force to overwrite.")
        output_parent = os.path.dirname(os.path.abspath(args.output_xml)) or "."
        if not os.path.isdir(output_parent):
            parser.error(f"Output directory does not exist: {output_parent}")

    return args


def main(argv):
    args = parse_args(argv)
    plan = build_plan(args)
    print_plan(args, plan)

    if args.dry_run:
        return 0

    output_parent = os.path.dirname(os.path.abspath(args.output_xml)) or "."
    free_bytes = shutil.disk_usage(output_parent).free
    if free_bytes < plan["estimated_output_bytes"]:
        raise RuntimeError(
            "Not enough free disk space for the temporary output file: "
            f"need about {fmt_bytes(plan['estimated_output_bytes'])}, "
            f"available {fmt_bytes(free_bytes)}"
        )

    fd, temporary_path = tempfile.mkstemp(
        prefix=".expanded-xml-", suffix=".tmp", dir=output_parent
    )
    try:
        with os.fdopen(fd, "wb") as output_handle:
            expander = XmlExpander(args, plan, output_handle)
            make_parser(expander).parse(args.input_xml)
        os.replace(temporary_path, args.output_xml)
    except Exception:
        try:
            os.unlink(temporary_path)
        except FileNotFoundError:
            pass
        raise

    output_size = os.path.getsize(args.output_xml)
    print("XML_EXPAND_RESULT_V1", file=sys.stderr)
    print(f"output_xml: {args.output_xml}", file=sys.stderr)
    print(f"output_size: {fmt_bytes(output_size)}", file=sys.stderr)
    print(f"duplicates_written: {fmt_int(expander.duplicates_written)}", file=sys.stderr)
    print(f"duplicated_bytes: {fmt_bytes(expander.duplicated_bytes)}", file=sys.stderr)
    print(f"mutated_fields: {fmt_int(expander.mutated_fields)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (RuntimeError, OSError, xml.sax.SAXException) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
PY
