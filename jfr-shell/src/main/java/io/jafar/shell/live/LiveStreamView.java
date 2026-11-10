package io.jafar.shell.live;

import jdk.jfr.consumer.RecordedEvent;

/**
 * The streaming capability view of a live session — the seam type the dispatcher and {@link
 * TailRunner} name instead of the concrete {@link LiveJfrSession} (a future JMX-backed stream plugs
 * in without touching the dispatcher). {@link LiveStreams#attach} returns this view.
 */
public interface LiveStreamView extends io.jafar.shell.core.Session {

  /**
   * Polls the next raw event, waiting up to {@code timeoutMillis}.
   *
   * @return the event, or {@code null} when the stream ended or the poll timed out (callers
   *     distinguish via {@link #isClosed()} / {@link #getStreamState()})
   * @throws InterruptedException when the polling thread is interrupted (the tail-cancel path)
   */
  RecordedEvent pollEvent(long timeoutMillis) throws InterruptedException;

  /** Stream state string ({@code starting}/{@code running}/{@code closed}/{@code error: ...}). */
  String getStreamState();

  /** Count of backlog events evicted by the drop-oldest policy. */
  long getDroppedCount();
}
