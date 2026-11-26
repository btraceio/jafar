package io.jafar.hdump.impl;

import java.util.Arrays;

/**
 * Computes {@link ApproximateRetainedSizeComputer#computeMinRetainedSize "minimum retained size"}
 * for every object of a {@link HeapGraph} at once, in O((N + E) log E) instead of one BFS per
 * object.
 *
 * <p>The per-object definition: BFS from {@code T} over strong references; every visited object
 * contributes its shallow size once; an object with more than one inbound reference (counted over
 * all objects, duplicates included) is not expanded — {@code T} included.
 *
 * <p>Why a per-object BFS is not needed: an expanded object other than {@code T} has exactly one
 * inbound reference, so it is reached from exactly one place. The objects with at most one inbound
 * reference therefore form a forest under "my only referrer" (when that referrer is itself
 * expandable), and the expanded set of {@code T} is exactly {@code T}'s subtree. What BFS adds on
 * top is the <em>frontier</em>: shared objects (inbound &gt; 1) referenced from the subtree, each
 * counted once however often it is referenced. Counting distinct frontier objects per subtree is
 * the classic "distinct colours in every subtree" problem: for each shared object, sort its
 * referrers in the forest by preorder, add its size at every referrer and subtract it at the lowest
 * common ancestor of each consecutive pair; a subtree sum then counts it exactly once if the
 * subtree contains any referrer. LCAs are answered offline (Tarjan, union-find).
 *
 * <p>The quadratic case the per-object BFS had — a long singly linked chain, where every node
 * expands the whole tail — becomes linear. Objects whose only-referrer links form a cycle (only
 * possible in unreachable garbage: nobody outside the cycle references it) fall back to the
 * per-object BFS, run once per cycle (every member of a cycle expands the whole cycle, so they
 * share one value).
 *
 * <p><strong>Memory</strong> (transient, N objects, E strong references, F references from
 * once-referenced objects to shared ones): the graph (4N object array + 4N + 4E CSR), about 13
 * {@code int[N]}/{@code byte[N]} working arrays, two {@code long[N]} (result and per-subtree sums),
 * the sorted frontier pairs (8F, released before the LCA pass) and the LCA queries (about 20 bytes
 * per query, at most F). Roughly 75N + 4E + 28F bytes at the peak.
 */
final class MinRetainedSizes {

  private static final byte UNKNOWN = 0;
  private static final byte ON_PATH = 1;
  private static final byte FOREST = 2;
  private static final byte CYCLIC = 3;

  private MinRetainedSizes() {}

