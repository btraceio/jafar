package io.jafar.shell.core.completion;

import java.util.List;
import org.jline.reader.Candidate;

/**
 * Strategy interface for context-specific tab completers.
 *
 * @param <M> the metadata service type
 */
public interface ContextCompleter<M extends MetadataService> {

  /** Returns whether this completer can handle the given context. */
  boolean canHandle(CompletionContext ctx);

  /**
   * Generates completion candidates for the given context.
   *
   * @param ctx the completion context
   * @param metadata the metadata service
   * @param candidates list to add candidates to
   */
  void complete(CompletionContext ctx, M metadata, List<Candidate> candidates);

  /**
   * Creates a JLine Candidate with display and description.
   *
   * @param value the completion value
   * @param display the display text
   * @param description the description text
   * @return a new Candidate
   */
  default Candidate candidate(String value, String display, String description) {
    return new Candidate(value, display, null, description, null, null, true);
  }

  /**
   * Creates a JLine Candidate that does not append a trailing space.
   *
   * @param value the completion value
   * @param display the display text
   * @param description the description text
   * @return a new Candidate with complete=false
   */
  default Candidate candidateNoSpace(String value, String display, String description) {
    return new Candidate(value, display, null, description, null, null, false);
  }

  /**
   * Creates a simple no-space Candidate using value as both display and description.
   *
   * @param value the completion value
   * @return a new Candidate with complete=false
   */
  default Candidate noSpace(String value) {
    return new Candidate(value, value, null, null, null, null, false);
  }

  /**
   * Calculates the prefix that JLine has consumed from the partial input. When JLine's word
   * boundary differs from the logical partial, this finds the common prefix to prepend.
   *
   * @param jlineWord the word as parsed by JLine
   * @param fullPartial the full partial input for the completion
   * @return the prefix string, or empty if jlineWord matches fullPartial
   */
  default String calculateJlinePrefix(String jlineWord, String fullPartial) {
    if (jlineWord.equals(fullPartial)) {
      return "";
    }
    if (jlineWord.endsWith(fullPartial)) {
      return jlineWord.substring(0, jlineWord.length() - fullPartial.length());
    }
    return "";
  }
}
