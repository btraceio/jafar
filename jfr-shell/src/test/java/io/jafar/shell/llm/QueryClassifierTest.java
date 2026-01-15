package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class QueryClassifierTest {
  private LLMProvider mockProvider;
  private QueryClassifier classifier;
  private Gson gson;

  @BeforeEach
  void setUp() {
    mockProvider = mock(LLMProvider.class);
    classifier = new QueryClassifier(mockProvider);
    gson = new Gson();
  }

  // ===== METADATA_QUERY Tests =====

  @Test
  void testMetadataQuery_WhatFields() {
    var result = classifier.classifyByRules("what fields does ExecutionSample have?");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.METADATA_QUERY, result.get().category());
    assertEquals(0.95, result.get().confidence(), 0.001);
    assertFalse(result.get().usedLLM());
  }

  @Test
  void testMetadataQuery_Describe() {
    var result = classifier.classifyByRules("describe the structure of GarbageCollection");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.METADATA_QUERY, result.get().category());
    assertEquals(0.95, result.get().confidence(), 0.001);
  }

  @Test
  void testMetadataQuery_ShowMetadata() {
    var result = classifier.classifyByRules("show metadata for FileRead");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.METADATA_QUERY, result.get().category());
  }

  @Test
  void testMetadataQuery_ListFields() {
    var result = classifier.classifyByRules("list the fields in jdk.ThreadPark");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.METADATA_QUERY, result.get().category());
  }

  // ===== EXISTENCE_CHECK Tests =====

  @Test
  void testExistenceCheck_IsPresent() {
    var result = classifier.classifyByRules("is ExecutionSample present?");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.EXISTENCE_CHECK, result.get().category());
    assertEquals(0.92, result.get().confidence(), 0.001);
  }

  @Test
  void testExistenceCheck_AreThereAny() {
    var result = classifier.classifyByRules("are there any GC events in the recording?");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.EXISTENCE_CHECK, result.get().category());
  }

  @Test
  void testExistenceCheck_DoesExist() {
    var result = classifier.classifyByRules("does jdk.FileWrite exist?");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.EXISTENCE_CHECK, result.get().category());
  }

  @Test
  void testExistenceCheck_Did() {
    var result = classifier.classifyByRules("did any native memory tracking events get recorded");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.EXISTENCE_CHECK, result.get().category());
  }

  // ===== TOPN_RANKING Tests =====

  @Test
  void testTopNRanking_TopWithNumber() {
    var result = classifier.classifyByRules("top 10 allocating classes");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
    assertEquals(0.90, result.get().confidence(), 0.001);
  }

  @Test
  void testTopNRanking_Hottest() {
    var result = classifier.classifyByRules("hottest methods by CPU time");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testTopNRanking_Most() {
    var result = classifier.classifyByRules("most frequent GC events");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testTopNRanking_Slowest() {
    var result = classifier.classifyByRules("slowest file operations");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testTopNRanking_Longest() {
    var result = classifier.classifyByRules("longest running methods");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testTopNRanking_Largest() {
    var result = classifier.classifyByRules("largest memory allocations");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  // ===== STATISTICS Tests =====

  @Test
  void testStatistics_Average() {
    var result = classifier.classifyByRules("average GC pause time");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
    assertEquals(0.89, result.get().confidence(), 0.001);
  }

  @Test
  void testStatistics_Mean() {
    var result = classifier.classifyByRules("mean allocation rate");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testStatistics_Total() {
    var result = classifier.classifyByRules("total bytes allocated");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testStatistics_Sum() {
    var result = classifier.classifyByRules("sum of file read sizes");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testStatistics_Min() {
    var result = classifier.classifyByRules("minimum GC duration");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testStatistics_Max() {
    var result = classifier.classifyByRules("maximum thread CPU time");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  // ===== DECORATOR_CORRELATION Tests =====

  @Test
  void testDecoratorCorrelation_RequestId() {
    var result = classifier.classifyByRules("show samples with requestId abc123");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_CORRELATION, result.get().category());
    assertEquals(0.88, result.get().confidence(), 0.001);
  }

  @Test
  void testDecoratorCorrelation_SpanId() {
    var result = classifier.classifyByRules("find events with spanId matching xyz");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_CORRELATION, result.get().category());
  }

  @Test
  void testDecoratorCorrelation_TraceId() {
    var result = classifier.classifyByRules("get all events for traceId 12345");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_CORRELATION, result.get().category());
  }

  @Test
  void testDecoratorCorrelation_TransactionId() {
    var result = classifier.classifyByRules("show transactionId correlations");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_CORRELATION, result.get().category());
  }

  // ===== DECORATOR_TEMPORAL Tests =====

  @Test
  void testDecoratorTemporal_During() {
    var result = classifier.classifyByRules("methods executed during GC pauses");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
    assertEquals(0.87, result.get().confidence(), 0.001);
  }

  @Test
  void testDecoratorTemporal_While() {
    var result = classifier.classifyByRules("samples taken while thread was blocked");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
  }

  @Test
  void testDecoratorTemporal_When() {
    var result = classifier.classifyByRules("file operations when pinning occurred");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
  }

  @Test
  void testDecoratorTemporal_Causing() {
    var result = classifier.classifyByRules("what was causing the high CPU usage");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
  }

  @Test
  void testDecoratorTemporal_Triggered() {
    var result = classifier.classifyByRules("events triggered by class loading");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
  }

  // ===== GROUPBY_AGGREGATED Tests =====

  @Test
  void testGroupByAggregated_CountBy() {
    var result = classifier.classifyByRules("count events by thread");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_AGGREGATED, result.get().category());
    assertEquals(0.86, result.get().confidence(), 0.001);
  }

  @Test
  void testGroupByAggregated_TotalBy() {
    // "total X by Y" matches STATISTICS pattern first (higher priority)
    var result = classifier.classifyByRules("total bytes by class name");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testGroupByAggregated_SumBy() {
    // "sum of X by Y" matches STATISTICS pattern first (higher priority)
    var result = classifier.classifyByRules("sum of allocations by package");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  @Test
  void testGroupByAggregated_AverageBy() {
    // "average X by Y" matches STATISTICS pattern first (higher priority)
    var result = classifier.classifyByRules("average duration by event type");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.STATISTICS, result.get().category());
  }

  // ===== GROUPBY_SIMPLE Tests =====

  @Test
  void testGroupBySimple_HowManyDifferent() {
    var result = classifier.classifyByRules("how many different threads are there?");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_SIMPLE, result.get().category());
    assertEquals(0.88, result.get().confidence(), 0.001);
  }

  @Test
  void testGroupBySimple_HowManyUnique() {
    var result = classifier.classifyByRules("how many unique classes allocated memory");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_SIMPLE, result.get().category());
  }

  @Test
  void testGroupBySimple_UniqueThreads() {
    var result = classifier.classifyByRules("unique thread names");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_SIMPLE, result.get().category());
  }

  @Test
  void testGroupBySimple_DistinctValues() {
    var result = classifier.classifyByRules("distinct event types in recording");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_SIMPLE, result.get().category());
  }

  // ===== TIME_RANGE_FILTER Tests =====

  @Test
  void testTimeRangeFilter_LongerThan() {
    var result = classifier.classifyByRules("GC pauses longer than 50ms");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
    assertEquals(0.85, result.get().confidence(), 0.001);
  }

  @Test
  void testTimeRangeFilter_Between() {
    var result = classifier.classifyByRules("events between 10:00 and 11:00");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  @Test
  void testTimeRangeFilter_After() {
    var result = classifier.classifyByRules("samples after timestamp 12345");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  @Test
  void testTimeRangeFilter_Before() {
    var result = classifier.classifyByRules("allocations before JIT warmup");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  @Test
  void testTimeRangeFilter_Duration() {
    var result = classifier.classifyByRules("file reads with duration over 100ms");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  // ===== SIMPLE_FILTER Tests =====

  @Test
  void testSimpleFilter_ShowWhere() {
    var result = classifier.classifyByRules("show file reads where bytes > 1MB");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.SIMPLE_FILTER, result.get().category());
    assertEquals(0.84, result.get().confidence(), 0.001);
  }

  @Test
  void testSimpleFilter_ListOver() {
    var result = classifier.classifyByRules("list allocations over 1KB");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.SIMPLE_FILTER, result.get().category());
  }

  @Test
  void testSimpleFilter_GetAbove() {
    // "above Xms duration" matches TIME_RANGE_FILTER due to "duration" keyword
    var result = classifier.classifyByRules("get GC events above 10ms duration");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  @Test
  void testSimpleFilter_FindBelow() {
    var result = classifier.classifyByRules("find threads with CPU below 5%");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.SIMPLE_FILTER, result.get().category());
  }

  // ===== SIMPLE_COUNT Tests =====

  @Test
  void testSimpleCount_CountEvents() {
    var result = classifier.classifyByRules("count GC events");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.SIMPLE_COUNT, result.get().category());
    assertEquals(0.91, result.get().confidence(), 0.001);
  }

  @Test
  void testSimpleCount_HowManyTotal() {
    var result = classifier.classifyByRules("how many total file reads");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.SIMPLE_COUNT, result.get().category());
  }

  // ===== Priority and Edge Cases =====

  @Test
  void testPriority_MetadataOverFilter() {
    // "what fields" should match METADATA_QUERY, not SIMPLE_FILTER
    var result = classifier.classifyByRules("what fields are in ExecutionSample");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.METADATA_QUERY, result.get().category());
  }

  @Test
  void testPriority_ExistenceOverCount() {
    // "is X present" should match EXISTENCE_CHECK, not SIMPLE_COUNT
    var result = classifier.classifyByRules("is GC present in recording");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.EXISTENCE_CHECK, result.get().category());
  }

  @Test
  void testPriority_CorrelationOverTemporal() {
    // requestId should match DECORATOR_CORRELATION, not DECORATOR_TEMPORAL
    var result = classifier.classifyByRules("events during request with requestId abc");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_CORRELATION, result.get().category());
  }

  @Test
  void testPriority_TopNOverStatistics() {
    // "top N" should match TOPN_RANKING, not STATISTICS even if has aggregation
    var result = classifier.classifyByRules("top 5 threads by total CPU time");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testPriority_GroupByAggregatedOverSimple() {
    // "count by" should match GROUPBY_AGGREGATED, not GROUPBY_SIMPLE
    var result = classifier.classifyByRules("count allocations by thread");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.GROUPBY_AGGREGATED, result.get().category());
  }

  @Test
  void testEdgeCase_EmptyString() {
    var result = classifier.classifyByRules("");

    assertFalse(result.isPresent()); // Should fall through to LLM
  }

  @Test
  void testEdgeCase_WhitespaceOnly() {
    var result = classifier.classifyByRules("   \t\n  ");

    assertFalse(result.isPresent()); // Should fall through to LLM
  }

  @Test
  void testEdgeCase_MixedCase() {
    var result = classifier.classifyByRules("ToP 10 HoTTeST mEtHoDs");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TOPN_RANKING, result.get().category());
  }

  @Test
  void testEdoratorTemporal_NotTimeRangeFilter() {
    // "during" should match DECORATOR_TEMPORAL, not TIME_RANGE_FILTER
    var result = classifier.classifyByRules("samples during GC pauses");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.DECORATOR_TEMPORAL, result.get().category());
  }

  @Test
  void testTimeRangeFilter_NotDecoratorTemporal() {
    // "longer than" without "during/while" should match TIME_RANGE_FILTER
    var result = classifier.classifyByRules("file reads longer than 100ms");

    assertTrue(result.isPresent());
    assertEquals(QueryCategory.TIME_RANGE_FILTER, result.get().category());
  }

  // ===== LLM Fallback Tests =====

  @Test
  void testLLMFallback_AmbiguousQuery() throws LLMException {
    String ambiguousQuery = "show me the data";

    // Should not match any rule-based pattern
    var ruleResult = classifier.classifyByRules(ambiguousQuery);
    assertFalse(ruleResult.isPresent());

    // Mock LLM response
    JsonObject jsonResponse = new JsonObject();
    jsonResponse.addProperty("category", "SIMPLE_FILTER");
    jsonResponse.addProperty("confidence", 0.65);
    jsonResponse.addProperty("reasoning", "Generic display query, likely needs filtering");

    LLMProvider.LLMResponse mockResponse =
        new LLMProvider.LLMResponse(gson.toJson(jsonResponse), "test-model", 70, 500);
    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(mockResponse);

    // Classify using LLM
    var result = classifier.classifyByLLM(ambiguousQuery);

    assertEquals(QueryCategory.SIMPLE_FILTER, result.category());
    assertEquals(0.65, result.confidence(), 0.001);
    assertTrue(result.reasoning().isPresent());
    assertTrue(result.usedLLM());

    // Verify LLM was called with correct parameters
    ArgumentCaptor<LLMProvider.LLMRequest> captor =
        ArgumentCaptor.forClass(LLMProvider.LLMRequest.class);
    verify(mockProvider)
        .completeStructured(
            captor.capture(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION));

    LLMProvider.LLMRequest request = captor.getValue();
    assertNotNull(request.systemPrompt());
    assertEquals(1, request.messages().size());
    assertEquals(LLMProvider.Role.USER, request.messages().get(0).role());
    assertEquals(0.1, request.options().get("temperature")); // Low temp for classification
    assertTrue(request.messages().get(0).content().contains(ambiguousQuery));
  }

  @Test
  void testLLMFallback_ConversationalQuery() throws LLMException {
    String conversationalQuery = "hello, how are you?";

    // Mock LLM response
    JsonObject jsonResponse = new JsonObject();
    jsonResponse.addProperty("category", "CONVERSATIONAL");
    jsonResponse.addProperty("confidence", 0.98);
    jsonResponse.addProperty("reasoning", "Greeting, not a data query");

    LLMProvider.LLMResponse mockResponse =
        new LLMProvider.LLMResponse(gson.toJson(jsonResponse), "test-model", 55, 400);
    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(mockResponse);

    var result = classifier.classifyByLLM(conversationalQuery);

    assertEquals(QueryCategory.CONVERSATIONAL, result.category());
    assertEquals(0.98, result.confidence(), 0.001);
    assertTrue(result.usedLLM());
  }

  @Test
  void testClassify_UsesRulesFirst() throws LLMException {
    // High-confidence rule-based match should not call LLM
    var result = classifier.classify("top 10 allocating classes");

    assertEquals(QueryCategory.TOPN_RANKING, result.category());
    assertEquals(0.90, result.confidence(), 0.001);
    assertFalse(result.usedLLM());

    // Verify LLM was NOT called
    verify(mockProvider, never()).completeStructured(any(), any());
  }

  @Test
  void testClassify_FallsBackToLLM() throws LLMException {
    String ambiguousQuery = "analyze the performance";

    // Mock LLM response
    JsonObject jsonResponse = new JsonObject();
    jsonResponse.addProperty("category", "COMPLEX_MULTIOP");
    jsonResponse.addProperty("confidence", 0.55);
    jsonResponse.addProperty("reasoning", "Vague query requiring multiple operations");

    LLMProvider.LLMResponse mockResponse =
        new LLMProvider.LLMResponse(gson.toJson(jsonResponse), "test-model", 85, 600);
    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(mockResponse);

    var result = classifier.classify(ambiguousQuery);

    assertEquals(QueryCategory.COMPLEX_MULTIOP, result.category());
    assertTrue(result.usedLLM());

    // Verify LLM was called
    verify(mockProvider, times(1))
        .completeStructured(any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION));
  }

  @Test
  void testLLMFallback_InvalidJSON() throws LLMException {
    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse("not valid json", "test-model", 10, 100));

    assertThrows(LLMException.class, () -> classifier.classifyByLLM("test query"));
  }

  @Test
  void testLLMFallback_MissingFields() throws LLMException {
    JsonObject incomplete = new JsonObject();
    incomplete.addProperty("category", "SIMPLE_COUNT");
    // Missing confidence field

    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(gson.toJson(incomplete), "test-model", 10, 100));

    assertThrows(LLMException.class, () -> classifier.classifyByLLM("test query"));
  }

  @Test
  void testLLMFallback_InvalidCategory() throws LLMException {
    JsonObject invalid = new JsonObject();
    invalid.addProperty("category", "INVALID_CATEGORY");
    invalid.addProperty("confidence", 0.9);

    when(mockProvider.completeStructured(
            any(), eq(io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION)))
        .thenReturn(new LLMProvider.LLMResponse(gson.toJson(invalid), "test-model", 10, 100));

    assertThrows(LLMException.class, () -> classifier.classifyByLLM("test query"));
  }

  // ===== Confidence Threshold Tests =====

  @ParameterizedTest
  @CsvSource({
    "0.95, true, false, false",
    "0.85, true, false, false",
    "0.75, false, true, false",
    "0.60, false, true, false",
    "0.50, false, false, true",
    "0.30, false, false, true"
  })
  void testConfidenceThresholds(
      double confidence, boolean isHigh, boolean isMedium, boolean isLow) {
    var result = ClassificationResult.fromRules(QueryCategory.SIMPLE_COUNT, confidence);

    assertEquals(isHigh, result.isHighConfidence());
    assertEquals(isMedium, result.isMediumConfidence());
    assertEquals(isLow, result.isLowConfidence());
  }

  @Test
  void testNeedsClarification() {
    var highConfidence = ClassificationResult.fromRules(QueryCategory.SIMPLE_COUNT, 0.8);
    assertFalse(highConfidence.needsClarification());

    var lowConfidence = ClassificationResult.fromRules(QueryCategory.COMPLEX_MULTIOP, 0.4);
    assertTrue(lowConfidence.needsClarification());
  }
}
