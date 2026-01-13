package io.jafar.mcp.service;

import io.jafar.mcp.exception.JfrMcpException;
import io.jafar.mcp.model.AnalysisResult;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Service providing pre-built JFR analysis patterns.
 *
 * <p>Each method executes a hardcoded JfrPath query for common analysis scenarios.
 */
@Service
public class JfrAnalysisService {

  private final JfrPathEvaluator evaluator;

  public JfrAnalysisService(JfrPathEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  /**
   * Analyzes thread activity by execution samples.
   *
   * @param session JFR session
   * @param topN number of top threads to return
   * @return analysis result
   */
  public AnalysisResult analyzeThreads(SessionRef session, int topN) {
    String query =
        String.format(
            "events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(%d, by=count)",
            topN);
    return executeAnalysis("threads", query, session);
  }

  /**
   * Analyzes garbage collection pauses.
   *
   * @param session JFR session
   * @return analysis result with GC statistics
   */
  public AnalysisResult analyzeGC(SessionRef session) {
    String query = "events/jdk.GarbageCollection | stats(duration)";
    return executeAnalysis("gc", query, session);
  }

  /**
   * Analyzes CPU hotspots by method samples.
   *
   * @param session JFR session
   * @param topN number of top methods to return
   * @return analysis result
   */
  public AnalysisResult analyzeCPU(SessionRef session, int topN) {
    String query =
        String.format(
            "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) |"
                + " top(%d, by=count)",
            topN);
    return executeAnalysis("cpu", query, session);
  }

  /**
   * Analyzes memory allocations by class.
   *
   * @param session JFR session
   * @param topN number of top classes to return
   * @return analysis result
   */
  public AnalysisResult analyzeAllocations(SessionRef session, int topN) {
    String query =
        String.format(
            "events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight)"
                + " | top(%d, by=sum)",
            topN);
    return executeAnalysis("allocations", query, session);
  }

  /**
   * Executes an analysis query and wraps result.
   *
   * @param analysisType analysis type identifier
   * @param queryString JfrPath query
   * @param session JFR session
   * @return analysis result
   */
  private AnalysisResult executeAnalysis(
      String analysisType, String queryString, SessionRef session) {
    try {
      JfrPath.Query query = JfrPathParser.parse(queryString);
      List<Map<String, Object>> results = evaluator.evaluate(session.session, query);
      return AnalysisResult.success(analysisType, queryString, results);
    } catch (Exception e) {
      throw new JfrMcpException(
          "Analysis failed: " + e.getMessage() + " (query: " + queryString + ")", e);
    }
  }
}
