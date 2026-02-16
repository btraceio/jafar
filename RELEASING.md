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

Update `jfr-shell-plugins.json` with the release version for both backend plugins:

```json
{
  "plugins": {
    "jdk": {
      "latestVersion": "0.4.0",
      ...
    },
    "jafar": {
      "latestVersion": "0.4.0",
      ...
    }
  }
}
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
git add build.gradle jafar-gradle-plugin/build.gradle jfr-shell-plugins.json CHANGELOG.md
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
4. ⏳ Wait for Maven Central sync, then update [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog)
5. ✅ Create GitHub Release with changelog notes

### 5. Monitor Release Workflow

Watch the workflow at: https://github.com/btraceio/jafar/actions

The release workflow typically takes 10-15 minutes to complete all steps.

### 5.5. JBang Catalog Update Timing

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

### 6. Verify Release

After the workflow completes, verify:

```bash
# Test JBang installation (may work immediately via GitHub Packages, or after Maven Central sync)
jbang --fresh jfr-shell@btraceio --version

# Test Maven artifact directly from Maven Central
# Note: Maven Central sync typically takes ~2 hours after publishing
# Check: https://central.sonatype.com/artifact/io.btrace/jafar-parser/0.8.0
```

**Expected Timeline:**
- **Immediately (0-15 min)**: GitHub Release created, artifacts published to Sonatype
- **Within 2 hours**: Artifacts appear on Maven Central
- **Within 2.5 hours**: JBang catalog updated automatically (if Maven Central sync completes)

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

Update `jfr-shell-plugins.json` to next SNAPSHOT version:
```json
{
  "plugins": {
    "jdk": {
      "latestVersion": "0.5.0-SNAPSHOT",
      ...
    },
    "jafar": {
      "latestVersion": "0.5.0-SNAPSHOT",
      ...
    }
  }
}
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
git add build.gradle jafar-gradle-plugin/build.gradle jfr-shell-plugins.json CHANGELOG.md
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
