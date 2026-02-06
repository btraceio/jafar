# HPROF Parser Verification Matrix

This document tracks the verification status of each parsing method against the OpenJDK HPROF specification.

**Specification Source:** [heapDumper.cpp](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/services/heapDumper.cpp)

**Legend:**
- ⬜ Not verified
- ⚠️ Issues found
- ✅ Verified correct
- ➖ Not applicable

## HPROF Format Quick Reference

### Header Format
```
"JAVA PROFILE 1.0.2\0"  - Null-terminated format string
u4                      - Identifier size (4 or 8 bytes)
u8                      - Timestamp (milliseconds since epoch)
```

### Record Structure
```
u1  - Tag (record type)
u4  - Timestamp offset (microseconds)
u4  - Record length (bytes following)
[body]
```

---

## 1. Top-Level Record Parsers

### 1.1 parseUtf8() - UTF8 String Records

**Location:** `HeapDumpImpl.java:491-497`

**Spec:** `DumperSupport::dump_string()` - Tag 0x01

**Format:**
```
id    - Symbol ID
[u1]* - UTF-8 characters (no null terminator)
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Tag value (0x01) | ⬜ | |
| ID size handling (4 vs 8) | ⬜ | |
| UTF-8 decoding | ⬜ | |
| String storage in map | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 1.2 parseLoadClass() - Class Loading Records

**Location:** `HeapDumpImpl.java:499-516`

**Spec:** `DumperSupport::dump_class_names()` - Tag 0x02

**Format:**
```
u4 - Class serial number
id - Class object ID
u4 - Stack trace serial
id - Class name ID
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Tag value (0x02) | ⬜ | |
| Serial number handling | ⬜ | |
| Class ID mapping | ⬜ | |
| Stack trace serial (unused) | ⬜ | |
| Name ID resolution | ⬜ | |
| HeapClassImpl creation | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 1.3 parseHeapDump() - Heap Dump Dispatcher

**Location:** `HeapDumpImpl.java:518-544`

**Spec:** Parses sub-records within HEAP_DUMP (0x0C) or HEAP_DUMP_SEGMENT (0x1C)

**Format:**
```
[u1 subtag + subrecord data]* until segment end
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Segment boundary tracking | ⬜ | |
| Sub-tag dispatch | ⬜ | |
| Unknown tag handling | ⬜ | |
| Position validation | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

## 2. GC Root Parsers

### 2.1 parseGcRoot() - ROOT_UNKNOWN (0xFF)

**Location:** `HeapDumpImpl.java:546-549`

**Spec:** `DumperSupport::dump_instance()` unknown roots

**Format:**
```
id - Object ID
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID reading | ⬜ | |
| GC root storage | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.2 parseGcRootJniGlobal() - ROOT_JNI_GLOBAL (0x01)

**Location:** `HeapDumpImpl.java:551-555`

**Spec:** `DumperSupport::write_global_jni_reference()`

**Format:**
```
id - Object ID
id - JNI global ref ID
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| JNI ref ID | ⬜ | |
| Metadata storage | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.3 parseGcRootJniLocal() - ROOT_JNI_LOCAL (0x02)

**Location:** `HeapDumpImpl.java:557-562`

**Spec:** `DumperSupport::write_local_jni_reference()`

**Format:**
```
id - Object ID
u4 - Thread serial number
u4 - Frame number (-1 for empty)
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| Thread serial | ⬜ | |
| Frame number (-1 handling) | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.4 parseGcRootJavaFrame() - ROOT_JAVA_FRAME (0x03)

**Location:** `HeapDumpImpl.java:564-569`

**Spec:** `DumperSupport::write_stack_frame_reference()`

**Format:**
```
id - Object ID
u4 - Thread serial number
u4 - Frame number (-1 for empty)
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| Thread serial | ⬜ | |
| Frame number | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.5 parseGcRootNativeStack() - ROOT_NATIVE_STACK (0x04)

**Location:** `HeapDumpImpl.java:571-575`

**Spec:** `DumperSupport::write_stack_frame_reference()` (native)

**Format:**
```
id - Object ID
u4 - Thread serial number
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| Thread serial | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.6 parseGcRootStickyClass() - ROOT_STICKY_CLASS (0x05)

**Status:** ⚠️ **MISSING** - Handled in skipGcRoot() only

**Spec:** System classes

**Format:**
```
id - Object ID
```

**Issue:** No dedicated parsing method, just skipped

---

### 2.7 parseGcRootThreadBlock() - ROOT_THREAD_BLOCK (0x06)

**Location:** `HeapDumpImpl.java:577-581`

**Spec:** Thread blocking on object

