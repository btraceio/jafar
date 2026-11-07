#!/usr/bin/env bash
# test_api_break_check.sh — hermetic tests for scripts/api-break-check.sh
# (R5 on the gate).
#
# The report fixtures below are copied VERBATIM from the recorded step-1
# japicmp samples (the T3 base run and the case-(a)/(f) mutation runs): the
# gate's marker grammar is derived from those exact bytes, so the fixtures
# must stay byte-identical — do not reformat or re-indent them.
#
# A scratch git repo carries the accepted-breaks fixtures: the growth and
# day-one paths read the accepted file at --base from the object database,
# so they need real git state, not just working-tree files.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/api-break-check.sh"

pass=0
fail=0

check() { # check <description> <expected-exit> <expected-grep> <cmd...>
    local desc="$1" want_rc="$2" want_grep="$3"
    shift 3
    local out rc=0
    out=$("$@" 2>&1) || rc=$?
    if [ "$rc" != "$want_rc" ]; then
        printf 'FAIL %s: exit %s, wanted %s\n%s\n' "$desc" "$rc" "$want_rc" "$out"
        fail=$((fail + 1))
    elif [ -n "$want_grep" ] && ! grep -qF "$want_grep" <<<"$out"; then
        printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$want_grep" "$out"
        fail=$((fail + 1))
    else
        printf 'ok   %s\n' "$desc"
        pass=$((pass + 1))
    fi
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp" "$scratch"' EXIT

# --- report fixtures, verbatim from the step-1 samples ----------------------
mkdir -p "$tmp/r-clean" "$tmp/r-case-a" "$tmp/r-case-f" "$tmp/r-pprof" "$tmp/r-missing"

cat > "$tmp/r-clean/parser.txt" <<'JFR_SAMPLE_EOF'
Comparing source compatibility of jafar-parser-0.29.0-SNAPSHOT-all.jar against baseline.jar
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ComplexType  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) boolean isSimpleType()
===  UNCHANGED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ErrorHandler  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW ANNOTATION: java.lang.Deprecated
+++  NEW CLASS: PUBLIC(+) FINAL(+) io.jafar.parser.api.EventDecodingException  (compatible)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW INTERFACE: java.io.Serializable
	+++  NEW SUPERCLASS: java.lang.RuntimeException
	+++  NEW CONSTRUCTOR: PUBLIC(+) EventDecodingException(java.lang.String, long, int, long, long, java.lang.Throwable)
	+++  NEW METHOD: PUBLIC(+) int getChunkIndex()
	+++  NEW METHOD: PUBLIC(+) long getPosition()
	+++  NEW METHOD: PUBLIC(+) long getSize()
	+++  NEW METHOD: PUBLIC(+) long getTypeId()
	+++  NEW METHOD: PUBLIC(+) java.lang.String getTypeName()
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.HandlerFactory  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	GENERIC TEMPLATES: === T:java.lang.Object
	+++  NEW METHOD: PUBLIC(+) java.lang.Object create(io.jafar.parser.internal_api.RecordingStream, io.jafar.parser.internal_api.metadata.MetadataClass, io.jafar.parser.api.ConstantPools)
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.UntypedJafarParser  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) io.jafar.parser.api.UntypedJafarParser onDecodeError(io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler)
+++  NEW INTERFACE: PUBLIC(+) ABSTRACT(+) STATIC(+) io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler  (not serializable)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW SUPERCLASS: java.lang.Object
	+++  NEW METHOD: PUBLIC(+) ABSTRACT(+) boolean onDecodeError(io.jafar.parser.api.EventDecodingException)
	+++  NEW ANNOTATION: java.lang.FunctionalInterface
***  MODIFIED CLASS: PUBLIC FINAL io.jafar.parser.api.Values  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) STATIC(+) java.util.Map<java.lang.String,java.lang.Object> resolvedDeepCollapsed(java.util.Map<java.lang.String,java.lang.Object>)
JFR_SAMPLE_EOF

