package io.jafar.parser.internal_api.metadata;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.internal_api.MutableMetadataLookup;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for MetadataFingerprint. */
public class MetadataFingerprintTest {

  @Test
  public void testEmptyFingerprint() {
    // Empty metadata should produce valid fingerprint
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<Long> empty = new HashSet<>();

    MetadataFingerprint fp = MetadataFingerprint.compute(lookup, empty);
    assertNotNull(fp);
    assertEquals(32, fp.getHashBytes().length, "SHA-256 produces 32 bytes");
  }

  @Test
  public void testToString() {
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<Long> reachable = new HashSet<>();

    MetadataFingerprint fp = MetadataFingerprint.compute(lookup, reachable);
    String str = fp.toString();

    assertTrue(str.startsWith("MetadataFingerprint["), "Should start with class name");
    assertTrue(str.endsWith("]"), "Should end with bracket");
    assertTrue(str.length() > 20, "Should contain hex representation of hash");
  }

  @Test
  public void testHashBytes() {
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<Long> reachable = new HashSet<>();

    MetadataFingerprint fp = MetadataFingerprint.compute(lookup, reachable);
    byte[] bytes = fp.getHashBytes();

    assertNotNull(bytes);
    assertEquals(32, bytes.length, "SHA-256 produces 32 bytes");

    // Verify it's a copy (mutation doesn't affect fingerprint)
    byte original = bytes[0];
    bytes[0] = (byte) ~bytes[0];
    byte[] bytes2 = fp.getHashBytes();
    assertEquals(original, bytes2[0], "getHashBytes should return a copy");
  }

  @Test
  public void testEmptyReachability() {
    // Empty event types should produce empty reachable set
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<String> eventTypes = new HashSet<>();

    Set<Long> reachable = MetadataFingerprint.computeReachableTypes(lookup, eventTypes);
    assertNotNull(reachable);
    assertTrue(reachable.isEmpty(), "Empty event types should produce empty reachable set");
  }

  @Test
  public void testNonExistentEventType() {
    // Non-existent event types should be handled gracefully
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<String> eventTypes = new HashSet<>();
    eventTypes.add("test.NonExistent");

    Set<Long> reachable = MetadataFingerprint.computeReachableTypes(lookup, eventTypes);
    assertNotNull(reachable);
    assertTrue(reachable.isEmpty(), "Non-existent types should be skipped");
  }

  @Test
  public void testFingerprintEquality() {
    // Test equals and hashCode
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    Set<Long> reachable = new HashSet<>();

    MetadataFingerprint fp1 = MetadataFingerprint.compute(lookup, reachable);
    MetadataFingerprint fp2 = MetadataFingerprint.compute(lookup, reachable);

    assertEquals(fp1, fp2, "Same inputs should produce equal fingerprints");
    assertEquals(fp1.hashCode(), fp2.hashCode(), "Equal fingerprints should have same hash code");
    assertEquals(fp1, fp1, "Fingerprint should equal itself");
    assertNotEquals(fp1, null, "Fingerprint should not equal null");
    assertNotEquals(fp1, "string", "Fingerprint should not equal other types");
  }
}
