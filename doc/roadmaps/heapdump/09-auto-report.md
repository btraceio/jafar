# Automatic Natural-Language Heap Report

## Goal

Generate a structured, narrative health report from a heap dump by orchestrating all existing analysis capabilities. Most developers aren't heap analysis experts — a report that says "here's what's wrong and here's what to do" is far more useful than raw query results.

Example output:
- "CRITICAL: 1.2GB (45% of heap) retained by 3 `HttpSession` objects via `ThreadLocal` in `RequestContext`."
- "WARNING: 180MB wasted in 42,000 `HashMap` instances with avg load factor 0.04. Consider using initial capacity hints."
- "INFO: String deduplication could save 95MB (3.6% of heap)."

## UX

**Top-level command** — `report` or `diagnose`.

Not a pipeline operator — it's an orchestration command that runs multiple analyses and produces a formatted report.

## Syntax

```
# Generate full report
report

# Generate report with specific focus areas
report --focus=leaks,waste

# Generate report in different formats
report --format=text
report --format=json
report --format=markdown

# Generate report and save to file
report --output=heap-report.md

# Customize severity thresholds
report --critical-threshold=100MB --warning-threshold=10MB
```

## Design

### Analysis pipeline

The `report` command runs analyses in order:

1. **Heap overview**: total size, object count, class count, dominator tree depth
2. **Leak detection**: run all registered `LeakDetector` implementations
3. **Dominator analysis**: identify top-N dominators by retained size
4. **Class histogram anomalies**: classes with disproportionate instance count or retained size
5. **Waste analysis** (#2, if implemented): collection right-sizing opportunities
6. **Cache analysis** (#8, if implemented): cache efficiency metrics
7. **GC root summary**: root type distribution, thread memory attribution (#5, if implemented)

### Report structure

```
=== Heap Health Report ===
Heap: dump.hprof (2.6 GB, 12.4M objects, 45,231 classes)

--- CRITICAL ---
[C1] ThreadLocal leak: 1.2GB retained by 3 HttpSession objects
     Path: Thread[http-nio-8080-exec-3] → ThreadLocalMap → RequestContext → HttpSession
     Action: Clear ThreadLocal after request completion

--- WARNING ---
[W1] Over-allocated collections: 180MB wasted
     42,000 HashMap instances with avg load factor 0.04
     Action: Pass initial capacity hints or use compact Map implementations

[W2] Duplicate strings: 95MB reclaimable
     23,400 unique strings duplicated avg 4.2 times
     Action: Enable -XX:+UseStringDeduplication or intern hot strings

--- INFO ---
[I1] Top dominator: com.example.AppCache holds 450MB (17% of heap)
     This is expected if the cache is intentional; verify maxSize configuration

[I2] 89% of heap is reachable from 12 GC roots
     Heap is well-concentrated; few roots control most memory
```

### Severity classification

| Severity | Criteria |
|----------|----------|
| CRITICAL | Retained size > `critical-threshold` AND matches a known leak pattern |
| WARNING | Retained size > `warning-threshold` OR waste > 5% of heap |
| INFO | Notable findings that may or may not be actionable |

### Finding structure (internal)

Each finding is a structured object:

```
Finding {
    severity: CRITICAL | WARNING | INFO
    category: "leak" | "waste" | "dominator" | "cache" | "gc-roots"
    title: String
    description: String
    retainedSize: long
    affectedObjects: int
    path: String          // reference chain or class path
    action: String        // suggested fix
    query: String         // HdumpPath query to reproduce
}
```

The `query` field is important — every finding includes the exact HdumpPath query that produces the underlying data, so the user can drill deeper.

### Extensibility

New analysis phases plug in via a `ReportContributor` interface:

```java
interface ReportContributor {
    String category();
    List<Finding> analyze(HeapSession session);
}
```

Existing leak detectors already implement a similar pattern (`LeakDetector`). The report command collects findings from all contributors, sorts by severity, and formats.

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Output format | Text vs JSON vs Markdown | All three; text default for terminal, JSON for programmatic, Markdown for sharing |
| LLM integration | Generate report text via LLM vs template-based | Template-based for determinism; LLM-enhanced summaries as optional mode |
| Finding deduplication | Show all vs merge similar | Merge similar findings (e.g., multiple ThreadLocal leaks → one finding with count) |
| Action suggestions | Generic vs context-specific | Context-specific where possible (include class names, field paths) |
| Progressive disclosure | Summary only vs summary + details | Summary by default; `--verbose` for full detail including reference chains |

## Complexity

**Low-Medium.** Orchestration of existing capabilities. The individual analyses already exist (leak detectors, dominator tree, class histogram). Main work is the report formatting, severity classification, and action-suggestion templates.

## Dependencies

- **Leak detectors** — `LeakDetectorRegistry` and all registered detectors (already exist)
- **Dominator tree** — already computed
- **Class histogram** — available from `classes` root
- **Optional**: #2 (Waste), #5 (Thread Attribution), #8 (Cache Profiling) — report is better with these but works without them

## Verification

- **Synthetic test**: create HPROF with known leak (ThreadLocal holding large object), verify CRITICAL finding is generated with correct path and size
- **Healthy heap**: analyze a heap with no issues, verify only INFO findings are generated
- **Format test**: verify text, JSON, and Markdown outputs are well-formed
- **Reproducibility**: verify every finding's `query` field produces valid HdumpPath results when executed
- **Regression**: store reference reports for test heaps; compare against future runs to catch regressions
