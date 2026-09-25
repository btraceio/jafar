#!/usr/bin/env bash
#
# Jafar MCP Server installer
#
# Usage:
#   curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | bash
#   curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | bash -s -- --daemon
#
# Options (via environment variables):
#   JFR_MCP_DEV=1      Install development snapshot instead of stable release
#   JFR_MCP_DAEMON=1   Same as passing --daemon
#
# Flags:
#   --daemon           Also install and start jfr-mcp as a supervised background service
#                       (systemd --user on Linux, launchd on macOS) running in SSE mode. Default
#                       behavior (no flag) is unchanged: install the command only, run manually.
#
set -euo pipefail

ALIAS="jfr-mcp"
if [ "${JFR_MCP_DEV:-}" = "1" ]; then
  ALIAS="jfr-mcp-dev"
fi

INSTALL_DAEMON="${JFR_MCP_DAEMON:-0}"
for arg in "$@"; do
  if [ "$arg" = "--daemon" ]; then
    INSTALL_DAEMON=1
  fi
done

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

# --- Optional: install as a supervised background daemon (SSE mode) ---
# Keep in sync with jfr-mcp/systemd/jafar-mcp.service and jfr-mcp/launchd/io.btrace.jafar-mcp.plist
# in the repo, which contain the same units for users who prefer to install them manually.
install_systemd_user_service() {
  local unit_dir="${HOME}/.config/systemd/user"
  local unit_name="jafar-mcp.service"
  if [ "${ALIAS}" = "jfr-mcp-dev" ]; then
    unit_name="jafar-mcp-dev.service"
  fi
  mkdir -p "${unit_dir}"
  cat > "${unit_dir}/${unit_name}" <<UNIT
[Unit]
Description=Jafar MCP Server (SSE/daemon mode)
After=network.target

[Service]
Type=simple
ExecStart=${HOME}/.jbang/bin/${ALIAS}
Restart=on-failure
RestartSec=2s

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now "${unit_name}"
  ok "Installed and started ${unit_name}"
  info "Check status: systemctl --user status ${unit_name}"
  info "Stop it:      systemctl --user stop ${unit_name}"
  info "Logs:         journalctl --user -u ${unit_name} -f"
}

install_launchd_service() {
  local plist_dir="${HOME}/Library/LaunchAgents"
  local label="io.btrace.jafar-mcp"
  if [ "${ALIAS}" = "jfr-mcp-dev" ]; then
    label="io.btrace.jafar-mcp-dev"
  fi
  local plist="${plist_dir}/${label}.plist"
  mkdir -p "${plist_dir}" "${HOME}/.jafar"
  cat > "${plist}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${label}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${HOME}/.jbang/bin/${ALIAS}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>${HOME}/.jafar/mcp-sse.log</string>
    <key>StandardErrorPath</key>
    <string>${HOME}/.jafar/mcp-sse.err.log</string>
</dict>
</plist>
PLIST
  launchctl unload "${plist}" >/dev/null 2>&1 || true
  launchctl load -w "${plist}"
  ok "Installed and started ${label}"
  info "Check status: launchctl list | grep ${label}"
  info "Stop it:      launchctl unload ${plist}"
  info "Logs:         ${HOME}/.jafar/mcp-sse.log"
}

if [ "${INSTALL_DAEMON}" = "1" ]; then
  echo ""
  info "Installing ${ALIAS} as a supervised background daemon (SSE mode) ..."
  case "$(uname -s)" in
    Linux)
      if command -v systemctl >/dev/null 2>&1; then
        install_systemd_user_service
      else
        err "systemctl not found — cannot install a systemd --user service on this system."
        err "See jfr-mcp/systemd/jafar-mcp.service in the repo to install one manually."
        exit 1
      fi
      ;;
    Darwin)
      install_launchd_service
      ;;
    *)
      err "Daemon supervision is only supported on Linux (systemd --user) and macOS (launchd)."
      exit 1
      ;;
  esac
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
