#!/usr/bin/env bash
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)

# Ensure target directories exist
mkdir -p "$HERE/demo/src/test/resources"
mkdir -p "$HERE/parser/src/test/resources"

download() {
  local url="$1" dest="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$url" -o "$dest"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --no-check-certificate -O "$dest" "$url"
  else
    echo "Neither curl nor wget is available to download $url" >&2
    return 1
  fi
}

AP_FILE="$HERE/demo/src/test/resources/test-ap.jfr"
JFR_FILE="$HERE/demo/src/test/resources/test-jfr.jfr"
DD_JFR_FILE="$HERE/demo/src/test/resources/test-dd.jfr"

# Use direct-download links (dl=1) and follow redirects
AP_URL="https://www.dropbox.com/scl/fi/lp5bj8adi3l7jge9ykayr/test-ap.jfr?rlkey=28wghlmp7ge4bxnan9ccwarby&dl=1"
JFR_URL="https://www.dropbox.com/scl/fi/5uhp13h9ltj38joyqmwo5/test-jfr.jfr?rlkey=p0wmznxgm7zud6xzaydled69c&dl=1"
DD_JFR_URL="https://www.dropbox.com/scl/fi/1hqtubs3pawamcydyp2t9/test-dd.jfr?rlkey=ug101qmbjgfd9xa1b2svq0zn8&st=oof9fvmo&dl=0"

if [ ! -f "$AP_FILE" ]; then
  echo "Downloading test-ap.jfr -> $AP_FILE"
  download "$AP_URL" "$AP_FILE"
fi

if [ ! -f "$JFR_FILE" ]; then
  echo "Downloading test-jfr.jfr -> $JFR_FILE"
  download "$JFR_URL" "$JFR_FILE"
fi

if [ ! -f "$DD_JFR_FILE" ]; then
  echo "Downloading test-dd.jfr -> $DD_JFR_FILE"
  download "$DD_JFR_URL" "$DD_JFR_FILE"
fi

PARSER_RES_DIR="$HERE/parser/src/test/resources"
mkdir -p "$PARSER_RES_DIR"

# Copy into parser test resources to keep parser tests self-contained
if [ ! -f "$PARSER_RES_DIR/test-ap.jfr" ]; then
  cp -f "$AP_FILE" "$PARSER_RES_DIR/test-ap.jfr"
fi
if [ ! -f "$PARSER_RES_DIR/test-jfr.jfr" ]; then
  cp -f "$JFR_FILE" "$PARSER_RES_DIR/test-jfr.jfr"
fi
if [ ! -f "$PARSER_RES_DIR/test-dd.jfr" ]; then
  cp -f "$JFR_FILE" "$PARSER_RES_DIR/test-dd.jfr"
fi
