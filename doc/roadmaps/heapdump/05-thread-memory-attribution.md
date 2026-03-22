# Thread-to-Memory Attribution

## Goal

Answer: "Which thread owns how much memory?"

Attribute heap memory to individual threads by analyzing GC root ownership. In server applications, individual request-processing threads can hold onto large response buffers, deserialized objects, or leaked contexts. This bridges the gap between heap analysis and thread dump analysis.

## UX

**Pipeline operator** — `threadOwner()` and `dominatedSize()`.

Two complementary operators:
- `threadOwner()` enriches object rows with the owning thread
- `dominatedSize()` enriches thread/GC-root rows with their dominated memory

## Syntax

```
# Which threads own the most memory?
gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated)

# Per-thread memory breakdown
gcroots/THREAD_OBJ | dominatedSize() | select(threadName, dominated, dominatedCount)

# Find which thread owns a specific object
objects/java.util.HashMap[retained > 10MB] | threadOwner() | select(id, class, retained, ownerThread)

# Find objects not exclusively owned by any thread (shared)
objects[retained > 1MB] | threadOwner() | filter(ownerThread = "shared") | top(20)

# Thread-local vs shared memory summary
gcroots/THREAD_OBJ | dominatedSize() | select(threadName, exclusive, shared)
```

## Design

### Thread ownership model

An object is "owned" by a thread if:
1. The thread's GC root (THREAD_OBJ or JAVA_FRAME) is the only root path to that object
2. In dominator tree terms: the object is dominated by a node that is itself dominated by a thread root

Classification:
- **Exclusive**: object reachable only from one thread's roots
- **Shared**: object reachable from multiple thread roots (or from non-thread roots like static fields)

### `threadOwner()` operator

For each object in the input stream:
1. Walk up the dominator tree to find the nearest thread root
2. If the object is dominated by a thread root subtree → `ownerThread = threadName`
3. If the object is reachable from multiple roots → `ownerThread = "shared"`

Enrichment columns:
| Column | Type | Description |
|--------|------|-------------|
| `ownerThread` | String | Thread name or "shared" |
| `ownerThreadId` | long | Java thread ID (or -1 for shared) |
| `ownership` | String | "exclusive" or "shared" |

### `dominatedSize()` operator

For each GC root (typically THREAD_OBJ) in the input stream:
1. Find all objects dominated by this root in the dominator tree
2. Sum their retained sizes

Enrichment columns:
| Column | Type | Description |
|--------|------|-------------|
| `dominated` | long | Total bytes exclusively dominated by this root |
| `dominatedCount` | int | Number of objects exclusively dominated |
| `exclusive` | long | Memory reachable only from this thread |
| `shared` | long | Memory reachable from this thread AND other roots |
| `threadName` | String | Thread name (from THREAD_OBJ) |

### Implementation approach

The dominator tree already assigns each object a single immediate dominator. Thread attribution walks up the dominator chain:

```
for each object:
    node = object
    while node != root:
        if node is THREAD_OBJ root:
            return node.threadName  // exclusive
        node = dominator(node)
    return "shared"
```

This is O(depth) per object, O(n * avg_depth) total. Can be optimized by caching thread ownership at dominator subtree roots.

## Key decisions

| Decision | Options | Recommendation |
|----------|---------|----------------|
| Ownership granularity | Thread only vs thread + stack frame | Thread only initially; stack frame attribution is a stretch goal |
| Shared memory handling | Attribute to "shared" vs split proportionally | "shared" label; proportional splitting adds complexity for marginal benefit |
| JAVA_FRAME roots | Merge with parent THREAD_OBJ vs separate | Merge — JAVA_FRAME roots belong to their thread |
| Caching | Cache per-query vs persist in session | Cache in session after first computation (reused across queries) |
| Non-thread roots | Ignore vs show as "static" / "jni" | Show with root type label: "static", "jni_global", etc. |

## Complexity

**Medium.** The dominator tree is already computed. Thread attribution is a traversal of the dominator tree partitioned by thread roots. The main work is the upward walk and caching.

## Dependencies

- **Dominator tree** — already computed in hdump-parser
- **GC root type information** — already available; the 3-pane GC root browser uses it
- **Thread name extraction** — reading `name` field from `java.lang.Thread` objects (HPROF field access)

## Verification

- **Unit test**: create HPROF with two threads each holding distinct large objects, verify exclusive attribution
- **Shared objects**: verify objects reachable from both threads are labeled "shared"
- **Static fields**: verify objects reachable only from static field roots are not attributed to any thread
- **Consistency check**: sum of all exclusive thread memory + shared memory should equal total heap retained size
