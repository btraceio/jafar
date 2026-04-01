# PprofPath Reference

PprofPath is the query language used by JAFAR to analyze pprof profiles. It has a single
root (`samples`), a filter syntax for narrowing samples, and a pipeline of operators for
aggregation, sorting, and flame graph generation.

## Grammar

```
query      ::= root ('[' predicates ']')? ('|' op)*
root       ::= 'samples'
predicates ::= predicate (('and' | 'or') predicate)*
predicate  ::= field op literal
op         ::= '=' | '==' | '!=' | '>' | '>=' | '<' | '<=' | '~'
literal    ::= quoted-string | number | bare-word
pipeline   ::= op-name '(' args ')'
```

## Root

`samples` is the only root. It returns all samples from the currently open pprof profile
as a flat list of rows.

```
samples
samples[thread='main']
samples | count()
```

## Row Fields

Each sample row contains the following fields:

| Field | Type | Description |
|-------|------|-------------|
| *value type name* | `long` | One field per sample type declared in the profile, named by `sampleType.type`. For example `cpu`, `alloc_objects`, `samples`. |
| `stackTrace` | `List<Map>` | Stack frames, leaf (hottest) first. Each frame is a map with `name` (function name), `filename`, and `line` (line number). |
| *label keys* | `String` or `long` | Any labels attached to the sample. String labels produce a `String` field; numeric labels produce a `long` field. Common examples: `thread`, `goroutine`. |

Value type names come from the profile's `sampleType` declarations. Use `info` in the shell to
see which types are present for the open profile.

### Accessing nested fields

Use a `/`-delimited path to reach inside `stackTrace`:

```
stackTrace/0/name      # leaf function name
stackTrace/0/filename  # leaf function source file
stackTrace/0/line      # leaf function line number
stackTrace/1/name      # caller function name
```

The index is zero-based. Out-of-range indices resolve to `null`.

## Predicates

Predicates appear in square brackets after `samples`:

```
samples[thread='main']
samples[cpu > 10000000]
samples[stackTrace/0/name ~ 'HashMap.*']
samples[thread='main' and cpu > 5000000]
```

### Operators

| Operator | Meaning |
|----------|---------|
| `=` or `==` | Equal (string or numeric) |
| `!=` | Not equal |
| `>` | Greater than |
| `>=` | Greater than or equal |
| `<` | Less than |
| `<=` | Less than or equal |
| `~` | Regex match (uses Java `Pattern.find`) |

### Boolean combinators

| Keyword | Alternative | Meaning |
|---------|-------------|---------|
| `and` | `&&` | Both predicates must match |
| `or` | `\|\|` | Either predicate must match |

Boolean operators are left-associative. Parentheses are not supported in predicates;
use chained `filter()` pipeline steps for complex logic.

### Literals

- **Quoted strings**: `'main'`, `"main"` — both single and double quotes are accepted.
- **Numbers**: integers (`10000000`) and decimals (`3.14`) are both valid.
- **Bare words**: unquoted identifiers like `main` — treated as strings.

## Pipeline Operators

Operators are chained with `|`:

```
samples | groupBy(thread, sum(cpu)) | head(10)
```

---

### `count()`

Returns the total number of rows as a single-row result.

```
samples | count()
→ { count: 45230 }
```

---

### `top(n [, field [, asc|desc]])`

Returns the top `n` rows sorted by `field` in descending order (default) or ascending order.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `n` | yes | — | Number of rows to return |
| `field` | no | first sample type | Field to sort by |
| `asc` or `desc` | no | `desc` | Sort direction |

```
samples | top(10)
samples | top(10, cpu)
samples | top(10, cpu, asc)
samples | top(5, alloc_space, desc)
```

---

### `groupBy(field [, count | sum(valueField)])`

Groups rows by `field` and aggregates each group.

| Form | Description |
|------|-------------|
| `groupBy(field)` | Count samples per group |
| `groupBy(field, count)` | Count samples per group (explicit) |
| `groupBy(field, sum(valueField))` | Sum `valueField` per group |

Results are sorted by the aggregate value in descending order.

The aggregate column is named:
- `count` for count aggregations
- `sum_<valueField>` for sum aggregations

