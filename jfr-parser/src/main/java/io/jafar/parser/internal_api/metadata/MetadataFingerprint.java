package io.jafar.parser.internal_api.metadata;

import io.jafar.parser.internal_api.MutableMetadataLookup;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Immutable fingerprint of JFR metadata structure.
 *
 * <p>Computes a SHA-256 hash of the structural metadata (class IDs, names, field types) to enable
 * safe reuse of generated handler classes across recordings with compatible metadata.
 *
 * <p>The fingerprint includes only reachable types from specified event types, excluding
 * annotations, settings, and other non-structural elements that don't affect bytecode generation.
 */
public final class MetadataFingerprint {
  private final byte[] hash;

  private MetadataFingerprint(byte[] hash) {
    this.hash = hash;
  }

  /**
   * Computes a metadata fingerprint for the given metadata lookup and reachable class IDs.
   *
   * @param metadata the metadata lookup containing all class definitions
   * @param reachableClassIds the set of class IDs that are transitively reachable from used event
   *     types
   * @return a new MetadataFingerprint instance
   */
  public static MetadataFingerprint compute(
      MutableMetadataLookup metadata, Set<Long> reachableClassIds) {
    try {
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      List<MetadataClass> classes = new ArrayList<>();

      // Collect only reachable classes
      for (long classId : reachableClassIds) {
        MetadataClass clz = metadata.getClass(classId);
        if (clz != null) {
          classes.add(clz);
        }
      }

      // Sort for deterministic order
      classes.sort(Comparator.comparingLong(MetadataClass::getId));

      // Write structural data
      for (MetadataClass clz : classes) {
        buffer = ensureCapacity(buffer, 1024); // Ensure space for class data
        buffer.putLong(clz.getId());
        writeString(buffer, clz.getName());
        writeString(buffer, clz.getSuperType());
        buffer.putInt(clz.getFields().size());

        for (MetadataField field : clz.getFields()) {
          buffer = ensureCapacity(buffer, 256); // Ensure space for field data
          buffer.putLong(field.getType().getId());
          writeString(buffer, field.getName());
          buffer.put((byte) (field.hasConstantPool() ? 1 : 0));
          buffer.putInt(field.getDimension());
        }
      }

      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(buffer.array(), 0, buffer.position());
      byte[] hash = md.digest();
      return new MetadataFingerprint(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Computes the set of transitively reachable type IDs from the given event type names.
   *
   * @param metadata the metadata lookup containing all class definitions
   * @param eventTypeNames the set of event type names to start reachability analysis from
   * @return the set of class IDs that are transitively reachable through field types
   */
  public static Set<Long> computeReachableTypes(
      MutableMetadataLookup metadata, Set<String> eventTypeNames) {
    Set<Long> reachable = new HashSet<>();
    Queue<Long> queue = new LinkedList<>();

    // Seed with event types
    for (String eventName : eventTypeNames) {
      MetadataClass clz = metadata.getClass(eventName);
      if (clz != null) {
        queue.add(clz.getId());
        reachable.add(clz.getId());
      }
    }

    // BFS traversal through field types
    while (!queue.isEmpty()) {
      long classId = queue.poll();
      MetadataClass clz = metadata.getClass(classId);
      if (clz == null) {
        continue;
      }

      for (MetadataField field : clz.getFields()) {
        long fieldTypeId = field.getType().getId();
        if (reachable.add(fieldTypeId)) {
          queue.add(fieldTypeId);
        }
      }
    }

    return reachable;
  }

  private static void writeString(ByteBuffer buffer, String str) {
    if (str == null) {
      buffer.putInt(-1);
    } else {
      byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
      buffer.putInt(bytes.length);
      buffer.put(bytes);
    }
  }

  private static ByteBuffer ensureCapacity(ByteBuffer buffer, int additional) {
    if (buffer.remaining() < additional) {
      ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
      buffer.flip();
      newBuffer.put(buffer);
      return newBuffer;
    }
    return buffer;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetadataFingerprint that = (MetadataFingerprint) o;
    return Arrays.equals(hash, that.hash);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(hash);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("MetadataFingerprint[");
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    sb.append("]");
    return sb.toString();
  }

  /**
   * Returns the raw hash bytes for debugging purposes.
   *
   * @return a copy of the hash bytes
   */
  public byte[] getHashBytes() {
    return Arrays.copyOf(hash, hash.length);
  }
}
