# JFR Shell - Interactive JFR Analysis Tool

## 🎉 Status: FULLY WORKING!

The JFR Shell is now a fully functional interactive Groovy-based JFR analysis tool.

## Quick Start

### 1. Run the Interactive Shell

```bash
# Option 1: Direct JAR execution  
java -jar jfr-shell/build/libs/jfr-shell-0.0.1-SNAPSHOT.jar

# Option 2: Using launcher script
./jfr-shell-launcher

# Option 3: Through Gradle (may have terminal issues)
./gradlew :jfr-shell:run --console=plain
```

### 2. Basic Usage

Once in the shell, you'll see:
```
╔═══════════════════════════════════════╗
║           JFR Shell v1.0              ║
║   Interactive JFR Analysis Tool       ║
╚═══════════════════════════════════════╝

Type 'help' for available commands, 'exit' to quit

jfr> _
```

### 3. Test Basic Functionality

```groovy
jfr> def x = 5 + 3
jfr> x
=> 8

jfr> [1, 2, 3, 4, 5].sum()
=> 15

jfr> def stats = [thread1: 100, thread2: 75, thread3: 50]
jfr> stats.sort { -it.value }
=> [thread1:100, thread2:75, thread3:50]
```

## JFR Analysis Example

```groovy
# Open a JFR recording
jfr> open("/path/to/recording.jfr")
Session opened: /path/to/recording.jfr
Found 23 event types

# Set up data collection
jfr> def threadStats = [:]

# Register event handler
jfr> handle(JFRExecutionSample) { event, ctl ->
       def threadId = event.sampledThread().javaThreadId()
       threadStats[threadId] = (threadStats[threadId] ?: 0) + 1
     }

# Process the recording
jfr> run()
Processed 12,345 events in 523 ms

# Analyze results
jfr> threadStats.sort { -it.value }.take(5)
=> [42:2341, 15:1205, 8:892, 23:634, 31:421]

# Export results
jfr> export(threadStats, "thread-analysis.json")
Data exported to: thread-analysis.json
```

## Available Commands

- `help` - Show available commands
- `info` - Show session information  
- `types` - Show available event types in current recording
- `close` - Close current session
- `exit` / `quit` - Exit shell

## Helper Functions

- `open(path)` - Open JFR recording
- `handle(EventType, {})` - Register event handler
- `run()` - Process recording
- `export(data, filename)` - Export data to file

## Command Line Options

```bash
java -jar jfr-shell.jar --help

Usage: jfr-shell [-hqV] [--generate-types] [-e=<executeCommand>] [-f=<jfrFile>]
                 [-s=<scriptFile>] [--types-output=<typesOutput>]
                 [--types-package=<typesPackage>]
Interactive JFR Analysis Shell
  -e, --execute=<executeCommand>
                         Groovy command to execute and exit
  -f, --file=<jfrFile>   JFR file to open immediately
      --generate-types   Generate typed interfaces from JFR file and exit
  -h, --help             Show this help message and exit.
  -q, --quiet            Suppress banner and startup messages
  -s, --script=<scriptFile>
                         Groovy script to execute and exit
      --types-output=<typesOutput>
                         Output directory for generated types
      --types-package=<typesPackage>
                         Package name for generated types
  -V, --version          Print version information and exit.
```

## Features

✅ **Interactive Groovy Shell** with full language support  
✅ **JFR Session Management** - Open/close recordings  
✅ **Typed Event Handling** - Type-safe event processing  
✅ **Data Export** - JSON export functionality  
✅ **Command System** - Built-in commands  
✅ **Terminal Integration** - Proper keyboard handling (Ctrl+C, Ctrl+D)  
✅ **Standalone Executable** - Single JAR with all dependencies  

## Example Scripts

Pre-built analysis templates are available in:
- `jfr-shell/src/main/resources/examples/thread_analysis.groovy`
- `jfr-shell/src/main/resources/examples/gc_analysis.groovy`

## Notes

- The shell provides full Groovy language capabilities
- Variables persist within a single line but not between lines (Groovy shell limitation)
- Use `def` for local variables within the shell
- The shell supports all standard Groovy operations, collections, closures, etc.
- Type generation happens automatically when opening JFR files