cat > "$tmp/r-case-a/parser.txt" <<'JFR_SAMPLE_EOF'
Comparing source compatibility of jafar-parser-0.29.0-SNAPSHOT-all.jar against baseline.jar
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ComplexType  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) boolean isSimpleType()
===  UNCHANGED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ErrorHandler  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW ANNOTATION: java.lang.Deprecated
+++  NEW CLASS: PUBLIC(+) FINAL(+) io.jafar.parser.api.EventDecodingException  (compatible)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW INTERFACE: java.io.Serializable
	+++  NEW SUPERCLASS: java.lang.RuntimeException
	+++  NEW CONSTRUCTOR: PUBLIC(+) EventDecodingException(java.lang.String, long, int, long, long, java.lang.Throwable)
	+++  NEW METHOD: PUBLIC(+) int getChunkIndex()
	+++  NEW METHOD: PUBLIC(+) long getPosition()
	+++  NEW METHOD: PUBLIC(+) long getSize()
	+++  NEW METHOD: PUBLIC(+) long getTypeId()
	+++  NEW METHOD: PUBLIC(+) java.lang.String getTypeName()
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.HandlerFactory  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	GENERIC TEMPLATES: === T:java.lang.Object
	+++  NEW METHOD: PUBLIC(+) java.lang.Object create(io.jafar.parser.internal_api.RecordingStream, io.jafar.parser.internal_api.metadata.MetadataClass, io.jafar.parser.api.ConstantPools)
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.UntypedJafarParser  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) io.jafar.parser.api.UntypedJafarParser onDecodeError(io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler)
+++  NEW INTERFACE: PUBLIC(+) ABSTRACT(+) STATIC(+) io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler  (not serializable)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW SUPERCLASS: java.lang.Object
	+++  NEW METHOD: PUBLIC(+) ABSTRACT(+) boolean onDecodeError(io.jafar.parser.api.EventDecodingException)
	+++  NEW ANNOTATION: java.lang.FunctionalInterface
***! MODIFIED CLASS: PUBLIC FINAL io.jafar.parser.api.Values  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) STATIC(+) java.util.Map<java.lang.String,java.lang.Object> resolvedDeepCollapsed(java.util.Map<java.lang.String,java.lang.Object>)
	---! REMOVED METHOD: PUBLIC(-) STATIC(-) java.util.Map<java.lang.String,java.lang.Object> resolvedShallow(java.util.Map<java.lang.String,java.lang.Object>)
JFR_SAMPLE_EOF

cat > "$tmp/r-case-f/parser.txt" <<'JFR_SAMPLE_EOF'
Comparing source compatibility of jafar-parser-0.29.0-SNAPSHOT-all.jar against baseline.jar
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ComplexType  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) boolean isSimpleType()
===  UNCHANGED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.ErrorHandler  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW ANNOTATION: java.lang.Deprecated
+++  NEW CLASS: PUBLIC(+) FINAL(+) io.jafar.parser.api.EventDecodingException  (compatible)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW INTERFACE: java.io.Serializable
	+++  NEW SUPERCLASS: java.lang.RuntimeException
	+++  NEW CONSTRUCTOR: PUBLIC(+) EventDecodingException(java.lang.String, long, int, long, long, java.lang.Throwable)
	+++  NEW METHOD: PUBLIC(+) int getChunkIndex()
	+++  NEW METHOD: PUBLIC(+) long getPosition()
	+++  NEW METHOD: PUBLIC(+) long getSize()
	+++  NEW METHOD: PUBLIC(+) long getTypeId()
	+++  NEW METHOD: PUBLIC(+) java.lang.String getTypeName()
**** MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.HandlerFactory  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	GENERIC TEMPLATES: === T:java.lang.Object
	+++  NEW METHOD: PUBLIC(+) java.lang.Object create(io.jafar.parser.internal_api.RecordingStream, io.jafar.parser.internal_api.metadata.MetadataClass, io.jafar.parser.api.ConstantPools)
	+++* NEW METHOD: PUBLIC(+) ABSTRACT(+) void reset()
***  MODIFIED INTERFACE: PUBLIC ABSTRACT io.jafar.parser.api.UntypedJafarParser  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) io.jafar.parser.api.UntypedJafarParser onDecodeError(io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler)
+++  NEW INTERFACE: PUBLIC(+) ABSTRACT(+) STATIC(+) io.jafar.parser.api.UntypedJafarParser$DecodeErrorHandler  (not serializable)
	+++  CLASS FILE FORMAT VERSION: 52.0 <- n.a.
	+++  NEW SUPERCLASS: java.lang.Object
	+++  NEW METHOD: PUBLIC(+) ABSTRACT(+) boolean onDecodeError(io.jafar.parser.api.EventDecodingException)
	+++  NEW ANNOTATION: java.lang.FunctionalInterface
***  MODIFIED CLASS: PUBLIC FINAL io.jafar.parser.api.Values  (not serializable)
	===  CLASS FILE FORMAT VERSION: 52.0 <- 52.0
	+++  NEW METHOD: PUBLIC(+) STATIC(+) java.util.Map<java.lang.String,java.lang.Object> resolvedDeepCollapsed(java.util.Map<java.lang.String,java.lang.Object>)
