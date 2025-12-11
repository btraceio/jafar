# JFR Shell via JBang

This guide covers using jfr-shell through [JBang](https://jbang.dev), which provides the easiest installation and usage experience without requiring Java or build tools.

## Installation Methods

### Direct Execution (No Installation)

The simplest way to use jfr-shell is to run it directly without any setup:

```bash
# From custom catalog (recommended)
jbang jfr-shell@btraceio recording.jfr

# From GitHub script URL
jbang https://github.com/btraceio/jbang-catalog/blob/main/jfr-shell.java recording.jfr

# From JitPack coordinates
jbang io.github.btraceio:jfr-shell:0.1.0 recording.jfr
```

### Install as Command

For repeated use, install jfr-shell as a permanent command:

```bash
# Install
jbang app install jfr-shell@btraceio

# Now use like a native command
jfr-shell recording.jfr
jfr-shell show recording.jfr "events/jdk.ExecutionSample | count()"
jfr-shell --help

# Uninstall
jbang app uninstall jfr-shell
```

## JBang Setup

If you don't have JBang installed:

### macOS / Linux

```bash
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

### Windows

```powershell
# PowerShell
iex "& { $(iwr https://ps.jbang.dev) } app setup"
```

### Package Managers

```bash
# macOS (Homebrew)
brew install jbangdev/tap/jbang

# SDKMAN (Linux/macOS/WSL)
sdk install jbang

# Windows (Scoop)
scoop install jbang

# Windows (Chocolatey)
choco install jbang
```

## Usage Examples

### Interactive Mode

Start an interactive REPL session:

```bash
# Open a file immediately
jbang jfr-shell@btraceio -f recording.jfr

# Or start empty and open files from within the shell
jbang jfr-shell@btraceio
jfr> open recording.jfr
jfr> help
jfr> exit
```

### Non-Interactive Queries

Execute single queries from the command line:

```bash
# Count events
jbang jfr-shell@btraceio show recording.jfr "events/jdk.ExecutionSample | count()"

# Top 10 files by bytes read
jbang jfr-shell@btraceio show recording.jfr \
  "events/jdk.FileRead | top(10, by=bytes)"

# Thread analysis
jbang jfr-shell@btraceio show recording.jfr \
  "events/jdk.ExecutionSample | groupBy(thread/name)"

# GC statistics
jbang jfr-shell@btraceio show recording.jfr \
  "events/jdk.GCHeapSummary[when/when='After GC']/heapUsed | stats()"
```

### JSON Output for Scripting

Get JSON output for integration with other tools:

```bash
# JSON format
jbang jfr-shell@btraceio show recording.jfr \
  "events/jdk.FileRead | top(10, by=bytes)" \
  --format json

# Pipe to jq for processing
jbang jfr-shell@btraceio show recording.jfr \
  "events/jdk.ExecutionSample | groupBy(thread/name)" \
  --format json | jq '.[] | select(.count > 1000)'
```

### Metadata Exploration

```bash
# List all event types
jbang jfr-shell@btraceio metadata recording.jfr --events-only

# Search for specific types
jbang jfr-shell@btraceio metadata recording.jfr --search jdk.File

# Show type structure as tree
jbang jfr-shell@btraceio show recording.jfr \
  "metadata/jdk.types.StackTrace" --tree --depth 2
```

### Chunk and Constant Pool Analysis

```bash
# Show chunk summary
jbang jfr-shell@btraceio chunks recording.jfr --summary

# Browse constant pool symbols
jbang jfr-shell@btraceio show recording.jfr "cp/jdk.types.Symbol"

# Filter constant pool entries
jbang jfr-shell@btraceio show recording.jfr \
  "cp/jdk.types.Symbol[string~'java/lang/.*']"
```

## Version Management

### Using Specific Versions

```bash
# Pin to a specific version
jbang io.github.btraceio:jfr-shell:0.1.0 recording.jfr

# Use latest development snapshot
jbang jfr-shell-latest@btraceio recording.jfr
# Or equivalently:
jbang io.github.btraceio:jfr-shell:main-SNAPSHOT recording.jfr
```

### Updating

```bash
# Update installed version
jbang app install --force jfr-shell@btraceio

# Clear cache and reinstall
jbang cache clear
jbang app install jfr-shell@btraceio
```

## Advantages of JBang

✅ **No Java installation required** - JBang downloads Java automatically
✅ **No build tools needed** - No Gradle, Maven, or other setup
✅ **Automatic dependency resolution** - JBang handles all dependencies
✅ **Version pinning** - Lock to specific versions or use snapshots
✅ **Cross-platform** - Works on Windows, macOS, Linux
✅ **Can run from any location** - No need to clone repository
✅ **Isolated environment** - Doesn't interfere with system Java

## Advanced Usage

### Custom Catalog

Add the catalog explicitly for tab completion and shorter commands:

```bash
# Add catalog
jbang catalog add btraceio https://github.com/btraceio/jbang-catalog/blob/main/jbang-catalog.json

# List catalogs
jbang catalog list

# List aliases in catalog
jbang alias list btraceio

# Remove catalog
jbang catalog remove btraceio
```

### Environment Variables

Control JBang behavior with environment variables:

```bash
# Use specific Java version
export JAVA_HOME=/path/to/java-21
jbang jfr-shell@btraceio recording.jfr

# Enable debug output
export JBANG_DEBUG=true
jbang jfr-shell@btraceio recording.jfr
```

### Shell Aliases

Create shell aliases for convenience:

```bash
# Bash/Zsh
echo 'alias jfr="jbang jfr-shell@btraceio"' >> ~/.bashrc
source ~/.bashrc

# Now use as:
jfr recording.jfr
jfr show recording.jfr "events/jdk.ExecutionSample | count()"
```

### Running in Docker

```bash
# JBang works in containers
docker run -it --rm -v $(pwd):/work jbangdev/jbang-action \
  jbang jfr-shell@btraceio /work/recording.jfr
```

### GitHub Actions

Use in CI/CD pipelines:

```yaml
- name: Analyze JFR Recording
  run: |
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    jbang jfr-shell@btraceio show recording.jfr "events/jdk.ExecutionSample | count()"
```

## Troubleshooting

### JBang Not Found

**Problem**: `jbang: command not found`

**Solution**: Install JBang first (see Installation section above).

---

### Java Version Mismatch

**Problem**: `UnsupportedClassVersionError` or Java version errors

**Solution**: JBang automatically downloads Java 21+ if needed. Force a fresh install:

```bash
jbang --fresh jfr-shell@btraceio recording.jfr
```

Or specify Java version explicitly:

```bash
export JAVA_HOME=/path/to/java-21
jbang jfr-shell@btraceio recording.jfr
```

---

### Catalog Not Found

**Problem**: `Could not resolve alias: jfr-shell@btraceio`

**Solution**: Add the catalog explicitly:

```bash
jbang catalog add btraceio \
  https://github.com/btraceio/jbang-catalog/blob/main/jbang-catalog.json

# Verify
jbang catalog list
```

Or use the full GitHub URL directly:

```bash
jbang https://github.com/btraceio/jbang-catalog/blob/main/jfr-shell.java recording.jfr
```

---

### JitPack Build Failed

**Problem**: `Could not resolve io.github.btraceio:jfr-shell:0.1.0`

**Solution**: Check JitPack build status:

```bash
# Visit in browser:
https://jitpack.io/com/github/btraceio/jfr-shell/0.1.0/build.log

# Or trigger build via curl:
curl https://jitpack.io/com/github/btraceio/jfr-shell/0.1.0/build.log
```

Wait a few minutes for JitPack to build the artifact, then retry.

---

### Slow First Run

**Problem**: First execution is slow

**Solution**: This is normal - JBang downloads Java and dependencies on first run. Subsequent runs are fast due to caching. To see what's happening:

```bash
jbang --verbose jfr-shell@btraceio recording.jfr
```

---

### Cache Issues

**Problem**: Outdated dependencies or corrupted cache

**Solution**: Clear JBang cache:

```bash
# Clear all cache
jbang cache clear

# Clear specific cache
jbang cache clear --force
```

---

### Permission Denied (macOS/Linux)

**Problem**: Script not executable

**Solution**: This shouldn't happen with JBang, but if it does:

```bash
chmod +x ~/.jbang/bin/jfr-shell
```

## Performance Considerations

### Cache Location

JBang caches artifacts in:
- **Linux**: `~/.jbang/cache`
- **macOS**: `~/.jbang/cache`
- **Windows**: `%LOCALAPPDATA%\jbang\cache`

First run downloads ~50MB (Java + dependencies). Subsequent runs are instant.

### Startup Time

```bash
# First run: ~10-30 seconds (downloads dependencies)
jbang jfr-shell@btraceio recording.jfr

# Subsequent runs: <1 second (cached)
jbang jfr-shell@btraceio recording.jfr
```

### Network Requirements

- First run requires internet connection to download artifacts
- Offline mode available after initial download:

```bash
jbang --offline jfr-shell@btraceio recording.jfr
```

## Comparison with Other Installation Methods

| Method | Setup Time | Disk Space | Java Required | Best For |
|--------|-----------|------------|---------------|----------|
| **JBang** | 1 minute | ~50MB | No (auto-downloads) | Quick start, CI/CD |
| **Standalone** | 5 minutes | ~55MB | No (bundled) | Offline use, distribution |
| **Gradle** | 2 minutes | ~200MB | Yes (25+) | Development |
| **Fat JAR** | 2 minutes | ~20MB | Yes (25+) | Custom deployment |

## See Also

- [JFR Shell README](../jfr-shell/README.md) - Main documentation
- [JfrPath Reference](jfrpath.md) - Query language syntax
- [JFR Shell Usage Guide](../JFR-SHELL-USAGE.md) - Complete feature overview
- [JBang Documentation](https://www.jbang.dev) - Official JBang docs
- [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) - JBang catalog repository
