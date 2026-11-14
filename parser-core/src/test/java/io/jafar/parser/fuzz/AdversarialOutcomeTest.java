package io.jafar.parser.fuzz;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.io.InputStream;
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
 * <p>Every seed is either parsed cleanly or rejected with an {@code IOException} whose cause chain
 * is legal per D4 (an {@code IOException} or {@code io.jafar.*} root, no {@code Error}), so none is
 * pinned in {@code known-escapes.txt}; each test also asserts that, because a pin whose input does
 * not escape must fail as stale from here as well as from {@link EscapeOracle}. Three seeds
 * (string-table-size, huge-length, deep-metadata) did escape the oracle when they were added, and
 * were pinned until the parser checked what they corrupt; their tests record the former outcome.
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
   * Corrupted string-table size: an element name index (108) lies outside the string table (size
   * 25). {@code MetadataEvent.readElement} looks the index up through {@code
   * ParsingUtils.metadataString}, which reports it as an {@code IOException} — the index came from
   * the recording, so it is the recording that is wrong. A legal escape per D4, not pinned.
   *
   * <p>Until the lookup was routed through {@code metadataString} this escaped with an {@code
   * IllegalArgumentException} root and was pinned in known-escapes.txt.
   */
  @Test
  void corruptedStringTableSizeIsRejectedAsMalformedMetadata() {
    assertLegalEscape(
        "adv-string-table-size",
        SHA_STRING_TABLE_SIZE,
        "String index 108 out of bounds (string table size: 25)");
  }

  /**
   * Huge declared string length (1<<24, 16 MiB, against a ~405-byte recording, with no payload
   * behind it): {@code ParsingUtils.readUTF8} checks the declared length against the bytes that are
   * left before it sizes a buffer from it, and rejects it with an {@code IOException}. A legal
   * escape per D4, not pinned.
   *
   * <p>Until that check existed the parser allocated {@code byte[1<<24]} first and failed on the
   * read with a bare {@code RuntimeException}("unexpected EOF"); a length of 2^31 narrowed to a
   * negative int and never terminated. Both are the same missing check.
   */
  @Test
  void hugeDeclaredLengthIsRejectedBeforeAnythingIsAllocated() {
    assertLegalEscape(
        "adv-huge-length",
        SHA_HUGE_LENGTH,
        "String of 16777216 byte(s) exceeds the remaining 278 byte(s)");
  }

  /**
   * Deeply nested metadata (chain of unknown 'deep' elements, depth 200,000): {@code
   * MetadataEvent.readElement} bounds the nesting at {@code MAX_ELEMENT_DEPTH} (64, the Go parser's
   * bound) and rejects the recording with an {@code IOException}. A legal escape per D4, not pinned
   * — and no {@code Error} in the chain.
   *
   * <p>Until the bound existed the elements recursed until the pool thread's stack overflowed, and
   * the unwind logged the same {@code StackOverflowError} once per recursion level.
   */
  @Test
  void deepMetadataNestingIsRejectedAtTheDepthBound() {
    assertLegalEscape(
        "adv-deep-metadata", SHA_DEEP_METADATA, "Metadata element nesting exceeds 64 levels");
  }

  /**
   * A seed the parser rejects as malformed: delivered as {@code IOException} with the given message
   * fragment under both fuzz strategies, with an {@code IOException} at the root of the cause chain
   * and no {@code Error} anywhere in it — and therefore not pinned.
   */
  private static void assertLegalEscape(String name, String expectedSha, String fragment) {
    String sha = assertSeedSha(name, expectedSha);
    assertNotPinned(
        sha, "a legal (all-IOException) escape must not be listed in known-escapes.txt");
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Outcome outcome = parse(seed(name), strategy);
      assertDelivered(outcome, strategy);
      Assertions.assertEquals(
          java.io.IOException.class, outcome.delivered.getClass(), strategy + ": delivered class");
      Assertions.assertTrue(
          outcome.delivered.getMessage().contains(fragment),
          strategy + ": message fragment; got '" + outcome.delivered.getMessage() + "'");
      Assertions.assertFalse(
          chainContainsError(outcome.delivered),
          strategy + ": legal escape — no Error may appear in the cause chain");
      Assertions.assertEquals(
          java.io.IOException.class.getName(),
          rootClassName(outcome.delivered),
          strategy + ": the root cause must be the IOException that names the defect (D4)");
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