JFR_SAMPLE_EOF

cat > "$tmp/r-pprof/pprof.txt" <<'JFR_SAMPLE_EOF'
Comparing source compatibility of pprof-parser-0.29.0-SNAPSHOT.jar against baseline.jar
***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)
	===  CLASS FILE FORMAT VERSION: 65.0 <- 65.0
	---! REMOVED CONSTRUCTOR: PUBLIC(-) PprofProfile$Sample(java.util.List<java.lang.Long>, java.util.List<java.lang.Long>, java.util.List<io.jafar.pprof.api.PprofProfile$Label>)
	+++  NEW CONSTRUCTOR: PUBLIC(+) PprofProfile$Sample(long[], long[], java.util.List<io.jafar.pprof.api.PprofProfile$Label>)
	***  MODIFIED METHOD: PUBLIC NON_FINAL (<- FINAL) boolean equals(java.lang.Object)
	***  MODIFIED METHOD: PUBLIC NON_FINAL (<- FINAL) int hashCode()
	***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) locationIds()
	***  MODIFIED METHOD: PUBLIC NON_FINAL (<- FINAL) java.lang.String toString()
	***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) values()
JFR_SAMPLE_EOF

# a reports directory that lacks shell.txt (missing-report case)
cp "$tmp/r-clean/parser.txt" "$tmp/r-missing/parser.txt"

# --- surface-table fixtures (rows copied from gradle/api-surfaces.txt) ------
printf '%s\n' 'parser :parser core maven:jafar-parser pkg:io.jafar.parser.api' > "$tmp/surfaces-parser"
{
    printf '%s\n' 'parser :parser core maven:jafar-parser pkg:io.jafar.parser.api'
    printf '%s\n' 'shell :shell-core shell maven:jafar-shell pkg:io.jafar.shell.backend,class:io.jafar.shell.core.Session'
} > "$tmp/surfaces-parser-shell"
printf '%s\n' 'pprof :pprof-parser shell tag pkg:io.jafar.pprof.api' > "$tmp/surfaces-pprof"

# --- PR-body fixtures --------------------------------------------------------
printf 'Fix thing.\n' > "$tmp/body-plain"
printf 'API-Break:   \n' > "$tmp/body-spaces"
printf 'Fix.\n\nAPI-Break: parser Values reworked\n' > "$tmp/body-break"

# --- scratch repo carrying the accepted-breaks fixtures ---------------------
scratch=$(mktemp -d)
git -C "$scratch" init -q -b main
# Local to the scratch repo: the developer's global commit.gpgSign would sign
# (and, with the agent down, fail) every scratch commit.
git -C "$scratch" config commit.gpgSign false
mkdir -p "$scratch/accepted"

# empty ratchet: header only
printf '%s\n' '# no accepted breaks' > "$scratch/accepted/empty.txt"

# the day-one pprof ratchet: the three entries the base reports carry
cat > "$scratch/accepted/pprof.txt" <<'JFR_SAMPLE_EOF'
# accepted: pprof Sample rework (test fixture, same entries as the real
# gradle/api-accepted-breaks.txt)
# reason: fixture
pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|---! REMOVED CONSTRUCTOR: PUBLIC(-) PprofProfile$Sample(java.util.List<java.lang.Long>, java.util.List<java.lang.Long>, java.util.List<io.jafar.pprof.api.PprofProfile$Label>)

# reason: fixture
pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) locationIds()

# reason: fixture
pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) values()

JFR_SAMPLE_EOF

# the same three entries plus one that matches no report change: stale
cp "$scratch/accepted/pprof.txt" "$scratch/accepted/pprof-stale.txt"
{
    printf '%s\n' '# reason: fixture — matches nothing in the reports'
    printf '%s\n' 'pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|---! REMOVED METHOD: PUBLIC(-) int staleFixture()'
} >> "$scratch/accepted/pprof-stale.txt"

# growth fixture: committed at base with the first entry only; the working
# tree gains the other two after the base commit (below)
cat > "$scratch/accepted/pprof-grow.txt" <<'JFR_SAMPLE_EOF'
# accepted: pprof Sample rework (growing fixture)
# reason: fixture
pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|---! REMOVED CONSTRUCTOR: PUBLIC(-) PprofProfile$Sample(java.util.List<java.lang.Long>, java.util.List<java.lang.Long>, java.util.List<io.jafar.pprof.api.PprofProfile$Label>)

JFR_SAMPLE_EOF

