#!/usr/bin/env bash
#
# Jafar MCP Server installer
#
# Usage:
#   curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | bash
#
# Options (via environment variables):
#   JFR_MCP_DEV=1    Install development snapshot instead of stable release
#
set -euo pipefail

ALIAS="jfr-mcp"
if [ "${JFR_MCP_DEV:-}" = "1" ]; then
  ALIAS="jfr-mcp-dev"
fi

info()  { printf '  \033[1;34m>\033[0m %s\n' "$*"; }
ok()    { printf '  \033[1;32m✔\033[0m %s\n' "$*"; }
err()   { printf '  \033[1;31m✘\033[0m %s\n' "$*" >&2; }

# --- Install JBang if missing ---
if command -v jbang >/dev/null 2>&1; then
  ok "jbang found: $(command -v jbang)"
else
  info "Installing jbang ..."
  curl -Ls https://sh.jbang.dev | bash -s - app setup
  # Source the jbang env so it is available in the current shell
  if [ -f "$HOME/.jbang/bin/jbang" ]; then
    export PATH="$HOME/.jbang/bin:$PATH"
  fi
  if ! command -v jbang >/dev/null 2>&1; then
    err "jbang installation failed – please install manually: https://www.jbang.dev/download"
    exit 1
  fi
  ok "jbang installed"
fi

# --- Install jfr-mcp ---
info "Installing ${ALIAS}@btraceio ..."
jbang app install --force "${ALIAS}@btraceio"
ok "${ALIAS} installed"

# --- Verify ---
info "Verifying installation ..."
if command -v "${ALIAS}" >/dev/null 2>&1; then
  ok "${ALIAS} is on PATH: $(command -v "${ALIAS}")"
else
  ok "Installed. Run with: jbang ${ALIAS}@btraceio --stdio"
fi

echo ""
info "Quick start:"
echo "    ${ALIAS} --stdio          # STDIO mode (Claude Desktop / Claude Code)"
echo "    ${ALIAS}                  # HTTP  mode on port 3000"
echo ""
info "Claude Desktop config (~/.config/Claude/claude_desktop_config.json):"
cat <<CONF
    {
      "mcpServers": {
        "jafar": {
          "command": "jbang",
          "args": ["${ALIAS}@btraceio", "--stdio"]
        }
      }
    }
CONF
echo ""
info "Claude Code:"
echo "    claude mcp add jafar -- jbang ${ALIAS}@btraceio --stdio"
