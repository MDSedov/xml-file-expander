#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/analyze_xml.sh [--top N] [--sample-limit N] /path/to/file.xml

Options:
  --top N             Number of paths to show per section. Default: 12.
  --sample-limit N    Number of non-empty values sampled per field for
                      uniqueness checks. Default: 500.

The report intentionally does not print XML text values. It prints structure,
counts, text lengths, inferred value types, and sample uniqueness ratios.
USAGE
}

TOP=12
SAMPLE_LIMIT=500
XML_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --top)
      [[ $# -ge 2 ]] || { echo "Missing value for --top" >&2; exit 1; }
      TOP="$2"
      shift 2
      ;;
    --sample-limit)
      [[ $# -ge 2 ]] || { echo "Missing value for --sample-limit" >&2; exit 1; }
      SAMPLE_LIMIT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$XML_FILE" ]]; then
        echo "Only one XML file can be analyzed at a time." >&2
        exit 1
      fi
      XML_FILE="$1"
      shift
      ;;
  esac
done

if [[ -z "$XML_FILE" ]]; then
  usage >&2
  exit 1
fi

if [[ ! -f "$XML_FILE" ]]; then
  echo "File does not exist: $XML_FILE" >&2
  exit 1
fi

if ! [[ "$TOP" =~ ^[0-9]+$ ]] || [[ "$TOP" -lt 1 ]]; then
  echo "--top must be a positive integer." >&2
  exit 1
fi

if ! [[ "$SAMPLE_LIMIT" =~ ^[0-9]+$ ]] || [[ "$SAMPLE_LIMIT" -lt 1 ]]; then
  echo "--sample-limit must be a positive integer." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required but was not found in PATH." >&2
  exit 1
fi

python3 - "$XML_FILE" "$TOP" "$SAMPLE_LIMIT" <<'PY'
import collections
import hashlib
import os
import re
import sys
import time
import xml.sax
from xml.sax.handler import ContentHandler, feature_external_ges, feature_external_pes

try:
    from xml.sax import SAXNotRecognizedException, SAXNotSupportedException, SAXParseException
except ImportError:  # pragma: no cover
    SAXNotRecognizedException = SAXNotSupportedException = SAXParseException = Exception

XML_FILE = sys.argv[1]
TOP = int(sys.argv[2])
SAMPLE_LIMIT = int(sys.argv[3])
TARGET_BYTES = int(1.5 * 1024 * 1024 * 1024)
MAX_TEXT_CAPTURE = 1024

TYPE_PATTERNS = [
    ("uuid", re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")),
    ("datetime", re.compile(r"^\d{4}-\d{2}-\d{2}[T ][0-9:.+-]+Z?$")),
    ("date", re.compile(r"^\d{4}-\d{2}-\d{2}$")),
    ("decimal", re.compile(r"^[+-]?\d+\.\d+$")),
    ("integer", re.compile(r"^[+-]?\d+$")),
    ("boolean", re.compile(r"^(true|false|TRUE|FALSE|True|False)$")),
]


def fmt_int(value):
    return f"{value:,}"


def fmt_bytes(value):
    mib = value / (1024 * 1024)
    if value >= 1024 * 1024 * 1024:
        return f"{fmt_int(value)} bytes ({value / (1024 ** 3):.3f} GiB)"
    return f"{fmt_int(value)} bytes ({mib:.1f} MiB)"


def shorten(value, limit=120):
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


def join_names(names, limit=8):
    names = list(names)
    if not names:
        return "none"
    visible = names[:limit]
    suffix = "" if len(names) <= limit else f", +{len(names) - limit}"
    return ",".join(visible) + suffix


def new_text_stat():
    return {
        "count": 0,
        "nonempty": 0,
        "min_len": None,
        "max_len": 0,
        "sample_count": 0,
        "sample_hashes": set(),
        "types": collections.Counter(),
        "truncated": 0,
    }


def update_text_stat(stat, value, is_truncated):
    stat["count"] += 1

    if is_truncated:
        stat["truncated"] += 1

    value_len = len(value)
    if value_len > 0:
        stat["nonempty"] += 1
        stat["min_len"] = value_len if stat["min_len"] is None else min(stat["min_len"], value_len)
        stat["max_len"] = max(stat["max_len"], value_len)

        if stat["sample_count"] < SAMPLE_LIMIT:
            digest = hashlib.sha256(value.encode("utf-8", "replace")).hexdigest()[:16]
            stat["sample_hashes"].add(digest)
            stat["sample_count"] += 1

    stat["types"][classify_value(value, is_truncated)] += 1


def classify_value(value, is_truncated):
    if not value:
        return "empty"
    if is_truncated:
        return "long"
    if len(value) > 256:
        return "long"
    for type_name, pattern in TYPE_PATTERNS:
        if pattern.match(value):
            return type_name
    return "text"


def format_text_stat(stat):
    min_len = 0 if stat["min_len"] is None else stat["min_len"]
    type_parts = [f"{name}:{count}" for name, count in stat["types"].most_common(3)]
    type_text = ",".join(type_parts) if type_parts else "none"
    truncated_suffix = f" truncated={fmt_int(stat['truncated'])}" if stat["truncated"] else ""
    return (
        f"count={fmt_int(stat['count'])} nonempty={fmt_int(stat['nonempty'])} "
        f"len={min_len}..{stat['max_len']} "
        f"sample_unique={len(stat['sample_hashes'])}/{stat['sample_count']} "
        f"types={type_text}{truncated_suffix}"
    )


def parse_xml_declaration(path):
    with open(path, "rb") as handle:
        head = handle.read(4096)

    bom = "none"
    if head.startswith(b"\xef\xbb\xbf"):
        bom = "utf-8"
    elif head.startswith(b"\xff\xfe"):
        bom = "utf-16le"
    elif head.startswith(b"\xfe\xff"):
        bom = "utf-16be"

    decl_match = re.search(br"<\?xml\s+([^?]+)\?>", head)
    if not decl_match:
        return "no", "not declared", bom

    enc_match = re.search(br"encoding\s*=\s*['\"]([^'\"]+)['\"]", decl_match.group(1), re.I)
    encoding = enc_match.group(1).decode("ascii", "replace") if enc_match else "not declared"
    return "yes", encoding, bom


class XmlAnalyzer(ContentHandler):
    def __init__(self):
        super().__init__()
        self.stack = []
        self.root_path = None
        self.total_elements = 0
        self.max_depth = 0
        self.path_counts = collections.Counter()
        self.subtree_totals = collections.Counter()
        self.subtree_max = collections.Counter()
        self.direct_child_counts = collections.Counter()
        self.direct_child_names = collections.defaultdict(collections.Counter)
        self.path_attr_names = collections.defaultdict(collections.Counter)
        self.leaf_stats = collections.defaultdict(new_text_stat)
        self.attr_stats = collections.defaultdict(new_text_stat)

    def startElement(self, name, attrs):
        parent_path = self.stack[-1]["path"] if self.stack else ""
        path = f"{parent_path}/{name}" if parent_path else f"/{name}"

        if self.root_path is None:
            self.root_path = path

        if parent_path:
            self.stack[-1]["children"] += 1
            self.direct_child_counts[(parent_path, name)] += 1
            self.direct_child_names[parent_path][name] += 1

        attr_names = list(attrs.getNames())
        for attr_name in attr_names:
            self.path_attr_names[path][attr_name] += 1
            update_text_stat(
                self.attr_stats[(path, attr_name)],
                normalize_text(attrs.getValue(attr_name)),
                False,
            )

        self.total_elements += 1
        self.path_counts[path] += 1
        self.max_depth = max(self.max_depth, len(self.stack) + 1)
        self.stack.append(
            {
                "name": name,
                "path": path,
                "children": 0,
                "subtree_elements": 1,
                "text_parts": [],
                "text_len": 0,
                "captured_len": 0,
                "has_non_ws_text": False,
            }
        )

    def characters(self, content):
        if not self.stack:
            return

        frame = self.stack[-1]
        frame["text_len"] += len(content)

        if content.strip():
            frame["has_non_ws_text"] = True

        remaining = MAX_TEXT_CAPTURE - frame["captured_len"]
        if remaining > 0:
            piece = content[:remaining]
            frame["text_parts"].append(piece)
            frame["captured_len"] += len(piece)

    def endElement(self, name):
        frame = self.stack.pop()
        path = frame["path"]
        subtree_elements = frame["subtree_elements"]

        self.subtree_totals[path] += subtree_elements
        self.subtree_max[path] = max(self.subtree_max[path], subtree_elements)

        if frame["children"] == 0:
            captured_text = "".join(frame["text_parts"])
            normalized = normalize_text(captured_text) if frame["has_non_ws_text"] else ""
            is_truncated = frame["text_len"] > frame["captured_len"]
            update_text_stat(self.leaf_stats[path], normalized, is_truncated)

        if self.stack:
            self.stack[-1]["subtree_elements"] += subtree_elements


def normalize_text(value):
    return " ".join(value.split())


def set_safe_feature(parser, feature, value):
    try:
        parser.setFeature(feature, value)
    except (SAXNotRecognizedException, SAXNotSupportedException):
        pass


def top_items(counter, limit):
    return counter.most_common(limit)


def format_repeat_candidate(path, count, analyzer):
    avg_subtree = analyzer.subtree_totals[path] / count
    children = analyzer.direct_child_names.get(path, collections.Counter())
    attrs = analyzer.path_attr_names.get(path, collections.Counter())
    return (
        f"{shorten(path)} count={fmt_int(count)} "
        f"avg_subtree_elements={avg_subtree:.1f} "
        f"max_subtree_elements={fmt_int(analyzer.subtree_max[path])} "
        f"child_tags={join_names([name for name, _ in children.most_common(8)])} "
        f"attrs={join_names([name for name, _ in attrs.most_common(8)])}"
    )


def print_numbered(lines):
    if not lines:
        print("  none")
        return
    for index, line in enumerate(lines, 1):
        print(f"  {index}. {line}")


def main():
    start = time.time()
    stat = os.stat(XML_FILE)
    decl, encoding, bom = parse_xml_declaration(XML_FILE)

    analyzer = XmlAnalyzer()
    parser = xml.sax.make_parser()
    set_safe_feature(parser, feature_external_ges, False)
    set_safe_feature(parser, feature_external_pes, False)
    parser.setContentHandler(analyzer)

    parse_status = "OK"
    parse_error = None
    try:
        parser.parse(XML_FILE)
    except SAXParseException as exc:
        parse_status = "ERROR"
        parse_error = f"line={exc.getLineNumber()} column={exc.getColumnNumber()} message={exc.getMessage()}"

    elapsed = time.time() - start
    multiplier = TARGET_BYTES / stat.st_size if stat.st_size else 0

    print("XML_ANALYSIS_V1")
    print(f"file_name: {os.path.basename(XML_FILE)}")
    print(f"file_size: {fmt_bytes(stat.st_size)}")
    print(f"target_1_5_gib_multiplier: x{multiplier:.2f}")
    print(f"xml_declaration: {decl}; encoding={encoding}; bom={bom}")
    print(f"parse_status: {parse_status}")
    if parse_error:
        print(f"parse_error: {parse_error}")
    print(f"root_path: {analyzer.root_path or 'unknown'}")
    print(f"total_elements_seen: {fmt_int(analyzer.total_elements)}")
    print(f"unique_element_paths: {fmt_int(len(analyzer.path_counts))}")
    print(f"max_depth: {fmt_int(analyzer.max_depth)}")
    print(f"analysis_seconds: {elapsed:.2f}")

    print()
    print("root_direct_children:")
    root_children = analyzer.direct_child_names.get(analyzer.root_path or "", collections.Counter())
    print_numbered(
        [
            f"{shorten((analyzer.root_path or '') + '/' + name)} count={fmt_int(count)}"
            for name, count in top_items(root_children, TOP)
        ]
    )

    print()
    print("repeat_candidates:")
    candidates = []
    for path, count in analyzer.path_counts.items():
        if count <= 1:
            continue
        avg_subtree = analyzer.subtree_totals[path] / count if count else 0
        child_count = len(analyzer.direct_child_names.get(path, {}))
        if avg_subtree >= 2 or child_count > 0:
            candidates.append((path, count, avg_subtree, child_count))
    candidates.sort(key=lambda item: (item[1], item[2], item[3]), reverse=True)
    print_numbered([format_repeat_candidate(path, count, analyzer) for path, count, _, _ in candidates[:TOP]])

    print()
    print("top_leaf_fields:")
    leaf_rows = sorted(
        analyzer.leaf_stats.items(),
        key=lambda item: (item[1]["count"], item[1]["nonempty"]),
        reverse=True,
    )
    print_numbered(
        [
            f"{shorten(path)} {format_text_stat(text_stat)}"
            for path, text_stat in leaf_rows[:TOP]
        ]
    )

    print()
    print("potential_unique_leaf_fields_sampled:")
    unique_leaf_rows = []
    for path, text_stat in analyzer.leaf_stats.items():
        if text_stat["count"] <= 1:
            continue
        sample_count = text_stat["sample_count"]
        if sample_count < min(50, text_stat["nonempty"]):
            continue
        if sample_count and len(text_stat["sample_hashes"]) / sample_count >= 0.95:
            unique_leaf_rows.append((path, text_stat))
    unique_leaf_rows.sort(key=lambda item: (item[1]["count"], item[1]["sample_count"]), reverse=True)
    print_numbered(
        [
            f"{shorten(path)} {format_text_stat(text_stat)}"
            for path, text_stat in unique_leaf_rows[:TOP]
        ]
    )

    print()
    print("attribute_fields:")
    attr_rows = sorted(
        analyzer.attr_stats.items(),
        key=lambda item: (item[1]["count"], item[1]["nonempty"]),
        reverse=True,
    )
    print_numbered(
        [
            f"{shorten(path)} @{attr_name} {format_text_stat(text_stat)}"
            for (path, attr_name), text_stat in attr_rows[:TOP]
        ]
    )

    print()
    print("notes:")
    print("  - Text values are not printed; sample_unique is based on SHA-256 hashes of first sampled non-empty values.")
    print("  - Good duplication targets are usually high-count repeat_candidates with many child_tags.")
    print("  - Fields with sample_unique close to N/N may need regeneration during expansion.")

    if parse_status != "OK":
        sys.exit(2)


if __name__ == "__main__":
    main()
PY
