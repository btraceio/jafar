package io.jafar.shell.core;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.core.SessionSnapshot.RecordingInfo;
import io.jafar.shell.core.SessionSnapshot.VariableInfo;
import io.jafar.shell.core.VariableStore.LazyQueryValue;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Service for exporting JFR Shell session state to various formats. */
public class SessionExporter {

  /** Options for controlling export behavior. */
  public static final class ExportOptions {
    private final boolean includeResults;
    private final int maxRows;
    private final String format;

    public ExportOptions(boolean includeResults, int maxRows, String format) {
      this.includeResults = includeResults;
      this.maxRows = maxRows;
      this.format = format;
    }

    public boolean includeResults() {
      return includeResults;
    }

    public int maxRows() {
      return maxRows;
    }

    public String format() {
      return format;
    }

    public static ExportOptions defaults() {
      return new ExportOptions(false, 1000, "json");
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private boolean includeResults = false;
      private int maxRows = 1000;
      private String format = "json";

      public Builder includeResults(boolean include) {
        this.includeResults = include;
        return this;
      }

      public Builder maxRows(int max) {
        this.maxRows = max;
        return this;
      }

      public Builder format(String fmt) {
        this.format = fmt;
        return this;
      }

      public ExportOptions build() {
        return new ExportOptions(includeResults, maxRows, format);
      }
    }
  }

  /**
   * Captures a snapshot of the session state.
   *
   * @param ref the session reference to export
   * @param opts export options
   * @return the captured snapshot
   */
  public SessionSnapshot captureSnapshot(SessionRef ref, ExportOptions opts) throws Exception {
    String exportedBy = "session #" + ref.id;
    if (ref.alias != null) {
      exportedBy += " (" + ref.alias + ")";
    }

    SessionSnapshot.Metadata metadata = SessionSnapshot.Metadata.create(exportedBy, opts.format());

    RecordingInfo recording = captureRecordingInfo(ref.session);

    List<VariableInfo> sessionVars = captureVariables(ref.variables, opts);

    // Global variables would be passed separately if we have access to the global store
    List<VariableInfo> globalVars = new ArrayList<>();

    // Command history - for now, empty (Phase 1 simplification)
    List<String> commandHistory = new ArrayList<>();

    Map<String, String> settings = new HashMap<>();
    settings.put("outputFormat", ref.outputFormat);

    return SessionSnapshot.builder()
        .metadata(metadata)
        .recording(recording)
        .sessionVariables(sessionVars)
        .globalVariables(globalVars)
        .commandHistory(commandHistory)
        .sessionSettings(settings)
        .build();
  }

  /**
   * Captures recording information from a JFR session.
   *
   * @param session the JFR session
   * @return recording information
   */
  private RecordingInfo captureRecordingInfo(JFRSession session) {
    Path path = session.getRecordingPath();
    String absolutePath = path.toAbsolutePath().toString();
    String fileName = path.getFileName().toString();

    long fileSize = 0;
    try {
      fileSize = Files.size(path);
    } catch (IOException e) {
      // File may have been moved/deleted, use 0
    }

    int eventTypeCount = session.getAvailableEventTypes().size();
    int metadataTypeCount = session.getAvailableMetadataTypes().size();

    // Get top 10 event types by count
    Map<String, Long> topEventTypes =
        session.getEventTypeCounts().entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .collect(
                HashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                HashMap::putAll);

    return new RecordingInfo(
        absolutePath, fileName, fileSize, eventTypeCount, metadataTypeCount, topEventTypes);
  }

  /**
   * Captures all variables from a variable store.
   *
   * @param store the variable store
   * @param opts export options
   * @return list of variable information
   */
  private List<VariableInfo> captureVariables(VariableStore store, ExportOptions opts)
      throws Exception {
    List<VariableInfo> result = new ArrayList<>();

    for (String name : store.names()) {
      Value value = store.get(name);
      if (value == null) {
        continue;
      }

      if (value instanceof ScalarValue scalar) {
        result.add(captureScalar(name, scalar));
      } else if (value instanceof MapValue mapValue) {
        result.add(captureMap(name, mapValue));
      } else if (value instanceof LazyQueryValue lazy) {
        result.add(captureLazy(name, lazy, opts));
      }
    }

    return result;
  }

