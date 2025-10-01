# JFR Shell (CLI)

A pure Java, interactive CLI for exploratory analysis of Java Flight Recorder (JFR) files powered by the Jafar parser.

- Runtime: Java 25
- Modules used: `parser`, `tools`
- UI: JLine-based REPL with code completion
- Args parsing: picocli

Status: Milestone M1 (multi-session management) complete; early JfrPath with events + metadata support. See `jfr-shell/PLAN.md` for roadmap.

## Features (current)

- Multi-session management:
  - `open <path> [--alias NAME]`
  - `sessions`
  - `use <id|alias>`
  - `info [id|alias]`
  - `close [id|alias|--all]`
- Interactive REPL with code-completion for commands, options, session IDs/aliases
- Clean design focused on a typed CLI API

Planned next milestones include expanded metadata/chunk/constant-pool browsing and richer JfrPath (filters, aggregations, formats).

## Build

- Build the shaded executable jar:
  - `./gradlew :jfr-shell:shadowJar`
- The fat jar is produced at `jfr-shell/build/libs/jfr-shell-<version>.jar`

Requirements:
- Java 25 toolchain is configured via Gradle toolchains.

## Run

- Run the CLI directly from Gradle (plain console recommended):
  - `./gradlew :jfr-shell:run --console=plain`
- Or use the fat jar:
  - `java -jar jfr-shell/build/libs/jfr-shell-<version>.jar`
- Optional: open a recording on startup:
  - `java -jar jfr-shell/build/libs/jfr-shell-<version>.jar --file /path/to/recording.jfr`

## Commands (M1)

- `open <path> [--alias NAME]`: Open a JFR file as a new session (sets current)
- `sessions`: List all sessions; current is marked with `*`
- `use <id|alias>`: Switch the current session
- `info [id|alias]`: Show basic session info (file, types discovered, handlers, run status)
- `close [id|alias|--all]`: Close the current, a specific, or all sessions
- `help`: Show built-in help
- `exit` | `quit`: Exit the shell

JfrPath usage (show):
- `show events/jdk.FileRead[bytes>=1000] --limit 5`
- `show events/jdk.ExecutionSample[thread/name~"main"] --limit 10`
- `show events/jdk.SocketRead[remoteHost~"10\.0\..*"] --limit 3`
 - Attribute projection (single-column values):
   - `show events/jdk.ExecutionSample/sampledThread/javaName --limit 5`
 - Metadata browsing via JfrPath:
   - `show metadata/jdk.Thread`
   - `show metadata/jdk.Thread/superType`

Code completion hints:
- Start typing a command and press Tab to complete.
- For `use`, `info`, and `close`, Tab completes session IDs and aliases; `close` also completes `--all`.
- For `open`, Tab completes `--alias` option.
 - For `show`, Tab completes roots (`events/`, `metadata/`, `cp/`, `chunks/`) and type names under them.

## Example session

```
$ java -jar jfr-shell/build/libs/jfr-shell-<version>.jar
╔═══════════════════════════════════════╗
║           JFR Shell (CLI)             ║
║     Interactive JFR exploration       ║
╚═══════════════════════════════════════╝
Type 'help' for commands, 'exit' to quit

jfr> open /path/to/recording.jfr --alias app
Opened session #1 (app): /path/to/recording.jfr
jfr> sessions
*#1 app - /path/to/recording.jfr
jfr> info
Session Information:
  Recording: /path/to/recording.jfr
  Event Types: 0
  Handlers: 0
  Has Run: false
jfr> close
Closed session #1
jfr> exit
```

## Development

- Tests: JUnit 5
  - `./gradlew :jfr-shell:test`
- Code style: Java 25, 4 spaces indentation, ~120 column target
- Keep the public API lean and focused on CLI internals.

## Roadmap (high level)

- M2: Metadata discovery (`metadata` command) with search/refresh; completion for type names
- M3: Metadata browsing (`metadata ...`)
- M4: Chunks and constant pools (`chunks`, `chunk`, `cp`)
- M5/M6: JfrPath query language (filter/project/aggregate) + table/json/csv output
- M7: UX polish (pager, contextual help, enhanced completion)
- M8+: Non-interactive subcommands for CI/scripting

See `jfr-shell/PLAN.md` for detailed milestones and acceptance criteria. This README will be updated alongside each milestone.
