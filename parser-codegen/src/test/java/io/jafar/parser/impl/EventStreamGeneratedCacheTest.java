package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.UntypedEventDeserializer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The cross-chunk deserializer cache must not generate while holding a map lock: generation takes
 * 0.3–0.8 ms per class and every chunk generates at its start, concurrently. {@code
 * ConcurrentHashMap.computeIfAbsent} holds the bin lock for the whole mapping function.
 */
public class EventStreamGeneratedCacheTest {
  private static final UntypedEventDeserializer A = (s, c) -> Collections.emptyMap();
  private static final UntypedEventDeserializer B = (s, c) -> Collections.emptyMap();

  private final ExecutorService pool = Executors.newFixedThreadPool(8);

  @AfterEach
  void shutdown() {
    pool.shutdownNow();
  }

  @Test
  void generationDoesNotBlockAnotherKeyInTheSameBin() throws Exception {
    // Equal hash codes, so both keys always share a bin.
    assertEquals("Aa".hashCode(), "BB".hashCode());
    UntypedDeserializerCache cache = new UntypedDeserializerCache.Impl();
    CountDownLatch generatingA = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);

    Future<UntypedEventDeserializer> slow =
        pool.submit(
            () ->
                EventStreamGenerated.shared(
                    cache,
                    "Aa",
                    () -> {
                      generatingA.countDown();
                      await(releaseA);
                      return A;
                    }));
    assertTrue(generatingA.await(10, TimeUnit.SECONDS), "generation of Aa never started");

    Future<UntypedEventDeserializer> other =
        pool.submit(() -> EventStreamGenerated.shared(cache, "BB", () -> B));
    try {
      assertSame(B, other.get(5, TimeUnit.SECONDS), "BB waited for Aa's generation");
    } finally {
      releaseA.countDown();
    }
    assertSame(A, slow.get(10, TimeUnit.SECONDS));
  }

  @Test
  void concurrentMissesOnOneKeyGenerateOnce() throws Exception {
    UntypedDeserializerCache cache = new UntypedDeserializerCache.Impl();
    AtomicInteger generations = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    @SuppressWarnings("unchecked")
    Future<UntypedEventDeserializer>[] results = new Future[8];
    for (int i = 0; i < results.length; i++) {
      results[i] =
          pool.submit(
              () -> {
                await(start);
                return EventStreamGenerated.shared(
                    cache,
                    "key",
                    () -> {
                      generations.incrementAndGet();
                      sleep(50);
                      return A;
                    });
              });
    }
    start.countDown();
    for (Future<UntypedEventDeserializer> r : results) {
      assertSame(A, r.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, generations.get());
  }

  @Test
  void failedGenerationIsRetriedByTheNextCaller() {
    UntypedDeserializerCache cache = new UntypedDeserializerCache.Impl();
    assertThrows(
        IllegalStateException.class,
        () ->
            EventStreamGenerated.shared(
                cache,
                "key",
                () -> {
                  throw new IllegalStateException("first attempt fails");
                }));
    assertTrue(cache.isEmpty(), "a failed placeholder must not stay cached");
    assertSame(A, EventStreamGenerated.shared(cache, "key", () -> A));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
