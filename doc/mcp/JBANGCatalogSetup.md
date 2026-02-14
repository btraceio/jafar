# JBang Catalog Setup for jfr-mcp

This document describes the files that need to be created in the external **btraceio/jbang-catalog** repository to enable JBang distribution of the Jafar MCP server.

## Required Files

The following files need to be added to https://github.com/btraceio/jbang-catalog:

### 1. jfr-mcp.java (Stable Releases)

**Path:** `jfr-mcp.java`

**Content:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.btrace:jfr-mcp:0.10.0
//DESCRIPTION Jafar MCP Server - AI-assisted JFR analysis via Model Context Protocol (Stable)
//JAVA 21+

/**
 * Jafar MCP Server launcher (stable releases).
 *
 * <p>Usage:
 * - HTTP mode:  jbang jfr-mcp@btraceio
 * - STDIO mode: jbang jfr-mcp@btraceio --stdio
 * - Custom port: jbang jfr-mcp@btraceio -Dmcp.port=8080
 *
 * <p>For development snapshots, use: jbang jfr-mcp-dev@btraceio
 */
public class jfr_mcp {
    public static void main(String[] args) throws Exception {
        // Simple pass-through to actual server
        // Port detection is built into JafarMcpServer
        io.jafar.mcp.JafarMcpServer.main(args);
    }
}
```

**Notes:**
- Version number (`0.10.0`) will be auto-updated by the release workflow
- This is a simple pass-through since port detection is built into the server
- The `{{VERSION}}` placeholder will be replaced during releases

---

### 2. jfr-mcp-dev.java (Development Snapshots)

**Path:** `jfr-mcp-dev.java`

**Content:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.btrace:jfr-mcp:main-SNAPSHOT
//REPOS mavencentral,ossrh-snapshots=https://s01.oss.sonatype.org/content/repositories/snapshots
//DESCRIPTION Jafar MCP Server - AI-assisted JFR analysis (Development Snapshots)
//JAVA 21+

/**
 * Jafar MCP Server launcher (development snapshots).
 *
 * <p>Always uses the latest main-SNAPSHOT build from Sonatype.
 *
 * <p>Usage:
 * - HTTP mode:  jbang jfr-mcp-dev@btraceio
 * - STDIO mode: jbang jfr-mcp-dev@btraceio --stdio
 * - Custom port: jbang jfr-mcp-dev@btraceio -Dmcp.port=8080
 *
 * <p>For stable releases, use: jbang jfr-mcp@btraceio
 */
public class jfr_mcp_dev {
    public static void main(String[] args) throws Exception {
        io.jafar.mcp.JafarMcpServer.main(args);
    }
}
```

**Notes:**
- Always uses `main-SNAPSHOT` (no version updates needed)
- Requires OSSRH snapshots repository
- Pulls latest snapshot on each `jbang --fresh` invocation

---

### 3. Update jbang-catalog.json

**Path:** `jbang-catalog.json`

**Add these entries to the `aliases` section:**

```json
{
  "aliases": {
    "jfr-shell": {
      "script-ref": "jfr-shell.java",
      "description": "Interactive JFR analysis shell (stable)"
    },
    "jfr-shell-latest": {
      "script-ref": "jfr-shell-latest.java",
      "description": "Interactive JFR analysis shell (latest snapshot)"
    },
    "jfr-mcp": {
      "script-ref": "jfr-mcp.java",
      "description": "Jafar MCP Server for AI-assisted JFR analysis (stable)"
    },
    "jfr-mcp-dev": {
      "script-ref": "jfr-mcp-dev.java",
      "description": "Jafar MCP Server for AI-assisted JFR analysis (development)"
    }
  }
}
```

**Notes:**
- The `jfr-mcp` alias points to the stable release script
- The `jfr-mcp-dev` alias points to the development snapshot script

---

## Automated Updates

The jafar repository's GitHub Actions workflows will automatically update these files:

### Release Workflow (release.yml)

When a new version is released (e.g., `v0.10.0`):

1. **Update jfr-mcp.java:**
   ```bash
   sed -i "s|io.btrace:jfr-mcp:[^\"]*|io.btrace:jfr-mcp:$VERSION|g" jfr-mcp.java
   ```

2. **Update jbang-catalog.json:**
   ```bash
   sed -i "s|io.btrace:jfr-mcp:[^\"]*|io.btrace:jfr-mcp:$VERSION|g" jbang-catalog.json
   ```

3. **Commit changes:**
   ```bash
   git add jbang-catalog.json jfr-mcp.java
   git commit -m "Update jafar-shell and jfr-mcp to version $VERSION"
   git push
   ```

