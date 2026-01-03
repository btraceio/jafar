/**
 * Validators for checking completion contexts and candidates are correct.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides validation logic that checks completion results against expected
 * behavior. Validators analyze completion contexts, candidate lists, and universal invariants to
 * ensure the completion system works correctly across all scenarios.
 *
 * <h2>Key Classes</h2>
 *
 * <ul>
 *   <li>{@link io.jafar.shell.cli.completion.property.validators.CompletionContextValidator}:
 *       Validates that completion contexts match expected values based on query structure and
 *       cursor position
 *   <li>{@link io.jafar.shell.cli.completion.property.validators.CandidateValidator}: Validates
 *       that completion candidates are appropriate for the given context (e.g., only valid fields
 *       suggested)
 *   <li>{@link io.jafar.shell.cli.completion.property.validators.CompletionInvariants}: Defines
 *       universal invariants that must hold for all completion scenarios (e.g., no duplicates, no
 *       nulls)
 * </ul>
 *
 * <h2>Validation Layers</h2>
 *
 * <p>The validation framework operates at three levels:
 *
 * <ol>
 *   <li><b>Structural</b>: Never crash, always return valid objects (checked by
 *       CompletionInvariants)
 *   <li><b>Semantic</b>: Context type matches query structure (checked by
 *       CompletionContextValidator)
 *   <li><b>Candidate</b>: Suggestions are appropriate for context (checked by CandidateValidator)
 * </ol>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>{@code
 * // Create validators
 * CompletionContextValidator contextValidator = new CompletionContextValidator();
 * CandidateValidator candidateValidator = new CandidateValidator(metadataService);
 * CompletionInvariants invariants = new CompletionInvariants();
 *
 * // Generate query and invoke completion
 * GeneratedQuery query = ...;
 * List<Candidate> candidates = invokeCompletion(query);
 * CompletionContext context = analyzeContext(query);
 *
 * // Validate context
 * ValidationResult contextResult = contextValidator.validateContext(query, context);
 * if (!contextResult.isValid()) {
 *     System.err.println("Context validation failed: " + contextResult.getReport());
 * }
 *
 * // Validate candidates
 * ValidationResult candidateResult = candidateValidator.validateForContext(context, candidates);
 * if (!candidateResult.isValid()) {
 *     System.err.println("Candidate validation failed: " + candidateResult.getReport());
 * }
 *
 * // Check invariants
 * ValidationResult invariantResult = invariants.checkAllInvariants(context, candidates);
 * if (!invariantResult.isValid()) {
 *     System.err.println("Invariant violations: " + invariantResult.getReport());
 * }
 * }</pre>
 *
 * <h2>Validation Results</h2>
 *
 * <p>All validators return {@link io.jafar.shell.cli.completion.property.models.ValidationResult}
 * objects that accumulate errors and warnings:
 *
 * <ul>
 *   <li><b>Errors</b>: Serious violations that indicate bugs (e.g., wrong context type, invalid
 *       candidates)
 *   <li><b>Warnings</b>: Potential issues that may be intentional (e.g., candidates not matching
 *       partial input)
 * </ul>
 *
 * <h2>Context Validation</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.validators.CompletionContextValidator}
 * analyzes query structure using token-based parsing to determine what completion context should be
 * detected. It validates:
 *
 * <ul>
 *   <li>Context type matches query structure
 *   <li>Event type is correctly identified
 *   <li>Field path is accurately extracted
 *   <li>Function name is recognized (if in function context)
 * </ul>
 *
 * <h2>Candidate Validation</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.validators.CandidateValidator} performs
 * context-specific validation using real JFR metadata:
 *
 * <ul>
 *   <li><b>ROOT</b>: Validates candidates are in {events, metadata, cp, chunks}
 *   <li><b>EVENT_TYPE</b>: Validates candidates are actual event types from metadata
 *   <li><b>FIELD_PATH</b>: Validates candidates are valid fields for the event type
 *   <li><b>FILTER_OPERATOR</b>: Validates candidates are valid comparison operators
 *   <li><b>PIPELINE_OPERATOR</b>: Validates candidates are recognized functions
 * </ul>
 *
 * <h2>Universal Invariants</h2>
 *
 * <p>The {@link io.jafar.shell.cli.completion.property.validators.CompletionInvariants} class
 * defines rules that must hold for all completion scenarios:
 *
 * <ul>
 *   <li>No duplicate candidates
 *   <li>All candidates are non-null
 *   <li>All candidate values are non-empty
 *   <li>Candidates match partial input (when applicable)
 *   <li>Context type determination is deterministic
 *   <li>Candidate counts are reasonable (not 0, not >200)
 * </ul>
 *
 * @see io.jafar.shell.cli.completion.property.PropertyBasedCompletionTests
 * @see io.jafar.shell.cli.completion.property.models.ValidationResult
 * @since 0.4.0
 */
package io.jafar.shell.cli.completion.property.validators;
