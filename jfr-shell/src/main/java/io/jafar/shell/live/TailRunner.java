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
   * Shared tail-filter validator — the single source for the interactive {@code tail} command, the
   * one-shot {@code jfr-shell tail} CLI, and the tests: parse errors and non-{@code events} roots
   * throw {@link IllegalArgumentException} with the canonical message.
   */
  public static io.jafar.shell.jfrpath.JfrPath.Query parseFilter(String expr) {
    io.jafar.shell.jfrpath.JfrPath.Query filter;
    try {
      filter = io.jafar.shell.jfrpath.JfrPathParser.parse(expr);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid tail filter: " + e.getMessage(), e);
    }
    if (filter.root != io.jafar.shell.jfrpath.JfrPath.Root.EVENTS) {
      throw new IllegalArgumentException(
          "tail filters must use the events root (e.g. 'events/jdk.ExecutionSample')");
    }
    return filter;
  }

  /** Shared tail-format validator: {@code table}/{@code json} only, canonical lowercase message. */
  public static Format parseFormat(String format) {
    return switch (format) {
      case "table" -> Format.TABLE;
      case "json" -> Format.JSON;
      default -> throw new IllegalArgumentException("unknown format: " + format + " (table|json)");
    };
  }

  /**
   * Installs a SIGINT handler that interrupts the tail thread, so Ctrl-C ends the stream instead of
   * killing the process (nothing else in the shell registers one — before this, Ctrl-C during a
   * command was default-JVM death). Uses JLine's {@code Signals} (native handler via the same
   * mechanism the terminal uses); in the TUI, raw mode keeps ISIG off and Ctrl-C arrives as a key
   * event that cancels the executor future — the interrupt below is then already set and the
   * handler is a harmless extra. JLine's {@code Signals.register} returns the previously installed
   * handler as the registration object, and {@code Signals.unregister} re-installs it —
   * capture-and-restore of the previous INT handler is preserved (jline 3.21.0 reflective wrapper
   * over {@code sun.misc.Signal.handle}). Returns the registration handle for {@link
   * #restoreSignal}.
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
      LiveStreamView session,
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
        String typeName = raw.getEventType().getName();
        // Pre-decode type membership: events whose type can never match are filtered without
        // paying for the full recursive decode.
        if (filter != null && !JfrPathEvaluator.matchesType(filter, typeName)) {
          filtered++;
          continue;
        }
        Map<String, Object> map = LiveEventDecoder.decode(raw);
        // The decorated start time is captured BEFORE the pipeline runs: a transform may drop
        // or rewrite the startTime key, but the table timestamp must stay the event's own.
        long startNanos =
            map.get(LiveEventDecoder.START_TIME_KEY) instanceof Long l
                ? l
                : raw.getStartTime().getEpochSecond() * 1_000_000_000L
                    + raw.getStartTime().getNano();
        if (filter != null) {
          if (!evaluator.matchesEvent(filter, typeName, map)) {
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
        sink.event(format(typeName, startNanos, map, format));
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

  static String format(String typeName, long startNanos, Map<String, Object> map, Format format) {
    if (format == Format.JSON) {
      return JsonLines.toJson(typeName, map);
    }
    StringBuilder sb = new StringBuilder(128);
    sb.append(TIME_FMT.format(Instant.ofEpochSecond(0, startNanos)));
    sb.append(' ').append(typeName);
    int fields = 0;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      String key = e.getKey();
      if (LiveEventDecoder.NON_DATA_KEYS.contains(key)) {
        continue;
      }
      if (fields >= MAX_TABLE_FIELDS) {
        sb.append(" …");
        break;
      }
      sb.append(' ').append(key).append('=').append(summarizeValue(e.getValue()));
      fields++;
    }
    return sb.toString();
  }

  /**
   * Bounded table rendering: non-scalar values become a type+size placeholder instead of a full
   * {@code toString} (never materialized before the 48-char truncation); long scalars still
   * truncate at {@link #MAX_VALUE_LEN} with an ellipsis.
   */
  private static String summarizeValue(Object v) {
    if (v instanceof Map<?, ?> m) {
      return "map(size=" + m.size() + ")";
    }
    if (v instanceof java.util.Collection<?> c) {
      return "list(size=" + c.size() + ")";
    }
    if (v instanceof Iterable<?> it) {
      int n = 0;
      for (Object ignored : it) {
        if (++n >= 1000) {
          return "list(size>=1000+)";
        }
      }
      return "list(size=" + n + ")";
    }
    String s = String.valueOf(v);
    return s.length() > MAX_VALUE_LEN ? s.substring(0, MAX_VALUE_LEN - 1) + "…" : s;
  }

  /**
   * Line-oriented sink printing straight through (readline shell, one-shot CLI). Event lines and
   * the final summary go to stdout in BOTH formats (the summary is the run's receipt); the periodic
   * {@code listening: ...} status line goes to stdout in TABLE mode and is SUPPRESSED in JSON mode
   * — stdout must stay pure JSON-lines.
   */
  public static final class PrintSink implements Sink {
    private final io.jafar.shell.cli.CommandDispatcher.IO io;
    private final Format format;

    public PrintSink(io.jafar.shell.cli.CommandDispatcher.IO io, Format format) {
      this.io = io;
      this.format = format;
    }

    @Override
    public void event(String line) {
      io.println(line);
    }

    @Override
    public void status(String line) {
      if (format == Format.JSON) {
        return; // keep stdout pure JSON-lines
      }
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
