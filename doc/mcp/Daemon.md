# Running jfr-mcp as a Supervised Daemon (SSE mode)

`jfr-mcp` has three run modes:

- **stdio** (`jfr-mcp --stdio`) — one process per client, managed by the MCP client itself
  (Claude Desktop, Claude Code, ...). Simple, and nothing in this document applies to it.
- **stdio, attached to a shared daemon** (`jfr-mcp --stdio --attach`) — looks like stdio to the
  client, but relays to one long-lived daemon that it starts if nobody has. See
  [One shared daemon for many stdio clients](#one-shared-daemon-for-many-stdio-clients---attach).
- **SSE/HTTP** (`jfr-mcp`, no flag) — a long-lived daemon on a local port that several MCP
  clients can connect to concurrently. This mode is useful when you want one server holding open
  JFR/heap-dump/pprof/otlp sessions across multiple tools or editor windows.

SSE mode is only a safe, durable option when something actually keeps the process alive and
restarts it after a crash. This page covers that supervision, plus the security properties SSE
mode relies on, and how `--attach` uses the same daemon without any supervisor.

## One shared daemon for many stdio clients (`--attach`)

Every plain `--stdio` client starts its own JVM: the first useful answer takes several seconds,
and nothing is shared between clients. `--stdio --attach` starts a thin relay instead. The
first one starts a daemon, and every later one (from any client, editor window or agent)
attaches to it, so they all reuse its warm JVM. Measured on a development machine, a warm attach
answers `initialize` in about 0.2-0.3s, against 4s or more for a private stdio server.

Configure it exactly like stdio, with the extra flag:

```json
{
  "mcpServers": {
    "jafar": {
      "command": "jbang",
      "args": ["jfr-mcp@btraceio", "--stdio", "--attach"]
    }
  }
}
```

This needs a `jfr-mcp` release that has `--attach`. An older jar ignores the flag and runs a
private stdio server, which still works, just without sharing.

What it does, and the settings that change it (all are JVM system properties, `-Dname=value`;
the ones starting `mcp.` and `jafar.` are passed on to the daemon it starts):

- **Starting the daemon.** If no daemon answers, the bridge starts one with `java -jar` from the
  same jar and JVM, never through jbang (a launchd or systemd environment often has no `jbang` on
  its PATH). Several clients starting at once start exactly one. It writes
  `mcp-sse.log` and `mcp-sse.err.log` next to its state files. Give up after
  `-Dmcp.attach.start.timeout.seconds` (default 20). On Windows the bridge does not start the
  daemon for you yet; start it yourself with `jfr-mcp` and the bridge attaches to it.
- **State files.** The port, bearer token, persisted sessions, logs, start lock and AOT cache all
  live in `~/.jafar`. `-Djafar.state.dir=<dir>` moves all of them, which is also how to run a
  second, separate daemon. The bridge reads the token itself, and refuses a token file that other
  users can read.
- **Stopping.** A daemon the bridge started exits after `-Dmcp.daemon.idle.timeout.minutes`
  (default 30; 0 never) with no client connected. One you started yourself, or a supervised
  one, is never stopped this way.
- **Noticing clients that left.** The daemon pings each SSE connection every
  `-Dmcp.sse.keepalive.seconds` (default 30; 0 turns it off). A closed client is only noticed when
  a write to it fails, so without the pings it would count as connected forever. This applies to
  every SSE daemon, supervised ones included, and a departed client is noticed within about two
  intervals.
- **Faster starts.** The first daemon the bridge starts records what it loads, and writes an
  ahead-of-time cache when it exits (needs JDK 25 or later). Daemons after that start from it,
  which cut daemon startup from about 1.0s to about 0.35s in testing. The cache is named by jar
  version and JVM version, kept in the state directory, and replaced when either changes.
- **Restarts.** If the daemon goes away, the bridge starts or finds one again and repeats the
  client's `initialize` on the new session. Requests that were in flight get a JSON-RPC error
  instead of waiting forever. Sessions the client had open are lost with the daemon, unless the
  daemon's [persisted sessions](#how-this-interacts-with-porttoken-detection) bring them back.
- **Upgrades.** A bridge checks the daemon's version. If a daemon the bridge started is the wrong
  version and has no client connected, the bridge stops it and starts a current one. If clients
  are connected, or the daemon was not started by a bridge, it is left alone and the bridge says
  so on stderr (`using a jfr-mcp daemon of version X although this bridge is Y`). Restart such a
  daemon yourself to upgrade it.

The bridge's stdout carries only protocol messages; everything else goes to stderr.

### Sessions belong to the client that opened them

One daemon serves every client, so each session is owned by the client that opened it:

- Other clients cannot see it in listings, reach it by id or alias, or close it. An id that
  belongs to another client is reported as `Session not found`.
- An alias only has to be unique among the opening client's own sessions, so two agents can both
  call their recording `cpu`.
- `*_close` with `closeAll=true` closes the caller's sessions, plus any shared ones (sessions
  restored after a restart belong to nobody and are visible to all), and reports how many it
  closed. It never touches another client's sessions.
- When a client disconnects, its sessions are closed.

### Checking on the daemon

Both endpoints need the bearer token, like every other `/mcp/*` request (default port 3000):

```bash
curl -s -H "Authorization: Bearer $(cat ~/.jafar/mcp-sse.token)" http://localhost:3000/mcp/health
```

```json
{"version":"0.28.0","pid":52852,"startedAt":"2026-10-03T10:36:54.014911Z","activeSessions":0,"autostarted":true}
```

`autostarted` is true for a daemon a bridge started. `activeSessions` counts connected clients.
To stop a daemon the way a newer bridge would:

```bash
curl -s -X POST -H "Authorization: Bearer $(cat ~/.jafar/mcp-sse.token)" http://localhost:3000/mcp/shutdown
```

It answers `202` and exits only for an auto-started daemon with no clients connected. Otherwise it
answers `409` with the reason (for example `this daemon was not auto-started, so only its
supervisor may stop it`) and keeps running.

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

The daemon writes its port to `~/.jafar/mcp-sse.port` and, unless auth is disabled, a bearer
token to `~/.jafar/mcp-sse.token` (see below) on startup, and deletes both on clean shutdown. Any
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
   `~/.jafar/mcp-sse.token` (owner-read/write only, where the filesystem supports it), and rejects
   any request to `/mcp/*` that doesn't present it as `Authorization: Bearer <token>`. Configure
   your MCP client to read that file and send the header; see the manual `curl` example in
   [Tutorial.md](Tutorial.md#manual-testing). Disable with `-Dmcp.auth.disable=true` if you
   understand the risk (e.g. a throwaway container on an isolated network).

Per-client session isolation is also part of SSE mode's safety story: each MCP connection owns the
sessions it opens, so one client cannot close, list, reach or take the alias of another client's
recordings, and opening a recording does not redirect another client's session-less tool calls.
See [Sessions belong to the client that opened them](#sessions-belong-to-the-client-that-opened-them).
This requires no configuration.
