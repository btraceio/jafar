package io.jafar.hdump.impl;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Naive, obviously-correct graph algorithms used as an independent oracle for the HPROF graph
 * operators. Everything is defined directly from the textbook definitions and is deliberately
 * unoptimised (dominators are O(n * (n + m))).
 *
 * <p>The graph has {@code n} nodes, an adjacency list {@code adj} (duplicates and self loops
 * allowed) and a set of root nodes. A virtual root with an edge to every root node is the entry.
 */
final class DominatorOracle {

  /** Marker for "idom is the virtual root" / "unreachable". */
  static final int NONE = -1;

  final int n;
  final int[][] adj;
  final int[] roots;
  final long[] shallow;

  /** Reachable from the virtual root. */
  final boolean[] reachable;

  /** strictDominators[v] = set of nodes d != v such that every root-to-v path contains d. */
  final BitSet[] strictDominators;

  /** idom[v]: immediate dominator node, or {@link #NONE} (virtual root or unreachable). */
  final int[] idom;

  /** Exact retained sizes (reachable nodes only; 0 for unreachable). */
  final long[] retained;

  DominatorOracle(int[][] adj, int[] roots, long[] shallow) {
    this.n = adj.length;
    this.adj = adj;
    this.roots = roots;
    this.shallow = shallow;

    this.reachable = reach(-1);
    this.strictDominators = new BitSet[n];
    for (int v = 0; v < n; v++) strictDominators[v] = new BitSet(n);

    // d strictly dominates v iff v is reachable but becomes unreachable when d is removed.
    for (int d = 0; d < n; d++) {
      if (!reachable[d]) continue;
      boolean[] without = reach(d);
      for (int v = 0; v < n; v++) {
        if (v != d && reachable[v] && !without[v]) strictDominators[v].set(d);
      }
    }

    this.idom = new int[n];
    for (int v = 0; v < n; v++) {
      idom[v] = NONE;
      if (!reachable[v]) continue;
      BitSet sd = strictDominators[v];
      if (sd.isEmpty()) continue; // only the virtual root dominates v
      // Strict dominators of v form a chain; the idom is the one dominated by all the others,
      // i.e. the one with the most strict dominators itself. Verify the definition explicitly.
      int best = -1;
      int bestCount = -1;
      for (int d = sd.nextSetBit(0); d >= 0; d = sd.nextSetBit(d + 1)) {
        int c = strictDominators[d].cardinality();
        if (c > bestCount) {
          bestCount = c;
          best = d;
        }
      }
      for (int d = sd.nextSetBit(0); d >= 0; d = sd.nextSetBit(d + 1)) {
        if (d != best && !strictDominators[best].get(d)) {
          throw new IllegalStateException(
              "oracle bug: strict dominators of " + v + " do not form a chain at " + d);
        }
      }
      idom[v] = best;
    }

    this.retained = new long[n];
    for (int v = 0; v < n; v++) {
      if (!reachable[v]) continue;
      retained[v] += shallow[v];
      BitSet sd = strictDominators[v];
      for (int d = sd.nextSetBit(0); d >= 0; d = sd.nextSetBit(d + 1)) retained[d] += shallow[v];
    }
  }

  /** Iterative reachability from the virtual root, with node {@code removed} deleted (-1: none). */
  private boolean[] reach(int removed) {
    boolean[] seen = new boolean[n];
    ArrayDeque<Integer> q = new ArrayDeque<>();
    for (int r : roots) {
      if (r != removed && !seen[r]) {
        seen[r] = true;
        q.add(r);
      }
    }
    while (!q.isEmpty()) {
      int u = q.poll();
      for (int w : adj[u]) {
        if (w != removed && !seen[w]) {
          seen[w] = true;
          q.add(w);
        }
      }
    }
    return seen;
  }

  /** Immediate dominator-tree children of {@code d} (sorted). */
  int[] children(int d) {
    int k = 0;
    int[] tmp = new int[n];
    for (int v = 0; v < n; v++) if (idom[v] == d) tmp[k++] = v;
    return Arrays.copyOf(tmp, k);
  }

  /**
   * Approximate ("minimum") retained size per the production rule: BFS from {@code t} over strong
   * edges; every visited node contributes its shallow size; a visited node whose global inbound
   * edge count (over all nodes, reachable or not, duplicates counted) is greater than 1 is not
   * expanded -- this includes {@code t} itself.
   */
  static long[] approximateRetained(int[][] adj, long[] shallow) {
    int n = adj.length;
    int[] inbound = new int[n];
    for (int[] out : adj) for (int w : out) inbound[w]++;
    long[] result = new long[n];
    int[] stamp = new int[n];
    Arrays.fill(stamp, -1);
    int[] queue = new int[n];
    for (int t = 0; t < n; t++) {
      int head = 0, tail = 0;
      queue[tail++] = t;
      stamp[t] = t;
      long total = 0;
      while (head < tail) {
        int u = queue[head++];
        total += shallow[u];
        if (inbound[u] > 1) continue;
        for (int w : adj[u]) {
          if (stamp[w] != t) {
            stamp[w] = t;
            queue[tail++] = w;
          }
        }
      }
      result[t] = total;
    }
    return result;
  }

  /**
   * BFS distance (edges) from the nearest seed (roots and static-referenced nodes are at distance
   * 0); -1 if unreachable.
   */
  static int[] bfsDistances(int[][] adj, int[] seeds) {
    int n = adj.length;
    int[] dist = new int[n];
    Arrays.fill(dist, -1);
    int[] queue = new int[n];
    int head = 0, tail = 0;
    for (int s : seeds) {
      if (dist[s] < 0) {
        dist[s] = 0;
        queue[tail++] = s;
      }
    }
    while (head < tail) {
      int u = queue[head++];
      for (int w : adj[u]) {
        if (dist[w] < 0) {
          dist[w] = dist[u] + 1;
          queue[tail++] = w;
        }
      }
    }
    return dist;
  }
}
