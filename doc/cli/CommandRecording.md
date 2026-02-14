# Tutorial: Recording JFR Shell Commands

This tutorial teaches you how to record your interactive JFR Shell commands into reusable scripts.

## Why Record Commands?

Recording is perfect for:

- 📝 **Capturing ad-hoc analysis** - Turn exploratory work into reusable scripts
- 🔄 **Reproducing issues** - Record exact steps to reproduce a problem
- 👥 **Sharing workflows** - Share your analysis procedure with teammates
- 📚 **Creating templates** - Build script templates through example
- 🎓 **Learning** - Record and review your analysis patterns

## Prerequisites

- JFR Shell installed and running
- At least one JFR recording file for practice
- Basic familiarity with JFR Shell commands

## Learning Path

1. [Basic Recording](#basic-recording)
2. [Viewing and Replaying Recordings](#viewing-and-replaying-recordings)
3. [Converting to Parameterized Scripts](#converting-to-parameterized-scripts)
4. [Advanced Workflows](#advanced-workflows)
5. [Best Practices](#best-practices)

## Basic Recording

### Step 1: Start JFR Shell

```bash
$ jfr-shell
╔═══════════════════════════════════════╗
║           JFR Shell (CLI)             ║
║     Interactive JFR exploration       ║
╚═══════════════════════════════════════╝
Type 'help' for commands, 'exit' to quit

jfr>
```

### Step 2: Start Recording

```bash
jfr> record start
Recording started: /Users/you/.jfr-shell/recordings/session-20251226143022.jfrs
```

By default, recordings go to `~/.jfr-shell/recordings/` with a timestamp filename.

Or specify a custom path:

```bash
jfr> record start /tmp/my-analysis.jfrs
Recording started: /tmp/my-analysis.jfrs
```

### Step 3: Do Your Analysis

Now perform your analysis as usual. Every command is recorded:

```bash
jfr> open /tmp/recording.jfr
Session 1 opened: /tmp/recording.jfr

jfr> info
Session 1: /tmp/recording.jfr
  Duration: 60.5s
  Events: 12,453
  Types: 47

jfr> show events/jdk.ExecutionSample | count()
Total: 8,234

jfr> show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)
┌───────────────────────┬───────┐
│ Thread                │ Count │
├───────────────────────┼───────┤
│ main                  │ 3,451 │
│ http-worker-1         │ 2,103 │
│ gc-thread             │ 1,234 │
│ async-processor       │   876 │
│ background-task       │   570 │
└───────────────────────┴───────┘
```

### Step 4: Check Recording Status

```bash
jfr> record status
Recording to: /tmp/my-analysis.jfrs
```

### Step 5: Stop Recording

```bash
jfr> record stop
Recording stopped: /tmp/my-analysis.jfrs

jfr> exit
Goodbye!
```

**Note:** If you forget to stop, recording auto-saves when you exit!

## Viewing and Replaying Recordings

### View the Recorded Script

```bash
$ cat /tmp/my-analysis.jfrs
```

Output:

```bash
# JFR Shell Recording
# Started: 2025-12-26T14:30:22Z
# Session: my-analysis.jfrs

# [14:30:35]
open /tmp/recording.jfr

# [14:30:40]
info

# [14:30:45]
show events/jdk.ExecutionSample | count()

# [14:31:05]
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

# Recording stopped: 2025-12-26T14:32:00Z
```

Notice:
- ✅ Header with timestamp
- ✅ Comments with command timestamps `# [HH:mm:ss]`
- ✅ All commands exactly as you typed them
- ✅ Footer with stop timestamp

### Replay the Recording

The recorded file is immediately executable:

```bash
$ jfr-shell script /tmp/my-analysis.jfrs
```

The exact same commands execute in sequence!

### Replay on Different Recording

Just replace the path in the recorded script:

```bash
$ cat /tmp/my-analysis.jfrs | sed 's|/tmp/recording.jfr|/tmp/other-recording.jfr|' | jfr-shell script -
```

Or manually edit the file and change the path.

## Converting to Parameterized Scripts

Recorded scripts have hardcoded values. Let's make them reusable!

### Original Recorded Script

```bash
# JFR Shell Recording
# Started: 2025-12-26T14:30:22Z

# [14:30:35]
open /tmp/prod-recording-20251226.jfr

# [14:30:45]
show events/jdk.ExecutionSample | count()

# [14:31:05]
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)

# [14:31:20]
show events/jdk.FileRead[bytes>=1000] --limit 10

# Recording stopped: 2025-12-26T14:32:00Z
```

### Parameterized Version

Edit the file to use variables:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Thread Analysis Script
# Converted from recorded session on 2025-12-26
#
# Usage:
#   ./thread-analysis.jfrs recording=/path/to/file.jfr top_n=5 min_bytes=1000
#
# Arguments:
#   recording  - Path to JFR recording file
#   top_n      - Number of top threads to show
#   min_bytes  - Minimum bytes for file read filter

# Open recording
open $1

# Count execution samples
show events/jdk.ExecutionSample | count()

# Top threads by sample count
show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(${top_n}, by=count)

# Large file reads
show events/jdk.FileRead[bytes>=${min_bytes}] --limit 10
```

### Make It Executable

```bash
chmod +x thread-analysis.jfrs
```

### Use It

```bash
# Basic usage
./thread-analysis.jfrs recording=/tmp/app.jfr top_n=5 min_bytes=1000

# Different parameters
./thread-analysis.jfrs recording=/tmp/prod.jfr top_n=20 min_bytes=5000

# Analyze multiple recordings
for f in /tmp/recordings/*.jfr; do
  echo "=== $f ==="
  ./thread-analysis.jfrs recording=$f top_n=10 min_bytes=2000
done
```

## Advanced Workflows

### Workflow 1: Iterative Refinement

Record, review, edit, and re-record to build the perfect script.

**Iteration 1: Initial Exploration**

```bash
jfr> record start /tmp/explore.jfrs
jfr> open /tmp/app.jfr
jfr> show events/jdk.ExecutionSample | count()
jfr> show events/jdk.GarbageCollection | count()
jfr> record stop
```

**Iteration 2: Add More Analysis**

```bash
jfr> record start /tmp/explore.jfrs  # Overwrites previous
jfr> open /tmp/app.jfr
jfr> info
jfr> show events/jdk.ExecutionSample | count()
jfr> show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
jfr> show events/jdk.GarbageCollection | stats(duration)
jfr> show events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name) | top(20, by=sum(allocationSize))
jfr> close
jfr> record stop
```

Now you have a refined, complete analysis script!

### Workflow 2: Building a Script Library

Create recordings for different analysis types:

```bash
# CPU profiling
jfr> record start ~/jfr-scripts/cpu-profiling.jfrs
jfr> open /tmp/sample.jfr
jfr> show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
jfr> show events/jdk.ExecutionSample | groupBy(stackTrace) | top(10, by=count)
jfr> record stop

# Memory profiling
jfr> record start ~/jfr-scripts/memory-profiling.jfrs
jfr> open /tmp/sample.jfr
jfr> show events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)
jfr> show events/jdk.ObjectAllocationInNewTLAB | groupBy(objectClass/name) | top(20, by=sum(allocationSize))
jfr> show events/jdk.GarbageCollection | stats(duration)
jfr> record stop

# I/O profiling
jfr> record start ~/jfr-scripts/io-profiling.jfrs
jfr> open /tmp/sample.jfr
jfr> show events/jdk.FileRead | sum(bytes)
jfr> show events/jdk.FileWrite | sum(bytes)
jfr> show events/jdk.FileRead[bytes>=10000] --limit 20
jfr> record stop
```

Now you have a library:
```
~/jfr-scripts/
├── cpu-profiling.jfrs
├── memory-profiling.jfrs
└── io-profiling.jfrs
```

### Workflow 3: Collaborative Debugging

Share exact reproduction steps with teammates.

**Scenario:** You found an issue and want to share your diagnostic steps.

```bash
jfr> record start /tmp/issue-123-diagnosis.jfrs
jfr> open /tmp/prod-snapshot.jfr
jfr> info
jfr> show events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(10, by=count)
jfr> show events/jdk.ThreadPark | groupBy(parkedClass/name) | top(10, by=count)
jfr> show events/jdk.JavaMonitorWait | stats(duration)
jfr> record stop
```

Send `/tmp/issue-123-diagnosis.jfrs` to your teammate. They can:

```bash
# Run your exact analysis on their recording
jfr-shell script issue-123-diagnosis.jfrs
```

Or convert to parameterized version for their recordings:

```bash
# Edit to use $1 variable
vim issue-123-diagnosis.jfrs

# Run on their recording
./issue-123-diagnosis.jfrs recording=/tmp/their-recording.jfr
```

### Workflow 4: Teaching and Onboarding

Record your analysis as a teaching aid.

**Example: Onboarding New Team Member**

```bash
jfr> record start /tmp/onboarding-demo.jfrs
jfr> # Let me show you how to analyze thread contention
jfr> open /tmp/example-recording.jfr
jfr> # First, check overall recording info
jfr> info
jfr> # Look for monitor wait events
jfr> show events/jdk.JavaMonitorEnter | count()
jfr> # Find which monitors have most contention
jfr> show events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(10, by=count)
jfr> # Check wait times
jfr> show events/jdk.JavaMonitorWait | stats(duration)
jfr> record stop
```

Share `/tmp/onboarding-demo.jfrs`:
- Comments explain the thinking
- New team members see the exact commands
- They can replay on their own recordings

## Best Practices

### 1. Use Descriptive Filenames

```bash
# Good
jfr> record start /tmp/thread-deadlock-analysis.jfrs
jfr> record start ~/scripts/performance-regression-check.jfrs
jfr> record start ./diagnostics/memory-leak-investigation.jfrs

# Bad
jfr> record start /tmp/test.jfrs
jfr> record start /tmp/script1.jfrs
jfr> record start /tmp/temp.jfrs
```

### 2. Add Comments During Recording

While recording doesn't capture your typed comments (comments starting with `#`), you can add them afterward when converting to a parameterized script.

### 3. Clean Up Mistakes

If you make a mistake during recording:

**Option A:** Stop and restart:
```bash
jfr> record stop
jfr> record start /tmp/my-analysis.jfrs  # Start fresh
```

**Option B:** Edit the recording after stopping:
```bash
jfr> record stop
# Edit /tmp/my-analysis.jfrs to remove incorrect commands
```

### 4. Test Your Recordings

Always test recorded scripts before sharing:

```bash
# Create recording
jfr> record start /tmp/new-script.jfrs
jfr> ...commands...
jfr> record stop

# Test immediately
$ jfr-shell script /tmp/new-script.jfrs

# Verify output is correct
```

### 5. Version Control Your Scripts

Store recordings in version control:

```bash
project/
├── jfr-scripts/
│   ├── daily-health-check.jfrs
│   ├── performance-analysis.jfrs
│   └── memory-diagnostic.jfrs
└── README.md
```

Update the README with usage instructions.

### 6. Organize by Purpose

Create a directory structure:

```bash
jfr-scripts/
├── diagnostics/
│   ├── thread-deadlock.jfrs
│   ├── memory-leak.jfrs
│   └── cpu-spike.jfrs
├── performance/
│   ├── baseline-profile.jfrs
│   ├── regression-check.jfrs
│   └── load-test-analysis.jfrs
└── monitoring/
    ├── daily-summary.jfrs
    └── weekly-report.jfrs
```

### 7. Add Headers After Recording

Edit recorded scripts to add comprehensive headers:

```bash
#!/usr/bin/env -S jbang jfr-shell@btraceio script -
# Thread Contention Analysis
#
# Purpose:
#   Identifies threads with high contention and blocking operations
#
# Created: 2025-12-26 (from recorded session)
# Author: Your Team
#
# Usage:
#   ./thread-contention.jfrs recording=/path/to/file.jfr top_n=10
#
# Arguments:
#   recording - JFR recording file path
#   top_n     - Number of top results to show

...rest of script...
```

### 8. Keep a Recording Journal

Maintain a log of your recordings:

```markdown
# JFR Script Journal

## 2025-12-26: Thread Deadlock Analysis
- File: `diagnostics/thread-deadlock-check.jfrs`
- Created from investigation of PROD-123
- Useful for checking lock acquisition patterns

## 2025-12-25: GC Pause Investigation
- File: `performance/gc-pause-analysis.jfrs`
- Created during performance tuning session
- Parameters: recording, max_acceptable_pause_ms
```

## Real-World Examples

### Example 1: Incident Investigation

**Scenario:** Production outage, need to analyze JFR snapshots.

```bash
# Start investigation recording
jfr> record start /tmp/prod-outage-20251226-analysis.jfrs

# Load production snapshot
jfr> open /tmp/prod-snapshot-14-30.jfr

# Check basic stats
jfr> info

# Look for thread issues
jfr> show events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(20, by=count)
jfr> show events/jdk.ThreadPark | count()

# Check GC pressure
jfr> show events/jdk.GarbageCollection | stats(duration)
jfr> show events/jdk.ObjectAllocationInNewTLAB | sum(allocationSize)

# Examine CPU usage
jfr> show events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(15, by=count)

jfr> record stop
Recording stopped: /tmp/prod-outage-20251226-analysis.jfrs

# Now parameterize and run on all snapshots
```

Convert to parameterized script and analyze all snapshots:

```bash
for snapshot in /tmp/prod-snapshot-*.jfr; do
  echo "=== Analyzing $snapshot ==="
  ./outage-analysis.jfrs recording=$snapshot
done
```

### Example 2: Performance Baseline

**Scenario:** Create baseline performance profile.

```bash
jfr> record start ~/baselines/app-v1.0-baseline.jfrs
jfr> open /tmp/v1.0-reference.jfr
jfr> info
jfr> show events/jdk.ExecutionSample | count()
jfr> show events/jdk.GarbageCollection | stats(duration)
jfr> show events/jdk.FileRead | sum(bytes)
jfr> show events/jdk.FileWrite | sum(bytes)
jfr> record stop
```

Use this baseline for future comparison:

```bash
# Compare new version against baseline
jfr-shell script app-v1.0-baseline.jfrs  # Run baseline
# ... compare output with new version metrics
```

## Troubleshooting

### Recording Not Saving

**Problem:** Recording seems to not save.

**Solutions:**
- Ensure you called `record stop` or exited cleanly
- Check the recording path: `record status`
- Verify write permissions on the target directory

### Can't Find Recorded File

**Problem:** Can't locate the recorded file.

**Solution:** Check the default location:
```bash
ls -lt ~/.jfr-shell/recordings/
```

Recent recordings appear first.

### Recorded Commands Don't Work

**Problem:** Replaying recorded script fails.

**Possible Causes:**
1. Hardcoded paths no longer exist
   - **Fix:** Parameterize the script with variables
2. Recording file moved
   - **Fix:** Update path in the script
3. Different JFR event types
   - **Fix:** Use `--continue-on-error` flag

## Next Steps

Now that you can record commands:

1. **Practice**: Record your next analysis session
2. **Build Library**: Create a collection of common analysis scripts
3. **Share**: Collaborate with teammates using recorded scripts
4. **Parameterize**: Convert recordings to reusable templates
5. **Automate**: Integrate scripts into CI/CD pipelines

## Summary

You've learned to:

- ✅ Start and stop command recording
- ✅ View recorded scripts
- ✅ Replay recorded commands
- ✅ Convert recordings to parameterized scripts
- ✅ Use recordings for collaboration and documentation
- ✅ Organize and maintain a script library

Recording transforms exploratory analysis into reusable, shareable, and documentable workflows!

Happy recording! 🎬
