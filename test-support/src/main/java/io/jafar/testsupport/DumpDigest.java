package io.jafar.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The value identity of one dump — everything the golden file records, and nothing more.
 *
 * <p>Only per-line hashes are kept, never the lines themselves, so a digest of even the largest
 * fixture (tck-test.jfr deep-resolves to ~480 MB of dump lines) stays a few dozen bytes in memory
 * (Risk 4). The full dump is materialized on disk only when a golden mismatches, for the review
 * diff.
 *
 * <p>The {@link #digestOf(List)} rule — sha256 over the sorted per-line sha256 values — lives here
 * and nowhere else (R7): every producer (JFR canonical dump, the hdump/pprof/otlp dumps, the
 * differential comparators) builds a {@code DumpDigest} through {@link #of}, so no two nets can
 * drift into hashing the same lines differently.
 */
public final class DumpDigest {

  /**
   * sha256 of the fixture's bytes, or {@code null} when the fixture has no stable byte identity.
   * The hdump synthetic generator embeds a wall-clock timestamp, so its goldens record an empty
   * {@code fixture-sha256} line and the fixture check is skipped (T21).
   */
  private final String fixtureSha256;

  /** Number of dump lines — the golden's {@code lines=} value. */
  private final long lines;

  /**
   * Event-type → count, sorted by type: the golden's human-readable {@code count.<type>=} lines.
   */
  private final SortedMap<String, Long> counts;

  /** sha256 over the sorted per-line sha256 values ({@link #digestOf}). */
  private final String digest;

  private DumpDigest(
      String fixtureSha256, long lines, SortedMap<String, Long> counts, String digest) {
    this.fixtureSha256 = fixtureSha256;
    this.lines = lines;
    this.counts = counts;
    this.digest = digest;
  }

  /**
   * Builds a digest from a dump. {@code sortedLines} is the full dump (the same lines the producer
   * would write into the mismatch artifact); the digest hashes them per line, so memory stays
   * small. {@code counts} is copied into a sorted map, so producers never have to sort it
   * themselves.
   */
  public static DumpDigest of(
      String fixtureSha256, List<String> sortedLines, Map<String, Long> counts) {
    if (sortedLines == null) {
      throw new AssertionError("DumpDigest.of requires the dump lines, not null");
    }
    SortedMap<String, Long> sorted = new TreeMap<String, Long>();
    if (counts != null) {
      sorted.putAll(counts);
    }
    return new DumpDigest(
        fixtureSha256,
        sortedLines.size(),
        Collections.unmodifiableSortedMap(sorted),
        digestOf(sortedLines));
  }

  /**
   * The digest rule (R7 — the only implementation): the sha256 of each line's UTF-8 bytes, as
   * lowercase hex, sorted, concatenated, hashed once more. Sorting the per-line hashes makes the
   * digest independent of the line order the producer happened to produce, while still pinning
   * exactly the multiset of lines.
   */
  public static String digestOf(List<String> sortedLines) {
    List<String> perLine = new ArrayList<String>(sortedLines.size());
    for (String line : sortedLines) {
      perLine.add(sha256Hex(line));
    }
    Collections.sort(perLine);
    StringBuilder concatenated = new StringBuilder();
    for (String hash : perLine) {
      concatenated.append(hash);
    }
    return sha256Hex(concatenated.toString());
  }

  /** sha256 of the UTF-8 bytes of {@code text}, lowercase hex. */
  public static String sha256Hex(String text) {
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  /** sha256 of {@code bytes}, lowercase hex. */
  public static String sha256Hex(byte[] bytes) {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is not available", e);
    }
    byte[] hash = sha256.digest(bytes);
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16));
      hex.append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }

  public String fixtureSha256() {
    return fixtureSha256;
  }

  public long lines() {
    return lines;
  }

  /** Event-type → count, sorted; unmodifiable. */
  public SortedMap<String, Long> counts() {
    return counts;
  }

  public String digest() {
    return digest;
  }
}
