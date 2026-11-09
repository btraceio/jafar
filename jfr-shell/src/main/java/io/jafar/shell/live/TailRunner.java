package io.jafar.shell.live;

import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;

/**
 * The streaming loop behind {@code tail} (issue #92), shared by the readline shell, the TUI and the
 * one-shot CLI. Polls the session's raw-event queue, decodes via {@link LiveEventDecoder}, matches
 * the JfrPath filter per event ({@link JfrPathEvaluator#matchesEvent} plus the per-event pipeline
 * subset via {@link JfrPathEvaluator#applyEventOps}), formats, and emits through the sink. Exits
 * on: end-marker/target close, thread interrupt (Ctrl-C), {@code --limit} reached.
 *
 * <p>Blocking by design — run it on the dispatch thread (readline: the shell's signal handler
 * covers Ctrl-C; TUI: the executor future cancel covers it; one-shot: the CLI's SIGINT handler).
 * Never pages: a stream cannot pause for {@code -- more --}.
 */
public final class TailRunner {

  /** Output sink abstraction — one implementation per surface. */
  public interface Sink {
    /** One formatted event line. */
    void event(String line);

    /** Periodic status (readline: printed; TUI: progress-message area). */
    void status(String line);

    /** Final summary; after this no more output happens. */
    void end(String line);
  }

  public enum Format {
    TABLE,
    JSON
  }

  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
  private static final long POLL_TIMEOUT_MS = 200;
  private static final long STATUS_CADENCE_MS = 1000;
  private static final int MAX_TABLE_FIELDS = 6;
  private static final int MAX_VALUE_LEN = 48;

  private TailRunner() {}

  /**
   * Installs a SIGINT handler that interrupts the tail thread, so Ctrl-C ends the stream instead of
   * killing the process (nothing else in the shell registers one — before this, Ctrl-C during a
   * command was default-JVM death). Uses JLine's {@code Signals} (native handler via the same
   * mechanism the terminal uses); in the TUI, raw mode keeps ISIG off and Ctrl-C arrives as a key
   * event that cancels the executor future — the interrupt below is then already set and the
   * handler is a harmless extra. Returns the registration handle for {@link #restoreSignal}.
   */
  private static Object installSignal(Thread tailThread) {
    try {
      return org.jline.utils.Signals.register("INT", () -> tailThread.interrupt());
    } catch (Throwable t) {
      // non-POSIX platform or no native signal support: stream still ends via session close
      return null;
    }
  }

  private static void restoreSignal(Object registration) {
    if (registration != null) {
      try {
        org.jline.utils.Signals.unregister("INT", registration);
      } catch (Throwable ignored) {
        // never let cleanup mask the tail result
      }
    }
  }

