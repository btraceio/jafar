package io.jafar.parser;

import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Functional interface for filtering JFR metadata classes.
 *
 * <p>This interface extends Predicate&lt;MetadataClass&gt; to provide a way to filter which JFR
 * types should be processed during parsing. It can be used to include or exclude specific event
 * types based on their metadata information.
 */
@FunctionalInterface
public interface TypeFilter extends Predicate<MetadataClass> {

  /**
   * A filter accepting the types with one of the given names, compared exactly: no wildcards and no
   * subtypes.
   *
   * <p>Two filters made from the same names are equal, so registering the same handler twice with
   * {@code named} filters of the same names counts as one handler registered twice.
   *
   * @param names the fully qualified type names to accept, such as {@code jdk.ExecutionSample}
   * @return a thread-safe filter
   */
  static TypeFilter named(Collection<String> names) {
    return new Named(new HashSet<>(names));
  }

  /**
   * A filter accepting the types with one of the given names. See {@link #named(Collection)}.
   *
   * @param names the fully qualified type names to accept
   * @return a thread-safe filter
   */
  static TypeFilter named(String... names) {
    return named(Arrays.asList(names));
  }

  /** The filter {@link #named(Collection)} returns. */
  final class Named implements TypeFilter {
    private final Set<String> names;

    private Named(Set<String> names) {
      this.names = names;
    }

    @Override
    public boolean test(MetadataClass type) {
      return names.contains(type.getName());
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Named && names.equals(((Named) o).names);
    }

    @Override
    public int hashCode() {
      return names.hashCode();
    }

    @Override
    public String toString() {
      return "TypeFilter.named(" + names + ")";
    }
  }
}
