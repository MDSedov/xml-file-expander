#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$PROJECT_DIR/tests/fixtures/sap_sample.xml"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

ANALYSIS="$TEST_TMP/analysis.txt"
SAFE_SAMPLE="$TEST_TMP/safe-sample.xml"
EXPANDED="$TEST_TMP/expanded.xml"
AUTO_EXPANDED="$TEST_TMP/auto-expanded.xml"

"$PROJECT_DIR/scripts/analyze_xml.sh" \
  --top 50 \
  --redact-file-name \
  "$FIXTURE" > "$ANALYSIS"
grep -q '^parse_status: OK$' "$ANALYSIS"
grep -q '^file_name: \[redacted\]$' "$ANALYSIS"

"$PROJECT_DIR/scripts/create_safe_sample.sh" \
  --items-per-collection 2 \
  "$FIXTURE" \
  "$SAFE_SAMPLE"

if grep -Eq 'Sensitive|Secret|Another|internal\.bank\.example|sap\.com' "$SAFE_SAMPLE"; then
  echo "The safe sample contains a source test value." >&2
  exit 1
fi

python3 - "$SAFE_SAMPLE" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()


def child(parent, name):
    return next(item for item in list(parent) if item.tag.rsplit("}", 1)[-1] == name)


values = child(root, "values")
org_items = [item for item in list(child(values, "ET_ORG")) if item.tag == "item"]
person_items = [item for item in list(child(values, "ET_PERSON")) if item.tag == "item"]

assert len(org_items) == 2
assert len(person_items) == 2
assert child(org_items[0], "OBJTYPE").text == child(org_items[1], "OBJTYPE").text
assert child(org_items[0], "IDOBJ").text != child(org_items[1], "IDOBJ").text
PY

"$PROJECT_DIR/scripts/expand_xml.sh" \
  --fixed-extra-copies 2 \
  --unique-field IDOBJ \
  "$SAFE_SAMPLE" \
  "$EXPANDED"

python3 - "$EXPANDED" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()


def child(parent, name):
    return next(item for item in list(parent) if item.tag.rsplit("}", 1)[-1] == name)


values = child(root, "values")
org_items = [item for item in list(child(values, "ET_ORG")) if item.tag == "item"]
person_items = [item for item in list(child(values, "ET_PERSON")) if item.tag == "item"]

assert len(org_items) == 6
assert len(person_items) == 6
PY

"$PROJECT_DIR/scripts/analyze_xml.sh" "$EXPANDED" > "$TEST_TMP/expanded-analysis.txt"
grep -q '^parse_status: OK$' "$TEST_TMP/expanded-analysis.txt"

"$PROJECT_DIR/scripts/expand_xml.sh" \
  --target-size 10KiB \
  "$SAFE_SAMPLE" \
  "$AUTO_EXPANDED"

python3 - "$AUTO_EXPANDED" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

size = os.path.getsize(sys.argv[1])
assert 10 * 1024 <= size < 12 * 1024, size
ET.parse(sys.argv[1])
PY

SAME_PATH="$TEST_TMP/same-path.xml"
cp "$FIXTURE" "$SAME_PATH"
if "$PROJECT_DIR/scripts/expand_xml.sh" \
  --force \
  --fixed-extra-copies 1 \
  "$SAME_PATH" \
  "$SAME_PATH" 2> "$TEST_TMP/same-path-error.txt"; then
  echo "Expander unexpectedly accepted the input file as its output." >&2
  exit 1
fi
cmp "$FIXTURE" "$SAME_PATH"
grep -q 'Input and output XML paths must be different' "$TEST_TMP/same-path-error.txt"

echo "All script integration tests passed."
