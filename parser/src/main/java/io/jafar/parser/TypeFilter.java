package io.jafar.parser;

import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.function.Predicate;

/**
 * Functional interface for filtering JFR metadata classes.
 *
 * <p>This interface extends Predicate&lt;MetadataClass&gt; to provide a way to filter which JFR
 * types should be processed during parsing. It can be used to include or exclude specific event
 * types based on their metadata information.
 */
@FunctionalInterface
public interface TypeFilter extends Predicate<MetadataClass> {}
