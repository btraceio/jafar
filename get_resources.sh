#!/usr/bin/env bash
# Fetches the binary JFR recordings the test suites and benchmarks use.
#
# Every file is checked for the JFR magic number before it is accepted, whether
# it was just downloaded, already on disk, or restored from a CI cache. A file
# hosting service that answers with an HTML page (Dropbox does, with HTTP 200,
# when a link is disabled) is reported as a failed download instead of being
# saved as a .jfr that later fails deep inside a test.
#
# The script attempts every recording, then exits non-zero naming each one it
# could not obtain.
#
# Mirrors: override a source with JAFAR_TEST_AP_URL, JAFAR_TEST_JFR_URL or
# JAFAR_TEST_DD_URL (any URL curl accepts, file:// included).
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)

DEMO_RES_DIR="$HERE/demo/src/test/resources"
PARSER_RES_DIR="$HERE/parser-core/src/test/resources"
mkdir -p "$DEMO_RES_DIR" "$PARSER_RES_DIR"

# Direct-download links (dl=1); redirects are followed.
AP_URL="${JAFAR_TEST_AP_URL:-https://www.dropbox.com/scl/fi/lp5bj8adi3l7jge9ykayr/test-ap.jfr?rlkey=28wghlmp7ge4bxnan9ccwarby&dl=1}"
JFR_URL="${JAFAR_TEST_JFR_URL:-https://www.dropbox.com/scl/fi/5uhp13h9ltj38joyqmwo5/test-jfr.jfr?rlkey=p0wmznxgm7zud6xzaydled69c&dl=1}"
DD_URL="${JAFAR_TEST_DD_URL:-https://www.dropbox.com/scl/fi/1hqtubs3pawamcydyp2t9/test-dd.jfr?rlkey=ug101qmbjgfd9xa1b2svq0zn8&st=oof9fvmo&dl=1}"

FAILED=()

# True if the file starts with the JFR chunk magic "FLR\0".
is_jfr() {
  [ -f "$1" ] && [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = "464c5200" ]
}

# Explains what a rejected file actually is, so the log names the cause.
describe() {
  local file="$1" size head title
  size=$(wc -c <"$file" | tr -d ' ')
  head=$(head -c 16 "$file" | od -An -c | tr -s ' ' | head -1)
  echo "    got $size bytes, starting with:$head" >&2
  title=$(grep -o -i '<title>[^<]*' "$file" 2>/dev/null | head -1 | sed 's/<[Tt][Ii][Tt][Ll][Ee]>//' || true)
  if [ -n "$title" ]; then
    echo "    it is an HTML page titled: $title" >&2
  fi
}

fetch() {
  local url="$1" dest="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$dest"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$dest" "$url"
  else
    echo "Neither curl nor wget is available to download $url" >&2
    return 1
  fi
}

# Ensures $dest holds a valid recording, downloading it from $url if needed.
obtain() {
  local name="$1" url="$2" dest="$3" tmp
  if [ -e "$dest" ]; then
    if is_jfr "$dest"; then
      return 0
    fi
    echo "Existing $dest is not a JFR recording; downloading it again" >&2
    describe "$dest"
    rm -f "$dest"
  fi
  echo "Downloading $name -> $dest"
  tmp="$dest.part"
  rm -f "$tmp"
  if ! fetch "$url" "$tmp"; then
    echo "ERROR: download of $name failed" >&2
    rm -f "$tmp"
    FAILED+=("$name")
    return 0
  fi
  if ! is_jfr "$tmp"; then
    echo "ERROR: $name is not a JFR recording (expected magic FLR\\0)" >&2
    describe "$tmp"
    rm -f "$tmp"
    FAILED+=("$name")
    return 0
  fi
  mv -f "$tmp" "$dest"
}

# Copies a valid recording into parser-core's test resources, replacing an
# invalid copy. A missing or invalid source is already reported by obtain.
copy_valid() {
  local src="$1" dest="$2"
  if is_jfr "$dest"; then
    return 0
  fi
  if [ -e "$dest" ]; then
    echo "Removing $dest: not a JFR recording" >&2
    rm -f "$dest"
  fi
  if is_jfr "$src"; then
    cp -f "$src" "$dest"
  fi
}

AP_FILE="$DEMO_RES_DIR/test-ap.jfr"
JFR_FILE="$DEMO_RES_DIR/test-jfr.jfr"
DD_FILE="$DEMO_RES_DIR/test-dd.jfr"

obtain test-ap.jfr "$AP_URL" "$AP_FILE"
obtain test-jfr.jfr "$JFR_URL" "$JFR_FILE"
obtain test-dd.jfr "$DD_URL" "$DD_FILE"

# Copies keep the parser tests self-contained. parser-core's test-dd.jfr has
# always been a copy of test-jfr.jfr, not of test-dd.jfr; kept as it was.
copy_valid "$AP_FILE" "$PARSER_RES_DIR/test-ap.jfr"
copy_valid "$JFR_FILE" "$PARSER_RES_DIR/test-jfr.jfr"
copy_valid "$JFR_FILE" "$PARSER_RES_DIR/test-dd.jfr"

if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "ERROR: could not obtain: ${FAILED[*]}" >&2
  echo "Tests and benchmarks that use these recordings will fail or be skipped." >&2
  echo "Use a mirror via JAFAR_TEST_AP_URL / JAFAR_TEST_JFR_URL / JAFAR_TEST_DD_URL." >&2
  exit 1
fi
echo "All test recordings present and valid."
