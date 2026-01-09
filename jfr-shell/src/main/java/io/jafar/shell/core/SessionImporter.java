package io.jafar.shell.core;

import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.core.SessionSnapshot.VariableInfo;
import io.jafar.shell.core.VariableStore.LazyQueryValue;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.jfrpath.JfrPath.Query;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Service for importing JFR Shell session state from various formats. */
public class SessionImporter {

  /** Options for controlling import behavior. */
  public static final class ImportOptions {
    private final String alias;
    private final String remapPath;

    public ImportOptions(String alias, String remapPath) {
      this.alias = alias;
      this.remapPath = remapPath;
    }

    public String alias() {
      return alias;
    }

    public String remapPath() {
      return remapPath;
    }

    public static ImportOptions defaults() {
      return new ImportOptions(null, null);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String alias = null;
      private String remapPath = null;

      public Builder alias(String a) {
        this.alias = a;
        return this;
      }

      public Builder remapPath(String path) {
        this.remapPath = path;
        return this;
      }

      public ImportOptions build() {
        return new ImportOptions(alias, remapPath);
      }
    }
  }

  /** IO interface for printing warnings and messages. */
  public interface IO {
    void println(String message);

    void error(String message);
  }

  /** Default IO that prints to System.out/err. */
  public static final IO SYSTEM_IO =
      new IO() {
        @Override
        public void println(String message) {
          System.out.println(message);
        }

        @Override
        public void error(String message) {
          System.err.println(message);
        }
      };

  private final IO io;

  public SessionImporter() {
    this(SYSTEM_IO);
  }

  public SessionImporter(IO io) {
    this.io = io;
  }

  /**
   * Imports a session from a JSON file.
   *
   * @param inputPath the input file path
   * @param opts import options
   * @param mgr the session manager
   * @return the imported session reference
   */
  public SessionRef importFromJson(Path inputPath, ImportOptions opts, SessionManager mgr)
      throws Exception {
    io.println("Importing session from " + inputPath + "...");

    SessionSnapshot snapshot = parseJson(inputPath);
    validateSnapshot(snapshot);

    io.println(
        "Snapshot version: "
            + snapshot.metadata.version
            + ", exported: "
            + snapshot.metadata.exportedAt);

    Path recordingPath = resolveRecordingPath(snapshot.recording, opts);

    SessionRef ref = mgr.open(recordingPath, opts.alias());

    restoreSessionSettings(ref, snapshot.sessionSettings);
    restoreVariables(ref, snapshot.sessionVariables, false);
    restoreVariables(ref, snapshot.globalVariables, true);

    io.println("Session imported successfully.");
    io.println("  Session ID: " + ref.id);
    if (ref.alias != null) {
      io.println("  Alias: " + ref.alias);
    }
    io.println("  Recording: " + recordingPath);
    io.println(
        "  Variables: "
            + snapshot.sessionVariables.size()
            + " session, "
            + snapshot.globalVariables.size()
            + " global");

    return ref;
  }

  /**
   * Parses a JSON file into a SessionSnapshot.
   *
   * @param inputPath the JSON file path
   * @return the parsed snapshot
   */
  private SessionSnapshot parseJson(Path inputPath) throws IOException {
    String json = Files.readString(inputPath);
    return parseJsonString(json);
  }

  /**
   * Parses a JSON string into a SessionSnapshot.
   *
   * @param json the JSON string
   * @return the parsed snapshot
   */
  private SessionSnapshot parseJsonString(String json) {
    // Simple JSON parser - extract fields using regex
    // Note: This is a simplified parser for Phase 1. For production, consider using a JSON library

    SessionSnapshot.Metadata metadata = parseMetadata(json);
    SessionSnapshot.RecordingInfo recording = parseRecording(json);
    List<VariableInfo> sessionVars = parseVariables(json, "sessionVariables");
    List<VariableInfo> globalVars = parseVariables(json, "globalVariables");
    List<String> commandHistory = parseStringArray(json, "commandHistory");
    Map<String, String> settings = parseSettings(json);

    return SessionSnapshot.builder()
        .metadata(metadata)
        .recording(recording)
        .sessionVariables(sessionVars)
        .globalVariables(globalVars)
        .commandHistory(commandHistory)
        .sessionSettings(settings)
        .build();
  }

