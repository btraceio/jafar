package io.jafar.shell.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration for LLM providers and privacy settings. Loads from {@code
 * ~/.jfr-shell/llm-config.properties} by default.
 */
public record LLMConfig(
    ProviderType provider,
    String endpoint,
    String model,
    String apiKey,
    PrivacySettings privacy,
    int timeoutSeconds,
    int maxTokens,
    double temperature,
    boolean multiLevelEnabled) {

  /** Type of LLM provider. */
  public enum ProviderType {
    /** Local LLM via Ollama (privacy-first, no data sharing). */
    LOCAL,
    /** OpenAI cloud API (requires API key). */
    OPENAI,
    /** Anthropic (Claude) cloud API (requires API key). */
    ANTHROPIC,
    /** Mock provider for testing (no network calls). */
    MOCK
  }

  /** Privacy mode for controlling data sharing. */
  public enum PrivacyMode {
    /** Only use local LLM, never send data to cloud. */
    LOCAL_ONLY,
    /** Ask user before sending data to cloud providers. */
    CLOUD_WITH_CONFIRM,
    /** Use heuristics: simple queries local, complex queries cloud. */
    SMART
  }

  /** Privacy settings for controlling what data is sent to LLMs. */
  public record PrivacySettings(
      PrivacyMode mode,
      boolean allowStackTraces,
      boolean allowThreadNames,
      boolean allowEventValues,
      Set<String> sensitivePatterns,
      boolean auditEnabled) {

    /**
     * Creates default privacy settings (maximum privacy).
     *
     * @return default privacy settings
     */
    public static PrivacySettings defaults() {
      return new PrivacySettings(
          PrivacyMode.LOCAL_ONLY,
          false, // No stack traces
          true, // Allow thread names
          false, // No event values
          Set.of("password", "secret", ".*key.*", "token", "credential"),
          true // Audit enabled
          );
    }
  }

  /**
   * Creates default configuration for local Ollama provider.
   *
   * @return default configuration
   */
  public static LLMConfig defaults() {
    return new LLMConfig(
        ProviderType.LOCAL,
        "http://localhost:11434",
        "llama3.1:8b",
        null, // No API key for local
        PrivacySettings.defaults(),
        30, // 30 second timeout
        2048, // Max tokens
        0.1, // Low temperature for deterministic queries
        false // Multi-level prompting disabled by default (Phase 4 migration)
        );
  }

  /**
   * Loads configuration from the default location: {@code ~/.jfr-shell/llm-config.properties}.
   *
   * @return loaded configuration, or defaults if file doesn't exist
   * @throws IOException if file exists but cannot be read
   */
  public static LLMConfig load() throws IOException {
    Path configPath = getConfigPath();
    if (!Files.exists(configPath)) {
      return defaults();
    }

    Properties props = new Properties();
    try (var reader = Files.newBufferedReader(configPath)) {
      props.load(reader);
    }

    return fromProperties(props);
  }

  /**
   * Saves this configuration to the default location: {@code ~/.jfr-shell/llm-config.properties}.
   *
   * @throws IOException if the file cannot be written
   */
  public void save() throws IOException {
    Path configPath = getConfigPath();
    Files.createDirectories(configPath.getParent());

    Properties props = toProperties();
    try (var writer = Files.newBufferedWriter(configPath)) {
      props.store(
          writer,
          "JFR Shell LLM Configuration\n"
              + "# Provider types: LOCAL, OPENAI, ANTHROPIC, MOCK\n"
              + "# Privacy modes: LOCAL_ONLY, CLOUD_WITH_CONFIRM, SMART");
    }
  }

  /**
   * Gets the path to the configuration file.
   *
   * @return configuration file path
   */
  private static Path getConfigPath() {
    String home = System.getProperty("user.home");
    return Path.of(home, ".jfr-shell", "llm-config.properties");
  }

  /**
   * Converts properties to configuration object.
   *
   * @param props properties to convert
   * @return configuration object
   */
  private static LLMConfig fromProperties(Properties props) {
    ProviderType provider =
        ProviderType.valueOf(props.getProperty("provider", "LOCAL").toUpperCase());
    String endpoint = props.getProperty("endpoint", "http://localhost:11434");
    String model = props.getProperty("model", "llama3.1:8b");
    String apiKey = props.getProperty("apiKey");

    PrivacyMode mode =
        PrivacyMode.valueOf(props.getProperty("privacy.mode", "LOCAL_ONLY").toUpperCase());
    boolean allowStackTraces =
        Boolean.parseBoolean(props.getProperty("privacy.allowStackTraces", "false"));
    boolean allowThreadNames =
        Boolean.parseBoolean(props.getProperty("privacy.allowThreadNames", "true"));
    boolean allowEventValues =
        Boolean.parseBoolean(props.getProperty("privacy.allowEventValues", "false"));
    Set<String> sensitivePatterns =
        Stream.of(
                props
                    .getProperty(
                        "privacy.sensitivePatterns", "password,secret,.*key.*,token,credential")
                    .split(","))
            .map(String::trim)
            .collect(Collectors.toSet());
    boolean auditEnabled = Boolean.parseBoolean(props.getProperty("privacy.auditEnabled", "true"));

    PrivacySettings privacy =
        new PrivacySettings(
            mode,
            allowStackTraces,
            allowThreadNames,
            allowEventValues,
            sensitivePatterns,
            auditEnabled);

    int timeoutSeconds = Integer.parseInt(props.getProperty("timeoutSeconds", "30"));
    int maxTokens = Integer.parseInt(props.getProperty("maxTokens", "2048"));
    double temperature = Double.parseDouble(props.getProperty("temperature", "0.1"));
    boolean multiLevelEnabled =
        Boolean.parseBoolean(props.getProperty("multiLevelEnabled", "false"));

    return new LLMConfig(
        provider, endpoint, model, apiKey, privacy, timeoutSeconds, maxTokens, temperature,
        multiLevelEnabled);
  }

  /**
   * Converts this configuration to properties.
   *
   * @return properties representation
   */
  private Properties toProperties() {
    Properties props = new Properties();
    props.setProperty("provider", provider.name());
    props.setProperty("endpoint", endpoint);
    props.setProperty("model", model);
    if (apiKey != null) {
      props.setProperty("apiKey", apiKey);
    }

    props.setProperty("privacy.mode", privacy.mode.name());
    props.setProperty("privacy.allowStackTraces", String.valueOf(privacy.allowStackTraces));
    props.setProperty("privacy.allowThreadNames", String.valueOf(privacy.allowThreadNames));
    props.setProperty("privacy.allowEventValues", String.valueOf(privacy.allowEventValues));
    props.setProperty("privacy.sensitivePatterns", String.join(",", privacy.sensitivePatterns));
    props.setProperty("privacy.auditEnabled", String.valueOf(privacy.auditEnabled));

    props.setProperty("timeoutSeconds", String.valueOf(timeoutSeconds));
    props.setProperty("maxTokens", String.valueOf(maxTokens));
    props.setProperty("temperature", String.valueOf(temperature));
    props.setProperty("multiLevelEnabled", String.valueOf(multiLevelEnabled));

    return props;
  }
}
