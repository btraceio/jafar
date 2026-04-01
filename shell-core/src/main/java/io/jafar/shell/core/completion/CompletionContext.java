package io.jafar.shell.core.completion;

import java.util.Map;

/**
 * Immutable context describing the current completion position in a shell input line.
 *
 * @param type the kind of completion expected
 * @param command the command being completed (e.g. "show")
 * @param rootType the query root type if known (e.g. "objects", "events")
 * @param typePattern the type/class pattern being completed
 * @param fieldPath the field path being completed
 * @param functionName the pipeline function being completed
 * @param parameterIndex the parameter index within a function call
 * @param partialInput the partial text the user has typed
 * @param fullLine the full input line
 * @param cursor the cursor position in fullLine
 * @param jlineWord the current word from JLine
 * @param extras optional extra context (module-specific)
 */
public record CompletionContext(
    CompletionContextType type,
    String command,
    String rootType,
    String typePattern,
    String fieldPath,
    String functionName,
    int parameterIndex,
    String partialInput,
    String fullLine,
    int cursor,
    String jlineWord,
    Map<String, Object> extras) {

  /** Returns a builder for constructing CompletionContext instances. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for CompletionContext. */
  public static final class Builder {
    private CompletionContextType type = CompletionContextType.UNKNOWN;
    private String command;
    private String rootType;
    private String typePattern;
    private String fieldPath;
    private String functionName;
    private int parameterIndex;
    private String partialInput = "";
    private String fullLine = "";
    private int cursor;
    private String jlineWord = "";
    private Map<String, Object> extras;

    private Builder() {}

    public Builder type(CompletionContextType type) {
      this.type = type;
      return this;
    }

    public Builder command(String command) {
      this.command = command;
      return this;
    }

    public Builder rootType(String rootType) {
      this.rootType = rootType;
      return this;
    }

    public Builder typePattern(String typePattern) {
      this.typePattern = typePattern;
      return this;
    }

    public Builder fieldPath(String fieldPath) {
      this.fieldPath = fieldPath;
      return this;
    }

    public Builder functionName(String functionName) {
      this.functionName = functionName;
      return this;
    }

    public Builder parameterIndex(int parameterIndex) {
      this.parameterIndex = parameterIndex;
      return this;
    }

    public Builder partialInput(String partialInput) {
      this.partialInput = partialInput;
      return this;
    }

    public Builder fullLine(String fullLine) {
      this.fullLine = fullLine;
      return this;
    }

    public Builder cursor(int cursor) {
      this.cursor = cursor;
      return this;
    }

    public Builder jlineWord(String jlineWord) {
      this.jlineWord = jlineWord;
      return this;
    }

    public Builder extras(Map<String, Object> extras) {
      this.extras = extras;
      return this;
    }

    public CompletionContext build() {
      return new CompletionContext(
          type,
          command,
          rootType,
          typePattern,
          fieldPath,
          functionName,
          parameterIndex,
          partialInput,
          fullLine,
          cursor,
          jlineWord,
          extras);
    }
  }
}
