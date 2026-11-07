package io.jafar.parser.fuzz;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Outcome tests for the adversarial recordings (AC-4.7): one test per {@code adv-*} seed committed
 * under {@code src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped/},
 * asserting the SPECIFIC observed outcome — the delivered jafar exception class plus a message
 * fragment, or the exact delivered-event count. "Did not crash" is not an outcome.
 *
 * <p>Three seeds (string-table-size, huge-length, deep-metadata) escape the fuzz oracle (an {@code
 * Error} or a non-jafar, non-{@code IOException} root cause in the delivered chain, D4). Per D3
 * their escapes are pinned in {@code known-escapes.txt} — the parser is NOT fixed in this run — and
 * each escape test asserts the pinned delivered throwable, looked up by the committed seed's sha256
 * so the allowlist stays the single source of truth for what is pinned (I1). The legal escapes and
 * the clean parse additionally assert they are NOT pinned: a pin whose input no longer escapes must
 * fail as stale, from this test as well as from {@link EscapeOracle}.
 *
 * <p>The intended paths below were confirmed by reading the delivered stack traces (Verification.md
 * R3, "half an event"); the source anchors are recorded per test in the comments.
 */
public class AdversarialOutcomeTest {

  private static final String CORPUS_DIR = "UntypedParserFuzzTestInputs/parseUntyped/";

  /**
   * sha256 of each committed seed, measured on the generator output (test-recordings, T24). {@code
   * SharedFixturesTest} in test-recordings guards the seeds against generator drift; these
   * constants bind THIS test's pinned outcomes to the exact committed bytes.
   */
  private static final String SHA_VALID_BASE =
      "b130a355db53c6683aad7ca39c37c55a24ee0b95fa9da36fad9bcea2bd78e3d2";

  private static final String SHA_STRING_TABLE_SIZE =
      "45bf4070483b634d5052a9f3040ed99029cb3321121be7e5f2c51c0bcfeb0c78";

  private static final String SHA_HUGE_LENGTH =
      "75e0b8ef6eaa45dd1a4476a2b4f563a9c270cf01d7a6a6ed1af91104880b9f95";

  private static final String SHA_BAD_CP_ID =
      "4eb3125e1082bbf52a54414632fad684bfb73a8bba071c5e6e36167003fac30f";

  private static final String SHA_DEEP_METADATA =
      "2c5b1a3f8b9fc832452617f9d20e3e8fa2e0b9f1011a167d5d0139f00d4e47bd";

  private static final String SHA_BAD_CHUNK_OFFSETS =
      "ed6a6661b5e039db98de604f7f0af9bbf512949adc11789f8e1ad2ac91d20120";

  /**
   * What one parse of a seed delivered: the throwable (or null for a clean parse) and the count.
   */
  private static final class Outcome {
    final Throwable delivered;
    final long events;

    Outcome(Throwable delivered, long events) {
      this.delivered = delivered;
      this.events = events;
    }
  }

  /** The strategies the fuzz target itself parses every input with (AC-4.5's replay shape). */
  private static final UntypedStrategy[] FUZZ_STRATEGIES = {
    UntypedStrategy.SPARSE_ACCESS, UntypedStrategy.FULL_ITERATION
  };

  // ---------------------------------------------------------------------------------------------
  // One test per adv-* seed
  // ---------------------------------------------------------------------------------------------

