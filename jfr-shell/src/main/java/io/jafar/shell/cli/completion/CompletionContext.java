package io.jafar.shell.cli.completion;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable context data extracted from the input line for completion. Contains all information
 * needed by context-specific completers.
 */
public record CompletionContext(
    /** The type of completion context */
    CompletionContextType type,

    /** The command being completed (show, metadata, etc.) - null if not determined */
    String command,

    /** Root path type (events, metadata, cp) - null if not in path context */
    String rootType,

    /** Event type name extracted from path - null if not determined */
    String eventType,

    /** Nested field path segments - empty if at root */
    List<String> fieldPath,

    /** Current function name if inside a function call - null otherwise */
    String functionName,

    /** Which parameter (0-indexed) in multi-param functions */
    int parameterIndex,

    /** What user has typed so far for the current token (for filtering candidates) */
    String partialInput,

    /** The full input line */
    String fullLine,

    /** Cursor position in the line */
    int cursor,

    /** Additional context-specific data */
    Map<String, String> extras) {
  /** Canonical constructor with validation */
  public CompletionContext {
    if (type == null) {
      type = CompletionContextType.UNKNOWN;
    }
    if (fieldPath == null) {
      fieldPath = Collections.emptyList();
    }
    if (partialInput == null) {
      partialInput = "";
    }
    if (fullLine == null) {
      fullLine = "";
    }
    if (extras == null) {
      extras = Collections.emptyMap();
    }
  }

  /** Builder for convenient construction */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private CompletionContextType type = CompletionContextType.UNKNOWN;
    private String command;
    private String rootType;
    private String eventType;
    private List<String> fieldPath = Collections.emptyList();
    private String functionName;
    private int parameterIndex = 0;
    private String partialInput = "";
    private String fullLine = "";
    private int cursor = 0;
    private Map<String, String> extras = Collections.emptyMap();

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

    public Builder eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder fieldPath(List<String> fieldPath) {
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

    public Builder extras(Map<String, String> extras) {
      this.extras = extras;
      return this;
    }

    public CompletionContext build() {
      return new CompletionContext(
          type,
          command,
          rootType,
          eventType,
          fieldPath,
          functionName,
          parameterIndex,
          partialInput,
          fullLine,
          cursor,
          extras);
    }
  }
}
