#!/usr/bin/env bash
# test_perf_ab_comment.sh — the perf-ab PR comment: scripts/perf_ab_comment.py renders it from a
# run's artifact, scripts/perf_ab_post.py puts it on the PR.
#
# The artifact is the PR's own output, so the renderer's job is to let nothing through but the
# numbers and escaped names: a malicious benchmark or frame name must come out inert, a file of
# the wrong shape must be refused (exit 2), and the comment must stay under its size cap. The
# poster must find the PR from the run's metadata only, edit only its own marked comment, and
# post nothing when the PR is ambiguous. It runs against a local fake of the GitHub API that
# records every request.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
render="$repo/scripts/perf_ab_comment.py"
post="$repo/scripts/perf_ab_post.py"

pass=0
fail=0
W=$(mktemp -d)
server_pid=""
trap '[ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null; rm -rf "$W" 2>/dev/null || true' EXIT

ok() { printf 'ok   %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf 'FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2"; fail=$((fail + 1)); }

# ------------------------------------------------------------------------------ fixtures
# artifact <dir> <benchmark name> <frame name>: a report with two rows, one attributed.
artifact() {
    local dir="$1" bench="$2" frame="$3"
    mkdir -p "$dir/attribution"
    BENCH="$bench" FRAME="$frame" DIR="$dir" python3 - <<'PY'
import json, os
d, bench, frame = os.environ["DIR"], os.environ["BENCH"], os.environ["FRAME"]
row = lambda name, ratio, p, verdict: {
    "benchmark": name, "params": {"chunks": "32"}, "mode": "avgt", "verdict": verdict,
    "ratio": ratio, "interval": {"low": ratio - 0.05, "high": ratio + 0.05},
    "test": {"p": p}, "base": {"n": 6}, "head": {"n": 6}, "noisy": False}
report = {"alpha": 0.01, "results": [
    row("io.jafar.B.ceilingSkipEveryEvent", 0.91, 0.0152, "uncalibrated"),
    row(bench, 0.817, 0.0022, "uncalibrated")]}
json.dump(report, open(f"{d}/report.json", "w"))
open(f"{d}/attribute.tsv", "w").write(f"{bench}\t-p chunks=32\n")
frames = [{"method": frame, "baselineTotalPct": 21.49, "candidateTotalPct": 6.22,
           "deltaPct": -15.27, "significant": True},
          {"method": "m.Quiet.frame", "baselineTotalPct": 1.0, "candidateTotalPct": 1.2,
           "deltaPct": 0.2, "significant": False}]
json.dump({"inclusiveFrames": frames, "frames": [],
           "comparability": ["Recordings appear comparable."]},
          open(f"{d}/attribution/1.json", "w"))
PY
}

URL=https://github.com/btraceio/jafar/actions/runs/123

# ----------------------------------------------------------------------- renderer: content
artifact "$W/a" io.jafar.B.headersWithStartTime java/nio/Buffer.nextGetIndex
out=$(python3 "$render" --artifact "$W/a" --run-url "$URL")
head -1 <<<"$out" | grep -qx '<!-- perf-ab-report -->' && ok "comment starts with the marker" || bad "no marker" "$out"
grep -qF '| headersWithStartTime | chunks=32 | 0.817 | 0.767 - 0.867 | 0.0022 | 6 / 6 | uncalibrated |' <<<"$out" \
    && ok "verdict row: short name, params, ratio, interval, p, forks, verdict" || bad "verdict row" "$out"
grep -qF '| java/nio/Buffer.nextGetIndex | total | 21.49% | 6.22% | -15.27 |' <<<"$out" \
    && ok "attribution row for a significant frame" || bad "attribution row" "$out"
grep -qF 'm.Quiet.frame' <<<"$out" && bad "an insignificant frame was listed" "$out" || ok "insignificant frames are left out"
grep -qF "$URL" <<<"$out" && ok "links the run" || bad "no run link" "$out"

# ----------------------------------------------------------------------- renderer: escaping
evil='x|y`z<img src=//e.io/a.png>[click](https://e.io) @everyone **b** _i_'
artifact "$W/evil" "io.jafar.B.$evil" "$evil"
out=$(python3 "$render" --artifact "$W/evil" --run-url "$URL")
for needle in '<img' '](https' '@everyone' '`z' '**b**' '|y'; do
    if grep -qF -- "$needle" <<<"$out"; then
        bad "a malicious name leaked $needle into the comment" "$out"
    else
        ok "malicious name: $needle is escaped"
    fi
done
rows=$(grep -c '^| ' <<<"$out")
[ "$rows" -eq 5 ] && ok "malicious names add no table rows (2 headers + 3 rows)" || bad "table rows: $rows, wanted 5" "$out"

# ----------------------------------------------------------------------- renderer: size cap
artifact "$W/big" io.jafar.B.headersWithStartTime "$(printf 'm.Long%.0s' $(seq 1 200))"
out=$(python3 "$render" --artifact "$W/big" --run-url "$URL" --max-chars 1500)
[ "${#out}" -le 1500 ] && ok "comment stays under --max-chars (${#out} chars)" || bad "comment is ${#out} chars"

# ----------------------------------------------------------------------- renderer: refusals
refuse() { # refuse <description> <python mutation of report.json as r>
    rm -rf "$W/m" && artifact "$W/m" io.jafar.B.h m.F
    MUT="$2" P="$W/m/report.json" python3 -c '
import json, os
r = json.load(open(os.environ["P"])); exec(os.environ["MUT"]); json.dump(r, open(os.environ["P"], "w"))'
    local rc=0 msg
    msg=$(python3 "$render" --artifact "$W/m" --run-url "$URL" 2>&1 >/dev/null) || rc=$?
    if [ "$rc" -eq 2 ] && grep -qF 'malformed artifact' <<<"$msg"; then ok "refused: $1"; else bad "not refused (exit $rc): $1" "$msg"; fi
}
refuse "ratio is a string" 'r["results"][0]["ratio"] = "1.0; <b>"'
refuse "unknown verdict" 'r["results"][0]["verdict"] = "<script>"'
refuse "benchmark is not a string" 'r["results"][0]["benchmark"] = ["x"]'
refuse "params hold a non-string" 'r["results"][0]["params"] = {"a": 1}'
refuse "results is not a list" 'r["results"] = {}'
refuse "p is not finite" 'r["results"][0]["test"]["p"] = float("nan")'
echo 'not json' > "$W/m/report.json"
rc=0; python3 "$render" --artifact "$W/m" --run-url "$URL" >/dev/null 2>&1 || rc=$?
[ "$rc" -eq 2 ] && ok "refused: report.json is not JSON" || bad "not JSON accepted (exit $rc)"

# --------------------------------------------------------------- poster: a fake GitHub API
# GET  /repos/o/r/commits/<sha>/pulls      PRs associated with a commit: every PR of the fixture
#                                          (#7 head aaa..., #8 head bbb..., #9 merged, head ddd...),
#                                          so the poster must pick by head commit itself
# GET  /repos/o/r/issues/<n>/comments      per PR: COMMENTS_<n>.json in the state dir
# POST /repos/o/r/issues/<n>/comments      create; PATCH /repos/o/r/issues/comments/<id>
# Every request is appended to requests.log.
cat > "$W/fake.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
state = sys.argv[1]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def record(self, body=None):
        with open(f"{state}/requests.log", "a") as fh:
            fh.write(f"{self.command} {self.path.split('?')[0]} {json.dumps(body) if body else ''}\n")
    def do_GET(self):
        self.record()
        path = self.path.split("?")[0]
        if "page=2" in self.path:
            return self.reply([])
        if path.startswith("/repos/o/r/commits/") and path.endswith("/pulls"):
            return self.reply(json.load(open(f"{state}/pulls.json")))
        if path.startswith("/repos/o/r/issues/") and path.endswith("/comments"):
            n = path.split("/")[5]
            f = f"{state}/comments_{n}.json"
            return self.reply(json.load(open(f)) if os.path.exists(f) else [])
        self.reply({"message": "not found"}, 404)
    def body(self):
        return json.loads(self.rfile.read(int(self.headers["Content-Length"])))
    def do_POST(self):
        self.record(self.body()); self.reply({"id": 999}, 201)
    def do_PATCH(self):
        self.record(self.body()); self.reply({"id": int(self.path.split("/")[-1])})
server = HTTPServer(("127.0.0.1", 0), H)
open(f"{state}/port", "w").write(str(server.server_port))
server.serve_forever()
PY
S="$W/api"
mkdir -p "$S"
A=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
B=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
D=dddddddddddddddddddddddddddddddddddddddd
printf '[{"number":7,"head":{"sha":"%s"},"state":"open"},{"number":8,"head":{"sha":"%s"},"state":"open"},
         {"number":9,"head":{"sha":"%s"},"state":"closed","merged_at":"2026-11-27T15:14:46Z"}]' \
    "$A" "$B" "$D" > "$S/pulls.json"
