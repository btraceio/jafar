package io.jafar.mcp.tools;

import io.jafar.mcp.model.AnalysisResult;
import io.jafar.mcp.service.JfrAnalysisService;
import io.jafar.mcp.service.JfrSessionService;
import io.jafar.shell.core.SessionManager.SessionRef;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for pre-built JFR analyses.
 *
 * <p>Provides convenient shortcuts for common analysis patterns.
 */
@Component
public class AnalysisTools {

  private final JfrSessionService sessionService;
  private final JfrAnalysisService analysisService;

  public AnalysisTools(JfrSessionService sessionService, JfrAnalysisService analysisService) {
    this.sessionService = sessionService;
    this.analysisService = analysisService;
  }

  /**
   * Analyzes thread activity by execution samples.
   *
   * @param sessionIdOrAlias session ID or alias
   * @param topN number of top threads (default 10)
   * @return analysis result
   */
  @McpTool(
      description =
          "Analyzes thread activity by execution samples. Shows which threads consumed the most CPU"
              + " time based on sampling data.")
  public AnalysisResult jfr_analyze_threads(
      @McpToolParam(description = "Session ID or alias to analyze", required = true)
          String sessionIdOrAlias,
      @McpToolParam(description = "Number of top threads to return (default: 10)", required = false)
          Integer topN) {
    SessionRef ref = sessionService.getSession(sessionIdOrAlias);
    return analysisService.analyzeThreads(ref, topN != null ? topN : 10);
  }

  /**
   * Analyzes garbage collection pause statistics.
   *
   * @param sessionIdOrAlias session ID or alias
   * @return analysis result with GC statistics
   */
  @McpTool(
      description =
          "Analyzes garbage collection pauses. Returns statistics including min, max, average,"
              + " standard deviation, and percentiles (p50, p90, p99) for GC pause durations.")
  public AnalysisResult jfr_analyze_gc(
      @McpToolParam(description = "Session ID or alias to analyze", required = true)
          String sessionIdOrAlias) {
    SessionRef ref = sessionService.getSession(sessionIdOrAlias);
    return analysisService.analyzeGC(ref);
  }

  /**
   * Analyzes CPU hotspots by method samples.
   *
   * @param sessionIdOrAlias session ID or alias
   * @param topN number of top methods (default 10)
   * @return analysis result
   */
  @McpTool(
      description =
          "Analyzes CPU hotspots by profiling execution samples. Shows the top methods consuming"
              + " CPU time based on stack trace sampling.")
  public AnalysisResult jfr_analyze_cpu(
      @McpToolParam(description = "Session ID or alias to analyze", required = true)
          String sessionIdOrAlias,
      @McpToolParam(description = "Number of top methods to return (default: 10)", required = false)
          Integer topN) {
    SessionRef ref = sessionService.getSession(sessionIdOrAlias);
    return analysisService.analyzeCPU(ref, topN != null ? topN : 10);
  }

  /**
   * Analyzes memory allocations by class.
   *
   * @param sessionIdOrAlias session ID or alias
   * @param topN number of top classes (default 10)
   * @return analysis result
   */
  @McpTool(
      description =
          "Analyzes memory allocations by class. Shows which classes are responsible for the most"
              + " memory allocation based on allocation sampling data.")
  public AnalysisResult jfr_analyze_allocations(
      @McpToolParam(description = "Session ID or alias to analyze", required = true)
          String sessionIdOrAlias,
      @McpToolParam(
              description = "Number of top allocating classes to return (default: 10)",
              required = false)
          Integer topN) {
    SessionRef ref = sessionService.getSession(sessionIdOrAlias);
    return analysisService.analyzeAllocations(ref, topN != null ? topN : 10);
  }
}
