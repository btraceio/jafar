#!/usr/bin/env bash
#
# Jafar installer: installs the jfr-mcp server, then registers the jafar-perf plugin
# (btraceio/jafar-perf-box) with every supported agent harness it finds.
#
# Usage:
#   curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/install.sh | bash
#   curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/install.sh | bash -s -- [options]
#
# Options:
#   --harness <list>   Comma-separated harnesses to register: claude, pi, or all.
#                      Default: every supported harness found on PATH.
#   --no-harness       Install jfr-mcp only; register no harness.
#   --daemon           Also install jfr-mcp as a supervised SSE daemon (see doc/mcp/Daemon.md).
#   --dev              Install the jfr-mcp development snapshot (same as JFR_MCP_DEV=1).
#   -h, --help         Show this help.
#
# Supported harnesses:
#   claude   Claude Code: adds the btraceio marketplace and installs jafar-perf@btraceio
#            (skills, agents, and the jafar MCP server).
#   pi       pi coding agent: installs the pi-mcp-adapter extension (pi has no built-in MCP
#            support) and the jafar-perf-box package (skills and the jafar MCP server).
#
# Re-running is safe: already-installed pieces are updated in place.
set -euo pipefail

JAFAR_REF="${JAFAR_REF:-main}"
MARKETPLACE_REPO="btraceio/jafar-perf-box"
MARKETPLACE_NAME="btraceio"
PLUGIN="jafar-perf@${MARKETPLACE_NAME}"
PI_PACKAGE="git:github.com/${MARKETPLACE_REPO}"
PI_MCP_ADAPTER="npm:pi-mcp-adapter"

# To add a harness: append its name here and define harness_<name>_detect (exit 0 when the
# harness is installed) and harness_<name>_register.
SUPPORTED_HARNESSES="claude pi"

info()  { printf '  \033[1;34m>\033[0m %s\n' "$*"; }
ok()    { printf '  \033[1;32m✔\033[0m %s\n' "$*"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$*" >&2; }
err()   { printf '  \033[1;31m✘\033[0m %s\n' "$*" >&2; }

usage() {
  cat <<'USAGE'
Usage: install.sh [--harness <claude,pi|all>] [--no-harness] [--daemon] [--dev]

Installs the jfr-mcp server, then registers the jafar-perf plugin with agent harnesses.

  --harness <list>   Comma-separated harnesses to register: claude, pi, or all.
                     Default: every supported harness found on PATH.
  --no-harness       Install jfr-mcp only; register no harness.
  --daemon           Also install jfr-mcp as a supervised SSE daemon.
  --dev              Install the jfr-mcp development snapshot (same as JFR_MCP_DEV=1).
  -h, --help         Show this help.
USAGE
}

# --- Harness: Claude Code ---------------------------------------------------------------------

harness_claude_detect() { command -v claude >/dev/null 2>&1; }

harness_claude_register() {
  claude plugin marketplace add "${MARKETPLACE_REPO}" || return 1
  claude plugin marketplace update "${MARKETPLACE_NAME}" || return 1
  claude plugin install "${PLUGIN}" || return 1
  claude plugin update "${PLUGIN}" || return 1
  ok "Claude Code: ${PLUGIN} installed (restart running Claude Code sessions to load it)"
}

# --- Harness: pi --------------------------------------------------------------------------------

harness_pi_detect() { command -v pi >/dev/null 2>&1; }

harness_pi_register() {
  local source
  for source in "${PI_MCP_ADAPTER}" "${PI_PACKAGE}"; do
    pi install "${source}" || return 1
    pi update "${source}" || return 1
  done
  ok "pi: jafar-perf-box skills and the jafar MCP server installed (via ${PI_MCP_ADAPTER})"
  info "pi reaches jafar's tools through the adapter's mcp proxy tool; the Claude Code agents"
  info "(subagents) are not available in pi."
}

# --- Arguments ----------------------------------------------------------------------------------

REQUESTED_HARNESSES="auto"
MCP_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --harness)
      [ $# -ge 2 ] || { err "--harness needs a value"; exit 2; }
      REQUESTED_HARNESSES="$2"; shift 2 ;;
    --harness=*) REQUESTED_HARNESSES="${1#--harness=}"; shift ;;
    --no-harness) REQUESTED_HARNESSES="none"; shift ;;
    --daemon) MCP_ARGS+=("--daemon"); shift ;;
    --dev) export JFR_MCP_DEV=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) err "Unknown option: $1 (see --help)"; exit 2 ;;
  esac