  /** Returns the minimum retained size of every node of {@code g}, indexed by node. */
  static long[] compute(HeapGraph g) {
    final int n = g.size();
    final int[] ss = g.succStart;
    final int[] su = g.succ;
    final int m = ss[n];
    long[] result = new long[n];
    if (n == 0) {
      return result;
    }
    int[] shallow = new int[n];
    for (int v = 0; v < n; v++) {
      shallow[v] = g.nodes[v].getShallowSize();
    }

    int[] inbound = new int[n];
    for (int e = 0; e < m; e++) {
      inbound[su[e]]++;
    }

    // Forest parent: the only referrer of a once-referenced object, when that referrer is itself
    // expandable (inbound <= 1). Objects with inbound <= 1 are the forest's nodes.
    int[] fparent = new int[n];
    Arrays.fill(fparent, -1);
    for (int v = 0; v < n; v++) {
      if (inbound[v] > 1) {
        continue;
      }
      for (int e = ss[v], end = ss[v + 1]; e < end; e++) {
        int c = su[e];
        if (inbound[c] == 1) {
          fparent[c] = v;
        }
      }
    }

    // Classify forest nodes: CYCLIC for the members of an only-referrer cycle, FOREST for the
    // rest. A node whose only referrer is a cycle member becomes a forest root: a cycle member's
    // single inbound reference comes from its cycle predecessor, so what hangs below the cycle is
    // a plain tree, and its expanded sets are its subtrees like anywhere else.
    byte[] state = new byte[n];
    int[] stack = new int[n];
    for (int v = 0; v < n; v++) {
      if (inbound[v] > 1 || state[v] != UNKNOWN) {
        continue;
      }
      int sp = 0;
      int x = v;
      while (x != -1 && state[x] == UNKNOWN) {
        state[x] = ON_PATH;
        stack[sp++] = x;
        x = fparent[x];
      }
      int tail = sp; // stack[0 .. tail) are not on a cycle
      if (x != -1 && state[x] == ON_PATH) {
        // a new cycle: stack[j .. sp) where stack[j] == x
        int j = sp - 1;
        while (stack[j] != x) {
          j--;
        }
        for (int i = j; i < sp; i++) {
          state[stack[i]] = CYCLIC;
        }
        tail = j;
      }
      if (tail > 0 && fparent[stack[tail - 1]] != -1 && state[fparent[stack[tail - 1]]] == CYCLIC) {
        fparent[stack[tail - 1]] = -1; // hangs off a cycle: a forest root
      }
      for (int i = 0; i < tail; i++) {
        state[stack[i]] = FOREST;
      }
    }

    // Children of the forest (CSR) and preorder numbering.
    int[] childStart = new int[n + 1];
    for (int c = 0; c < n; c++) {
      if (state[c] == FOREST && fparent[c] != -1) {
        childStart[fparent[c] + 1]++;
      }
    }
    for (int v = 0; v < n; v++) {
      childStart[v + 1] += childStart[v];
    }
    int[] children = new int[childStart[n]];
    int[] fill = new int[n];
    for (int c = 0; c < n; c++) {
      if (state[c] == FOREST && fparent[c] != -1) {
        int p = fparent[c];
        children[childStart[p] + fill[p]++] = c;
      }
    }
    fill = null;

    int[] tin = new int[n]; // node -> preorder index; meaningful for FOREST nodes only
    int[] order = new int[n]; // preorder index -> node
    int[] treeOf = new int[n]; // node -> preorder index of its tree's root
    int count = 0;
    for (int r = 0; r < n; r++) {
      if (state[r] != FOREST || fparent[r] != -1) {
        continue;
      }
      int sp = 0;
      stack[sp++] = r;
      int rootTin = count;
      while (sp > 0) {
        int u = stack[--sp];
        tin[u] = count;
        order[count++] = u;
        treeOf[u] = rootTin;
        for (int i = childStart[u + 1] - 1; i >= childStart[u]; i--) {
          stack[sp++] = children[i];
        }
      }
    }
    final int forestSize = count;

    // Frontier references: (shared object, preorder of a forest referrer), sorted.
    int pairs = 0;
    for (int k = 0; k < forestSize; k++) {
      int p = order[k];
      for (int e = ss[p], end = ss[p + 1]; e < end; e++) {
        if (inbound[su[e]] > 1) {
          pairs++;
        }
      }
    }
    long[] frontier = new long[pairs];
    int fp = 0;
    for (int k = 0; k < forestSize; k++) {
      int p = order[k];
      for (int e = ss[p], end = ss[p + 1]; e < end; e++) {
        int x = su[e];
        if (inbound[x] > 1) {
          frontier[fp++] = ((long) x << 32) | k;
        }
      }
    }
    Arrays.sort(frontier);

    // delta[k]: +size at each referrer, -size at the LCA of consecutive referrers (same tree).
    long[] delta = new long[forestSize];
    int queries = 0;
    for (int i = 0; i < pairs; i++) {
      if (i > 0 && sameTreeNext(frontier, i, treeOf, order)) {
        queries++;
      }
    }
    int[] qa = new int[queries];
    int[] qb = new int[queries];
    int[] qw = new int[queries];
    int q = 0;
    for (int i = 0; i < pairs; i++) {
      long cur = frontier[i];
      if (i > 0 && cur == frontier[i - 1]) {
        continue; // the same referrer twice (duplicate reference)
      }
      int x = (int) (cur >>> 32);
      int k = (int) cur;
      delta[k] += shallow[x];
      if (i > 0 && sameTreeNext(frontier, i, treeOf, order)) {
        qa[q] = (int) frontier[i - 1];
        qb[q] = k;
        qw[q] = shallow[x];
        q++;
      }
    }
    queries = q;
    frontier = null;
    subtractAtLcas(
        forestSize, order, fparent, tin, childStart, children, qa, qb, qw, queries, delta, stack);

    // Subtree sums over preorder (children come after their parent), in place in delta.
    long[] acc = delta;
    for (int k = forestSize - 1; k >= 0; k--) {
      int u = order[k];
      acc[k] += shallow[u];
      int p = fparent[u];
      if (p != -1) {
        acc[tin[p]] += acc[k];
      }
    }

    int[] mark = null;
    int[] queue = null;
    int stamp = 0;
    for (int v = 0; v < n; v++) {
      if (inbound[v] > 1) {
        result[v] = shallow[v];
      } else if (state[v] == FOREST) {
        result[v] = acc[tin[v]];
      } else if (result[v] == 0) {
        // Cycle member: every member expands the whole cycle, so all members share one value.
        if (mark == null) {
          mark = new int[n];
          queue = new int[n];
        }
        long size = bfs(v, ss, su, inbound, shallow, mark, queue, ++stamp);
        int x = v;
        do {
          result[x] = size;
          x = fparent[x];
        } while (x != v);
      }
    }
    return result;
  }

