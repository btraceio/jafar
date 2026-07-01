package io.jafar.jfr2pprof.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Loads a {@link MappingConfig} from a YAML file or reader using snakeyaml-engine. */
public final class MappingLoader {

  private MappingLoader() {}

  /**
   * Loads a {@link MappingConfig} from the given file path.
   *
   * @param path path to the YAML mapping file
   * @return parsed and validated {@link MappingConfig}
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the YAML content fails validation
   */
  public static MappingConfig load(Path path) throws IOException {
    try (Reader r = Files.newBufferedReader(path)) {
      return load(r);
    }
  }

  /**
   * Loads a {@link MappingConfig} from the given reader.
   *
   * @param reader source of the YAML content
   * @return parsed and validated {@link MappingConfig}
   * @throws IllegalArgumentException if the YAML content fails validation
   */
  public static MappingConfig load(Reader reader) {
    Load yaml = new Load(LoadSettings.builder().build());
    Object raw = yaml.loadFromReader(reader);

    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) raw;

    FrameFormat frameFormat = parseFrameFormat(root);
    List<ProfileSpec> profiles = parseProfiles(root);

    validate(profiles);

    int idx = 0;
    for (ProfileSpec p : profiles) {
      p.setSampleTypeIndex(idx);
      idx += p.values().size();
    }

    return new MappingConfig(frameFormat, profiles);
  }

  @SuppressWarnings("unchecked")
  private static FrameFormat parseFrameFormat(Map<String, Object> root) {
    Object frameObj = root.get("frame");
    if (frameObj == null) {
      return FrameFormat.defaultFormat();
    }
    Map<String, Object> frameMap = (Map<String, Object>) frameObj;
    String format = (String) frameMap.getOrDefault("format", "{class}.{method}");
    boolean includeLineNumbers = Boolean.TRUE.equals(frameMap.get("includeLineNumbers"));
    return new FrameFormat(format, includeLineNumbers);
  }

  @SuppressWarnings("unchecked")
  private static List<ProfileSpec> parseProfiles(Map<String, Object> root) {
    Object profilesObj = root.get("profiles");
    if (profilesObj == null) {
      return Collections.emptyList();
    }
    List<Object> profilesList = (List<Object>) profilesObj;
    List<ProfileSpec> profiles = new ArrayList<>(profilesList.size());
    for (Object profileObj : profilesList) {
      Map<String, Object> profileMap = (Map<String, Object>) profileObj;

      String type = (String) profileMap.getOrDefault("type", "");
      String event = (String) profileMap.get("event");
      String stackField = (String) profileMap.getOrDefault("stackField", "stackTrace");

      List<ValueSpec> values = parseValues(profileMap);
      List<LabelSpec> labels = parseLabels(profileMap);

      profiles.add(new ProfileSpec(type, event, stackField, values, labels));
    }
    return profiles;
  }

  @SuppressWarnings("unchecked")
  private static List<ValueSpec> parseValues(Map<String, Object> profileMap) {
    Object valuesObj = profileMap.get("values");
    if (valuesObj == null) {
      return Collections.emptyList();
    }
    List<Object> valuesList = (List<Object>) valuesObj;
    List<ValueSpec> values = new ArrayList<>(valuesList.size());
    for (Object valueObj : valuesList) {
      Map<String, Object> valueMap = (Map<String, Object>) valueObj;
      String name = (String) valueMap.get("name");
      String unit = (String) valueMap.get("unit");
      String field = (String) valueMap.get("field");
      double scale = 1.0;
      Object scaleObj = valueMap.get("scale");
      if (scaleObj instanceof Number) {
        scale = ((Number) scaleObj).doubleValue();
      }
      values.add(new ValueSpec(name, unit, field, scale));
    }
    return values;
  }

  @SuppressWarnings("unchecked")
  private static List<LabelSpec> parseLabels(Map<String, Object> profileMap) {
    Object labelsObj = profileMap.get("labels");
    if (labelsObj == null) {
      return Collections.emptyList();
    }
    List<Object> labelsList = (List<Object>) labelsObj;
    List<LabelSpec> labels = new ArrayList<>(labelsList.size());
    for (Object labelObj : labelsList) {
      Map<String, Object> labelMap = (Map<String, Object>) labelObj;
      String jfr = (String) labelMap.get("jfr");
      String pprof = (String) labelMap.get("pprof");
      labels.add(new LabelSpec(jfr, pprof));
    }
    return labels;
  }

  private static void validate(List<ProfileSpec> profiles) {
    if (profiles.isEmpty()) {
      throw new IllegalArgumentException("profiles list must not be empty");
    }
    for (ProfileSpec p : profiles) {
      if (p.event() == null || p.event().isEmpty()) {
        throw new IllegalArgumentException("each profile must have a non-empty 'event' field");
      }
      if (p.values().isEmpty()) {
        throw new IllegalArgumentException(
            "profile '" + p.event() + "' must have at least one value");
      }
      for (ValueSpec v : p.values()) {
        if (v.name() == null) {
          throw new IllegalArgumentException(
              "value in profile '" + p.event() + "' must have a 'name'");
        }
        if (v.unit() == null) {
          throw new IllegalArgumentException(
              "value '" + v.name() + "' in profile '" + p.event() + "' must have a 'unit'");
        }
        if (v.field() == null) {
          throw new IllegalArgumentException(
              "value '" + v.name() + "' in profile '" + p.event() + "' must have a 'field'");
        }
        if (!v.isCount()) {
          validateDottedPath(v.field(), "value '" + v.name() + "' field");
        }
      }
    }
  }

  private static void validateDottedPath(String path, String context) {
    String[] segments = path.split("\\.", -1);
    for (String segment : segments) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException(context + " '" + path + "' contains an empty segment");
      }
    }
  }
}
