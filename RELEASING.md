# Release Process

This document describes the automated release process for JAFAR.

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main` branch
2. CHANGELOG.md is updated with release notes for the new version
3. You have set up GitHub secrets:
   - `SONATYPE_USERNAME` - Sonatype OSSRH username
   - `SONATYPE_PASSWORD` - Sonatype OSSRH password

## Release Steps

### 1. Update Version

Edit `build.gradle` and update the version:

```gradle
project.version="0.4.0"  // Remove -SNAPSHOT suffix for releases
```

Also update `jafar-gradle-plugin/build.gradle`:

```gradle
version = "0.4.0"  // Remove -SNAPSHOT suffix
```

### 2. Update CHANGELOG.md

Ensure the changelog has an entry for the new version:

```markdown
## [0.4.0] - 2025-01-15

### Added
- Feature X
- Feature Y

### Fixed
- Bug Z
```

### 3. Commit and Push

```bash
git add build.gradle jafar-gradle-plugin/build.gradle CHANGELOG.md
git commit -m "Prepare for release v0.4.0"
git push origin main
```

### 4. Create and Push Release Tag

```bash
git tag -a v0.4.0 -m "Release v0.4.0"
git push origin v0.4.0
```

**This triggers the automated release workflow** which will:
1. ✅ Publish `jafar-parser`, `jafar-tools`, and `jfr-shell` to Maven Central (Sonatype)
2. ✅ Publish `jafar-gradle-plugin` to Maven Central (Sonatype)
3. ✅ Publish `jfr-shell` to GitHub Packages (backup distribution)
4. ✅ Automatically update [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog)
5. ✅ Create GitHub Release with changelog notes

### 5. Monitor Release Workflow

Watch the workflow at: https://github.com/btraceio/jafar/actions

The release workflow typically takes 10-15 minutes to complete all steps.

### 6. Verify Release

After the workflow completes, verify:

```bash
# Test JBang installation
jbang --fresh jfr-shell@btraceio --version

# Test Maven artifact (wait ~2 hours for Maven Central sync)
# Check: https://central.sonatype.com/artifact/io.btrace/jafar-parser/0.4.0
```

### 7. Prepare for Next Development Iteration

Update versions to next SNAPSHOT:

Edit `build.gradle`:
```gradle
project.version="0.5.0-SNAPSHOT"
```

Edit `jafar-gradle-plugin/build.gradle`:
```gradle
version = "0.5.0-SNAPSHOT"
```

Update CHANGELOG.md:
```markdown
## [Unreleased]

(empty for now)

## [0.4.0] - 2025-01-15
...
```

Commit:
```bash
git add build.gradle jafar-gradle-plugin/build.gradle CHANGELOG.md
git commit -m "Prepare for next development iteration"
git push origin main
```

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

- Check if the catalog update job succeeded in the workflow
- Manually verify: https://github.com/btraceio/jbang-catalog/commits/main
- If needed, manually create PR to update catalog

## Manual Release (Emergency)

If automated workflow fails, you can manually release:

```bash
# 1. Publish to Sonatype
SONATYPE_USERNAME=xxx SONATYPE_PASSWORD=xxx ./gradlew publish -x :jfr-shell:publish

# 2. Publish jfr-shell to GitHub Packages
GITHUB_ACTOR=xxx GITHUB_TOKEN=xxx ./gradlew :jfr-shell:publishMavenPublicationToGitHubPackagesRepository

# 3. Trigger JitPack manually
curl "https://jitpack.io/com/github/btraceio/jafar/v0.4.0/build.log"

# 4. Update JBang catalog manually
# Clone btraceio/jbang-catalog and update version in:
# - jbang-catalog.json
# - jfr-shell.java
```

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):
- **Major (X.0.0)**: Breaking API changes
- **Minor (0.X.0)**: New features, backward compatible
- **Patch (0.0.X)**: Bug fixes, backward compatible

Development versions use `-SNAPSHOT` suffix (e.g., `0.4.0-SNAPSHOT`).
