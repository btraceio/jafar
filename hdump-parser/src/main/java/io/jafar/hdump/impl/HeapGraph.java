package io.jafar.hdump.impl;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

/**
 * The strong-reference graph of a set of heap objects, in dense int form.
 *
 * <p>Nodes are numbered {@code 0 .. objects - 1} in the iteration order of the source map. Edges
 * are the {@link HeapObjectImpl#getStrongOutboundReferenceIds() strong outbound references} that
 * name an object of the same map, in compressed-sparse-row form: the successors of node {@code v}
 * are {@code succ[succStart[v] .. succStart[v + 1])}, in reference order, duplicates and self loops
 * kept. The id-to-node index is kept only when asked for.
 */
final class HeapGraph {

  final HeapObjectImpl[] nodes; // node -> object
  final int[] succStart; // length nodes + 1
  final int[] succ;
  final Long2IntOpenHashMap indexOf; // object id -> node (-1 if absent); null unless kept
  // Instances whose class shadows a field name (HeapClassImpl.hasDuplicateInstanceFieldNames);
  // null unless the index is kept.
  final java.util.BitSet shadowedFieldNodes;

  private HeapGraph(
      HeapObjectImpl[] nodes,
      int[] succStart,
      int[] succ,
      Long2IntOpenHashMap indexOf,
      java.util.BitSet shadowedFieldNodes) {
    this.nodes = nodes;
    this.succStart = succStart;
    this.succ = succ;
    this.indexOf = indexOf;
    this.shadowedFieldNodes = shadowedFieldNodes;
  }

  /** Node of {@code id}, or -1. Only on a graph built with {@code keepIndex}. */
  int nodeOf(long id) {
    return indexOf.get(id);
  }

  int size() {
    return nodes.length;
  }

  static HeapGraph build(Long2ObjectMap<HeapObjectImpl> objectsById) {
    return build(objectsById, false);
  }

  /** Builds the graph; with {@code keepIndex} the id-to-node index is kept for {@link #nodeOf}. */
  static HeapGraph build(Long2ObjectMap<HeapObjectImpl> objectsById, boolean keepIndex) {
    final int n = objectsById.size();
    HeapObjectImpl[] nodes = new HeapObjectImpl[n];
    Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap(n);
    indexOf.defaultReturnValue(-1);
    int i = 0;
    for (HeapObjectImpl obj : objectsById.values()) {
      nodes[i] = obj;
      indexOf.put(obj.getId(), i);
      i++;
    }
    long edges = 0;
    for (int v = 0; v < n; v++) {
      edges += nodes[v].getStrongOutboundReferenceIds().length;
    }
    if (edges > Integer.MAX_VALUE - 8) {
      throw new IllegalStateException("Too many references for graph analysis: " + edges);
    }
    int[] succStart = new int[n + 1];
    int[] succ = new int[(int) edges];
    int pos = 0;
    for (int v = 0; v < n; v++) {
      for (long ref : nodes[v].getStrongOutboundReferenceIds()) {
        int t = indexOf.get(ref);
        if (t >= 0) {
          succ[pos++] = t;
        }
      }
      succStart[v + 1] = pos;
    }
    java.util.BitSet shadowed = null;
    if (keepIndex) {
      shadowed = new java.util.BitSet();
      for (int v = 0; v < n; v++) {
        HeapObjectImpl obj = nodes[v];
        if (!obj.isArray()
            && obj.getHeapClass() instanceof HeapClassImpl c
            && c.hasDuplicateInstanceFieldNames()) {
          shadowed.set(v);
        }
      }
    }
    return new HeapGraph(nodes, succStart, succ, keepIndex ? indexOf : null, shadowed);
  }
}
