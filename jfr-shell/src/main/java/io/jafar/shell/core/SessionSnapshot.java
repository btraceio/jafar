package io.jafar.shell.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a JFR Shell session state that can be serialized for export/import.
 * Contains all session information including recording details, variables, command history, and
 * settings.
 */
public final class SessionSnapshot {
  public final Metadata metadata;
  public final RecordingInfo recording;
  public final List<VariableInfo> sessionVariables;
  public final List<VariableInfo> globalVariables;
  public final List<String> commandHistory;
  public final Map<String, String> sessionSettings;

  public SessionSnapshot(
      Metadata metadata,
      RecordingInfo recording,
      List<VariableInfo> sessionVariables,
      List<VariableInfo> globalVariables,
      List<String> commandHistory,
      Map<String, String> sessionSettings) {
    this.metadata = metadata;
    this.recording = recording;
    this.sessionVariables = new ArrayList<>(sessionVariables);
    this.globalVariables = new ArrayList<>(globalVariables);
    this.commandHistory = new ArrayList<>(commandHistory);
    this.sessionSettings = new HashMap<>(sessionSettings);
  }

  /** Metadata about the snapshot export. */
  public static final class Metadata {
    public final String version;
    public final String jafarVersion;
    public final Instant exportedAt;
    public final String exportedBy;
    public final String format;

    public Metadata(
        String version, String jafarVersion, Instant exportedAt, String exportedBy, String format) {
      this.version = version;
      this.jafarVersion = jafarVersion;
      this.exportedAt = exportedAt;
      this.exportedBy = exportedBy;
      this.format = format;
    }

    /** Creates a new Metadata instance with current defaults. */
    public static Metadata create(String exportedBy, String format) {
      return new Metadata("1.0", getJafarVersion(), Instant.now(), exportedBy, format);
    }

    private static String getJafarVersion() {
      // Try to read version from package or fall back to "unknown"
      Package pkg = SessionSnapshot.class.getPackage();
      String version = pkg != null ? pkg.getImplementationVersion() : null;
      return version != null ? version : "unknown";
    }
  }

  /** Information about the JFR recording file. */
  public static final class RecordingInfo {
    public final String absolutePath;
    public final String fileName;
    public final long fileSize;
    public final int eventTypeCount;
    public final int metadataTypeCount;
    public final Map<String, Long> topEventTypes;

    public RecordingInfo(
        String absolutePath,
        String fileName,
        long fileSize,
        int eventTypeCount,
        int metadataTypeCount,
        Map<String, Long> topEventTypes) {
      this.absolutePath = absolutePath;
      this.fileName = fileName;
      this.fileSize = fileSize;
      this.eventTypeCount = eventTypeCount;
      this.metadataTypeCount = metadataTypeCount;
      this.topEventTypes = new HashMap<>(topEventTypes);
    }
  }

  /** Information about a stored variable. */
  public static final class VariableInfo {
    public final String name;
    public final String type;
    public final String sourceQuery;
    public final Object value;
    public final boolean cached;
    public final Integer rowCount;
    public final Map<String, Object> metadata;

    public VariableInfo(
        String name,
        String type,
        String sourceQuery,
        Object value,
        boolean cached,
        Integer rowCount,
        Map<String, Object> metadata) {
      this.name = name;
      this.type = type;
      this.sourceQuery = sourceQuery;
      this.value = value;
      this.cached = cached;
      this.rowCount = rowCount;
      this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    /** Creates a VariableInfo for a scalar value. */
    public static VariableInfo scalar(String name, Object value) {
      return new VariableInfo(name, "scalar", null, value, false, null, null);
    }

    /** Creates a VariableInfo for a map value. */
    public static VariableInfo map(String name, Map<String, Object> value) {
      return new VariableInfo(name, "map", null, value, false, null, null);
    }

    /** Creates a VariableInfo for a lazy query value. */
    public static VariableInfo lazy(
        String name,
        String sourceQuery,
        boolean cached,
        Integer rowCount,
        Object cachedValue,
        Map<String, Object> metadata) {
      return new VariableInfo(name, "lazy", sourceQuery, cachedValue, cached, rowCount, metadata);
    }
  }

  /** Builder for constructing SessionSnapshot instances. */
  public static final class Builder {
    private Metadata metadata;
    private RecordingInfo recording;
    private List<VariableInfo> sessionVariables = new ArrayList<>();
    private List<VariableInfo> globalVariables = new ArrayList<>();
    private List<String> commandHistory = new ArrayList<>();
    private Map<String, String> sessionSettings = new HashMap<>();

    public Builder metadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder recording(RecordingInfo recording) {
      this.recording = recording;
      return this;
    }

    public Builder addSessionVariable(VariableInfo variable) {
      this.sessionVariables.add(variable);
      return this;
    }

    public Builder sessionVariables(List<VariableInfo> variables) {
      this.sessionVariables = new ArrayList<>(variables);
      return this;
    }

    public Builder addGlobalVariable(VariableInfo variable) {
      this.globalVariables.add(variable);
      return this;
    }

    public Builder globalVariables(List<VariableInfo> variables) {
      this.globalVariables = new ArrayList<>(variables);
      return this;
    }

    public Builder addCommand(String command) {
      this.commandHistory.add(command);
      return this;
    }

    public Builder commandHistory(List<String> history) {
      this.commandHistory = new ArrayList<>(history);
      return this;
    }

    public Builder addSetting(String key, String value) {
      this.sessionSettings.put(key, value);
      return this;
    }

    public Builder sessionSettings(Map<String, String> settings) {
      this.sessionSettings = new HashMap<>(settings);
      return this;
    }

    public SessionSnapshot build() {
      if (metadata == null) {
        throw new IllegalStateException("Metadata is required");
      }
      if (recording == null) {
        throw new IllegalStateException("RecordingInfo is required");
      }
      return new SessionSnapshot(
          metadata, recording, sessionVariables, globalVariables, commandHistory, sessionSettings);
    }
  }

  /** Creates a new builder for constructing SessionSnapshot instances. */
  public static Builder builder() {
    return new Builder();
  }
}
