package io.jafar.parser.fuzz;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Crash oracle for the untyped-parser fuzz target (AC-4.5, kb/design-format-correctness).
 *
 * <p>Contract (D4): after parsing a fuzz input, only {@code io.jafar.*} exceptions and {@link
 * IOException} may escape. Concretely, a parse FAILS the oracle when
 *
 * <ol>
 *   <li>any throwable of the whole cause chain is an {@link Error} (StackOverflowError,
 *       OutOfMemoryError, ...), no matter how deep the parser wrapped it; or
 *   <li>the root cause of the chain is neither an {@code io.jafar.*} class nor an {@code
 *       IOException}.
 * </ol>
 *
 * <p>Every failure message names the input's sha256 and the full cause chain, so a finding can be
 * reproduced from the corpus file and pinned in {@code known-escapes.txt} (I1: shrink-only; a
 * listed input passes only when it escapes with exactly the listed delivered throwable, and a
 * listed input that no longer escapes fails as stale).
 *
 * <p>Hang oracle: the parse runs on a dedicated daemon thread and must produce a verdict within
 * {@code jafar.fuzz.inputTimeoutMs} (default 10000 ms). A timeout fails as a hang; hangs have no
 * throwable and can therefore never be listed in {@code known-escapes.txt}.
 */
final class EscapeOracle {

  /** The parse under test. May throw anything; the oracle decides what may legally escape. */
  @FunctionalInterface
  interface CheckedAction {
    void run() throws Exception;
  }

  private static final Pattern ENTRY = Pattern.compile("^[0-9a-f]{64} [A-Za-z0-9_.$]+$");
  private static final String ESCAPES_RESOURCE = "known-escapes.txt";
  private static final int MAX_CHAIN_DEPTH = 64;

  private EscapeOracle() {}

  /** Production entry point: the committed allowlist and the configured timeout. */
  static void check(byte[] data, CheckedAction parseAction) {
    check(data, parseAction, loadKnownEscapes(), timeoutMs());
  }

