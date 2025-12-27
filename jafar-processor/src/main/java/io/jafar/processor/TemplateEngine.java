package io.jafar.processor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple template engine for code generation.
 *
 * <p>Loads templates from resources and replaces placeholders in the form {{KEY}} with provided
 * values.
 */
class TemplateEngine {

  private final String templateContent;

  /**
   * Loads a template from resources.
   *
   * @param resourcePath the path to the template resource (e.g., "templates/HandlerTemplate.java")
   * @throws IOException if the template cannot be loaded
   */
  public TemplateEngine(String resourcePath) throws IOException {
    this.templateContent = loadTemplate(resourcePath);
  }

  /**
   * Renders the template by replacing all placeholders with provided values.
   *
   * @param values map of placeholder names to their replacement values
   * @return the rendered template
   */
  public String render(Map<String, String> values) {
    String result = templateContent;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String placeholder = "{{" + entry.getKey() + "}}";
      String value = entry.getValue() != null ? entry.getValue() : "";
      result = result.replace(placeholder, value);
    }
    return result;
  }

  /**
   * Builder for convenient template rendering.
   *
   * @return a new builder instance
   */
  public Builder builder() {
    return new Builder(this);
  }

  /**
   * Loads a template from the classpath.
   *
   * @param resourcePath the resource path
   * @return the template content
   * @throws IOException if the resource cannot be loaded
   */
  private String loadTemplate(String resourcePath) throws IOException {
    InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
    if (inputStream == null) {
      throw new IOException("Template not found: " + resourcePath);
    }

    StringBuilder content = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        content.append(line).append("\n");
      }
    }
    return content.toString();
  }

  /** Builder for fluent template value assignment. */
  public static class Builder {
    private final TemplateEngine engine;
    private final Map<String, String> values;

    private Builder(TemplateEngine engine) {
      this.engine = engine;
      this.values = new HashMap<>();
    }

    /**
     * Sets a template value.
     *
     * @param key the placeholder key (without {{}} delimiters)
     * @param value the replacement value
     * @return this builder
     */
    public Builder set(String key, String value) {
      values.put(key, value);
      return this;
    }

    /**
     * Renders the template with the accumulated values.
     *
     * @return the rendered template
     */
    public String render() {
      return engine.render(values);
    }
  }
}
