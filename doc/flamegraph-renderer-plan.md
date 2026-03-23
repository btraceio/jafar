# Flamegraph Renderer — Implementation Plan

## Goal

Add a `| flamegraph()` JfrPath pipeline operator that renders a terminal flamegraph
from any JFR event type that carries a `stackTrace` field. Output is ANSI-colored
filled cells rendered via the TamboUI `Buffer` API — identical rendering path to
`TuiTableRenderer`, works in both plain CLI and `--tui` mode.

## Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Rendering API | `Buffer` direct (`setString` + `Style.bg(Color.rgb(...))`) | Canvas `Rectangle` only draws outlines; Buffer cell painting is exact fit |
| Orientation | Root at bottom (classic Brendan Gregg style) | Leaves (hot methods) at top, entry points at bottom |
| Output format | ANSI inline (same as TuiTableRenderer) | Works in both `--tui` and plain CLI; no special-casing needed |
| JSON output | Not implemented | No meaningful use case for flamegraph tree in JSON form here |
| `direction` param | `top-down` / `bottom-up` | Controls whether stack is read leaf-first or root-first |

## Files to Create / Modify

### 1. `jfr-shell-core` — `FlameNode`

**New file:** `jfr-shell-core/src/main/java/io/jafar/shell/core/FlameNode.java`

Simple tree node. Extracted and generalized from `JafarMcpServer.FlameNode`:

```java
public final class FlameNode {
    public final String name;
    public long value;                                  // sample count
    public final Map<String, FlameNode> children = new LinkedHashMap<>();

    public FlameNode(String name) { this.name = name; }

    public void addPath(List<String> frames) {
        value++;
        if (!frames.isEmpty()) {
            children.computeIfAbsent(frames.get(0), FlameNode::new)
                    .addPath(frames.subList(1, frames.size()));
        }
    }
}
```

No static `build()` helper in this class — frame extraction stays in
`JfrPathEvaluator` (it already handles untyped Map structures correctly).

---

### 2. `jfr-shell-core` — `JfrPath`

**Modify:** `jfr-shell-core/src/main/java/io/jafar/shell/jfrpath/JfrPath.java`

Add `FlameGraphOp` alongside `StackProfileOp`:

```java
public static final class FlameGraphOp implements PipelineOp {
    public final String direction;    // "top-down" | "bottom-up", default "bottom-up"

    public FlameGraphOp(String direction) {
        this.direction = (direction == null || direction.isBlank()) ? "bottom-up" : direction;
    }
}
```

Default is `bottom-up` because the root appears at the bottom of the rendered output
(entry points at the bottom row, leaves at the top row).

---

### 3. `jfr-shell-core` — `JfrPathParser`

**Modify:** `jfr-shell-core/src/main/java/io/jafar/shell/jfrpath/JfrPathParser.java`

Add `parseFlameGraph()` method (pattern mirrors `parseStackProfile()`):

```
flamegraph()
flamegraph(direction=bottom-up)
flamegraph(direction=top-down)
```

Register in the pipeline operator dispatch (wherever `stackprofile` is recognized).

---

### 4. `jfr-shell-core` — `JfrPathEvaluator`

**Modify:** `jfr-shell-core/src/main/java/io/jafar/shell/jfrpath/JfrPathEvaluator.java`

Two integration points (same pattern as `StackProfileOp`):

**a) Direct evaluation path (line ~712):**
```java
case JfrPath.FlameGraphOp fg ->
    aggregateFlameGraph(session, query, fg.direction, progress);
```

**b) Row-based pipeline path (line ~3052):**
```java
case JfrPath.FlameGraphOp fg ->
    applyFlameGraph(rows, fg.direction);
```

**`aggregateFlameGraph()` implementation:**
- Stream events from session, filter by `hasStackTraceFrames()`
- For each event call `extractFramesForProfile(event, direction)` (already exists)
- Feed each frame list into `FlameNode root = new FlameNode("root"); root.addPath(frames)`
- Return: `List.of(Map.of("__flamegraph", root))`

**`applyFlameGraph()` implementation:**
- Same but operates on already-loaded `List<Map<String, Object>> rows`
- Extracts frames from each row's stackTrace

**Return value encoding:**

The evaluator returns `List<Map<String, Object>>`. A flamegraph result is encoded as
a single-element list with a sentinel key:

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("__flamegraph", flameNodeRoot);   // FlameNode object
return List.of(result);
```

`CommandDispatcher` detects `rows.size() == 1 && rows.get(0).containsKey("__flamegraph")`
and routes to `FlameGraphRenderer`.

---

### 5. `jfr-shell` — `FlameGraphRenderer`

**New file:** `jfr-shell/src/main/java/io/jafar/shell/cli/FlameGraphRenderer.java`

Core rendering algorithm:

```
terminalWidth  = detect (same as TuiTableRenderer.terminalWidth())
maxDepth       = computeDepth(root)
totalSamples   = (int) root.value
height         = maxDepth + 1     // one row per depth level