  /**
   * Test entry point: the allowlist and the timeout are injected so the fire tests (AC-4.6) never
   * depend on, or mutate, the committed file or global system properties.
   */
  static void check(
      byte[] data, CheckedAction parseAction, Map<String, String> knownEscapes, long timeoutMs) {
    String sha = sha256Hex(data);
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              // Daemon on purpose: a parse that swallows the hang-cancel interrupt must not keep
              // the test JVM alive.
              Thread thread = new Thread(runnable, "fuzz-oracle-" + sha.substring(0, 8));
              thread.setDaemon(true);
              return thread;
            });
    try {
      // Adversarial metadata nesting makes the parser log an exception per recursion level —
      // a single deep-metadata seed can push hundreds of megabytes of stack traces to stderr,
      // which Gradle captures into the JUnit XML (observed: 506 MB for one green replay run).
      // The oracle's verdict carries the exception chain; parse logs are noise, so the parse
      // runs with stderr silenced and the original stream restored afterwards.
      PrintStream originalErr = System.err;
      Future<?> verdict =
          executor.submit(
              () -> {
                System.setErr(
                    new PrintStream(
                        new OutputStream() {
                          @Override
                          public void write(int b) {}
                        },
                        false));
                try {
                  parseAction.run();
                } finally {
                  System.setErr(originalErr);
                }
                return null;
              });
      try {
        verdict.get(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException hang) {
        verdict.cancel(true);
        throw new AssertionError(
            "fuzz hang: input sha256="
                + sha
                + " produced no verdict within "
                + timeoutMs
                + " ms (jafar.fuzz.inputTimeoutMs); "
                + "a hang has no throwable and can never be listed in known-escapes.txt",
            hang);
      } catch (ExecutionException escaped) {
        failUnlessAllowed(sha, escaped.getCause(), knownEscapes);
        return;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(
            "fuzz oracle interrupted while waiting for the parse of input sha256=" + sha,
            interrupted);
      }
      // Completed normally: the input is clean, so a list entry for it can no longer escape.
      failStale(sha, knownEscapes);
    } finally {
      executor.shutdownNow();
    }
  }

  /** The per-input hang bound. Overridable for nightly fuzzing sessions, not for PR runs. */
  static long timeoutMs() {
    return Long.getLong("jafar.fuzz.inputTimeoutMs", 10_000L);
  }

  private static void failUnlessAllowed(
      String sha, Throwable delivered, Map<String, String> knownEscapes) {
    List<Throwable> chain = causeChain(delivered);
    String verdict = escapeVerdict(chain);
    if (verdict == null) {
      // Legal escape per D4 — but a list entry for it can no longer be exercised.
      failStale(sha, knownEscapes);
      return;
    }
    String listed = knownEscapes.get(sha);
    if (listed == null) {
      throw new AssertionError(
          "fuzz escape: input sha256="
              + sha
              + ": "
              + verdict
              + "; delivered "
              + delivered.getClass().getName()
              + (delivered.getMessage() == null ? "" : ": " + delivered.getMessage())
              + "; cause chain: ["
              + chainToString(chain)
              + "]; if this is a parser defect to pin (D3), add '"
              + sha
              + " "
              + delivered.getClass().getName()
              + "' with a '# reason:' comment to src/test/resources/io/jafar/parser/fuzz/"
              + ESCAPES_RESOURCE
              + " — never fix the parser in the fuzz task");
    }
    if (!listed.equals(delivered.getClass().getName())) {
      throw new AssertionError(
          "fuzz escape changed: input sha256="
              + sha
              + " is listed as "
              + listed
              + " but now delivers "
              + delivered.getClass().getName()
              + "; "
              + verdict
              + "; cause chain: ["
              + chainToString(chain)
              + "]; update the entry (or fix the parser and remove it)");
    }
    // Pinned known-escape with exactly the listed delivered throwable (I1): pass.
  }

  private static void failStale(String sha, Map<String, String> knownEscapes) {
    if (knownEscapes.containsKey(sha)) {
      throw new AssertionError(
          "stale known-escape "
              + sha
              + ": remove the entry (the input no longer escapes with "
              + knownEscapes.get(sha)
              + ")");
    }
  }

  /** {@code null} means the chain is a legal escape; otherwise a sentence saying why it is not. */
  private static String escapeVerdict(List<Throwable> chain) {
    for (int depth = 0; depth < chain.size(); depth++) {
      if (chain.get(depth) instanceof Error) {
        return "an Error appeared in the cause chain at depth " + depth + " (D4)";
      }
    }
    Throwable root = chain.get(chain.size() - 1);
    boolean legalRoot =
        root instanceof IOException || root.getClass().getName().startsWith("io.jafar.");
    if (!legalRoot) {
      return "the root cause "
          + root.getClass().getName()
          + " is neither an io.jafar.* exception nor an IOException (D4)";
    }
    return null;
  }

  private static List<Throwable> causeChain(Throwable delivered) {
    List<Throwable> chain = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = delivered;
    while (current != null && seen.add(current) && chain.size() < MAX_CHAIN_DEPTH) {
      chain.add(current);
      current = current.getCause();
    }
    if (chain.isEmpty()) {
      // Unreachable from the callers, but the chain is dereferenced below: never be empty.
      throw new AssertionError("fuzz escape: null throwable delivered");
    }
    return chain;
  }

  private static String chainToString(List<Throwable> chain) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chain.size(); i++) {
      if (i > 0) {
        sb.append(" <- ");
      }
      sb.append(chain.get(i).getClass().getName());
    }
    return sb.toString();
  }

  static String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest.digest(data)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is not available in this JVM", e);
    }
  }

  /**
   * Loads the committed allowlist from beside this class. Fail-loud on a missing file or a
   * malformed entry (R3): a silent empty list would turn every escape into an unlisted finding with
   * no way to tell a shrunk list from a misplaced one.
   */
  static Map<String, String> loadKnownEscapes() {
    Map<String, String> escapes = new LinkedHashMap<>();
    try (InputStream in = EscapeOracle.class.getResourceAsStream(ESCAPES_RESOURCE)) {
      if (in == null) {
        throw new AssertionError(
            "fuzz oracle misconfiguration: "
                + ESCAPES_RESOURCE
                + " not found on the test classpath");
      }
      byte[] raw = in.readAllBytes();
      String reason = null;
      for (String line : new String(raw, StandardCharsets.UTF_8).split("\n", -1)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("# reason:")) {
          reason = trimmed.substring("# reason:".length()).trim();
          continue;
        }
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        if (!ENTRY.matcher(trimmed).matches()) {
          throw new AssertionError(
              "malformed known-escape entry '"
                  + trimmed
                  + "': expected '<sha256> <throwable FQCN>'");
        }
        if (reason == null || reason.isEmpty()) {
          throw new AssertionError(
              "known-escape entry '" + trimmed + "' has no preceding '# reason:' comment");
        }
        int separator = trimmed.indexOf(' ');
        escapes.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        reason = null;
      }
      return escapes;
    } catch (java.io.IOException e) {
      throw new AssertionError("failed to read " + ESCAPES_RESOURCE, e);
    }
  }
}
