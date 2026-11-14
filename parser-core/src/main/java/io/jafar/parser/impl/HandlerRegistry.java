package io.jafar.parser.impl;

import java.util.Arrays;

/**
 * The handlers one parser dispatches events to: a copy-on-write array with a registration count per
 * handler.
 *
 * <p><b>Dispatch.</b> {@link #snapshot()} is one volatile read; the array it returns is never
 * modified, so a chunk thread iterates it with an indexed loop while other threads register and
 * deregister. A handler deregistered while an event is being dispatched may still receive that one
 * event, and no later one.
 *
 * <p><b>Counting.</b> Registering a handler that is already present (by {@code equals}) does not
 * add a second slot - the handler is still called once per event - but counts a second
 * registration; the handler stays until every registration has been removed. A registration is
 * identified by the generation {@link #add} returned, so one taken before {@link #clear()} cannot
 * remove a handler registered after it.
 *
 * <p>Shared by a parser and the copies made by {@code withParserListener}; all writes go through
 * the registry's own lock.
 *
 * @param <H> the handler type
 */
final class HandlerRegistry<H> {
  private final H[] empty;

  /** The handlers in registration order. Replaced, never modified in place. */
  private volatile H[] handlers;

  /** Registrations per handler, parallel to {@link #handlers}. Guarded by {@code this}. */
  private int[] counts = new int[0];

  /** Incremented by {@link #clear()}. Guarded by {@code this}. */
  private long generation;

  /**
   * @param empty a zero-length array of the handler type; it is the snapshot of an empty registry
   *     and the template the larger arrays are created from
   */
  HandlerRegistry(H[] empty) {
    if (empty.length != 0) {
      throw new IllegalArgumentException("empty must have length 0");
    }
    this.empty = empty;
    this.handlers = empty;
  }

  /**
   * The current handlers, in registration order. The returned array must not be modified.
   *
   * @return the handlers; zero-length when there are none
   */
  H[] snapshot() {
    return handlers;
  }

  /**
   * Counts one registration of {@code handler}, appending it if it is not present.
   *
   * @return the generation to pass to {@link #remove}
   */
  synchronized long add(H handler) {
    H[] current = handlers;
    int idx = indexOf(current, handler);
    if (idx >= 0) {
      counts[idx]++;
    } else {
      H[] updated = Arrays.copyOf(current, current.length + 1);
      updated[current.length] = handler;
      counts = Arrays.copyOf(counts, current.length + 1);
      counts[current.length] = 1;
      handlers = updated;
    }
    return generation;
  }

  /**
   * Removes one registration of {@code handler}; the handler is dropped when none is left. Does
   * nothing if the handler is absent or was registered before the last {@link #clear()}.
   *
   * @param handler the handler
   * @param registeredIn the value {@link #add} returned for the registration being removed
   */
  synchronized void remove(H handler, long registeredIn) {
    if (registeredIn != generation) {
      return;
    }
    H[] current = handlers;
    int idx = indexOf(current, handler);
    if (idx < 0) {
      return;
    }
    if (--counts[idx] > 0) {
      return;
    }
    int remaining = current.length - 1;
    if (remaining == 0) {
      handlers = empty;
      counts = new int[0];
      return;
    }
    H[] updated = Arrays.copyOf(empty, remaining);
    int[] updatedCounts = new int[remaining];
    System.arraycopy(current, 0, updated, 0, idx);
    System.arraycopy(current, idx + 1, updated, idx, remaining - idx);
    System.arraycopy(counts, 0, updatedCounts, 0, idx);
    System.arraycopy(counts, idx + 1, updatedCounts, idx, remaining - idx);
    handlers = updated;
    counts = updatedCounts;
  }

  /** Removes every handler and invalidates the registrations made so far. */
  synchronized void clear() {
    handlers = empty;
    counts = new int[0];
    generation++;
  }

  private static <H> int indexOf(H[] array, H handler) {
    for (int i = 0; i < array.length; i++) {
      if (array[i].equals(handler)) {
        return i;
      }
    }
    return -1;
  }
}