python3 "$W/fake.py" "$S" &
server_pid=$!
for _ in $(seq 1 50); do [ -s "$S/port" ] && break; sleep 0.1; done
API="http://127.0.0.1:$(cat "$S/port")"
python3 "$render" --artifact "$W/a" --run-url "$URL" > "$W/comment.md"

post_run() { # post_run <head sha> <pull_requests json> [comment file]
    : > "$S/requests.log"
    GITHUB_API_URL="$API" GITHUB_REPOSITORY=o/r GH_TOKEN=t HEAD_SHA="$1" PULL_REQUESTS="$2" \
        python3 "$post" --comment "${3:-$W/comment.md}"
}

# The run names its PR: that one, without listing open PRs.
out=$(post_run "$A" '[{"number":7}]' 2>&1) && grep -q '^POST /repos/o/r/issues/7/comments ' "$S/requests.log" \
    && ! grep -q 'GET /repos/o/r/commits/' "$S/requests.log" \
    && ok "PR from the run's metadata: comment created on #7" || bad "metadata PR" "$out
$(cat "$S/requests.log")"

# A fork PR: pull_requests is empty, so the open PR whose head is the run's head commit.
out=$(post_run "$B" '[]' 2>&1) && grep -q '^POST /repos/o/r/issues/8/comments ' "$S/requests.log" \
    && ok "fork PR: found by head commit (#8)" || bad "fork PR lookup" "$out
