package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Interface for looking up metadata elements during JFR parsing.
 *
 * <p>This interface provides methods to retrieve string constants and class metadata by their
 * identifiers, which are essential for parsing JFR recordings.
 */
public interface MetadataLookup {

  /**
   * Gets a string constant by its index.
   *
   * @param idx the index of the string constant
   * @return the string value at the specified index
   */
  String getString(int idx);

  /**
   * Gets a metadata class by its identifier.
   *
   * @param id the identifier of the metadata class
   * @return the metadata class for the specified identifier
   */
  MetadataClass getClass(long id);
}
