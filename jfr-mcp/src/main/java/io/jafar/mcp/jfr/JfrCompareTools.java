package io.jafar.mcp.jfr;

import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.JfrCompare;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jfr_compare} — compares a candidate recording against a baseline.
 *
 * <p>"Is this build slower than the last one, and where?" was previously unanswerable in a single
 * call: JfrPath has no cross-recording join, so a comparison meant running every analysis twice and
 * diffing the numbers by hand, which invites the classic mistake of comparing raw counts between
 * recordings of different lengths.
 *
 * <p>This tool normalises before it compares. Event counts become per-second rates using each
 * recording's own observed span, and stack frames are compared as a percentage of that recording's
 * samples rather than as sample counts, so two recordings profiled at different sampling intervals
 * remain comparable. Where the two recordings are not safely comparable at all — different
 * execution-sample event types, or wildly different durations — the result says so in {@code
 * comparability} instead of quietly producing a plausible number. A frame is only called a
 * regression when its move is significant given both recordings' sample counts; anything less is
 * {@code inconclusive}, and a recording too thin to judge at all says so in the findings.
 *
 * <p>The comparison itself lives in shell-core ({@link JfrCompare}), so {@code jfr-shell compare}
 * can run it headless; this class is the MCP adapter around it: session ids in, JSON out.
 */
public final class JfrCompareTools {

  private static final Logger LOG = LoggerFactory.getLogger(JfrCompareTools.class);

  private final SessionRegistry sessionRegistry;
  private final McpResultFactory resultFactory;
  private final JfrCompare compare;

  /**
   * @param queryParser unused since the comparison moved to shell-core, which parses its one query
   *     ({@code events/<type>}) with {@code JfrPathParser} as the other core analyses do; kept so
   *     the constructor is unchanged
   * @param analysisTools unused for the same reason: the core reaches the same analyses through the
   *     evaluator
   */
  public JfrCompareTools(
      SessionRegistry sessionRegistry,
      QueryEvaluator evaluator,
      QueryParser queryParser,
      McpResultFactory resultFactory,
      JfrAnalysisTools analysisTools) {
    this.sessionRegistry = sessionRegistry;
    this.resultFactory = resultFactory;
    // Through the evaluator this server was given, not one of the core's own: a test substitutes
    // it.
    this.compare = new JfrCompare(JfrAnalysisTools.querySource(evaluator));
  }

  public McpServerFeatures.SyncToolSpecification createJfrCompareTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "baselineSessionId": {
              "type": "string",
              "description": "Session ID or alias of the baseline (the 'before' recording). Required."
            },
            "candidateSessionId": {
              "type": "string",
              "description": "Session ID or alias of the candidate (the 'after' recording). Defaults to the current session."
            },
            "eventType": {
              "type": "string",
              "description": "Execution sample event type. Auto-detected per recording when omitted."
            },
            "minDeltaPct": {
              "type": "number",
              "description": "Noise floor in percentage points for per-frame changes (default: 1.0)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of changed frames to return (default: 25)"
            }
          },
          "required": ["baselineSessionId"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        Tool.builder()
            .name("jfr_compare")
            .description(
                "Compares a candidate JFR recording against a baseline and reports what changed: "
                    + "event rates per second, GC and exception rates, and per-frame CPU self-time "
                    + "shares. Normalises for recording duration and sampling rate, flags changes "
                    + "below the noise floor as insignificant, and warns when the two recordings "
                    + "are not comparable. Use for before/after regression checks; open both "
                    + "recordings with jfr_open first.")
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, args) -> handleJfrCompare(exchange, args.arguments()));
  }

  public CallToolResult handleJfrCompare(McpSyncServerExchange exchange, Map<String, Object> args) {
    String baselineId = (String) args.get("baselineSessionId");
    String candidateId = (String) args.get("candidateSessionId");
    String eventType = (String) args.get("eventType");
    double minDeltaPct =
        args.get("minDeltaPct") instanceof Number n
            ? n.doubleValue()
            : JfrCompare.DEFAULT_MIN_DELTA_PCT;
    int limit = args.get("limit") instanceof Number n ? n.intValue() : JfrCompare.DEFAULT_LIMIT;

    if (baselineId == null || baselineId.isBlank()) {
      return resultFactory.error("baselineSessionId is required");
    }
    if (limit <= 0) {
      return resultFactory.error("limit must be positive");
    }

    try {
      SessionRegistry.SessionInfo baseline = sessionRegistry.getOrCurrent(baselineId);
      SessionRegistry.SessionInfo candidate = sessionRegistry.getOrCurrent(candidateId);

      if (baseline.id() == candidate.id()) {
        return resultFactory.error(
            "Baseline and candidate are the same session ("
                + baseline.id()
                + "). Open the second recording with jfr_open and pass both session ids.");
      }

      // Self time only: inclusive shares are not part of jfr_compare's response.
      CompareResult result =
          compare.compare(
              target(baseline),
              target(candidate),
              new CompareOptions(eventType, minDeltaPct, limit, false));
      return resultFactory.success(
          result.toMap(
              describe(baseline, result.baseline()), describe(candidate, result.candidate())));

    } catch (IllegalArgumentException e) {
      LOG.warn("Compare error: {}", e.getMessage());
      return resultFactory.error(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to compare recordings: {}", e.getMessage(), e);
      return resultFactory.error("Failed to compare recordings: " + e.getMessage());
    }
  }

  private static AnalysisTarget target(SessionRegistry.SessionInfo info) {
    return new AnalysisTarget(info.id(), info.recordingPath(), info.session());
  }

  /** The core's description of one side, with this server's session id and alias in front. */
  private static Map<String, Object> describe(
      SessionRegistry.SessionInfo info, io.jafar.shell.core.compare.RecordingProfile profile) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("sessionId", info.id());
    if (info.alias() != null) {
      map.put("alias", info.alias());
    }
    map.putAll(CompareResult.describe(profile));
    return map;
  }
}
