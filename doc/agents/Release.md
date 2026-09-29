# Release process

Agent-facing summary. [RELEASING.md](../../RELEASING.md) is the complete reference and wins on any
detail this page omits.

The project uses a fully automated release workflow. See [RELEASING.md](../../RELEASING.md) for complete details.

## Quick Release Steps

The project version is **derived from git tags** (`gradle/version-from-tag.gradle`): exactly on
`vX.Y.Z` → `X.Y.Z`, elsewhere → `<newest tag>-SNAPSHOT`. No version numbers are ever edited;
a release is made by tagging. See [RELEASING.md](../../RELEASING.md).

1. **Update CHANGELOG.md** with release notes for the new version, commit and push to main
2. **Create and push tag** (or run `scripts/release.sh [--dry-run] <major|minor|patch>`, which
   derives the version from the newest tag and handles the release branch):
   ```bash
   git tag -a v0.4.0 -m "Release v0.4.0"
   git push origin v0.4.0
   ```

## What Happens Automatically

The release workflow (`.github/workflows/release.yml`) automatically:
- Tags the Go module as `go-parser/vX.Y.Z` (validated first: a Go module version is immutable once
  the proxy has served it) - see [RELEASING.md](../../RELEASING.md) section 5
- Publishes `jafar-parser` and `jafar-tools` to Maven Central (Sonatype)
- Publishes `jafar-gradle-plugin` to Maven Central (Sonatype)
- Publishes `jfr-shell` to GitHub Packages
- Triggers JitPack build and waits for completion
- Commits the `jfr-shell-plugins.json` plugin catalog update to `main` (never downgrades)
- Updates [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) with new version
- Creates GitHub Release with changelog notes

## Version Management

- **Single source of truth**: `gradle/version-from-tag.gradle` — derives the version from git tags
  (exactly on `vX.Y.Z` → `X.Y.Z`; elsewhere → `<newest vX.Y.Z tag anywhere>-SNAPSHOT`; no git
  metadata → `0.0.0-SNAPSHOT`). Applied by the root `build.gradle` and by
  `jafar-gradle-plugin/build.gradle` (a separate Gradle build, cannot read the root's version)
- **Subprojects**: Use `rootProject.version` (automatic sync)
- **Go module**: no version in a file; it is the `go-parser/vX.Y.Z` git tag, created by the release
  workflow from the Java version. Plain `vX.Y.Z` tags do **not** version the Go module - a
  subdirectory module needs the directory prefix
- **Backend plugins registry**: `jfr-shell-plugins.json` — updated automatically by the release
  workflow (must always point to the latest **released** version, never SNAPSHOT — see below)
- **Development versions**: `<latest tag>-SNAPSHOT` automatically; no bump commits

## Post-Release

Nothing to do. There is no "prepare for next development iteration" step: main and release
branches report `<latest tag>-SNAPSHOT` automatically as soon as the release tag exists, and the
release workflow commits the `jfr-shell-plugins.json` update to `main`.

## Plugin Catalog Versioning Rule

`jfr-shell-plugins.json` is fetched at runtime from the `main` branch by `PluginRegistry` to resolve backend plugin versions for installation. It must **always** contain the latest released version and `"repository": "maven-central"`. Never set it to a SNAPSHOT version — doing so breaks backend installation for all users.

The catalog version must never be downgraded across major/minor boundaries. For example, if the catalog already points to `0.12.0` and a patch release `0.11.5` is published, the catalog must remain at `0.12.0`.

## Testing Releases

```bash
# Verify JBang distribution (available immediately)
jbang --fresh jfr-shell@btraceio --version

# Verify Maven Central (takes ~2 hours to sync)
# Check: https://central.sonatype.com/artifact/io.btrace/jafar-parser/X.Y.Z
```

## Manual Release (Emergency Only)

If automated workflow fails:
```bash
# Publish to Sonatype
SONATYPE_USERNAME=xxx SONATYPE_PASSWORD=xxx ./gradlew publish -x :jfr-shell:publish

# Publish jfr-shell to GitHub Packages
GITHUB_ACTOR=xxx GITHUB_TOKEN=xxx ./gradlew :jfr-shell:publishMavenPublicationToGitHubPackagesRepository
```
