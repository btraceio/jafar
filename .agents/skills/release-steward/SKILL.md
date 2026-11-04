---
name: release-steward
description: Steward a Jafar release end to end — changelog, dry-run, tag, watch the workflow, verify the publish, and watch for the "Maven Central sync failed" failure class. Use when asked to cut a release, release a plane, publish, or when a release workflow run or a Maven Central sync issue needs attention.
---

# Steward a release

Read [AGENTS.md](../../../AGENTS.md) first, then
[doc/agents/Release.md](../../../doc/agents/Release.md) — it is the process. This skill is
the stewardship loop around it; when the two disagree, Release.md wins.

## Before tagging

1. The changelog section for the coming tag is committed and pushed to `main`
   (`## [core/v0.30.1]` — the header the workflow will match).
2. `./gradlew test shadowJar spotlessCheck` — the full gate, before the tag exists.
3. `scripts/release.sh --dry-run [plane] <type>` — read the printed plan: the version
   derived from the plane's newest tag, the tag name, the branch. A wrong number here is
   a wrong release everywhere downstream.
4. Embedded-dependency rule: if the release embeds an updated `io.btrace` module (fat
   jars: check `unzip -p <jar> META-INF/MANIFEST.MF | grep Embedded-Modules`), the
   release notes must say so — the app release is the only way that fix reaches the
   app's users.

## The release

`scripts/release.sh [plane] <type>` — it tags and pushes; the tag triggers the workflow.
Do not hand-run `git tag` (bootstrap case only, see RELEASING.md). Watch the workflow run
to completion: publish → catalog moves → GitHub release. A plane's release tags that
plane's artifacts only — a run that wants to publish more or less than the plane's set is
a malfunction, not a variant.

## Failure modes this skill exists for

- **"Maven Central sync failed for untagged-*"** issues: the burst class from pushes
  without release tags. A release that derives its version from anything other than a
  real plane tag is this bug — `scripts/tests/test_release_plan.sh` pins the refusal;
  if you see it live, the version gate was bypassed by hand, and that is the finding.
- **Catalog downgrade**: `jfr-shell-plugins.json` entries and jbang aliases must never
  move down; a plane's entries move only when that plane released.
- **Maven Central lag**: normal up to ~2h; the scheduled sync workflow retries the jbang
  aliases. Verify with the per-plane artifact URL before escalating.
- **API-breaking change**: every SPI-consuming plane (shell, mcp, llm-anthropic,
  llm-openai) must be tagged the same day. A breaking change released on one plane alone
  strands the plugins.

## After the publish

Verify, then report what you verified and what you did not:

```bash
jbang --fresh jafar-shell@btraceio --version    # shell catalog, ~2h after publish
# per-plane artifact availability:
#   https://central.sonatype.com/artifact/io.btrace/<artifact>/<version>
```

Close the loop: the tracking issue for the release gets the tag, the workflow run and the
verification evidence — merging the tag alone is not done.