git -C "$scratch" add -A
git -C "$scratch" -c user.email=t@t -c user.name=t commit -q -m base

# after the base commit: grow pprof-grow.txt, and add a day-one file that
# git has never seen
{
    printf '%s\n' '# reason: fixture'
    printf '%s\n' 'pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) locationIds()
'
    printf '%s\n' '# reason: fixture'
    printf '%s\n' 'pprof|***! MODIFIED CLASS: PUBLIC STATIC FINAL io.jafar.pprof.api.PprofProfile$Sample  (not serializable)|***! MODIFIED METHOD: PUBLIC long[](<- <java.lang.Long>) (<-java.util.List(<- <java.lang.Long>)) values()
'
} >> "$scratch/accepted/pprof-grow.txt"
cp "$scratch/accepted/pprof.txt" "$scratch/accepted/pprof-day1.txt"

gate() { # gate <reports> <accepted> <body-or-> <surfaces> [base]
    local reports="$1" accepted="$2" body="$3" surfaces="$4" base="${5:-main}"
    local args=(--reports "$reports" --accepted "$accepted" --surfaces "$surfaces" --base "$base")
    if [ "$body" != "-" ]; then
        args+=(--body-file "$body")
    fi
    (cd "$scratch" && "$script" "${args[@]}")
}

# 1. no break -> passes
check "no break: passes" 0 "" \
    gate "$tmp/r-clean" "$scratch/accepted/empty.txt" - "$tmp/surfaces-parser"

# 2. a break without a statement -> blocked, names the member
check "break, empty PR body: blocked, names the member" 1 "resolvedShallow" \
    gate "$tmp/r-case-a" "$scratch/accepted/empty.txt" "$tmp/body-plain" "$tmp/surfaces-parser"

# 3. a break with a statement -> passes
check "break with API-Break statement: passes" 0 "" \
    gate "$tmp/r-case-a" "$scratch/accepted/empty.txt" "$tmp/body-break" "$tmp/surfaces-parser"

# 4. API-Break: followed by spaces only is not a statement
check "API-Break: followed by spaces only: blocked" 1 "no API-Break statement" \
    gate "$tmp/r-case-a" "$scratch/accepted/empty.txt" "$tmp/body-spaces" "$tmp/surfaces-parser"

# 5. a missing report for a table surface
check "missing report: blocked" 1 "missing report for shell (task skipped or not run)" \
    gate "$tmp/r-missing" "$scratch/accepted/empty.txt" - "$tmp/surfaces-parser-shell"

# 6. a break covered by an accepted entry -> passes
check "accepted break: passes" 0 "" \
    gate "$tmp/r-pprof" "$scratch/accepted/pprof.txt" - "$tmp/surfaces-pprof"

# 7. a stale accepted entry -> blocked
check "stale accepted entry: blocked" 1 "delete stale accepted entries" \
    gate "$tmp/r-pprof" "$scratch/accepted/pprof-stale.txt" - "$tmp/surfaces-pprof"

# 8. growth in the scratch repo, without a statement -> blocked
check "growth without statement: blocked" 1 "add to the PR body: API-Break: <reason>" \
    gate "$tmp/r-pprof" "$scratch/accepted/pprof-grow.txt" - "$tmp/surfaces-pprof"

# 9. growth with a statement -> passes
check "growth with API-Break statement: passes" 0 "" \
    gate "$tmp/r-pprof" "$scratch/accepted/pprof-grow.txt" "$tmp/body-break" "$tmp/surfaces-pprof"

# 10. an unreadable base -> refused, never silently passed (R3)
check "unreadable base: refused" 2 "refusing to pass on an unreadable base" \
    gate "$tmp/r-clean" "$scratch/accepted/empty.txt" - "$tmp/surfaces-parser" no-such-ref

# 11. local mode (no PR body) with a break -> blocked
check "local mode with a break: blocked, asks for the statement" 1 "add to the PR body: API-Break: <reason>" \
    gate "$tmp/r-case-a" "$scratch/accepted/empty.txt" - "$tmp/surfaces-parser"

# 12. the source-only case-(f) incompatibility counts as a break
check "source-only break (case f): counts, names the member" 1 "reset" \
    gate "$tmp/r-case-f" "$scratch/accepted/empty.txt" - "$tmp/surfaces-parser"

# 13. day one: the accepted file absent at base — the initial list is the
#     ratchet's starting point, not growth (the branch-base end-to-end case)
check "day one: file absent at base, entries are not growth" 0 "" \
    gate "$tmp/r-pprof" "$scratch/accepted/pprof-day1.txt" - "$tmp/surfaces-pprof"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
