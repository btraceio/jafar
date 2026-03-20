package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.HeapObject;
import java.util.Map;

/**
 * Analyzes JDK collection objects for capacity waste. Reads internal fields of known collection
 * types to compute capacity, size, load factor, and wasted bytes.
 *
 * <p>Supported collections: HashMap, LinkedHashMap, HashSet, LinkedHashSet, ArrayList,
 * ConcurrentHashMap, ArrayDeque.
 */
final class CollectionWasteAnalyzer {

  private CollectionWasteAnalyzer() {}

  /**
   * Analyzes a heap object for collection waste and enriches the row with waste columns.
   *
   * @param obj the heap object to analyze
   * @param row the result row to enrich with waste columns
   * @param refSize the reference size in bytes (4 or 8)
   * @return true if the object was a recognized collection type, false otherwise
   */
  static boolean analyze(HeapObject obj, Map<String, Object> row, int refSize) {
    if (obj.getHeapClass() == null) {
      return false;
    }
    // HeapClass names use JVM internal form (slashes, not dots)
    String className = obj.getHeapClass().getName();
    return switch (className) {
      case "java/util/HashMap", "java/util/LinkedHashMap" -> analyzeHashMap(obj, row, refSize);
      case "java/util/HashSet", "java/util/LinkedHashSet" -> analyzeHashSet(obj, row, refSize);
      case "java/util/ArrayList" -> analyzeArrayList(obj, row, refSize);
      case "java/util/concurrent/ConcurrentHashMap" -> analyzeConcurrentHashMap(obj, row, refSize);
      case "java/util/ArrayDeque" -> analyzeArrayDeque(obj, row, refSize);
      default -> false;
    };
  }

  private static boolean analyzeHashMap(HeapObject obj, Map<String, Object> row, int refSize) {
    Object tableObj = obj.getFieldValue("table");
    int capacity = 0;
    if (tableObj instanceof HeapObject tableArr && tableArr.isArray()) {
      capacity = tableArr.getArrayLength();
    }

    Object sizeObj = obj.getFieldValue("size");
    int size = sizeObj instanceof Number ? ((Number) sizeObj).intValue() : 0;

    populateWasteColumns(row, capacity, size, refSize);
    return true;
  }

  private static boolean analyzeHashSet(HeapObject obj, Map<String, Object> row, int refSize) {
    Object mapObj = obj.getFieldValue("map");
    if (!(mapObj instanceof HeapObject mapHeap)) {
      addNullWasteColumns(row);
      return true;
    }
    return analyzeHashMap(mapHeap, row, refSize);
  }

  private static boolean analyzeArrayList(HeapObject obj, Map<String, Object> row, int refSize) {
    Object elementDataObj = obj.getFieldValue("elementData");
    int capacity = 0;
    if (elementDataObj instanceof HeapObject elementArr && elementArr.isArray()) {
      capacity = elementArr.getArrayLength();
    }

    Object sizeObj = obj.getFieldValue("size");
    int size = sizeObj instanceof Number ? ((Number) sizeObj).intValue() : 0;

    populateWasteColumns(row, capacity, size, refSize);
    return true;
  }

  private static boolean analyzeConcurrentHashMap(
      HeapObject obj, Map<String, Object> row, int refSize) {
    Object tableObj = obj.getFieldValue("table");
    int capacity = 0;
    if (tableObj instanceof HeapObject tableArr && tableArr.isArray()) {
      capacity = tableArr.getArrayLength();
    }

    // ConcurrentHashMap.size() = baseCount + sum(counterCells[i].value)
    Object baseCountObj = obj.getFieldValue("baseCount");
    long size = baseCountObj instanceof Number ? ((Number) baseCountObj).longValue() : 0;
    Object cellsObj = obj.getFieldValue("counterCells");
    if (cellsObj instanceof HeapObject cellsArr && cellsArr.isArray()) {
      Object[] cells = cellsArr.getArrayElements();
      if (cells != null) {
        for (Object cell : cells) {
          if (cell instanceof HeapObject cellObj) {
            Object val = cellObj.getFieldValue("value");
            if (val instanceof Number n) {
              size += n.longValue();
            }
          }
        }
      }
    }

    populateWasteColumns(row, capacity, (int) Math.max(size, 0), refSize);
    return true;
  }

  private static boolean analyzeArrayDeque(HeapObject obj, Map<String, Object> row, int refSize) {
    Object elementsObj = obj.getFieldValue("elements");
    int capacity = 0;
    if (elementsObj instanceof HeapObject elemArr && elemArr.isArray()) {
      capacity = elemArr.getArrayLength();
    }

    Object headObj = obj.getFieldValue("head");
    Object tailObj = obj.getFieldValue("tail");
    int head = headObj instanceof Number ? ((Number) headObj).intValue() : 0;
    int tail = tailObj instanceof Number ? ((Number) tailObj).intValue() : 0;
    int size = capacity > 0 ? (tail - head + capacity) % capacity : 0;

    populateWasteColumns(row, capacity, size, refSize);
    return true;
  }

  private static void populateWasteColumns(
      Map<String, Object> row, int capacity, int size, int refSize) {
    row.put("capacity", capacity);
    row.put("size", size);
    row.put("loadFactor", capacity > 0 ? (double) size / capacity : 0.0);
    long wastedBytes = (long) (capacity - size) * refSize;
    row.put("wastedBytes", wastedBytes);
    String wasteType;
    if (capacity == 0 && size == 0) {
      wasteType = "emptyDefault";
    } else if (capacity > 0 && size == 0) {
      wasteType = "emptyDefault";
    } else if (wastedBytes > 0) {
      wasteType = "overCapacity";
    } else {
      wasteType = "normal";
    }
    row.put("wasteType", wasteType);
  }

  static void addNullWasteColumns(Map<String, Object> row) {
    row.put("capacity", null);
    row.put("size", null);
    row.put("loadFactor", null);
    row.put("wastedBytes", null);
    row.put("wasteType", null);
  }
}
