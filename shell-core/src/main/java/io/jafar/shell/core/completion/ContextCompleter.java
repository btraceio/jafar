package io.jafar.shell.core.completion;

import java.util.List;
import org.jline.reader.Candidate;

/**
 * Strategy interface for context-specific completion providers. Each implementation handles one
 * type of completion context.
 *
 * @param <M> the type of metadata service used by this completer
 */
public interface ContextCompleter<M> {

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
   * @param metadata the metadata service for accessing type/field information
   * @param candidates list to add candidates to
   */
  void complete(CompletionContext ctx, M metadata, List<Candidate> candidates);

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

  /**
   * Calculates the prefix to prepend to completion candidates based on JLine's current word.
   *
   * <p>JLine filters candidates based on the current word prefix. When the word is "groupBy(" and
   * we want to suggest "id", we need to return "groupBy(id" so JLine sees it as matching. This
   * method extracts the prefix part of jlineWord that comes before the partial input.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>jlineWord="groupBy(", partial="" → prefix="groupBy("
   *   <li>jlineWord="groupBy(na", partial="na" → prefix="groupBy("
   *   <li>jlineWord="", partial="" → prefix=""
   *   <li>jlineWord=null, partial="x" → prefix=""
   * </ul>
   *
   * @param jlineWord the current word from JLine's ParsedLine.word()
   * @param partial the partial input being completed (from context analysis)
   * @return the prefix to prepend to candidate values
   */
  default String calculateJlinePrefix(String jlineWord, String partial) {
    if (jlineWord == null || jlineWord.isEmpty()) {
      return "";
    }
    if (partial == null || partial.isEmpty()) {
      return jlineWord;
    }
    if (jlineWord.length() > partial.length()) {
      return jlineWord.substring(0, jlineWord.length() - partial.length());
    }
    return "";
  }
}