  /**
   * Control case: the un-corrupted valid shape parses cleanly and delivers exactly 4 events under
   * both fuzz strategies. The count, not "did not crash", is the outcome.
   */
  @Test
  void validBaseDeliversExactlyFourEvents() {
    String sha = assertSeedSha("adv-valid-base", SHA_VALID_BASE);
    assertNotPinned(sha, "a clean parse must not be listed in known-escapes.txt");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed("adv-valid-base"), strategy);
      Assertions.assertNull(
          outcome.delivered, strategy + ": adv-valid-base must parse cleanly (control case)");
      Assertions.assertEquals(4, outcome.events, strategy + ": exact delivered-event count");
    }
  }

  /**
   * Undeclared constant-pool id: rejected at the checkpoint boundary — {@code
   * CheckpointEvent.readConstantPools} throws {@code IOException}("... the type is not declared in
   * the chunk metadata"), wrapped and delivered as {@code IOException}. A legal escape per D4, so
   * it must NOT be pinned.
   */
  @Test
  void badConstantPoolIdIsRejectedAtTheCheckpointBoundary() {
    String sha = assertSeedSha("adv-bad-cp-id", SHA_BAD_CP_ID);
    assertNotPinned(
        sha, "a legal (all-IOException) escape must not be listed in known-escapes.txt");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed("adv-bad-cp-id"), strategy);
      assertDelivered(outcome, strategy);
      Assertions.assertEquals(
          java.io.IOException.class, outcome.delivered.getClass(), strategy + ": delivered class");
      Assertions.assertTrue(
          outcome.delivered.getMessage().contains("the type is not declared in the chunk metadata"),
          strategy + ": message fragment; got '" + outcome.delivered.getMessage() + "'");
      Assertions.assertFalse(
          chainContainsError(outcome.delivered),
          strategy + ": legal escape — no Error may appear in the cause chain");
    }
  }

  /**
   * Lying chunk size field (+4 bytes): chunk 1's events run past its boundary (observed WARN "Event
   * size 70 at position 405 exceeds chunk boundary, stopping") and the NEXT chunk header is misread
   * — {@code ChunkHeader.<init>} rejects the garbage magic 20001. Delivered {@code IOException};
   * legal escape, not pinned.
   */
  @Test
  void badChunkOffsetsFailOnTheNextChunkHeader() {
    String sha = assertSeedSha("adv-bad-chunk-offsets", SHA_BAD_CHUNK_OFFSETS);
    assertNotPinned(
        sha, "a legal (all-IOException) escape must not be listed in known-escapes.txt");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed("adv-bad-chunk-offsets"), strategy);
      assertDelivered(outcome, strategy);
      Assertions.assertEquals(
          java.io.IOException.class, outcome.delivered.getClass(), strategy + ": delivered class");
      Assertions.assertTrue(
          outcome.delivered.getMessage().contains("Invalid JFR Magic Number: 20001"),
          strategy + ": message fragment; got '" + outcome.delivered.getMessage() + "'");
      Assertions.assertFalse(
          chainContainsError(outcome.delivered),
          strategy + ": legal escape — no Error may appear in the cause chain");
    }
  }

  /**
   * Corrupted string-table size: the index 108 escapes {@code MutableMetadataLookup.getString}'s
   * table (size 25) as an {@code IllegalArgumentException} that the chunk boundary wraps but does
   * not convert — the root cause is neither {@code io.jafar.*} nor {@code IOException} (D4). Pinned
   * in known-escapes.txt; the test asserts the pinned delivered throwable plus the specific
   * message.
   */
  @Test
  void corruptedStringTableSizeEscapesWithThePinnedThrowable() {
    String sha = assertSeedSha("adv-string-table-size", SHA_STRING_TABLE_SIZE);
    String pinned = assertPinned(sha, "adv-string-table-size");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed("adv-string-table-size"), strategy);
      assertDelivered(outcome, strategy);
      Assertions.assertEquals(
          pinned, outcome.delivered.getClass().getName(), strategy + ": the pinned throwable");
      Assertions.assertTrue(
          outcome
              .delivered
              .getMessage()
              .contains("String index 108 out of bounds (string table size: 25)"),
          strategy + ": message fragment; got '" + outcome.delivered.getMessage() + "'");
    }
  }

  /**
   * Huge declared string length (1<<24, 16 MiB, against a ~405-byte recording, with no payload
   * behind it): {@code ParsingUtils.readUTF8} allocates {@code byte[1<<24]} and {@code
   * RecordingStream.read} rejects the read with {@code RuntimeException}("unexpected EOF"), which
   * the chunk boundary wraps but does not convert — a non-jafar root cause (D4), delivered as
   * {@code IOException}("Error occurred while parsing JFR recording: unexpected EOF"). Pinned;
   * asserted with the pinned delivered throwable plus message.
   *
   * <p>The case originally declared an exact 2^31 length. That variant is the real unbounded-work
   * vector — the length narrows to a negative int, passes the buffer-reuse check, and the parser
   * loops on it past {@code shutdownNow()} (an abandoned executor burned ~17 CPU-hours on it) —
   * recorded under R8/D3 for the report; a fix belongs in the parser, out of scope for this run.
   * The seed was shrunk to 1<<24 so the parse completes in bounded time while keeping the
   * corrupted-huge-length semantics (the reader fails on the declared length, not on any real
   * payload).
   */
  @Test
  void hugeDeclaredLengthEscapesWithThePinnedThrowable() {
    String sha = assertSeedSha("adv-huge-length", SHA_HUGE_LENGTH);
    String pinned = assertPinned(sha, "adv-huge-length");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed("adv-huge-length"), strategy);
      assertDelivered(outcome, strategy);
      Assertions.assertEquals(
          pinned, outcome.delivered.getClass().getName(), strategy + ": the pinned throwable");
      Assertions.assertTrue(
          outcome
              .delivered
              .getMessage()
              .contains("Error occurred while parsing JFR recording: unexpected EOF"),
          strategy + ": message fragment; got '" + outcome.delivered.getMessage() + "'");
    }
  }

  /**
   * Deeply nested metadata (chain of unknown 'deep' elements, depth 200,000): {@code
   * readSubelements} recurses until the pool thread's stack overflows. The delivered class is
   * stably {@code java.io.IOException}, but WHICH Error sits at the chain's root depends on JVM
   * history — {@code StackOverflowError}, or the {@code NoClassDefFoundError} produced when the
   * SOE-damaged {@code StackTraceElement$HashedModules} class-init fails mid-unwind (both observed)
   * — so only the delivered throwable is pinned, and this test asserts an Error is present without
   * naming it.
   *
   * <p>stderr is silenced for this one parse: the unwind logs the SAME pinned SOE at ERROR once per
   * recursion level (~2,300 entries, each a full 1024-frame trace — ~250 MB). Swallowing it changes
   * nothing about the outcome asserted here (R9: the payload is asserted, not the logs), and the
   * un-silenced replay of this seed by {@code UntypedParserFuzzTest} keeps the full evidence in its
   * test report.
   */
  @Test
  void deepMetadataRecursionEscapesWithThePinnedThrowable() {
    String sha = assertSeedSha("adv-deep-metadata", SHA_DEEP_METADATA);
    String pinned = assertPinned(sha, "adv-deep-metadata");
    PrintStream originalErr = System.err;
    System.setErr(
        new PrintStream(
            new OutputStream() {
              @Override
              public void write(int b) {
                // discard: see the Javadoc — log spam of a pinned, already-reported escape
              }
            },
            true));
    try {
      Outcome outcome = parse(seed("adv-deep-metadata"), UntypedStrategy.SPARSE_ACCESS);
      Assertions.assertNotNull(outcome.delivered, "the deep-recursion vector must escape");
      Assertions.assertEquals(
          pinned, outcome.delivered.getClass().getName(), "the pinned delivered throwable");
      Assertions.assertTrue(
          chainContainsError(outcome.delivered),
          "the pinned escape reason: an Error must appear in the cause chain (D4); chain root "
              + rootClassName(outcome.delivered));
    } finally {
      System.setErr(originalErr);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** Loads the committed seed bytes; fail loudly on a missing resource (R3). */
  private static byte[] seed(String name) {
    try (InputStream in = EscapeOracle.class.getResourceAsStream(CORPUS_DIR + name)) {
      if (in == null) {
        throw new AssertionError(
            "adversarial seed '"
                + name
                + "' is missing from "
                + CORPUS_DIR
                + "; regenerate with ./gradlew :test-recordings:generateAdversarialRecordings");
      }
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      throw new AssertionError("failed to read adversarial seed '" + name + "'", e);
    }
  }

  /**
   * Parses the seed with a fresh context, counting delivered events. The temp-file staging mirrors
   * the fuzz target: the seed bytes on disk, nothing shared between parses.
   */
  private static Outcome parse(byte[] data, UntypedStrategy strategy) {
    try {
      Path recording = Files.createTempFile("adv-outcome", ".jfr");
      try {
        Files.write(recording, data);
        long[] events = {0};
        Throwable delivered;
        try {
          ParsingContext ctx = ParsingContext.create();
          try (UntypedJafarParser parser = ctx.newUntypedParser(recording, strategy)) {
            parser.handle((type, event, ctl) -> events[0]++);
            parser.run();
          }
          delivered = null;
        } catch (Throwable t) {
          delivered = t;
        }
        return new Outcome(delivered, events[0]);
      } finally {
        Files.deleteIfExists(recording);
      }
    } catch (java.io.IOException e) {
      throw new AssertionError("failed to stage the adversarial seed for parsing", e);
    }
  }

  private static void assertDelivered(Outcome outcome, UntypedStrategy strategy) {
    Assertions.assertNotNull(
        outcome.delivered, strategy + ": expected the seed to deliver a throwable");
  }

  /** Binds the committed seed bytes to the sha these outcomes (and pins) were measured on. */
  private static String assertSeedSha(String name, String expected) {
    String actual = EscapeOracle.sha256Hex(seed(name));
    Assertions.assertEquals(
        expected,
        actual,
        "seed '"
            + name
            + "' drifted from the committed bytes the outcomes were measured on;"
            + " regenerate with ./gradlew :test-recordings:generateAdversarialRecordings and"
            + " re-pin in known-escapes.txt if it escapes");
    return actual;
  }

  /**
   * The escape must be pinned in known-escapes.txt; returns the pinned delivered throwable FQCN.
   */
  private static String assertPinned(String sha, String name) {
    Map<String, String> escapes = EscapeOracle.loadKnownEscapes();
    String pinned = escapes.get(sha);
    Assertions.assertNotNull(
        pinned,
        "adversarial seed '"
            + name
            + "' (sha256 "
            + sha
            + ") escapes the oracle but has no"
            + " known-escapes.txt entry; add '<sha> <delivered throwable FQCN>' with a '# reason:'"
            + " comment (D3: pin, do not fix the parser)");
    return pinned;
  }

  /** A seed that does not escape must not carry a pin — that pin would be stale (I1). */
  private static void assertNotPinned(String sha, String why) {
    Map<String, String> escapes = EscapeOracle.loadKnownEscapes();
    Assertions.assertFalse(
        escapes.containsKey(sha),
        "known-escapes.txt pins sha256 "
            + sha
            + " ("
            + escapes.get(sha)
            + ") but the seed does"
            + " not escape: remove the stale entry. "
            + why);
  }

  private static boolean chainContainsError(Throwable delivered) {
    Throwable t = delivered;
    while (t != null) {
      if (t instanceof java.lang.Error) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  private static String rootClassName(Throwable delivered) {
    Throwable t = delivered;
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t.getClass().getName();
  }
}