buffer = Buffer.empty(Rect.of(terminalWidth, height))

renderNode(root, xOffset=0, depth=0):
    cellX    = (int)(xOffset * terminalWidth / totalSamples)
    cellXEnd = (int)((xOffset + node.value) * terminalWidth / totalSamples)
    cellW    = max(1, cellXEnd - cellX)
    row      = height - depth - 1          // root at bottom row
    color    = frameColor(node.name)
    style    = Style.create().bg(color).fg(contrastColor(color))
    label    = truncate(simpleName(node.name), cellW)
    spaces   = " ".repeat(cellW)
    buffer.setString(cellX, row, spaces, style)    // fill background
    if cellW >= 3:
        buffer.setString(cellX, row, label, style) // overlay label
    childX = xOffset
    for child in node.children.values():
        renderNode(child, childX, depth + 1)
        childX += child.value
```

**Color scheme** — warm red/orange/yellow palette, varies by package hash:

```java
private static Color frameColor(String name) {
    int h = name.hashCode();
    // warm palette: R=200-255, G=0-230, B=0-100
    int r = 200 + (Math.abs(h) % 56);
    int g = Math.abs(h >> 6) % 230;
    int b = Math.abs(h >> 12) % 100;
    return Color.rgb(r, g, b);
}

private static Color contrastColor(Color bg) {
    // simple luminance threshold: use BLACK on light, WHITE on dark
    Color.Rgb rgb = bg.toRgb();
    double lum = 0.299 * rgb.red() + 0.587 * rgb.green() + 0.114 * rgb.blue();
    return lum > 128 ? Color.BLACK : Color.WHITE;
}
```

**Label extraction** — strip fully qualified class path to `ClassName.method`:
```java
private static String simpleName(String fullName) {
    // "io.netty.channel.epoll.EpollEventLoop.run" → "EpollEventLoop.run"
    int dot = fullName.lastIndexOf('.', fullName.lastIndexOf('.') - 1);
    return dot >= 0 ? fullName.substring(dot + 1) : fullName;
}
```

**Output:**
```java
String output = buffer.toAnsiStringTrimmed();
PagedPrinter pager = PagedPrinter.forIO(io);
for (String line : output.split("\r?\n", -1)) {
    if (!line.isBlank()) pager.println(line);
}
```

---

### 6. `jfr-shell` — `CommandDispatcher`

**Modify:** `jfr-shell/src/main/java/io/jafar/shell/cli/CommandDispatcher.java`

In `cmdShow()`, before the existing format routing block (~line 623), insert:

```java
// Flamegraph result — always rendered as ANSI regardless of format
if (rows.size() == 1 && rows.get(0).containsKey("__flamegraph")) {
    FlameNode root = (FlameNode) rows.get(0).get("__flamegraph");
    FlameGraphRenderer.render(root, io);
    return;
}
```

---

### 7. `jfr-shell` — `FunctionRegistry`

**Modify:** `jfr-shell/src/main/java/io/jafar/shell/cli/completion/FunctionRegistry.java`

Register in `registerPipelineOperators()` (alongside `stackprofile`):

```java
register(
    FunctionSpec.builder("flamegraph")
        .pipeline()
        .description("Render a flamegraph from events with stack traces")
        .template("flamegraph()")
        .enumKeyword("direction", List.of("bottom-up", "top-down"),
                     "Stack traversal direction (default: bottom-up)")
        .requiresAny()
        .build());
```

---

## Usage Examples

```
# CPU flamegraph from execution samples
show jdk.ExecutionSample | flamegraph()

# Wall-clock flamegraph (Datadog profiler)
show datadog.ExecutionSample | flamegraph()

# Filter to a specific thread before flaming
show jdk.ExecutionSample | filter(thread.javaName = "main") | flamegraph()

# Allocation flamegraph
show jdk.ObjectAllocationInNewTLAB | flamegraph()
```

## Limitations

- Width is bounded by terminal width (~120-220 chars typically); very wide trees clip
- No interactive zoom (static inline rendering); pipe to `less -R` to scroll
- `simpleName()` heuristic may not always produce the best label for lambdas/anonymous classes
- Frames with 0 width at current terminal width are silently dropped

## Out of Scope

- Canvas widget (`dev.tamboui.widgets.canvas.Canvas`) — not used; Buffer is sufficient
- Sharing `FlameNode` with `jfr-mcp` — `JafarMcpServer` keeps its own copy for now
- Interactive zoom / drill-down (possible future TUI enhancement)