  /**
   * Captures a scalar variable.
   *
   * @param name variable name
   * @param value scalar value
   * @return variable information
   */
  private VariableInfo captureScalar(String name, ScalarValue value) {
    return VariableInfo.scalar(name, value.value());
  }

  /**
   * Captures a map variable.
   *
   * @param name variable name
   * @param value map value
   * @return variable information
   */
  @SuppressWarnings("unchecked")
  private VariableInfo captureMap(String name, MapValue value) {
    return VariableInfo.map(name, (Map<String, Object>) value.get());
  }

  /**
   * Captures a lazy query variable.
   *
   * @param name variable name
   * @param value lazy query value
   * @param opts export options
   * @return variable information
   */
  private VariableInfo captureLazy(String name, LazyQueryValue value, ExportOptions opts)
      throws Exception {
    String queryString = value.getQueryString();
    boolean cached = value.isCached();
    Integer rowCount = null;
    Object cachedValue = null;
    Map<String, Object> metadata = new HashMap<>();

    if (cached) {
      try {
        rowCount = value.size();
      } catch (Exception e) {
        // If we can't get size, leave it null
      }

      if (opts.includeResults() && rowCount != null) {
        Object result = value.get();
        if (result instanceof List<?> list) {
          if (list.size() > opts.maxRows()) {
            // Truncate to max rows
            cachedValue = list.subList(0, opts.maxRows());
            metadata.put("truncated", true);
            metadata.put("originalRowCount", list.size());
          } else {
            cachedValue = result;
          }
        } else {
          cachedValue = result;
        }
      }
    }

    return VariableInfo.lazy(name, queryString, cached, rowCount, cachedValue, metadata);
  }

  /**
   * Exports a snapshot to JSON format.
   *
   * @param snapshot the snapshot to export
   * @param outputPath the output file path
   */
  public void exportToJson(SessionSnapshot snapshot, Path outputPath) throws IOException {
    String json = toJson(snapshot);
    Files.writeString(outputPath, json);
  }

  /**
   * Converts a SessionSnapshot to JSON string.
   *
   * @param snapshot the snapshot to convert
   * @return JSON representation
   */
  private String toJson(SessionSnapshot snapshot) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");

    // Metadata
    sb.append("  \"metadata\": ");
    appendMetadata(sb, snapshot.metadata);
    sb.append(",\n");

    // Recording
    sb.append("  \"recording\": ");
    appendRecording(sb, snapshot.recording);
    sb.append(",\n");

    // Session variables
    sb.append("  \"sessionVariables\": ");
    appendVariables(sb, snapshot.sessionVariables);
    sb.append(",\n");

    // Global variables
    sb.append("  \"globalVariables\": ");
    appendVariables(sb, snapshot.globalVariables);
    sb.append(",\n");

    // Command history
    sb.append("  \"commandHistory\": ");
    appendStringList(sb, snapshot.commandHistory);
    sb.append(",\n");

    // Session settings
    sb.append("  \"sessionSettings\": ");
    appendMap(sb, snapshot.sessionSettings);
    sb.append("\n");