  private SessionSnapshot.Metadata parseMetadata(String json) {
    String metadataBlock = extractObject(json, "metadata");

    String version = extractString(metadataBlock, "version");
    String jafarVersion = extractString(metadataBlock, "jafarVersion");
    String exportedAtStr = extractString(metadataBlock, "exportedAt");
    Instant exportedAt = Instant.parse(exportedAtStr);
    String exportedBy = extractString(metadataBlock, "exportedBy");
    String format = extractString(metadataBlock, "format");

    return new SessionSnapshot.Metadata(version, jafarVersion, exportedAt, exportedBy, format);
  }

  private SessionSnapshot.RecordingInfo parseRecording(String json) {
    String recordingBlock = extractObject(json, "recording");

    String absolutePath = extractString(recordingBlock, "absolutePath");
    String fileName = extractString(recordingBlock, "fileName");
    long fileSize = Long.parseLong(extractNumber(recordingBlock, "fileSize"));
    int eventTypeCount = Integer.parseInt(extractNumber(recordingBlock, "eventTypeCount"));
    int metadataTypeCount = Integer.parseInt(extractNumber(recordingBlock, "metadataTypeCount"));
    Map<String, Long> topEventTypes = parseEventTypes(recordingBlock);

    return new SessionSnapshot.RecordingInfo(
        absolutePath, fileName, fileSize, eventTypeCount, metadataTypeCount, topEventTypes);
  }

  private Map<String, Long> parseEventTypes(String recordingBlock) {
    Map<String, Long> result = new HashMap<>();
    String topEventTypesBlock = extractObject(recordingBlock, "topEventTypes");

    Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");
    Matcher matcher = pattern.matcher(topEventTypesBlock);
    while (matcher.find()) {
      String typeName = matcher.group(1);
      long count = Long.parseLong(matcher.group(2));
      result.put(typeName, count);
    }

    return result;
  }

  private List<VariableInfo> parseVariables(String json, String arrayName) {
    List<VariableInfo> result = new ArrayList<>();
    String variablesBlock = extractArray(json, arrayName);

    // Split by variable objects
    Pattern varPattern = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
    Matcher matcher = varPattern.matcher(variablesBlock);

    while (matcher.find()) {
      String varBlock = matcher.group();
      VariableInfo varInfo = parseVariable(varBlock);
      if (varInfo != null) {
        result.add(varInfo);
      }
    }

    return result;
  }

  private VariableInfo parseVariable(String varBlock) {
    String name = extractString(varBlock, "name");
    String type = extractString(varBlock, "type");
    String sourceQuery = extractString(varBlock, "sourceQuery");
    boolean cached = extractBoolean(varBlock, "cached");
    Integer rowCount = extractInteger(varBlock, "rowCount");

    // Parse metadata if present
    Map<String, Object> metadata = new HashMap<>();
    if (varBlock.contains("\"metadata\"")) {
      String metadataBlock = extractObject(varBlock, "metadata");
      if (metadataBlock.contains("\"truncated\"")) {
        metadata.put("truncated", extractBoolean(metadataBlock, "truncated"));
      }
      if (metadataBlock.contains("\"originalRowCount\"")) {
        metadata.put("originalRowCount", extractInteger(metadataBlock, "originalRowCount"));
      }
    }

    // Parse value based on type
    Object value = parseValueForType(varBlock, type);

    if ("scalar".equals(type)) {
      return VariableInfo.scalar(name, value);
    } else if ("map".equals(type)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> mapValue = (Map<String, Object>) value;
      return VariableInfo.map(name, mapValue);
    } else if ("lazy".equals(type)) {
      return VariableInfo.lazy(name, sourceQuery, cached, rowCount, value, metadata);
    }

    return null;
  }

  private Object parseValueForType(String varBlock, String type) {
    String valueSection = extractValueSection(varBlock);

    if (valueSection.equals("null")) {
      return null;
    }

    if ("scalar".equals(type)) {
      return parseScalarValue(valueSection);
    } else if ("map".equals(type) || "lazy".equals(type)) {
      // For simplicity, keep as string representation for now
      // In a real implementation, would recursively parse the JSON structure
      if (valueSection.startsWith("{")) {
        return parseMapValue(valueSection);
      } else if (valueSection.startsWith("[")) {
        return parseListValue(valueSection);
      }
      return parseScalarValue(valueSection);
    }

    return null;
  }