$(cat "$S/requests.log")"

# A PR merged while the run was still going: GitHub leaves pull_requests empty, and the PR is
# found by its head commit among the commit's PRs, merged or not (perf-ab on #315 hit this).
out=$(post_run "$D" '[]' 2>&1) && grep -q '^POST /repos/o/r/issues/9/comments ' "$S/requests.log" \
    && grep -q '^GET /repos/o/r/commits/'"$D"'/pulls ' "$S/requests.log" \
    && ok "merged PR: found by head commit (#9)" || bad "merged PR lookup" "$out
$(cat "$S/requests.log")"

# An earlier perf-ab comment by the bot is edited; a human's comment with the marker is not.
printf '[{"id":11,"user":{"login":"someone"},"body":"<!-- perf-ab-report --> quoted"},
         {"id":12,"user":{"login":"github-actions[bot]"},"body":"<!-- perf-ab-report -->\\nold"},
         {"id":13,"user":{"login":"github-actions[bot]"},"body":"test summary"}]' > "$S/comments_7.json"
out=$(post_run "$A" '[{"number":7}]' 2>&1) && grep -q '^PATCH /repos/o/r/issues/comments/12 ' "$S/requests.log" \
    && ! grep -q '^POST' "$S/requests.log" && ok "edits only its own marked comment (#12)" || bad "upsert" "$out
$(cat "$S/requests.log")"

# No PR, or two: nothing is posted.
c=cccccccccccccccccccccccccccccccccccccccc
rc=0; out=$(post_run "$c" '[]' 2>&1) || rc=$?
[ "$rc" -eq 1 ] && ! grep -qE '^(POST|PATCH)' "$S/requests.log" && grep -qF 'expected one PR' <<<"$out" \
    && ok "no matching PR: nothing posted, exit 1" || bad "no PR (exit $rc)" "$out"
rc=0; out=$(post_run "$A" '[{"number":7},{"number":8}]' 2>&1) || rc=$?
[ "$rc" -eq 1 ] && ! grep -qE '^(POST|PATCH)' "$S/requests.log" \
    && ok "two PRs: nothing posted, exit 1" || bad "two PRs (exit $rc)" "$out"

# Inputs the workflow controls are still checked.
rc=0; out=$(post_run 'HEAD; rm -rf /' '[]' 2>&1) || rc=$?
[ "$rc" -eq 1 ] && grep -qF 'HEAD_SHA is not a full commit id' <<<"$out" && ok "a head that is not a commit id is refused" || bad "head check (exit $rc)" "$out"
printf 'no marker\n' > "$W/plain.md"
rc=0; out=$(post_run "$A" '[{"number":7}]' "$W/plain.md" 2>&1) || rc=$?
[ "$rc" -eq 1 ] && ! grep -qE '^(POST|PATCH)' "$S/requests.log" && ok "a comment without the marker is not posted" || bad "marker check (exit $rc)" "$out"

echo "perf_ab_comment: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
