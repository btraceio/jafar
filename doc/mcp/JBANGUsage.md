# Jafar MCP Server via JBang

This guide covers using the Jafar MCP (Model Context Protocol) server through [JBang](https://jbang.dev), which provides the easiest installation and usage experience without requiring Java or build tools.

## Installation Methods

### Direct Execution (No Installation)

The simplest way to use the MCP server is to run it directly without any setup:

```bash
# From custom catalog (recommended - stable)
jbang jfr-mcp@btraceio

# Development snapshots (latest features)
jbang jfr-mcp-dev@btraceio

# From JitPack coordinates (specific version)
jbang io.github.btraceio:jfr-mcp:0.10.0
```

### Install as Command

For repeated use, install the MCP server as a permanent command:

```bash
# Install stable version
jbang app install jfr-mcp@btraceio

# Or install development version
jbang app install jfr-mcp-dev@btraceio

# Now use like a native command
jfr-mcp                    # HTTP mode on port 3000
jfr-mcp --stdio            # STDIO mode for Claude Desktop
jfr-mcp -Dmcp.port=8080    # Custom port

# Uninstall
jbang app uninstall jfr-mcp
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

### HTTP Mode (Web Clients)

Start the server for web-based MCP clients:

```bash
# Default port 3000
jbang jfr-mcp@btraceio

# Custom port
jbang jfr-mcp@btraceio -Dmcp.port=8080

# Development snapshot
jbang jfr-mcp-dev@btraceio
```

Server endpoints:
- **SSE**: `http://localhost:3000/mcp/sse`
- **Message**: `http://localhost:3000/mcp/message`

### STDIO Mode (Claude Desktop)

For Claude Desktop integration:

```bash
# Stable version
jbang jfr-mcp@btraceio --stdio

# Development version
jbang jfr-mcp-dev@btraceio --stdio
```

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

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

### Port Detection

The MCP server automatically detects if another instance is already running:

```bash
# First instance starts normally
jbang jfr-mcp@btraceio
# Server starts on port 3000

# Second instance detects port in use and exits silently
jbang jfr-mcp@btraceio
# Exits immediately without error

# Use a different port if needed
jbang jfr-mcp@btraceio -Dmcp.port=8080
```

**Note:** Port detection only applies to HTTP mode. STDIO mode always starts a fresh instance.

## Version Management

### Stable vs Development

**Stable Release (`jfr-mcp@btraceio`):**
- Tested, production-ready versions
- Updated on each official release
- Recommended for most users

**Development Snapshots (`jfr-mcp-dev@btraceio`):**
- Latest features from main branch
- Published on every commit
- For testing new features or bug fixes

### Using Specific Versions

```bash
# Pin to a specific version
jbang io.github.btraceio:jfr-mcp:0.10.0

# Use latest development snapshot
jbang jfr-mcp-dev@btraceio

# Force refresh to get absolute latest snapshot
jbang --fresh jfr-mcp-dev@btraceio
```

### Updating

```bash
# Update stable version
jbang app install --force jfr-mcp@btraceio

# Update development version
jbang app install --force jfr-mcp-dev@btraceio

# Clear cache and reinstall
jbang cache clear
jbang app install jfr-mcp@btraceio
```

### Switching Between Versions

**Install both versions:**
```bash
# Both can coexist as different commands
jbang app install jfr-mcp@btraceio
jbang app install jfr-mcp-dev@btraceio

# Use stable
jfr-mcp --stdio

# Use dev
jfr-mcp-dev --stdio
```

**Or replace one with the other:**
```bash
# Switch to dev
jbang app uninstall jfr-mcp
jbang app install jfr-mcp-dev@btraceio

# Switch back to stable
jbang app uninstall jfr-mcp-dev
jbang app install jfr-mcp@btraceio
```

## Advanced Usage

### Custom Catalog

Add the catalog explicitly for tab completion:

```bash
# Add catalog
jbang catalog add btraceio https://github.com/btraceio/jbang-catalog/blob/main/jbang-catalog.json

# List available aliases
jbang alias list btraceio

# Remove catalog
jbang catalog remove btraceio
```

### Environment Variables

Control server behavior:

```bash
# Custom port via environment
MCP_PORT=8080 jbang jfr-mcp@btraceio

# Use specific Java version
export JAVA_HOME=/path/to/java-21
jbang jfr-mcp@btraceio

# Enable JBang debug output
export JBANG_DEBUG=true
jbang jfr-mcp@btraceio
```

### Shell Aliases

Create convenient shell aliases:

```bash
# Bash/Zsh
echo 'alias jfr-mcp="jbang jfr-mcp@btraceio"' >> ~/.bashrc
echo 'alias jfr-mcp-dev="jbang jfr-mcp-dev@btraceio"' >> ~/.bashrc
source ~/.bashrc

# Now use as:
jfr-mcp                    # Stable
jfr-mcp-dev               # Development
```

### Running in Docker

```bash
# JBang works in containers
docker run -it --rm -p 3000:3000 jbangdev/jbang-action \
  jbang jfr-mcp@btraceio
```

### GitHub Actions

Use in CI/CD pipelines:

```yaml
- name: Start Jafar MCP Server
  run: |
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    jbang jfr-mcp@btraceio &
    sleep 5

- name: Test MCP Server
  run: |
    curl -s http://localhost:3000/mcp/sse
```

## Troubleshooting

### JBang Not Found

**Problem**: `jbang: command not found`

**Solution**: Install JBang first (see Installation section above).

---

### Port Already in Use

**Problem**: Want to start server but port 3000 is already in use.

**Solution**: The server automatically detects running instances and exits silently. You can:
- Use the existing instance on port 3000
- Start on a different port: `jbang jfr-mcp@btraceio -Dmcp.port=8080`
- Stop the existing instance and restart

**Manual check:**
```bash
# Check what's using port 3000
lsof -i :3000

# Or on Windows
netstat -ano | findstr :3000
```

---

### Java Version Mismatch

**Problem**: `UnsupportedClassVersionError` or Java version errors

**Solution**: JBang automatically downloads Java 21+ if needed. Force a fresh install:

```bash
jbang --fresh jfr-mcp@btraceio
```

Or specify Java version explicitly:

```bash
export JAVA_HOME=/path/to/java-21
jbang jfr-mcp@btraceio
```

---

### Catalog Not Found

**Problem**: `Could not resolve alias: jfr-mcp@btraceio`

**Solution**: Add the catalog explicitly:

```bash
jbang catalog add btraceio \
  https://github.com/btraceio/jbang-catalog/blob/main/jbang-catalog.json

# Verify
jbang catalog list
```

Or use the full GitHub URL directly:

```bash
jbang https://github.com/btraceio/jbang-catalog/blob/main/jfr-mcp.java
```

---

### Development Snapshot Not Updating

**Problem**: `jfr-mcp-dev` doesn't have latest changes

**Solution**: Force a fresh fetch:

```bash
# Clear cache and reinstall
jbang cache clear
jbang --fresh jfr-mcp-dev@btraceio
```

---

### Claude Desktop Not Detecting Server

**Problem**: Claude Desktop doesn't show the Jafar MCP server

**Solution**:
1. Verify JBang is in PATH:
   ```bash
   which jbang
   ```

2. Test server manually:
   ```bash
   jbang jfr-mcp@btraceio --stdio
   # Should start and wait for input
   ```

3. Check Claude Desktop logs:
   - macOS: `~/Library/Logs/Claude/`
   - Windows: `%APPDATA%\Claude\logs\`

4. Restart Claude Desktop after config changes

---

### Slow First Run

**Problem**: First execution is slow

**Solution**: This is normal - JBang downloads Java and dependencies on first run. Subsequent runs are fast due to caching. To see what's happening:

```bash
jbang --verbose jfr-mcp@btraceio
```

---

### Cache Issues

**Problem**: Outdated dependencies or corrupted cache

**Solution**: Clear JBang cache:

```bash
# Clear all cache
jbang cache clear

# Clear and force reinstall
jbang cache clear --force
jbang app install --force jfr-mcp@btraceio
```

## Performance Considerations

### Cache Location

JBang caches artifacts in:
- **Linux**: `~/.jbang/cache`
- **macOS**: `~/.jbang/cache`
- **Windows**: `%LOCALAPPDATA%\jbang\cache`

First run downloads ~70MB (Java + MCP SDK + Jetty + dependencies). Subsequent runs are instant.

### Startup Time

```bash
# First run: ~10-30 seconds (downloads dependencies)
jbang jfr-mcp@btraceio

# Subsequent runs: <2 seconds (cached)
jbang jfr-mcp@btraceio
```

### Network Requirements

- First run requires internet connection to download artifacts
- HTTP mode requires network access for port binding
- STDIO mode works offline after initial download:

```bash
jbang --offline jfr-mcp@btraceio --stdio
```

## Comparison with Other Installation Methods

| Method | Setup Time | Disk Space | Java Required | Best For |
|--------|-----------|------------|---------------|----------|
| **JBang** | 1 minute | ~70MB | No (auto-downloads) | Quick start, Claude Desktop |
| **Gradle** | 2 minutes | ~200MB | Yes (25+) | Development |
| **Fat JAR** | 2 minutes | ~35MB | Yes (25+) | Custom deployment, containers |

## Advantages of JBang

✅ **No Java installation required** - JBang downloads Java automatically
✅ **No build tools needed** - No Gradle, Maven, or other setup
✅ **Automatic dependency resolution** - JBang handles all dependencies
✅ **Version pinning** - Lock to specific versions or use snapshots
✅ **Cross-platform** - Works on Windows, macOS, Linux
✅ **Can run from any location** - No need to clone repository
✅ **Isolated environment** - Doesn't interfere with system Java
✅ **Perfect for Claude Desktop** - Simple one-line configuration

## Common Workflows

### Quick Testing with Claude Desktop

```bash
# Install
jbang app install jfr-mcp@btraceio

# Configure Claude Desktop
cat << EOF > ~/Library/Application\ Support/Claude/claude_desktop_config.json
{
  "mcpServers": {
    "jafar": {
      "command": "jbang",
      "args": ["jfr-mcp@btraceio", "--stdio"],
      "env": {}
    }
  }
}
EOF

# Restart Claude Desktop
# Ready to analyze JFR files!
```

### Testing Latest Features

```bash
# Switch to development version
jbang app uninstall jfr-mcp
jbang app install jfr-mcp-dev@btraceio

# Update Claude Desktop config to use dev version
# (change "jfr-mcp@btraceio" to "jfr-mcp-dev@btraceio")

# Test new features
# When done, switch back to stable
```

### Running Alongside jfr-shell

```bash
# Install both tools
jbang app install jfr-shell@btraceio
jbang app install jfr-mcp@btraceio

# Use jfr-shell for interactive analysis
jfr-shell recording.jfr

# Use jfr-mcp for AI-assisted analysis via Claude
jfr-mcp --stdio
```

## See Also

- [MCP Server Tutorial](Tutorial.md) - Complete MCP server guide
- [JFR Shell via JBang](../jbang/JFRShellUsage.md) - Interactive JFR analysis tool
- [JfrPath Reference](../cli/JFRPath.md) - Query language syntax
- [JBang Documentation](https://www.jbang.dev) - Official JBang docs
- [btraceio/jbang-catalog](https://github.com/btraceio/jbang-catalog) - JBang catalog repository
- [Model Context Protocol](https://modelcontextprotocol.io) - MCP specification