  private Object parseScalarValue(String value) {
    value = value.trim();
    if (value.equals("null")) {
      return null;
    }
    if (value.startsWith("\"") && value.endsWith("\"")) {
      return unquote(value);
    }
    if (value.equals("true")) {
      return true;
    }
    if (value.equals("false")) {
      return false;
    }
    try {
      if (value.contains(".")) {
        return Double.parseDouble(value);
      } else {
        return Long.parseLong(value);
      }
    } catch (NumberFormatException e) {
      return value;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMapValue(String value) {
    // Simplified map parsing - sufficient for Phase 1
    Map<String, Object> map = new HashMap<>();

    // For now, return empty map - full recursive JSON parsing would be complex
    // This is acceptable for Phase 1 as we're primarily focused on queries
    return map;
  }

  private List<Object> parseListValue(String value) {
    // Simplified list parsing
    List<Object> list = new ArrayList<>();

    // For now, return empty list - full parsing would require complete JSON parser
    return list;
  }

  private String extractValueSection(String varBlock) {
    Pattern pattern = Pattern.compile("\"value\"\\s*:\\s*(.+?)\\s*(?:,\\s*\"\\w+\"|\\})\\s*$");
    Matcher matcher = pattern.matcher(varBlock);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    return "null";
  }

  private List<String> parseStringArray(String json, String arrayName) {
    List<String> result = new ArrayList<>();
    String arrayBlock = extractArray(json, arrayName);

    Pattern pattern = Pattern.compile("\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(arrayBlock);
    while (matcher.find()) {
      result.add(matcher.group(1));
    }

    return result;
  }

  private Map<String, String> parseSettings(String json) {
    Map<String, String> result = new HashMap<>();
    String settingsBlock = extractObject(json, "sessionSettings");

    Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(settingsBlock);
    while (matcher.find()) {
      result.put(matcher.group(1), matcher.group(2));
    }

    return result;
  }

  private String extractObject(String json, String fieldName) {
    Pattern pattern =
        Pattern.compile(
            "\"" + fieldName + "\"\\s*:\\s*\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", Pattern.DOTALL);
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return "{}";
  }

  private String extractArray(String json, String fieldName) {
    Pattern pattern =
        Pattern.compile(
            "\"" + fieldName + "\"\\s*:\\s*\\[([^\\[\\]]*(?:\\[[^\\[\\]]*\\][^\\[\\]]*)*)\\]",
            Pattern.DOTALL);
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return "[]";
  }

  private String extractString(String json, String fieldName) {
    Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return unescapeJson(matcher.group(1));
    }
    return "";
  }

  private String extractNumber(String json, String fieldName) {
    Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([\\d.]+)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return "0";
  }

  private boolean extractBoolean(String json, String fieldName) {
    Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(true|false)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return Boolean.parseBoolean(matcher.group(1));
    }
    return false;
  }

  private Integer extractInteger(String json, String fieldName) {
    Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    return null;
  }

  private String unquote(String quoted) {
    if (quoted.startsWith("\"") && quoted.endsWith("\"")) {
      return unescapeJson(quoted.substring(1, quoted.length() - 1));
    }
    return quoted;
  }

  private String unescapeJson(String escaped) {
    return escaped
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\b", "\b")
        .replace("\\f", "\f")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  /**
   * Validates the snapshot format and version.
   *
   * @param snapshot the snapshot to validate
   */
  private void validateSnapshot(SessionSnapshot snapshot) throws Exception {
    if (!snapshot.metadata.version.equals("1.0")) {
      throw new Exception(
          "Unsupported snapshot version: "
              + snapshot.metadata.version
              + ". This tool supports version 1.0.");
    }

    if (snapshot.recording == null) {
      throw new Exception("Invalid snapshot: missing recording information");
    }
  }

  /**
   * Resolves the recording path, considering remap options and existence checks.
   *
   * @param recording the recording info from snapshot
   * @param opts import options
   * @return the resolved path
   */
  private Path resolveRecordingPath(SessionSnapshot.RecordingInfo recording, ImportOptions opts)
      throws Exception {
    Path path;

    // Check for remapped path first
    if (opts.remapPath() != null) {
      path = Paths.get(opts.remapPath());
      io.println("Using remapped path: " + path);
    } else {
      path = Paths.get(recording.absolutePath);
    }

    // Check if file exists
    if (!Files.exists(path)) {
      // Try relative to current directory
      Path relativePath = Paths.get(recording.fileName);
      if (Files.exists(relativePath)) {
        io.println("Warning: Original path not found, using: " + relativePath);
        path = relativePath;
      } else {
        String message =
            "Recording file not found at: "
                + path
                + "\nUse --remap-path to specify a new location.";
        throw new Exception(message);
      }
    }

    return path;
  }

  /**
   * Restores session settings.
   *
   * @param ref the session reference
   * @param settings the settings map
   */
  private void restoreSessionSettings(SessionRef ref, Map<String, String> settings) {
    if (settings.containsKey("outputFormat")) {
      ref.outputFormat = settings.get("outputFormat");
    }
  }

  /**
   * Restores variables into the session.
   *
   * @param ref the session reference
   * @param vars the variable information list
   * @param isGlobal whether these are global variables
   */
  private void restoreVariables(SessionRef ref, List<VariableInfo> vars, boolean isGlobal) {
    for (VariableInfo varInfo : vars) {
      try {
        if ("scalar".equals(varInfo.type)) {
          restoreScalar(ref, varInfo, isGlobal);
        } else if ("map".equals(varInfo.type)) {
          restoreMap(ref, varInfo, isGlobal);
        } else if ("lazy".equals(varInfo.type)) {
          restoreLazy(ref, varInfo, isGlobal);
        }
      } catch (Exception e) {
        io.error("Warning: Failed to restore variable '" + varInfo.name + "': " + e.getMessage());
      }
    }
  }

  /**
   * Restores a scalar variable.
   *
   * @param ref the session reference
   * @param varInfo the variable information
   * @param isGlobal whether this is a global variable
   */
  private void restoreScalar(SessionRef ref, VariableInfo varInfo, boolean isGlobal) {
    ScalarValue value = new ScalarValue(varInfo.value);
    ref.variables.set(varInfo.name, value);
  }

  /**
   * Restores a map variable.
   *
   * @param ref the session reference
   * @param varInfo the variable information
   * @param isGlobal whether this is a global variable
   */
  @SuppressWarnings("unchecked")
  private void restoreMap(SessionRef ref, VariableInfo varInfo, boolean isGlobal) {
    Map<String, Object> mapValue = (Map<String, Object>) varInfo.value;
    MapValue value = new MapValue(mapValue);
    ref.variables.set(varInfo.name, value);
  }

  /**
   * Restores a lazy query variable.
   *
   * @param ref the session reference
   * @param varInfo the variable information
   * @param isGlobal whether this is a global variable
   */
  private void restoreLazy(SessionRef ref, VariableInfo varInfo, boolean isGlobal)
      throws Exception {
    // Parse the query string
    Query query;
    try {
      query = JfrPathParser.parse(varInfo.sourceQuery);
    } catch (Exception e) {
      throw new Exception("Failed to parse query: " + varInfo.sourceQuery, e);
    }

    // Create lazy value
    LazyQueryValue lazyValue = new LazyQueryValue(query, ref, varInfo.sourceQuery);

    // Pre-populate cache if results were included
    if (varInfo.cached && varInfo.value != null) {
      lazyValue.setCachedResult(varInfo.value);

      if (varInfo.metadata.containsKey("truncated")) {
        boolean truncated = (boolean) varInfo.metadata.get("truncated");
        if (truncated) {
          int originalCount = (int) varInfo.metadata.get("originalRowCount");
          io.println(
              "Note: Variable '"
                  + varInfo.name
                  + "' was truncated from "
                  + originalCount
                  + " to "
                  + varInfo.rowCount
                  + " rows");
        }
      }
    }

    ref.variables.set(varInfo.name, lazyValue);
  }
}
