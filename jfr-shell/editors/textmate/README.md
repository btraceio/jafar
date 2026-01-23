# JFR Shell TextMate Grammar

This TextMate grammar bundle provides syntax highlighting for JFR Shell script files (`.jfrs`).

## Installation

### IntelliJ IDEA / JetBrains IDEs

1. Open **Settings** (Ctrl+Alt+S / Cmd+,)
2. Navigate to **Editor > TextMate Bundles**
3. Click **+** and select the `textmate` directory (this folder)
4. The grammar will be loaded and `.jfrs` files will be highlighted

### Sublime Text

1. Copy the `syntaxes/jfrshell.tmLanguage.json` to your Sublime Text packages directory:
   - macOS: `~/Library/Application Support/Sublime Text/Packages/User/`
   - Linux: `~/.config/sublime-text/Packages/User/`
   - Windows: `%APPDATA%\Sublime Text\Packages\User\`
2. Restart Sublime Text

### Other TextMate-Compatible Editors

Copy the grammar file to your editor's syntax definitions directory.

## Supported Syntax Elements

- **Comments**: Lines starting with `#`
- **Shell commands**: `open`, `close`, `show`, `set`, `echo`, etc.
- **Control flow**: `if`, `elif`, `else`, `endif`
- **JfrPath roots**: `events`, `metadata`, `chunks`, `cp`
- **Pipeline functions**: `groupBy()`, `top()`, `stats()`, `select()`, etc.
- **Operators**: `==`, `!=`, `>`, `<`, `~`, `and`, `or`, `not`
- **Literals**: strings, numbers, booleans
- **Variables**: `${var}`, `$1`, `$@`
- **Event types**: `jdk.ExecutionSample`, etc.

## Example

```jfrshell
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Sample JFR Shell script

open ${1:?recording file required}

echo "=== Top Threads ==="
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)

if ${verbose:-false}
  metadata --summary
endif

close
```