**Format:**
```
id - Object ID
u4 - Thread serial number
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| Thread serial | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.8 parseGcRootMonitorUsed() - ROOT_MONITOR_USED (0x07)

**Status:** ⚠️ **MISSING** - Handled in skipGcRoot() only

**Spec:** Busy monitor

**Format:**
```
id - Object ID
```

**Issue:** No dedicated parsing method, just skipped

---

### 2.9 parseGcRootThreadObj() - ROOT_THREAD_OBJ (0x08)

**Location:** `HeapDumpImpl.java:583-588`

**Spec:** Thread objects

**Format:**
```
id - Thread object ID
u4 - Thread serial number
u4 - Stack trace serial number
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Thread object ID | ⬜ | |
| Thread serial | ⬜ | |
| Stack trace serial | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 2.10 Extended GC Roots (HPROF 1.0.3 / Android)

**Status:** ⚠️ **ALL MISSING** - Only skipGcRoot() handles these

These extended root types are skipped but not parsed:

| Tag | Name | Format | Status |
|-----|------|--------|--------|
| 0x89 | ROOT_INTERNED_STRING | id | ⚠️ Missing parser |
| 0x8A | ROOT_FINALIZING | id | ⚠️ Missing parser |
| 0x8B | ROOT_DEBUGGER | id | ⚠️ Missing parser |
| 0x8C | ROOT_REFERENCE_CLEANUP | id | ⚠️ Missing parser |
| 0x8D | ROOT_VM_INTERNAL | id | ⚠️ Missing parser |
| 0x8E | ROOT_JNI_MONITOR | id | ⚠️ Missing parser |
| 0x90 | UNREACHABLE | id | ⚠️ Missing parser |
| 0xFE | HEAP_DUMP_INFO | u4 heap_type, id heap_name | ⚠️ Missing parser |

**Note:** These are Android/modern JVM extensions, may not be critical for basic functionality

---

## 3. Heap Object Parsers

### 3.1 parseClassDump() - CLASS_DUMP (0x20)

**Location:** `HeapDumpImpl.java:590-642`

**Spec:** `DumperSupport::dump_instance_class()`

**Format:**
```
id  - Class object ID
u4  - Stack trace serial
id  - Super class ID
id  - Class loader ID
id  - Signers ID
id  - Protection domain ID
id  - Reserved (null)
id  - Reserved (null)
u4  - Instance size (bytes)
u2  - Constant pool size
[id field_name, u1 type, value]* constant pool entries
u2  - Static field count
[id field_name, u1 type, value]* static fields
u2  - Instance field count
[id field_name, u1 type]* instance fields (no values)
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Class object ID | ⬜ | |
| Stack trace serial | ⬜ | |
| Super class ID | ⬜ | |
| Class loader, signers, protection domain | ⬜ | |
| Reserved fields (2x id) | ⬜ | |
| Instance size | ⬜ | |
| Constant pool parsing | ⚠️ | Lines 609-614: Skipped with TODO |
| Static field count | ⬜ | |
| Static field values | ⬜ | |
| Instance field definitions | ⬜ | |
| Field type codes | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

**Known Issue:** Constant pool is read but not stored (lines 609-614)

---

### 3.2 parseInstanceDump() - INSTANCE_DUMP (0x21)

**Location:** `HeapDumpImpl.java:644-671`

**Spec:** `DumperSupport::dump_instance()`

**Format:**
```
id - Object ID
u4 - Stack trace serial
id - Class object ID
u4 - Data size (bytes)
[u1]* - Field values (raw bytes, class hierarchy order)
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object ID | ⬜ | |
| Stack trace serial | ⬜ | |
| Class object ID | ⬜ | |
| Data size | ⬜ | |
| Lazy vs eager loading | ⬜ | Lines 664-666: < 256 bytes → eager |
| Reference extraction | ⬜ | |
| Class field traversal | ⬜ | |
| Superclass field handling | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⚠️ | Reference extraction incomplete |

**Known Issue:** InboundIndexBuilder doesn't extract instance field references

---

### 3.3 parseObjArrayDump() - OBJ_ARRAY_DUMP (0x22)

**Location:** `HeapDumpImpl.java:673-700`

**Spec:** `DumperSupport::dump_object_array()`

**Format:**
```
id - Array object ID
u4 - Stack trace serial
u4 - Element count
id - Array class ID
[id]* - Element object IDs
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Array object ID | ⬜ | |
| Stack trace serial | ⬜ | |
| Element count | ⬜ | |
| Array class ID | ⬜ | |
| Element ID extraction | ⬜ | |
| Null reference handling | ⬜ | |
| Large array handling | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⬜ | |

---

### 3.4 parsePrimArrayDump() - PRIM_ARRAY_DUMP (0x23)

**Location:** `HeapDumpImpl.java:702-746`

**Spec:** `DumperSupport::dump_prim_array()`

**Format:**
```
id - Array object ID
u4 - Stack trace serial
u4 - Element count
u1 - Element type code (T_BOOLEAN=4, T_CHAR=5, T_FLOAT=6, T_DOUBLE=7,
                        T_BYTE=8, T_SHORT=9, T_INT=10, T_LONG=11)
