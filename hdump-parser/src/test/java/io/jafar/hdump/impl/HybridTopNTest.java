package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link HybridDominatorComputer.TopN} must keep exactly what the plain "offer a new pair, poll
 * when over capacity" loop keeps — including which of several equal retained sizes survive, which
 * depends on the heap's array layout — and in the same iteration order.
 */
class HybridTopNTest {

  @Test
  void sameSelectionAsOfferThenPollWithManyTies() {
    Random rnd = new Random(42);
    for (int trial = 0; trial < 20_000; trial++) {
      int topN = rnd.nextInt(9); // includes 0
      int values = 1 + rnd.nextInt(6); // small value range: many ties
      int count = rnd.nextInt(60);
      PriorityQueue<long[]> reference =
          new PriorityQueue<>(topN + 1, Comparator.comparingLong(a -> a[0]));
      HybridDominatorComputer.TopN topNQueue = new HybridDominatorComputer.TopN(topN);
      for (int i = 0; i < count; i++) {
        long retained = 1 + rnd.nextInt(values);
        long id = 1000 + i;
        reference.offer(new long[] {retained, id});
        if (reference.size() > topN) {
          reference.poll();
        }
        topNQueue.offer(retained, id);
      }
      assertEquals(ids(reference), ids(topNQueue.entries()), "trial " + trial);
    }
  }

  private static List<Long> ids(Iterable<long[]> entries) {
    List<Long> out = new ArrayList<>();
    for (long[] e : entries) out.add(e[1]);
    return out;
  }
}
