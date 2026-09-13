# Release process

Agent-facing summary. [RELEASING.md](../../RELEASING.md) is the complete reference and wins on any
detail this page omits.

The project uses a fully automated release workflow. See [RELEASING.md](../../RELEASING.md) for complete details.

## Quick Release Steps

1. **Update versions** in `build.gradle`, `jafar-gradle-plugin/build.gradle`, and `jfr-shell-plugins.json` (remove `-SNAPSHOT`)
2. **Update CHANGELOG.md** with release notes for the new version
3. **Commit and push** changes to main branch
4. **Create and push tag**:
   ```bash
   git tag -a v0.4.0 -m "Release v0.4.0"
   git push origin v0.4.0
   ```

## What Happens Automatically

The release workflow (`.github/workflows/release.yml`) automatically:
- Tags the Go module as `go-parser/vX.Y.Z` (validated first: a Go module version is immutable once
  the proxy has served it) - see [RELEASING.md](../../RELEASING.md) section 5.6
- Publishes `jafar-parser` and `jafar-tools` to Maven Central (Sonatype)
- Publishes `jafar-gradle-plugin` to Maven Central (Sonatype)
- Publishes `jfr-shell` to GitHub Packages
- Triggers JitPack build and waits for completion
- Updates [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) with new version
- Creates GitHub Release with changelog notes

## Version Management

- **Root version**: Defined in `build.gradle` as `project.version="X.Y.Z"`
- **Go module**: no version in a file; it is the `go-parser/vX.Y.Z` git tag, created by the release
  workflow from the Java version. Plain `vX.Y.Z` tags do **not** version the Go module - a
  subdirectory module needs the directory prefix
- **Subprojects**: Use `rootProject.version` (automatic sync)
- **Gradle plugin**: Has separate version in `jafar-gradle-plugin/build.gradle`
- **Backend plugins registry**: `jfr-shell-plugins.json` (must always point to the latest **released** version, never SNAPSHOT — see below)
- **Development versions**: Use `-SNAPSHOT` suffix (e.g., `0.4.0-SNAPSHOT`)

## Post-Release

After release completes, prepare for next development iteration:

```bash
# Update to next SNAPSHOT version
# Edit build.gradle: project.version="0.5.0-SNAPSHOT"
# Edit jafar-gradle-plugin/build.gradle: version = "0.5.0-SNAPSHOT"
# Do NOT update jfr-shell-plugins.json — it must keep pointing to the latest release
# Update CHANGELOG.md with [Unreleased] section

git add build.gradle jafar-gradle-plugin/build.gradle CHANGELOG.md
git commit -m "Prepare for next development iteration"
git push origin main
```

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
