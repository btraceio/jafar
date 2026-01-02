package io.jafar.shell.cli.completion.property.validators;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextType;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.property.models.ValidationResult;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Validates that completion candidates are appropriate for the given context.
 *
 * <p>This validator checks that:
 * - Candidates match the completion context type
 * - Field names exist in metadata
 * - Operators are valid
 * - Function names are recognized
 * - Candidates match any partial input
 */
public class CandidateValidator {

  private final MetadataService metadata;

  public CandidateValidator(MetadataService metadata) {
    this.metadata = metadata;
  }

  /**
   * Validates candidates for a given completion context.
   *
   * @param context the completion context
   * @param candidates the list of candidates to validate
   * @return validation result with errors and warnings
   */
  public ValidationResult validateForContext(
      CompletionContext context, List<Candidate> candidates) {

    ValidationResult result = new ValidationResult();

    // Context-specific validation
    switch (context.type()) {
      case ROOT:
        validateRootCandidates(candidates, result);
        break;
      case EVENT_TYPE:
        validateEventTypeCandidates(candidates, context, result);
        break;
      case FIELD_PATH:
        validateFieldPathCandidates(candidates, context, result);
        break;
      case FILTER_FIELD:
        validateFilterFieldCandidates(candidates, context, result);
        break;
      case FILTER_OPERATOR:
        validateFilterOperatorCandidates(candidates, result);
        break;
      case FILTER_LOGICAL:
        validateFilterLogicalCandidates(candidates, result);
        break;
      case PIPELINE_OPERATOR:
        validatePipelineOperatorCandidates(candidates, result);
        break;
      case FUNCTION_PARAM:
        validateFunctionParamCandidates(candidates, context, result);
        break;
      case CHUNK_ID:
        validateChunkIdCandidates(candidates, result);
        break;
      case COMMAND_OPTION:
        validateCommandOptionCandidates(candidates, result);
        break;
      case METADATA_SUBPROP:
        validateMetadataSubpropCandidates(candidates, result);
        break;
      default:
        result.addWarning("Unknown context type: " + context.type());
    }

    // Universal validation: candidates should match partial input
    validatePartialMatch(candidates, context, result);

    return result;
  }

  // ==================== Context-Specific Validators ====================

  /**
   * Validates root candidates (events, metadata, cp, chunks).
   */
  private void validateRootCandidates(List<Candidate> candidates, ValidationResult result) {
    Set<String> validRoots = Set.of("events", "metadata", "cp", "chunks");

    for (Candidate c : candidates) {
      String root = extractRoot(c.value());
      if (!validRoots.contains(root)) {
        result.addError("Invalid root in candidate: '" + root + "' from '" + c.value() + "'");
      }
    }

    // Check for minimum expected roots
    if (candidates.isEmpty()) {
      result.addWarning("Expected at least one root candidate");
    }
  }

  /**
   * Validates event type candidates.
   */
  private void validateEventTypeCandidates(
      List<Candidate> candidates, CompletionContext context, ValidationResult result) {

    String rootType = context.rootType();
    Set<String> validTypes;

    if ("events".equals(rootType)) {
      validTypes = metadata.getEventTypes();
    } else if ("metadata".equals(rootType)) {
      validTypes = metadata.getAllMetadataTypes();
    } else if ("cp".equals(rootType)) {
      validTypes = metadata.getConstantPoolTypes();
    } else {
      result.addWarning("Unknown root type for event type validation: " + rootType);
      return;
    }

    if (validTypes.isEmpty()) {
      result.addWarning("No metadata available for validation");
      return;
    }

    for (Candidate c : candidates) {
      String typeName = extractTypeName(c.value());
      if (!validTypes.contains(typeName)) {
        result.addError(
            "Invalid " + rootType + " type: '" + typeName + "' in candidate '" + c.value() + "'");
      }
    }
  }

