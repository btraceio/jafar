# Running jfr-mcp as a Supervised Daemon (SSE mode)

`jfr-mcp` has two run modes:

- **stdio** (`jfr-mcp --stdio`) — one process per client, managed by the MCP client itself
  (Claude Desktop, Claude Code, ...). This is the default and recommended mode; nothing in this
  document applies to it.
- **SSE/HTTP** (`jfr-mcp`, no flag) — a long-lived daemon on a local port that several MCP
  clients can connect to concurrently. This mode is useful when you want one server holding open
  JFR/heap-dump/pprof/otlp sessions across multiple tools or editor windows.

SSE mode is only a safe, durable option when something actually keeps the process alive and
restarts it after a crash. This page covers that supervision, plus the security properties SSE
mode relies on.

## Installing the daemon

```bash
curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | bash -s -- --daemon
```

Setting `JFR_MCP_DAEMON=1` in the environment is equivalent to passing `--daemon`:

```bash
curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | JFR_MCP_DAEMON=1 bash
```

This installs `jfr-mcp` as usual, then additionally installs and starts a supervised background
service:

| Platform | Mechanism | Unit installed at |
|----------|-----------|--------------------|
| Linux | `systemd --user`, `Restart=on-failure` | `~/.config/systemd/user/jafar-mcp.service` |
| macOS | `launchd`, `KeepAlive` (restart on crash) | `~/Library/LaunchAgents/io.btrace.jafar-mcp.plist` |

Set `JFR_MCP_DEV=1` alongside `--daemon` to supervise the development snapshot
(`jfr-mcp-dev`) instead; the installed unit/plist is named accordingly
(`jafar-mcp-dev.service` / `io.btrace.jafar-mcp-dev`).

You can also copy the unit files from the repository and install them yourself:
[`jfr-mcp/systemd/jafar-mcp.service`](../../jfr-mcp/systemd/jafar-mcp.service) and
[`jfr-mcp/launchd/io.btrace.jafar-mcp.plist`](../../jfr-mcp/launchd/io.btrace.jafar-mcp.plist)
(replace `__HOME__` with your home directory in the plist).

Both units only restart the process on a crash or non-zero exit — not on the clean `exit 0` that
happens when a second `jfr-mcp` invocation detects the daemon is already running (see
"Port Detection" in [JBANGUsage.md](JBANGUsage.md#port-detection)) and exits immediately.

### Managing the service

**Linux (systemd):**
```bash
systemctl --user status jafar-mcp.service
systemctl --user stop jafar-mcp.service
systemctl --user restart jafar-mcp.service
journalctl --user -u jafar-mcp.service -f   # follow logs
```

**macOS (launchd):**
```bash
launchctl list | grep io.btrace.jafar-mcp
launchctl unload ~/Library/LaunchAgents/io.btrace.jafar-mcp.plist
launchctl load -w ~/Library/LaunchAgents/io.btrace.jafar-mcp.plist
tail -f ~/.jafar/mcp-sse.log ~/.jafar/mcp-sse.err.log
```

### How this interacts with port/token detection

Once it has bound its port, the daemon writes the port and the address clients should use (an IP
literal such as `127.0.0.1`, not `localhost`, which may resolve to `::1` first) to
`~/.jafar/mcp-sse.port` and, unless auth is disabled, a bearer token to `~/.jafar/mcp-sse.token`
(see below). It deletes both on clean shutdown. A second instance that loses the race for the port
neither writes nor deletes these files, so it cannot disturb the running daemon. Any
`jfr-mcp` invocation — including the one the service manager runs on restart — checks this port
file first: if a server is already reachable there, it prints the URL — plus, unless auth is
disabled, a second line `Auth token file: <path>` — and exits 0 instead of starting a second
daemon. This is what makes it safe for `Restart=on-failure` / `KeepAlive` to
re-run the exact same command after a crash: the file is stale in that case (the old process is
gone), so the restarted process proceeds to actually bind the port.

## Security properties of SSE mode

Because one SSE daemon can serve several concurrent MCP client connections, and any process that
can reach its port could otherwise read or manipulate open JFR/heap-dump/pprof/otlp sessions
(which can hold sensitive process data), SSE mode has two protections on by default:

1. **Loopback binding.** The daemon binds to `127.0.0.1` by default. Override with
   `-Dmcp.host=<address>` (e.g. `0.0.0.0` to accept connections from other hosts) — only do this
   on a network you trust, and prefer combining it with the bearer token below rather than
   disabling auth.
2. **Bearer token auth.** On startup the daemon generates a random token, writes it to
   `~/.jafar/mcp-sse.token` (created owner-read/write only before the token is written, where the
   filesystem supports POSIX permissions), and rejects
   any request to `/mcp/*` that doesn't present it as `Authorization: Bearer <token>`. Configure
   your MCP client to read that file and send the header; see the manual `curl` example in
   [Tutorial.md](Tutorial.md#manual-testing). Disable with `-Dmcp.auth.disable=true` if you
   understand the risk (e.g. a throwaway container on an isolated network).

Per-client session isolation is also part of SSE mode's safety story: each MCP connection has its
own "current session" pointer, so one client opening a recording does not redirect another
client's session-less tool calls. A client that reconnects gets a new connection, and the sessions
its previous connection left open become a fallback, so its session-less calls keep resolving to
the recording it had open. This requires no configuration.
