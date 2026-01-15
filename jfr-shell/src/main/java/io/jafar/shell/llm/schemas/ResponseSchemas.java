package io.jafar.shell.llm.schemas;

import io.jafar.shell.llm.LLMProvider.JsonSchema;
import io.jafar.shell.llm.LLMProvider.PropertySchema;
import java.util.List;
import java.util.Map;

/**
 * JSON schemas for pipeline step responses. Each schema defines the expected structure for LLM
 * responses in different pipeline steps (classification, clarification, generation, repair).
 */
public class ResponseSchemas {

  /** Schema for query classification response. */
  public static final JsonSchema CLASSIFICATION =
      new JsonSchema(
          "ClassificationResponse",
          "Query classification result",
          Map.of(
              "category",
              new PropertySchema("string", "Query category enum value", Map.of()),
              "confidence",
              new PropertySchema(
                  "number", "Confidence 0.0-1.0", Map.of("minimum", 0.0, "maximum", 1.0)),
              "reasoning",
              new PropertySchema("string", "Brief reasoning for classification", Map.of())),
          List.of("category", "confidence", "reasoning"));

  /** Schema for clarification question response. */
  public static final JsonSchema CLARIFICATION =
      new JsonSchema(
          "ClarificationResponse",
          "Clarification question for ambiguous query",
          Map.of(
              "clarificationQuestion",
              new PropertySchema("string", "Single question to ask user", Map.of()),
              "suggestedChoices",
              new PropertySchema(
                  "array", "2-4 suggested answers", Map.of("items", Map.of("type", "string"))),
              "ambiguityScore",
              new PropertySchema(
                  "number", "Ambiguity score 0.0-1.0", Map.of("minimum", 0.0, "maximum", 1.0))),
          List.of("clarificationQuestion", "suggestedChoices", "ambiguityScore"));

  /** Schema for JfrPath query generation response. */
  public static final JsonSchema GENERATION =
      new JsonSchema(
          "GenerationResponse",
          "JfrPath query generation result",
          Map.of(
              "query",
              new PropertySchema("string", "JfrPath query string", Map.of()),
              "explanation",
              new PropertySchema("string", "1-2 sentence explanation", Map.of()),
              "confidence",
              new PropertySchema(
                  "number", "Confidence 0.0-1.0", Map.of("minimum", 0.0, "maximum", 1.0)),
              "warning",
              new PropertySchema("string", "Optional warning message", Map.of())),
          List.of("query", "explanation", "confidence"));

  /** Schema for query repair response. */
  public static final JsonSchema REPAIR =
      new JsonSchema(
          "RepairResponse",
          "Query repair result",
          Map.of(
              "query",
              new PropertySchema("string", "Repaired JfrPath query", Map.of()),
              "changes",
              new PropertySchema(
                  "array", "List of changes made", Map.of("items", Map.of("type", "string"))),
              "confidence",
              new PropertySchema(
                  "number", "Confidence 0.0-1.0", Map.of("minimum", 0.0, "maximum", 1.0)),
              "warning",
              new PropertySchema("string", "Optional warning", Map.of())),
          List.of("query", "changes", "confidence"));

  private ResponseSchemas() {
    // Utility class - no instantiation
  }
}