  /**
   * Validates field path candidates.
   */
  private void validateFieldPathCandidates(
      List<Candidate> candidates, CompletionContext context, ValidationResult result) {

    String eventType = context.eventType();
    if (eventType == null || eventType.isEmpty()) {
      result.addWarning("No event type in context for field path validation");
      return;
    }

    String rootType = context.rootType();
    List<String> fieldPath = context.fieldPath();

    // Special handling for metadata types - "fields" is a valid subproperty
    if ("metadata".equals(rootType) && fieldPath.isEmpty()) {
      // At metadata/Type/ level, "fields" is valid
      for (Candidate c : candidates) {
        String fieldName = extractFieldName(c.value());
        if ("fields".equals(fieldName)) {
          continue; // "fields" is a valid metadata subproperty
        }
      }
      // Don't validate further for metadata types at this level
      return;
    }

    List<String> validFields = metadata.getNestedFieldNames(eventType, fieldPath);

    if (validFields.isEmpty()) {
      result.addWarning("No fields available for " + eventType + " at path " + fieldPath);
      return;
    }

    for (Candidate c : candidates) {
      String fieldName = extractFieldName(c.value());
      if (!validFields.contains(fieldName)) {
        result.addError(
            "Invalid field '"
                + fieldName
                + "' for "
                + eventType
                + " at path "
                + fieldPath
                + " in candidate '"
                + c.value()
                + "'");
      }
    }
  }

  /**
   * Validates filter field candidates.
   */
  private void validateFilterFieldCandidates(
      List<Candidate> candidates, CompletionContext context, ValidationResult result) {

    // Same as field path validation
    validateFieldPathCandidates(candidates, context, result);
  }

  /**
   * Validates filter operator candidates.
   */
  private void validateFilterOperatorCandidates(
      List<Candidate> candidates, ValidationResult result) {

    Set<String> validOperators = Set.of("==", "!=", ">", ">=", "<", "<=", "~");

    for (Candidate c : candidates) {
      String op = c.value().trim();
      if (!validOperators.contains(op)) {
        result.addError("Invalid operator: '" + op + "'");
      }
    }
  }

  /**
   * Validates filter logical operator candidates.
   */
  private void validateFilterLogicalCandidates(
      List<Candidate> candidates, ValidationResult result) {

    Set<String> validLogical = Set.of("&&", "||", "]"); // ] to close filter

    for (Candidate c : candidates) {
      String op = c.value().trim();
      if (!validLogical.contains(op) && !op.contains("]")) {
        result.addError("Invalid logical operator: '" + op + "'");
      }
    }
  }

  /**
   * Validates pipeline operator candidates.
   */
  private void validatePipelineOperatorCandidates(
      List<Candidate> candidates, ValidationResult result) {

    Set<String> validFunctions =
        Set.of(
            "count", "sum", "groupBy", "top", "stats", "quantiles", "select", "sketch",
            "len", "uppercase", "lowercase", "trim", "abs", "round", "floor", "ceil",
            "contains", "replace", "decorateByTime", "decorateByKey");

    for (Candidate c : candidates) {
      String functionName = extractFunctionName(c.value());
      if (!validFunctions.contains(functionName)) {
        result.addWarning("Unknown function in candidate: '" + functionName + "'");
      }
    }

    if (candidates.isEmpty()) {
      result.addWarning("Expected pipeline operator candidates");
    }
  }

  /**
   * Validates function parameter candidates.
   */
  private void validateFunctionParamCandidates(
      List<Candidate> candidates, CompletionContext context, ValidationResult result) {

    String functionName = (String) context.extras().get("functionName");
    if (functionName == null) {
      result.addWarning("No function name in context");
      return;
    }

    // For field-based functions, validate fields
    if (Set.of("sum", "groupBy", "select").contains(functionName)) {
      String eventType = context.eventType();
      if (eventType != null) {
        List<String> validFields = metadata.getFieldNames(eventType);
        if (!validFields.isEmpty()) {
          for (Candidate c : candidates) {
            String fieldName = c.value().trim();
            if (!validFields.contains(fieldName) && !fieldName.isEmpty()) {
              result.addWarning(
                  "Field '"
                      + fieldName
                      + "' not found in "
                      + eventType
                      + " for "
                      + functionName
                      + "()");
            }
          }
        }
      }
    }
  }