[element_data]* - Raw element bytes
```

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Array object ID | ⬜ | |
| Stack trace serial | ⬜ | |
| Element count | ⬜ | |
| Element type code | ⬜ | BasicType values |
| Synthetic class creation | ⬜ | Lines 714-725 |
| Synthetic class naming | ⬜ | `[Z`, `[B`, `[I`, etc. |
| Element size calculation | ⬜ | |
| In-memory mode | ⬜ | |
| Indexed mode | ⚠️ | Type info lost (line 796) |

**Known Issue:** Element type not preserved in indexed mode

---

## 4. Indexed Mode Specific Methods

### 4.1 collectObjectAddresses() - Pass 1

**Location:** `HeapDumpImpl.java:217-250`

**Purpose:** First pass - collect all object addresses

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| All object types collected | ⬜ | |
| Address sorting | ⬜ | |
| addressToId32 mapping | ⬜ | |
| Memory efficiency | ⬜ | |

---

### 4.2 buildIndexes() - Pass 2

**Location:** `HeapDumpImpl.java:298-322`

**Purpose:** Second pass - build on-disk indexes

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object metadata written | ⬜ | |
| Class ID mapping | ⚠️ | 32-bit IDs not mapped back |
| Array type preservation | ⚠️ | Primitive type lost |
| Index file format | ⬜ | |

**Known Issues:**
- Line 783: Class ID mapping broken (32-bit → 64-bit)
- Line 796: Primitive array type lost

---

### 4.3 getOrCreateClassId() - Class ID Assignment

**Location:** `HeapDumpImpl.java:387-394`

**Purpose:** Assign 32-bit sequential IDs to classes

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Sequential ID generation | ⬜ | |
| classIdMap population | ⬜ | |
| Reverse mapping | ⚠️ | **MISSING** - not persisted |

**Critical Issue:** No reverse mapping from 32-bit ID → 64-bit address → HeapClassImpl

---

### 4.4 InboundIndexBuilder - Reference Extraction

**Location:** `InboundIndexBuilder.java:101-150`

**Purpose:** Extract inbound references for retained size calculation

| Verification Check | Status | Notes |
|-------------------|--------|-------|
| Object array refs | ⬜ | Lines 125-142 |
| Instance field refs | ⚠️ | Lines 121-123: **SKIPPED** |
| Class field info access | ⚠️ | Not available |
| Reference counting | ⚠️ | Incomplete |

**Critical Issue:** Instance field references not extracted - causes wrong retained sizes

---

## 5. Basic Type Handling

### 5.1 BasicType Constants

**Location:** `BasicType.java`

**Spec:** OpenJDK `globalDefinitions.hpp` BasicType enum

| Type Code | Name | Size | Status |
|-----------|------|------|--------|
| 2 | OBJECT | idSize (4 or 8) | ⬜ |
| 4 | BOOLEAN | 1 | ⬜ |
| 5 | CHAR | 2 | ⬜ |
| 6 | FLOAT | 4 | ⬜ |
| 7 | DOUBLE | 8 | ⬜ |
| 8 | BYTE | 1 | ⬜ |
| 9 | SHORT | 2 | ⬜ |
| 10 | INT | 4 | ⬜ |
| 11 | LONG | 8 | ⬜ |

**Verification:** Compare against OpenJDK BasicType values

---

## Summary Statistics

### Overall Verification Status

| Category | Total Methods | Not Verified | Issues Found | Verified |
|----------|--------------|--------------|--------------|----------|
| Top-level records | 3 | 3 | 0 | 0 |
| GC roots (standard) | 9 | 9 | 2 missing | 0 |
| GC roots (extended) | 8 | 8 | 8 missing | 0 |
| Heap objects | 4 | 4 | 2 issues | 0 |
| Indexed mode | 4 | 4 | 3 critical | 0 |
| **TOTAL** | **28** | **28** | **15** | **0** |

### Critical Issues Summary

1. **Class ID Mapping** (HeapDumpImpl:783) - Type mismatch in indexed mode
2. **Primitive Array Type Loss** (HeapDumpImpl:796) - Element type not preserved
3. **Incomplete Reference Extraction** (InboundIndexBuilder:121) - Instance fields skipped
4. **Missing Extended GC Root Parsers** - 8 root types only skipped, not parsed
5. **Constant Pool Ignored** (HeapDumpImpl:609-614) - Data read but not stored

### Priority Order

**P0 (Critical - Causes wrong results/crashes):**
1. Class ID mapping bug
2. Primitive array type loss
3. Incomplete reference extraction

**P1 (Completeness - Missing functionality):**
4. Extended GC root parsers
5. Constant pool storage

**P2 (Nice to have):**
6. Adaptive threshold tuning (line 664-666)

---

## Verification Procedure

For each method:
1. ✅ Read corresponding heapDumper.cpp implementation
2. ✅ Document expected byte format
3. ✅ Compare field order and types
4. ✅ Create synthetic test case
5. ✅ Verify with real heap dump
6. ✅ Test in-memory mode
7. ✅ Test indexed mode
8. ✅ Mark as verified or document issues

---

**Last Updated:** 2026-02-06
**Specification Version:** OpenJDK master (HPROF 1.0.2)