    sb.append("}");
    return sb.toString();
  }

  private void appendMetadata(StringBuilder sb, SessionSnapshot.Metadata metadata) {
    sb.append("{\n");
    sb.append("    \"version\": ").append(quote(metadata.version)).append(",\n");
    sb.append("    \"jafarVersion\": ").append(quote(metadata.jafarVersion)).append(",\n");
    sb.append("    \"exportedAt\": ").append(quote(metadata.exportedAt.toString())).append(",\n");
    sb.append("    \"exportedBy\": ").append(quote(metadata.exportedBy)).append(",\n");
    sb.append("    \"format\": ").append(quote(metadata.format)).append("\n");
    sb.append("  }");
  }

  private void appendRecording(StringBuilder sb, RecordingInfo recording) {
    sb.append("{\n");
    sb.append("    \"absolutePath\": ").append(quote(recording.absolutePath)).append(",\n");
    sb.append("    \"fileName\": ").append(quote(recording.fileName)).append(",\n");
    sb.append("    \"fileSize\": ").append(recording.fileSize).append(",\n");
    sb.append("    \"eventTypeCount\": ").append(recording.eventTypeCount).append(",\n");
    sb.append("    \"metadataTypeCount\": ").append(recording.metadataTypeCount).append(",\n");
    sb.append("    \"topEventTypes\": ");
    appendEventTypesMap(sb, recording.topEventTypes);
    sb.append("\n  }");
  }

  private void appendVariables(StringBuilder sb, List<VariableInfo> variables) {
    sb.append("[\n");
    for (int i = 0; i < variables.size(); i++) {
      VariableInfo var = variables.get(i);
      sb.append("    {\n");
      sb.append("      \"name\": ").append(quote(var.name)).append(",\n");
      sb.append("      \"type\": ").append(quote(var.type)).append(",\n");

      if (var.sourceQuery != null) {
        sb.append("      \"sourceQuery\": ").append(quote(var.sourceQuery)).append(",\n");
      }

      sb.append("      \"cached\": ").append(var.cached).append(",\n");

      if (var.rowCount != null) {
        sb.append("      \"rowCount\": ").append(var.rowCount).append(",\n");
      }

      if (!var.metadata.isEmpty()) {
        sb.append("      \"metadata\": ");
        appendObjectMap(sb, var.metadata);
        sb.append(",\n");
      }

      sb.append("      \"value\": ");
      appendValue(sb, var.value);
      sb.append("\n");

      sb.append("    }");
      if (i < variables.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
    }
    sb.append("  ]");
  }

  private void appendEventTypesMap(StringBuilder sb, Map<String, Long> map) {
    sb.append("{\n");
    int i = 0;
    for (Map.Entry<String, Long> entry : map.entrySet()) {
      sb.append("      ").append(quote(entry.getKey())).append(": ").append(entry.getValue());
      if (i < map.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("    }");
  }

  private void appendMap(StringBuilder sb, Map<String, String> map) {
    sb.append("{\n");
    int i = 0;
    for (Map.Entry<String, String> entry : map.entrySet()) {
      sb.append("    ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
      if (i < map.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("  }");
  }

  private void appendObjectMap(StringBuilder sb, Map<String, Object> map) {
    sb.append("{\n");
    int i = 0;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      sb.append("        ").append(quote(entry.getKey())).append(": ");
      appendValue(sb, entry.getValue());
      if (i < map.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("      }");
  }

  private void appendStringList(StringBuilder sb, List<String> list) {
    sb.append("[\n");
    for (int i = 0; i < list.size(); i++) {
      sb.append("    ").append(quote(list.get(i)));
      if (i < list.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
    }
    sb.append("  ]");
  }

  @SuppressWarnings("unchecked")
  private void appendValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String str) {
      sb.append(quote(str));
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof List<?> list) {
      appendList(sb, list);
    } else if (value instanceof Map<?, ?> map) {
      appendNestedMap(sb, (Map<String, Object>) map);
    } else {
      // Fallback for unknown types
      sb.append(quote(value.toString()));
    }
  }

  private void appendList(StringBuilder sb, List<?> list) {
    sb.append("[");
    for (int i = 0; i < list.size(); i++) {
      appendValue(sb, list.get(i));
      if (i < list.size() - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
  }

  @SuppressWarnings("unchecked")
  private void appendNestedMap(StringBuilder sb, Map<String, Object> map) {
    sb.append("{");
    int i = 0;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      sb.append(quote(entry.getKey())).append(": ");
      appendValue(sb, entry.getValue());
      if (i < map.size() - 1) {
        sb.append(", ");
      }
      i++;
    }
    sb.append("}");
  }

  private String quote(String str) {
    if (str == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    for (char c : str.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