  /**
   * True when {@code frontier[i]} is a different referrer of the same shared object as {@code
   * frontier[i - 1]} and both referrers lie in the same tree.
   */
  private static boolean sameTreeNext(long[] frontier, int i, int[] treeOf, int[] order) {
    long prev = frontier[i - 1];
    long cur = frontier[i];
    if ((prev >>> 32) != (cur >>> 32) || prev == cur) {
      return false;
    }
    return treeOf[order[(int) prev]] == treeOf[order[(int) cur]];
  }

  /**
   * Offline Tarjan LCA over the forest; for every query (preorder indexes {@code qa[i]}, {@code
   * qb[i]}) subtracts {@code qw[i]} from {@code delta} at their lowest common ancestor.
   */
  private static void subtractAtLcas(
      int forestSize,
      int[] order,
      int[] fparent,
      int[] tin,
      int[] childStart,
      int[] children,
      int[] qa,
      int[] qb,
      int[] qw,
      int queries,
      long[] delta,
      int[] stackNode) {
    if (queries == 0) {
      return;
    }
    if (queries > (Integer.MAX_VALUE - 8) / 2) {
      throw new IllegalStateException("Too many shared-object references: " + queries);
    }
    // Queries per endpoint (preorder index), CSR.
    int[] qStart = new int[forestSize + 1];
    for (int i = 0; i < queries; i++) {
      qStart[qa[i] + 1]++;
      qStart[qb[i] + 1]++;
    }
    for (int k = 0; k < forestSize; k++) {
      qStart[k + 1] += qStart[k];
    }
    int[] qList = new int[qStart[forestSize]];
    int[] fill = new int[forestSize];
    for (int i = 0; i < queries; i++) {
      qList[qStart[qa[i]] + fill[qa[i]]++] = i;
      qList[qStart[qb[i]] + fill[qb[i]]++] = i;
    }
    fill = null;

    // Union-find over preorder indexes; anc[set representative] = current ancestor.
    int[] uf = new int[forestSize];
    int[] anc = new int[forestSize];
    boolean[] done = new boolean[forestSize];
    int[] stackChild = new int[forestSize];
    for (int r = 0; r < forestSize; r++) {
      int root = order[r];
      if (fparent[root] != -1) {
        continue;
      }
      int sp = 0;
      stackNode[sp] = r;
      stackChild[sp] = childStart[root];
      uf[r] = r;
      anc[r] = r;
      sp++;
      while (sp > 0) {
        int top = sp - 1;
        int k = stackNode[top];
        int u = order[k];
        int ci = stackChild[top];
        if (ci < childStart[u + 1]) {
          stackChild[top] = ci + 1;
          int c = tin[children[ci]];
          uf[c] = c;
          anc[c] = c;
          stackNode[sp] = c;
          stackChild[sp] = childStart[children[ci]];
          sp++;
          continue;
        }
        // u finished
        done[k] = true;
        for (int j = qStart[k]; j < qStart[k + 1]; j++) {
          int qi = qList[j];
          int other = qa[qi] == k ? qb[qi] : qa[qi];
          if (done[other] && other != k) {
            delta[anc[find(uf, other)]] -= qw[qi];
          }
        }
        sp--;
        if (sp > 0) {
          int pk = stackNode[sp - 1];
          uf[find(uf, k)] = find(uf, pk);
          anc[find(uf, pk)] = pk;
        }
      }
    }
  }

  private static int find(int[] uf, int x) {
    int r = x;
    while (uf[r] != r) {
      r = uf[r];
    }
    while (uf[x] != r) {
      int next = uf[x];
      uf[x] = r;
      x = next;
    }
    return r;
  }

  /** The per-object definition, for objects whose only-referrer chain is cyclic. */
  private static long bfs(
      int t, int[] ss, int[] su, int[] inbound, int[] shallow, int[] mark, int[] queue, int stamp) {
    int head = 0;
    int tail = 0;
    queue[tail++] = t;
    mark[t] = stamp;
    long total = 0;
    while (head < tail) {
      int u = queue[head++];
      total += shallow[u];
      if (inbound[u] > 1) {
        continue;
      }
      for (int e = ss[u], end = ss[u + 1]; e < end; e++) {
        int c = su[e];
        if (mark[c] != stamp) {
          mark[c] = stamp;
          queue[tail++] = c;
        }
      }
    }
    return total;
  }
}
