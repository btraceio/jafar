package io.jafar.shell.cli.completion;

import java.util.List;
import org.jline.reader.Candidate;

/**
 * Strategy interface for context-specific completion providers. Each implementation handles one
 * type of completion context.
 */
public interface ContextCompleter {

  /**
   * Checks if this completer can handle the given context.
   *
   * @param ctx the completion context
   * @return true if this completer should handle the context
   */
  boolean canHandle(CompletionContext ctx);

  /**
   * Generates completion candidates for the given context.
   *
   * @param ctx the completion context with all necessary data
   * @param metadata the metadata service for accessing event/field information
   * @param candidates list to add candidates to
   */
  void complete(CompletionContext ctx, MetadataService metadata, List<Candidate> candidates);

  /**
   * Creates a candidate that won't add trailing space after selection. Use for templates ending
   * with ( or other characters that expect immediate continuation.
   */
  default Candidate noSpace(String value) {
    return new Candidate(value, value, null, null, null, null, false);
  }

  /** Creates a candidate with display text and description. */
  default Candidate candidate(String value, String display, String description) {
    return new Candidate(value, display, null, description, null, null, true);
  }

  /** Creates a candidate that won't add trailing space, with display and description. */
  default Candidate candidateNoSpace(String value, String display, String description) {
    return new Candidate(value, display, null, description, null, null, false);
  }
}