  /**
   * Validates chunk ID candidates.
   */
  private void validateChunkIdCandidates(List<Candidate> candidates, ValidationResult result) {
    List<Integer> validChunks = metadata.getChunkIds();

    if (validChunks.isEmpty()) {
      result.addWarning("No chunk IDs available");
      return;
    }

    for (Candidate c : candidates) {
      try {
        String idStr = c.value().replaceAll(".*/(\\d+).*", "$1");
        int id = Integer.parseInt(idStr);
        if (!validChunks.contains(id)) {
          result.addWarning("Chunk ID " + id + " not in valid chunks: " + validChunks);
        }
      } catch (NumberFormatException e) {
        result.addError("Invalid chunk ID format in candidate: '" + c.value() + "'");
      }
    }
  }

  /**
   * Validates command option candidates.
   */
  private void validateCommandOptionCandidates(
      List<Candidate> candidates, ValidationResult result) {

    for (Candidate c : candidates) {
      if (!c.value().startsWith("--")) {
        result.addError("Command option should start with '--': '" + c.value() + "'");
      }
    }
  }

  /**
   * Validates metadata subproperty candidates (e.g., "fields").
   */
  private void validateMetadataSubpropCandidates(
      List<Candidate> candidates, ValidationResult result) {

    // Currently only "fields" is valid
    Set<String> validSubprops = Set.of("fields");

    for (Candidate c : candidates) {
      String subprop = c.value().replaceAll(".*/", "");
      if (!validSubprops.contains(subprop)) {
        result.addWarning("Unknown metadata subproperty: '" + subprop + "'");
      }
    }
  }

  // ==================== Universal Validators ====================

  /**
   * Validates that candidates match the partial input from context.
   */
  private void validatePartialMatch(
      List<Candidate> candidates, CompletionContext context, ValidationResult result) {

    String partial = context.partialInput();
    if (partial == null || partial.isEmpty()) {
      return; // No partial input to match
    }

    String partialLower = partial.toLowerCase();

    for (Candidate c : candidates) {
      String relevantPortion = extractRelevantPortion(c.value()).toLowerCase();
      if (!relevantPortion.startsWith(partialLower)) {
        result.addWarning(
            "Candidate '"
                + c.value()
                + "' (relevant: '"
                + relevantPortion
                + "') doesn't match partial '"
                + partial
                + "'");
      }
    }
  }

  // ==================== Extraction Helpers ====================

  /**
   * Extracts root type from a candidate value.
   */
  private String extractRoot(String value) {
    if (value.contains("/")) {
      return value.substring(0, value.indexOf("/"));
    }
    return value;
  }

  /**
   * Extracts type name from a candidate value (after first slash).
   */
  private String extractTypeName(String value) {
    if (!value.contains("/")) {
      return value;
    }
    String afterRoot = value.substring(value.indexOf("/") + 1);
    if (afterRoot.contains("/")) {
      return afterRoot.substring(0, afterRoot.indexOf("/"));
    }
    return afterRoot;
  }

  /**
   * Extracts field name from a candidate value (after last slash).
   */
  private String extractFieldName(String value) {
    if (value.contains("/")) {
      return value.substring(value.lastIndexOf("/") + 1);
    }
    return value;
  }

  /**
   * Extracts function name from a candidate value (before opening paren).
   */
  private String extractFunctionName(String value) {
    if (value.contains("(")) {
      return value.substring(0, value.indexOf("("));
    }
    return value;
  }

  /**
   * Extracts the relevant portion of a candidate for matching against partial input.
   */
  private String extractRelevantPortion(String value) {
    // For paths, extract the last segment
    if (value.contains("/")) {
      return value.substring(value.lastIndexOf("/") + 1);
    }
    // For functions, extract the name
    if (value.contains("(")) {
      return value.substring(0, value.indexOf("("));
    }
    return value;
  }
}