  /**
   * Streams matching events from {@code session} until interrupted, the target closes, or {@code
   * limit} events were emitted ({@code limit <= 0} = unbounded).
   *
   * @param filter parsed JfrPath query (root {@code events}); {@code null} = all events
   * @return exit reason for the summary line
   */
  public static String run(
      LiveJfrSession session,
      io.jafar.shell.jfrpath.JfrPath.Query filter,
      Format format,
      long limit,
      Sink sink) {
    JfrPathEvaluator evaluator = new JfrPathEvaluator();
    long emitted = 0;
    long filtered = 0;
    long lastStatus = System.currentTimeMillis();
    String reason = "stream ended";
    Thread self = Thread.currentThread();
    Object signalRegistration = installSignal(self);
    try {
      while (true) {
        if (Thread.interrupted()) {
          reason = "interrupted";
          break;
        }
        jdk.jfr.consumer.RecordedEvent raw = session.pollEvent(POLL_TIMEOUT_MS);
        if (raw == null) {
          String state = session.getStreamState();
          if (session.isClosed() || state.equals("closed") || state.startsWith("error")) {
            reason = state.startsWith("error") ? state : "target stream closed";
            break;
          }
          long now = System.currentTimeMillis();
          if (now - lastStatus >= STATUS_CADENCE_MS) {
            sink.status(
                "listening: "
                    + emitted
                    + " shown, "
                    + filtered
                    + " filtered, "
                    + session.getDroppedCount()
                    + " dropped"
                    + (session.getStreamState().startsWith("error")
                        ? " (" + session.getStreamState() + ")"
                        : ""));
            lastStatus = now;
          }
          continue;
        }
        Map<String, Object> map = LiveEventDecoder.decode(raw);
        map.put(JfrPathEvaluator.EVENT_TYPE_KEY, raw.getEventType().getName());
        if (filter != null) {
          if (!evaluator.matchesEvent(filter, map)) {
            filtered++;
            continue;
          }
          try {
            Map<String, Object> transformed = evaluator.applyEventOps(filter.pipeline, map);
            if (transformed == null) {
              filtered++;
              continue;
            }
            map = transformed;
          } catch (io.jafar.shell.jfrpath.UnsupportedPipelineOperationException e) {
            sink.status("error: " + e.getMessage());
            reason = "unsupported pipeline operator";
            break;
          }
        }
        sink.event(format(raw.getEventType().getName(), map, format));
        emitted++;
        if (limit > 0 && emitted >= limit) {
          reason = "limit reached";
          break;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reason = "interrupted";
    } finally {
      restoreSignal(signalRegistration);
    }
    String summary =
        "tail finished ("
            + reason
            + "): "
            + emitted
            + " events shown, "
            + filtered
            + " filtered, "
            + session.getDroppedCount()
            + " dropped by the live session";
    sink.end(summary);
    return summary;
  }

  static String format(String typeName, Map<String, Object> map, Format format) {
    if (format == Format.JSON) {
      return JsonLines.toJson(typeName, map);
    }
    StringBuilder sb = new StringBuilder(128);
    Object start = map.get("startTime");
    if (start instanceof Long nanos) {
      sb.append(TIME_FMT.format(Instant.ofEpochSecond(0, nanos)));
    }
    sb.append(' ').append(typeName);
    int fields = 0;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      String key = e.getKey();
      if (key.equals(JfrPathEvaluator.EVENT_TYPE_KEY)
          || key.equals("startTime")
          || key.equals("duration")) {
        continue;
      }
      if (fields >= MAX_TABLE_FIELDS) {
        sb.append(" …");
        break;
      }
      String value = String.valueOf(e.getValue());
      if (value.length() > MAX_VALUE_LEN) {
        value = value.substring(0, MAX_VALUE_LEN - 1) + "…";
      }
      sb.append(' ').append(key).append('=').append(value);
      fields++;
    }
    return sb.toString();
  }

  /** Line-oriented sink printing straight through (readline shell, one-shot CLI). */
  public static final class PrintSink implements Sink {
    private final io.jafar.shell.cli.CommandDispatcher.IO io;

    public PrintSink(io.jafar.shell.cli.CommandDispatcher.IO io) {
      this.io = io;
    }

    @Override
    public void event(String line) {
      io.println(line);
    }

    @Override
    public void status(String line) {
      io.println(line);
    }

    @Override
    public void end(String line) {
      io.println(line);
    }
  }

  /**
   * TUI sink: {@code io.println} appends to the TUI output buffer unboundedly during a run, so this
   * sink keeps a bounded ring of the last lines, surfaces periodic status through the
   * progress-message channel ({@code System.err} → the TUI's capturing stream), and flushes the
   * ring once at completion. Ctrl-C cancel discards the ring (existing TUI cancel semantics —
   * documented limitation; readline is the streaming surface).
   */
  public static final class TuiSink implements Sink {
    private final io.jafar.shell.cli.CommandDispatcher.IO io;
    private final int capacity;
    private final java.util.ArrayDeque<String> ring;
    private long lastStatus = 0;

    public TuiSink(io.jafar.shell.cli.CommandDispatcher.IO io, int capacity) {
      this.io = io;
      this.capacity = capacity;
      this.ring = new java.util.ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void event(String line) {
      while (ring.size() >= capacity) {
        ring.pollFirst();
      }
      ring.addLast(line);
    }

    @Override
    public synchronized void status(String line) {
      long now = System.currentTimeMillis();
      // The progress channel updates at most once per second; the ring flush happens at end.
      if (now - lastStatus >= STATUS_CADENCE_MS) {
        io.error(line); // TUI routes System.err into the progress-message area
        lastStatus = now;
      }
    }

    @Override
    public synchronized void end(String line) {
      for (String l : new ArrayList<>(ring)) {
        io.println(l);
      }
      io.println(line);
    }
  }
}
