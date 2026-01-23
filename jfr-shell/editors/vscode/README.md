# JFR Shell Language Support for VS Code

This extension provides syntax highlighting for JFR Shell script files (`.jfrs`).

## Features

- Syntax highlighting for `.jfrs` files
- Comment toggling with `#`
- Auto-closing brackets and quotes
- Bracket matching

## Installation

### From Source (Development)

1. Clone or download the `vscode` directory
2. Open VS Code and press `F5` to launch Extension Development Host
3. Or package the extension:
   ```bash
   npm install -g @vscode/vsce
   vsce package
   ```
   Then install the generated `.vsix` file

### Manual Installation

1. Copy the `vscode` directory to your VS Code extensions folder:
   - macOS: `~/.vscode/extensions/jfr-shell-0.1.0`
   - Linux: `~/.vscode/extensions/jfr-shell-0.1.0`
   - Windows: `%USERPROFILE%\.vscode\extensions\jfr-shell-0.1.0`
2. Restart VS Code

## Supported Syntax Elements

| Element | Examples |
|---------|----------|
| Comments | `# This is a comment` |
| Commands | `open`, `close`, `show`, `set`, `echo` |
| Control flow | `if`, `elif`, `else`, `endif` |
| JfrPath roots | `events`, `metadata`, `chunks`, `cp` |
| Functions | `groupBy()`, `top()`, `stats()`, `select()` |
| Operators | `==`, `!=`, `>`, `<`, `~`, `and`, `or` |
| Strings | `"double"`, `'single'` |
| Numbers | `123`, `-45.67` |
| Variables | `${var}`, `$1`, `$@` |
| Event types | `jdk.ExecutionSample` |
| Options | `--format`, `--limit` |

## Example

```jfrshell
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# GC Analysis Script

open ${1:?recording file required}

echo "=== GC Pause Statistics ==="
show events/jdk.GarbageCollection | stats(duration)

echo "=== GC by Type ==="
show events/jdk.GarbageCollection | groupBy(name) | top(10, by=count)

close
```

## License

Apache-2.0

## Related

- [jfr-shell](https://github.com/btraceio/jafar) - Interactive JFR analysis tool
- [Jafar](https://github.com/btraceio/jafar) - Fast JFR parser
