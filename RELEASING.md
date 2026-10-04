# Release Process

This document describes the automated release process for JAFAR.

The project releases in **version planes** (see ["Version planes"](#version-planes) below):
each plane tags independently and publishes only its own artifacts. Versions are **derived from
tags** (`gradle/version-from-tag.gradle`), never stored in any build file: a build exactly on a
plane's tag reports `X.Y.Z`, any other build reports `X.Y.Z-SNAPSHOT` from that plane's newest
tag. A release is therefore made by tagging - no version numbers are edited anywhere.

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main` branch
2. CHANGELOG.md is updated with release notes for the new version
3. You have set up GitHub secrets:
   - `SONATYPE_USERNAME` - Sonatype OSSRH username
   - `SONATYPE_PASSWORD` - Sonatype OSSRH password

## Release Steps

### 1. Update CHANGELOG.md

Add a section for the release being cut. The header carries the tag; the section holds only
that release's notes:

```markdown
## [core/v0.29.1] - 2025-01-15

### Fixed
- Bug Z
```

Pre-split sections keep the bare-version header (`## [0.29.0]`) and still match. Commit and
push it to `main`.

### 2. Tag the plane's release

Run `scripts/release.sh [--dry-run] [plane] <major|minor|patch>` - it derives the version from
that plane's newest tag, applies the branch policy (release branches are a shell-plane thing;
every other plane tags from the default branch) and pushes. Dry-run first. Hand-tagging
`git tag <plane>/vX.Y.Z` is a fallback for when the script cannot be used (see the bootstrap in
"Version planes"); do not hand-tag as a habit.

**A plane's tag triggers the release workflow**, which publishes **that plane's artifacts
only** (that is the point of the split: a tag re-ships what changed in it, not every artifact at
a shared root version):
1. Publish that plane to Maven Central (Sonatype): shell = jafar-shell + the two backend
   plugins; core = jafar-parser/jafar-tools + jafar-gradle-plugin (and tags go-parser/vX.Y.Z);
   mcp = jfr-mcp; the llm planes = their one artifact
2. Move only that plane's entries: commit the jfr-shell-plugins.json plugin-catalog update to
   main (per-plugin, never a downgrade), update btraceio/jbang-catalog aliases for the planes
   that have them
3. Create the GitHub Release with the changelog section matched by the tag

> There is no separate binary distribution channel: no release assets are attached to GitHub
> releases, and nothing is published to GitHub Packages. JBang resolves the Maven artifacts, and
> the `jfr-shell-plugins.json` catalog points users at the installable backend plugins.

> **Bundled catalog note:** the released `jafar-shell` jar bundles
> `jfr-shell-plugins.json` (offline fallback for `PluginRegistry`). The publish job patches that
> one file in the working tree to point at the release being published — the tag itself is not
> rewritten, so checking out the tag shows the previous release's catalog in that one file. The
> exact diff applied at release time is recorded in the workflow run's step summary.

### 3. Monitor Release Workflow

Watch the workflow at: https://github.com/btraceio/jafar/actions

The release workflow typically takes 10-15 minutes to complete all steps.

### 4. JBang Catalog Update Timing

The JBang catalog is updated automatically once artifacts are available on Maven Central:

**Immediate (within 10 minutes):**
- If Maven Central sync completes quickly (rare), the catalog updates during the release workflow
- The workflow polls Maven Central every 30-60 seconds for up to 10 minutes

**Scheduled (30-minute intervals):**
- If Maven Central sync is delayed (typical ~2 hours), a scheduled workflow checks every 30 minutes
- Once artifacts are available, the catalog is updated automatically
- No manual intervention required

**Fallback (after 24 hours):**
- If Maven Central sync fails after 24 hours, a GitHub issue is created automatically
- The issue includes troubleshooting steps and manual update instructions

**Tracking:**
- Pending updates are tracked in `.github/pending-jbang-updates.json`
- This file is automatically created if Maven Central is not immediately available
- It's removed once the catalog is updated or an issue is created

### 5. Go Module Tag

The `tag-go-module` job creates a second tag, `go-parser/vX.Y.Z`, pointing at the same commit as
the release tag. It runs only for tag-triggered releases, after `publish-maven`.

This is not cosmetic. A Go module in a subdirectory is versioned by tags prefixed with that
directory: consumers ask for `github.com/btraceio/jafar/go-parser@vX.Y.Z` and the `go` command
looks for the tag `go-parser/vX.Y.Z`. The plain `vX.Y.Z` tag is read as a version of the
repository root, which does not contain the package, so without the prefixed tag a consumer gets:

```
go: module github.com/btraceio/jafar@vX.Y.Z found,
    but does not contain package github.com/btraceio/jafar/go-parser
```

The job validates before it tags - `gofmt`, `go vet` and `go test` on the released commit -
because **a module version is immutable once the Go proxy has served it**. A broken tag cannot be
corrected, only abandoned in favour of the next patch version.

**Going to 2.0.0 needs a code change first.** From v2 on, Go requires the major version in the
module path itself. The job refuses to tag `go-parser/v2.0.0` while `go-parser/go.mod` still reads
`module github.com/btraceio/jafar/go-parser`; the path has to become
`github.com/btraceio/jafar/go-parser/v2`, with every import updated, before that release can go
out. See [Go major version suffixes](https://go.dev/ref/mod#major-version-suffixes).

### 6. Verify Release

After the workflow completes, verify:

```bash
# Test the Go module (core-plane releases only; available as soon as the tag is pushed)
go list -m github.com/btraceio/jafar/go-parser@X.Y.Z

# Test JBang installation (available as soon as Maven Central syncs)
jbang --fresh jfr-shell@btraceio --version

# Test Maven artifact directly from Maven Central
# Note: Maven Central sync typically takes ~2 hours after publishing
# Check: https://central.sonatype.com/artifact/io.btrace/jafar-parser/0.8.0
```

**Expected Timeline:**
- **Immediately (0-15 min)**: GitHub Release created, artifacts published to Sonatype, Go module tag pushed, plugin catalog committed to `main`
- **Within 2 hours**: Artifacts appear on Maven Central
- **Within 2.5 hours**: JBang catalog updated automatically (if Maven Central sync completes)

There is no "prepare for next development iteration" step: main and release branches report
`<latest tag>-SNAPSHOT` automatically as soon as the tag exists, and the release workflow commits
the `jfr-shell-plugins.json` update to `main`.

### Plugin Catalog Version Rule

Each plugin entry in `jfr-shell-plugins.json` carries its own version (the planes release
independently) and `"repository": "maven-central"`; the release workflow's update job moves
**only the released plane's entries** (shell: jdk/jafar; llm-anthropic: anthropic;
llm-openai: openai/ollama) and skips the core/mcp planes, which publish nothing in it. The
catalog is consumed from main at runtime by PluginRegistry, so an entry must never be a
SNAPSHOT, and must never be downgraded within its plane: if `anthropic` already points to
`0.12.0`, a tag `llm-anthropic/v0.11.5` leaves the catalog at `0.12.0`.

## Troubleshooting

### JitPack Build Fails

- Check build logs: `https://jitpack.io/com/github/btraceio/jafar/v0.4.0`
- Common issues:
  - Missing JDK versions (should be auto-provisioned via Foojay resolver)
  - Build timeout (increase complexity limit in jitpack.yml)

### Sonatype Publish Fails

- Verify credentials are set in GitHub secrets
- Check Sonatype OSSRH status: https://status.central.sonatype.com/
- Review workflow logs for authentication errors

### JBang Catalog Not Updated

The catalog update process has multiple fallbacks:

1. **Check Maven Central availability:**
   - Visit: `https://repo1.maven.org/maven2/io/btrace/jafar-shell/VERSION/jafar-shell-VERSION.pom`
   - Replace `VERSION` with your release version (e.g., `0.8.0`)
   - If you get HTTP 404, Maven Central hasn't synced yet (typically takes ~2 hours)

2. **Check pending updates:**
   - Look for `.github/pending-jbang-updates.json` in the main branch
   - If present, the scheduled workflow will retry every 30 minutes

3. **Check scheduled workflow:**
   - Workflow runs: https://github.com/btraceio/jafar/actions/workflows/sync-jbang-catalog.yml
   - Should automatically update catalog once Maven Central sync completes

4. **Check for auto-created issues:**
   - After 24 hours, an issue is created if sync fails
   - Look for issues labeled `release` and `maven-central`

5. **Manual update (if needed):**
   - Clone https://github.com/btraceio/jbang-catalog
   - Update version in `jbang-catalog.json` and `jafar-shell.java`
   - Create PR with changes
   - Remove `.github/pending-jbang-updates.json` from main branch

## Manual Release (Emergency)

If automated workflow fails, you can manually release:

```bash
# 1. Publish the main artifacts (same set the workflow publishes)
SONATYPE_USERNAME=xxx SONATYPE_PASSWORD=xxx \
  ./gradlew :parser:shadowJar :parser:publishAllPublicationsToMavenCentralRepository :tools:shadowJar :tools:publishAllPublicationsToMavenCentralRepository :jfr-shell:shadowJar :jfr-shell:publishAllPublicationsToMavenCentralRepository :jfr-mcp:shadowJar :jfr-mcp:publishAllPublicationsToMavenCentralRepository :llm-anthropic:shadowJar :llm-anthropic:publishAllPublicationsToMavenCentralRepository

# 1b. Publish backend plugins
./gradlew :jfr-shell-jdk:publishAllPublicationsToMavenCentralRepository :jfr-shell-jafar:publishAllPublicationsToMavenCentralRepository --no-daemon --stacktrace

# 2. Update JBang catalog manually
# Clone btraceio/jbang-catalog and update version in:
# - jbang-catalog.json
# - jafar-shell.java
```

## Version planes

Releases are split into planes: each plane tags and publishes only its own artifacts, so a
parser-only change re-ships ~1 MiB and does not re-publish the 29-MiB LLM plugin, and vice
versa. The version is **derived from git tags** per plane by `gradle/version-from-tag.gradle`
(single source of truth: the root build, every subproject, and the `jafar-gradle-plugin`
included build all apply it). Builds never edit version numbers; releasing a plane means
cutting its next tag.

| Plane | Tag shape | Publishes | Cost | Branch policy |
|---|---|---|---|---|
| shell | `vX.Y.Z` | `jafar-shell` (fat), `jfr-shell-jdk`, `jfr-shell-jafar` | ~10 MiB | release branches (`release/X.Y._`): minor/major from the default branch, patches continue the line |
| core | `core/vX.Y.Z` | `jafar-parser` (+parser-core/codegen), `jafar-tools`, `jafar-gradle-plugin`; tags `go-parser/vX.Y.Z` | ~1 MiB | tags from the default branch, patches included |
| mcp | `mcp/vX.Y.Z` | `jfr-mcp` | ~14 MiB | tags from the default branch |
| llm-anthropic | `llm-anthropic/vX.Y.Z` | `llm-anthropic` | ~29 MiB | tags from the default branch |
| llm-openai | `llm-openai/vX.Y.Z` | `llm-openai` | ~0.1 MiB | tags from the default branch |

Per-project context:

| Build context | Version |
|---|---|
| Exactly on the plane's tag | `X.Y.Z` |
| Anywhere else with git metadata | `<newest tag of the plane's shape>-SNAPSHOT`; a plane never tagged rides the newest `vX.Y.Z` number until its first tag lands |
| No git metadata (source tarball) | `0.0.0-SNAPSHOT` |

Only well-formed tags count (`vX.Y.Z` / `<plane>/vX.Y.Z`); loose globs can match rc tags, so
every hit is re-checked. Tag names carry the plane, so no two planes' tags can collide.

Coupling rules — what a change implies:

- **Self-contained planes release independently.** The shell and MCP fat jars embed their
dependencies; their manifests record exactly which (`Embedded-Modules:
io.btrace:parser@X,...` — read back with `unzip -p jar META-INF/MANIFEST.MF`). App releases pick
up core-plane changes at their own cadence, so a shell release notes tell the users which
parser they are getting.
- **Backend plugins and the LLM plugins resolve their SPI from the shell at run time** (the
plugin classloader parents to the app), so a *non-breaking* shell-core change ships nothing
outside the shell and MCP planes; an **API-breaking change** (japicmp = major version bump
required by the plugin API policy) means tagging every SPI-consuming plane (shell, mcp,
llm-anthropic, llm-openai) the same day, each with its own number.
- **The Go module versions with the core plane**: `go-parser/vX.Y.Z` is created by a
core-plane release at the same commit as the Java parser. The parity rule in
[AGENTS.md](AGENTS.md) (the two untyped parsers kept in step) is what makes the shared number
meaningful.

Semantic versioning applies per plane: major = breaking API, minor = features, patch = fixes.
All planes are 0.x — no compatibility guarantees yet; the first `v1.0.0` per plane is where the
compatibility promise starts (Go's own rules agree: v0.x = experimental).