done

case "${REQUESTED_HARNESSES}" in
  auto)
    HARNESSES=""
    for h in ${SUPPORTED_HARNESSES}; do
      if "harness_${h}_detect"; then HARNESSES="${HARNESSES} ${h}"; fi
    done ;;
  all) HARNESSES="${SUPPORTED_HARNESSES}" ;;
  none) HARNESSES="" ;;
  *)
    HARNESSES="$(printf '%s' "${REQUESTED_HARNESSES}" | tr ',' ' ')"
    for h in ${HARNESSES}; do
      case " ${SUPPORTED_HARNESSES} " in
        *" ${h} "*) ;;
        *) err "Unsupported harness: ${h} (supported: ${SUPPORTED_HARNESSES})"; exit 2 ;;
      esac
    done ;;
esac

# --- jfr-mcp ------------------------------------------------------------------------------------

# Run from a checkout: use its jfr-mcp/install.sh. Piped through bash: fetch it from GitHub.
script_dir=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
echo ""
info "Installing jfr-mcp ..."
if [ -n "${HARNESSES// /}" ]; then
  export JFR_MCP_SKIP_CLIENT_HINTS=1
fi
if [ -n "${script_dir}" ] && [ -f "${script_dir}/jfr-mcp/install.sh" ]; then
  bash "${script_dir}/jfr-mcp/install.sh" ${MCP_ARGS[@]+"${MCP_ARGS[@]}"}
else
  curl -fsSL "https://raw.githubusercontent.com/btraceio/jafar/${JAFAR_REF}/jfr-mcp/install.sh" \
    | bash -s -- ${MCP_ARGS[@]+"${MCP_ARGS[@]}"}
fi

# The harness plugins start the server with `jbang jfr-mcp@btraceio --stdio`, so they need
# jbang on the PATH of the harness process, and they always run the stable release.
if [ -x "${HOME}/.jbang/bin/jbang" ]; then
  export PATH="${HOME}/.jbang/bin:${PATH}"
fi

# --- Harnesses ----------------------------------------------------------------------------------

echo ""
if [ -z "${HARNESSES// /}" ]; then
  if [ "${REQUESTED_HARNESSES}" = "auto" ]; then
    warn "No supported agent harness found on PATH (looked for: ${SUPPORTED_HARNESSES})."
    warn "Install one and re-run, or pass --harness <name>."
  fi
  exit 0
fi

if [ "${JFR_MCP_DEV:-}" = "1" ]; then
  warn "--dev installs the jfr-mcp-dev command, but the harness plugins still run the stable"
  warn "release (jbang jfr-mcp@btraceio)."
fi

failed=""
for h in ${HARNESSES}; do
  info "Registering the jafar-perf plugin with ${h} ..."
  if ! "harness_${h}_detect"; then
    err "${h} is not installed or not on PATH; skipping."
    failed="${failed} ${h}"
    continue
  fi
  if ! "harness_${h}_register"; then
    err "Registering with ${h} failed; see the output above."
    failed="${failed} ${h}"
  fi
done

if [ -n "${failed}" ]; then
  echo ""
  err "Not registered:${failed}"
  exit 1
fi
if ! command -v jbang >/dev/null 2>&1; then
  warn "jbang is not on your PATH yet. Open a new shell (or add ~/.jbang/bin to PATH) before"
  warn "starting the harness, or it will not be able to start the jafar MCP server."
fi
