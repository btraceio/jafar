# Release process

Agent-facing summary. [RELEASING.md](../../RELEASING.md) is the complete reference and wins on any
detail this page omits.

## Releases are per-plane

The repo releases in **planes**, each with its own tag shape, version line and publish set — a
parser-only change re-ships ~1 MiB and does not re-publish the shell or the LLM plugins:

| Plane | Tag | Publishes | Cost |
|---|---|---|---|
| shell | `vX.Y.Z` | `jafar-shell` (fat jar; the `jfr-shell` module builds it), `jfr-shell-jdk`, `jfr-shell-jafar` | ~10 MiB |
| core | `core/vX.Y.Z` | `jafar-parser` (+parser-core/codegen), `jafar-tools`, `jafar-gradle-plugin`; tags `go-parser/vX.Y.Z` | ~1 MiB |
| mcp | `mcp/vX.Y.Z` | `jfr-mcp` (fat jar) | ~14 MiB |
| llm-anthropic | `llm-anthropic/vX.Y.Z` | `llm-anthropic` | ~29 MiB |
| llm-openai | `llm-openai/vX.Y.Z` | `llm-openai` | ~0.1 MiB |

Versions are **derived from tags** (`gradle/version-from-tag.gradle` — the single source of
truth for the root build, every subproject, and the included `jafar-gradle-plugin` build).
Exactly on a plane's tag → `X.Y.Z`; elsewhere → `<newest tag of the plane's shape>-SNAPSHOT`; a
plane that has never been tagged rides the newest bare `vX.Y.Z` number until its first tag
lands. No version number is ever edited; a release is made by tagging.

## Releasing (agent procedure)

1. **Update CHANGELOG.md** — section header `## [<tag>]` (e.g. `## [core/v0.30.1]`) holding that
   release's notes; commit and push to `main`
2. **Release**: `scripts/release.sh [--dry-run] [plane] <major|minor|patch>` — derives the
   version from the newest tag of the plane, tags, pushes. Dry-run first is the standing
   practice.

Do **not** hand-run `git tag` unless the automation cannot do it (see the bootstrap case in
RELEASING.md). The tag pushes trigger the release workflow, which:
- publishes **only that plane's artifacts** to Maven Central (Sonatype)
- moves **only that plane's entries** in `jfr-shell-plugins.json` (committed to `main`, never a
  downgrade) and in [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) (its
  aliases exist for shell/mcp/core planes; the LLM plugins have no jbang aliases — they install
  from the shell's plugin catalog)
- on a core release, tags `go-parser/vX.Y.Z` (validated first: a Go module version is immutable
  once the proxy has served it)
- creates the GitHub Release with the changelog section **matched by the tag**; the notes fall
  back to the bare-version header, and to the tag text if no section exists

## Coupling rules an agent must respect

- **Self-contained planes release independently.** The shell and MCP fat jars embed everything;
  their manifests record exactly which io.btrace modules and versions went in
  (`Embedded-Modules:`, read with `unzip -p jar META-INF/MANIFEST.MF | grep Embedded-Modules`).
  When an agent updates an embedded dependency and releases the app, the release notes must say
  so — the app release is the only way that fix reaches the app's users.
- **Backend and LLM plugins resolve the shell's interfaces at run time** (classloader
  delegation), so a *non-breaking* shell-core change ships nothing outside the shell and mcp
  planes. An **API-breaking change** (japicmp = a major version bump per the plugin API policy)
  requires tagging every SPI-consuming plane — shell, mcp, llm-anthropic, llm-openai — the same
  day, each with its own number. Never publish a breaking SPI change without those tags.
- **The Go module versions with the core plane** (parser parity — see AGENTS.md): a core release
  tags it; other planes must not.
- **jbang-catalog**: `jafar-shell` aliases = the shell plane, `jfr-mcp` = the mcp plane,
  `jafar-tools` (latest-only) = the core plane. Update only what moved.
- **jfr-shell-plugins.json**: each plugin entry carries its own version; only the released
  plane's entries move (`jq` keyed per plugin, never `.plugins[]` mass-bump). Never a SNAPSHOT;
  never a downgrade of a plane's line.

## Post-release

Nothing to do: main reports each plane's `<newest tag>-SNAPSHOT` automatically the moment the
tag exists; the catalog commits are automatic. If Maven Central lags 2+ hours the scheduled
sync workflow retries the jbang-catalog aliases (currently keyed to the latest shell release —
per-plane catalog updates already happen in `release.yml`).

## Verification an agent owes a release

```bash
scripts/release.sh --dry-run [plane] <type>     # branch policy + tag/version derivation
./gradlew test shadowJar spotlessCheck          # the full gate before tagging
# after publish:
jbang --fresh jafar-shell@btraceio --version    # shell catalog, ~2h after publish (Maven sync)
# versioned-artifact availability (per plane):
#   https://central.sonatype.com/artifact/io.btrace/<artifact>/<version>
```

## Manual release (emergency only)

If the workflow fails, publish **the plane's artifacts only** — the same tasks the workflow's
plane-gated steps run, e.g. for the core plane:

```bash
SONATYPE_USERNAME=xxx SONATYPE_PASSWORD=xxx \
  ./gradlew :parser:shadowJar :parser:publishAllPublicationsToMavenCentralRepository \
            :tools:shadowJar :tools:publishAllPublicationsToMavenCentralRepository
# gradle plugin (also core plane):
cd jafar-gradle-plugin && ./gradlew publishAllPublicationsToMavenCentralRepository
```

Then move the plane's catalog entries and jbang aliases by hand, following the same plane rules.
The workflow run for the same tag would have done exactly this list — do not invent steps.