```
# Count samples per thread
samples | groupBy(thread)

# Total CPU per thread
samples | groupBy(thread, sum(cpu))

# Top allocating functions
samples | groupBy(stackTrace/0/name, sum(alloc_objects))
```

---

### `stats(field)`

Computes descriptive statistics for a numeric field over all rows.

Returns a single row with: `field`, `count`, `sum`, `min`, `max`, `avg`.

```
samples | stats(cpu)

| field | count | sum         | min  | max        | avg    |
|-------|-------|-------------|------|------------|--------|
| cpu   | 45230 | 30000000000 | 1000 | 2048000000 | 663250 |
```

---

### `head(n)`

Returns the first `n` rows. Applied after sorting or grouping, it gives the top entries.

```
samples | head(10)
samples | groupBy(thread, sum(cpu)) | head(5)
```

---

### `tail(n)`

Returns the last `n` rows.

```
samples | sortBy(cpu, asc) | tail(5)
```

---

### `filter(predicate)`

Filters rows within the pipeline using the same predicate syntax as `samples[...]`.
Also available as `where(predicate)`.

```
samples | groupBy(thread, sum(cpu)) | filter(sum_cpu > 1000000000)
samples | head(100) | filter(stackTrace/0/name ~ 'Foo')
```

---

### `select(field1, field2, ...)`

Projects rows to a subset of fields. Fields not present in a row are omitted silently.

Nested paths (`stackTrace/0/name`) are supported.

```
samples | select(cpu, thread)
samples | select(cpu, thread, stackTrace/0/name) | head(20)
```

---

### `sortBy(field [, asc|desc])`

Sorts rows by a field. Default direction is descending.
Also available as `sort(...)` and `orderBy(...)`.

```
samples | sortBy(cpu)
samples | sortBy(cpu, asc)
samples | groupBy(thread) | sortBy(count, asc)
```

---

### `stackprofile([valueField])`

Aggregates samples into a folded stack profile for flame graph generation.

Each unique call stack becomes one row with:
- `stack` — semicolon-separated frame names, root first (flame graph convention)
- `<valueField>` — sum of the value field across all samples with that stack.
  If no field is specified, the first declared sample type is used.

```
samples | stackprofile()
samples | stackprofile(cpu)
samples | stackprofile(alloc_objects)

# Flame graph for one thread only
samples[thread='main'] | stackprofile()
```

Results are sorted by the value field descending.

**Output format:**

```
| stack                                          | cpu           |
|------------------------------------------------|---------------|
| main;processRequest;readDatabase;executeQuery  | 5,432,100,000  |
| main;processRequest;serializeResponse          | 2,109,876,543  |
```

---

### `distinct(field)`

Returns the distinct values of `field`, one per row. Also available as `unique(field)`.

```
samples | distinct(thread)
samples | distinct(stackTrace/0/name)
```

---

## Chaining Operators

Operators are applied left-to-right. The output of each step is the input to the next:

```
# Top 10 threads by CPU, only those with > 1s total CPU
samples
  | groupBy(thread, sum(cpu))
  | filter(sum_cpu > 1000000000)
  | sortBy(sum_cpu)
  | head(10)
```

---

## Examples

### Count all samples

```
samples | count()
```

### Most expensive functions (CPU)

```
samples | groupBy(stackTrace/0/name, sum(cpu)) | head(20)
```

### Thread breakdown

```
samples | groupBy(thread, sum(cpu)) | head(10)
```

### Flame graph for all samples

```
samples | stackprofile()
```

### Flame graph, allocation-weighted

```
samples | stackprofile(alloc_space)
```

### Filter to one thread, then profile

```
samples[thread='main'] | stackprofile(cpu)
```

### Samples with very long CPU hold

```
samples[cpu > 100000000] | top(10, cpu)
```

### Statistics for allocation objects

```
samples | stats(alloc_objects)
```

### Distinct goroutines (Go profiles)

```
samples | distinct(goroutine)
```

### Caller hotspots (one frame above leaf)

```
samples | groupBy(stackTrace/1/name, sum(cpu)) | head(10)
```
