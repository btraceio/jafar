package io.jafar.shell.llm.tuning;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import io.jafar.shell.llm.ContextBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Represents a variation of the system prompt with specific modifications. Allows testing
 * different prompt strategies to improve LLM query generation.
 */
public class PromptVariant {
  private final String id;
  private final String description;
  private final PromptModifications modifications;

  public PromptVariant(String id, String description, PromptModifications modifications) {
    this.id = id;
    this.description = description;
    this.modifications = modifications;
  }

  public String getId() {
    return id;
  }

  public String getDescription() {
    return description;
  }

  public PromptModifications getModifications() {
    return modifications;
  }

  /**
   * Loads all prompt variants from a JSON file.
   *
   * @param path path to the variants JSON file
   * @return list of prompt variants
   * @throws IOException if file cannot be read or parsed
   */
  public static List<PromptVariant> loadAll(String path) throws IOException {
    return loadAll(Path.of(path));
  }

  /**
   * Loads all prompt variants from a JSON file.
   *
   * @param path path to the variants JSON file
   * @return list of prompt variants
   * @throws IOException if file cannot be read or parsed
   */
  public static List<PromptVariant> loadAll(Path path) throws IOException {
    String json = Files.readString(path);
    Gson gson = new Gson();
    VariantsData data = gson.fromJson(json, VariantsData.class);

    List<PromptVariant> variants = new ArrayList<>();
    for (VariantData vd : data.variants) {
      PromptModifications mods =
          new PromptModifications(
              Optional.ofNullable(vd.modifications.customGrammar),
              Optional.ofNullable(vd.modifications.customExamples),
              Optional.ofNullable(vd.modifications.customRules),
              Optional.ofNullable(vd.modifications.customFormat),
              vd.modifications.llmParams != null ? vd.modifications.llmParams : Map.of());
      variants.add(new PromptVariant(vd.id, vd.description, mods));
    }
    return variants;
  }

  /**
   * Builds a modified system prompt by applying this variant's modifications to the base context
   * builder.
   *
   * @param baseBuilder the base context builder
   * @return modified system prompt
   */
  public String buildSystemPrompt(ContextBuilder baseBuilder) {
    // If no modifications, return the base prompt
    if (modifications.customGrammar().isEmpty()
        && modifications.customExamples().isEmpty()
        && modifications.customRules().isEmpty()
        && modifications.customFormat().isEmpty()) {
      return baseBuilder.buildSystemPrompt();
    }

    // Build modified prompt by replacing sections
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are an expert JFR (Java Flight Recording) analysis assistant. ");
    prompt.append(
        "Your role is to translate natural language questions into valid JfrPath queries.\n\n");

    // Grammar section
    if (modifications.customGrammar().isPresent()) {
      prompt.append(modifications.customGrammar().get());
    } else {
      prompt.append(baseBuilder.buildJfrPathGrammar());
    }
    prompt.append("\n\n");

    // Event types
    prompt.append("AVAILABLE EVENT TYPES:\n");
    prompt.append(baseBuilder.buildEventTypesList());
    prompt.append("\n\n");

    // Session context
    prompt.append("CURRENT SESSION:\n");
    prompt.append(baseBuilder.buildSessionContext());
    prompt.append("\n\n");

    // Examples section
    if (modifications.customExamples().isPresent()) {
      prompt.append(modifications.customExamples().get());
    } else {
      prompt.append(baseBuilder.buildExamples());
    }
    prompt.append("\n\n");

    // Response format section
    if (modifications.customFormat().isPresent()) {
      prompt.append(modifications.customFormat().get());
    } else {
      prompt.append(baseBuilder.buildResponseFormat());
    }
    prompt.append("\n\n");

    // Rules section
    if (modifications.customRules().isPresent()) {
      prompt.append(modifications.customRules().get());
    } else {
      prompt.append(baseBuilder.buildRules());
    }

    return prompt.toString();
  }

  /**
   * Modifications that can be applied to a prompt variant. Includes custom sections and LLM
   * parameters.
   */
  public record PromptModifications(
      Optional<String> customGrammar,
      Optional<String> customExamples,
      Optional<String> customRules,
      Optional<String> customFormat,
      Map<String, Object> llmParams) {}

  /** Internal classes for JSON deserialization. */
  private static class VariantsData {
    @SerializedName("variants")
    List<VariantData> variants;
  }

  private static class VariantData {
    @SerializedName("id")
    String id;

    @SerializedName("description")
    String description;

    @SerializedName("modifications")
    ModificationsData modifications;
  }

  private static class ModificationsData {
    @SerializedName("customGrammar")
    String customGrammar;

    @SerializedName("customExamples")
    String customExamples;

    @SerializedName("customRules")
    String customRules;

    @SerializedName("customFormat")
    String customFormat;

    @SerializedName("llmParams")
    Map<String, Object> llmParams;
  }
}
