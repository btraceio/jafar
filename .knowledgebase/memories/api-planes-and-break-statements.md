---
id: api-planes-and-break-statements
title: "An api break on a published jafar surface needs an API-Break statement and same-day plane tags - gradle/api-surfaces.txt is the single list both gate halves read"
kind: finding
tags: [api, api break, api-break, japicmp, release-planes, versioning, ratchet, gates]
applies_to: [gradle/api-surfaces.txt, gradle/version-from-tag.gradle, scripts/api-break-check.sh]
source: generated-code-safety-harness/design-api-evolution
check: bash scripts/tests/test_api_break_check.sh 2>&1 | tail -1
status: active
recorded: 2026-11-07
valid_at: 2026-11-07
---


How an api break is allowed to happen in jafar, mechanically.

**Planes.** Artifacts release on independent version planes
(`gradle/version-from-tag.gradle`, the `planeByProject` map): shell (`vX.Y.Z`),
core (`core/vX.Y.Z`, which also tags `go-parser/vX.Y.Z` at the same commit), mcp,
llm-anthropic, llm-openai. `hdump-parser` is explicitly mapped to the shell
plane; `pprof-parser` and `otlp-parser` have no entry and therefore default to
the shell plane. A release-API breaking change (japicmp major) tags every
SPI-consuming plane the same day, each with its own number (RELEASING.md,
"Version planes"). Changing one plane's surface without re-tagging the coupled
planes is the coupling bug this memory exists to prevent.

**One list (R7).** `gradle/api-surfaces.txt` names every gated surface (parser,
shell, mcp, hdump, pprof, otlp, llm-spi) and feeds both halves of the gate:
`scripts/api-baseline.sh` materializes each baseline jar (`maven:` for published
aggregates, downloaded from Maven Central with .sha1 verification - never
resolved through Gradle, whose composite substitution would compare current
code with itself; `tag` for unpublished modules, built from a checkout of the
plane's baseline tag), and `gradle/api-compat.gradle` registers the per-surface
japicmp tasks and cross-checks the plane column against `planeByProject` (drift
fails the build).

**The deciding half.** The Gradle japicmp tasks never fail on a break - a
legitimate API-Break PR must be able to turn green - so
`scripts/api-break-check.sh` parses the txt reports: a change is incompatible
iff its marker token is exactly four characters and the fourth is `!` or `*`
(the marker grammar was derived from real recorded japicmp samples, see the
script header). It enforces the ratchet against
`gradle/api-accepted-breaks.txt`: accepted entries can only shrink (a stale
entry that no longer matches fails with "delete stale accepted entries"), and
accepting a *new* break needs a written reason in the PR body -
`API-Break: <reason>`, matched as `API-Break:` followed by non-whitespace; the
label with nothing after it is not a statement. An unreadable `--base` ref
exits 2 instead of resolving to an empty diff and passing (R3: a fallback that
hides a misconfiguration is a bug).

**The convention.** This is the general escape-hatch pattern: every invariant
that may legitimately bend (`Parity:`, `API-Break:`, `Alloc-Budget:`) has a
sibling check script that demands a written reason in the PR body at the exact
moment the invariant bends. The harness cannot demand taste; it can demand a
visible reason.

Evidence class: proven from source (the three anchored files; the gate's
hermetic tests live in `scripts/tests/test_api_break_check.sh` - run them, see
the `check:` line).