### Sync Workflow (sync-jbang-catalog.yml)

Runs every 30 minutes to check if Maven Central has the latest artifacts:

1. Checks both `jafar-shell` and `jfr-mcp` on Maven Central
2. If both artifacts are available, updates catalog files
3. Commits and pushes changes

**Note:** `jfr-mcp-dev.java` is never updated (always uses `main-SNAPSHOT`).

---

## Testing the Setup

After creating these files, test them:

### Test Stable Version
```bash
# Direct execution
jbang jfr-mcp@btraceio --stdio

# Install as command
jbang app install jfr-mcp@btraceio
jfr-mcp --stdio
```

### Test Development Version
```bash
# Direct execution
jbang jfr-mcp-dev@btraceio --stdio

# Install as command
jbang app install jfr-mcp-dev@btraceio
jfr-mcp-dev --stdio
```

### Verify Version Resolution
```bash
# Check what version JBang resolves
jbang info jfr-mcp@btraceio
jbang info jfr-mcp-dev@btraceio
```

### Test Claude Desktop Integration
```json
{
  "mcpServers": {
    "jafar": {
      "command": "jbang",
      "args": ["jfr-mcp@btraceio", "--stdio"],
      "env": {}
    }
  }
}
```

Restart Claude Desktop and verify the MCP server is detected.

---

## Troubleshooting

### Version Not Updating

**Problem:** Stable version still shows old version after release

**Solution:**
1. Check if artifact is available on Maven Central:
   ```bash
   curl -I https://repo1.maven.org/maven2/io/btrace/jfr-mcp/0.10.0/jfr-mcp-0.10.0.pom
   ```

2. Clear JBang cache and retry:
   ```bash
   jbang cache clear
   jbang --fresh jfr-mcp@btraceio
   ```

### Development Snapshots Not Found

**Problem:** `jfr-mcp-dev` can't find `main-SNAPSHOT`

**Solution:**
1. Verify snapshot publishing to OSSRH:
   ```bash
   curl -I https://s01.oss.sonatype.org/content/repositories/snapshots/io/btrace/jfr-mcp/main-SNAPSHOT/
   ```

2. Check if snapshot publishing is enabled in `build.gradle`

### Catalog Not Found

**Problem:** `jfr-mcp@btraceio` not resolving

**Solution:**
1. Add catalog explicitly:
   ```bash
   jbang catalog add btraceio https://github.com/btraceio/jbang-catalog/blob/main/jbang-catalog.json
   ```

2. Verify catalog is accessible:
   ```bash
   jbang catalog list
   jbang alias list btraceio
   ```

---

## Files Summary

| File | Purpose | Auto-Updated | Notes |
|------|---------|--------------|-------|
| `jfr-mcp.java` | Stable release launcher | Yes (on release) | Version auto-updated by workflow |
| `jfr-mcp-dev.java` | Dev snapshot launcher | No (always main-SNAPSHOT) | Pulls latest snapshot |
| `jbang-catalog.json` | Alias registry | Yes (on release) | Defines both stable and dev aliases |

---

## Pull Request Template

When submitting the PR to btraceio/jbang-catalog, use this template:

**Title:** Add Jafar MCP Server JBang distribution

**Description:**
Add JBang distribution for the Jafar MCP Server, enabling AI-assisted JFR analysis via Model Context Protocol.

**Changes:**
- Add `jfr-mcp.java` - Stable release launcher (auto-updated on releases)
- Add `jfr-mcp-dev.java` - Development snapshot launcher (always uses main-SNAPSHOT)
- Update `jbang-catalog.json` - Register both stable and dev aliases

**Usage:**
```bash
# Stable
jbang app install jfr-mcp@btraceio
jfr-mcp --stdio

# Development
jbang app install jfr-mcp-dev@btraceio
jfr-mcp-dev --stdio
```

**Automated Updates:**
The jafar repository's release workflow will automatically update `jfr-mcp.java` with new version numbers on each release.

**Related:**
- Jafar repository: https://github.com/btraceio/jafar
- MCP Server docs: https://github.com/btraceio/jafar/blob/main/doc/mcp/Tutorial.md

---

## Checklist

- [ ] Create `jfr-mcp.java` with current version
- [ ] Create `jfr-mcp-dev.java` with main-SNAPSHOT
- [ ] Update `jbang-catalog.json` with new aliases
- [ ] Test stable version: `jbang jfr-mcp@btraceio --stdio`
- [ ] Test dev version: `jbang jfr-mcp-dev@btraceio --stdio`
- [ ] Verify Claude Desktop integration
- [ ] Submit pull request to btraceio/jbang-catalog
- [ ] Update jafar docs with JBang installation instructions (already done)
