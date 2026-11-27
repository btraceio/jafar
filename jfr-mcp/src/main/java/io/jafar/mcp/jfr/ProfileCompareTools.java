package io.jafar.mcp.jfr;

import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.otlp.shell.OtlpProfileReader;
import io.jafar.otlp.shell.OtlpSession;
import io.jafar.pprof.shell.PprofProfileReader;
import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.JfrCompare;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code profile_compare} — compares a candidate profile against a baseline when either side may be
 * a pprof or an OTLP profile: {@code jfr_compare}'s comparison ({@link ProfileCompare}), with each
 * side named by its format and its session id.
 *
 * <p>A separate tool rather than new arguments on {@code jfr_compare}: that tool's response is a
 * documented contract, and session ids alone cannot name a side, because each format's registry
 * numbers its sessions from 1.
 */
public final class ProfileCompareTools {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileCompareTools.class);

  private final SessionRegistry jfrSessions;
  private final PprofSessionRegistry pprofSessions;
  private final OtlpSessionRegistry otlpSessions;
  private final McpResultFactory resultFactory;
  private final JfrCompare jfrReader;

  /** Without OTLP sessions: an {@code otlp} side then finds no session. */
  public ProfileCompareTools(
      SessionRegistry jfrSessions,
      PprofSessionRegistry pprofSessions,
      QueryEvaluator evaluator,
      McpResultFactory resultFactory) {
    this(jfrSessions, pprofSessions, new OtlpSessionRegistry(), evaluator, resultFactory);
  }

  public ProfileCompareTools(
      SessionRegistry jfrSessions,
      PprofSessionRegistry pprofSessions,
      OtlpSessionRegistry otlpSessions,
      QueryEvaluator evaluator,
      McpResultFactory resultFactory) {
    this.jfrSessions = jfrSessions;
    this.pprofSessions = pprofSessions;
    this.otlpSessions = otlpSessions;
    this.resultFactory = resultFactory;
    // Through the evaluator this server was given, as jfr_compare reads: a test substitutes it.
    this.jfrReader = new JfrCompare(JfrAnalysisTools.querySource(evaluator));
  }

  public McpServerFeatures.SyncToolSpecification createProfileCompareTool() {
    String side =
        """
            {
              "type": "object",
              "properties": {
                "format": {
                  "type": "string",
                  "enum": ["jfr", "pprof", "otlp"],
                  "description": "The side's format: jfr (opened with jfr_open), pprof (opened with pprof_open) or otlp (opened with otlp_open)."
                },
                "sessionId": {
                  "type": "string",
                  "description": "Session ID or alias in that format's sessions. Defaults to that format's current session."
                },
                "sampleType": {
                  "type": "string",
                  "description": "This side's sample type: a JFR event type, or a pprof or OTLP type or type/unit (for OTLP, the profiles of that sample type). Overrides the top-level sampleType."
                }
              },
              "required": ["format"]
            }
        """;
    String schema =
        String.format(
            Locale.ROOT,
            """
        {
          "type": "object",
          "properties": {
            "baseline": %s,
            "candidate": %s,
            "sampleType": {
              "type": "string",
              "description": "pprof or OTLP sample type (type or type/unit) for every pprof and OTLP side. Default: the first CPU sample type, else the first. Ignored for JFR sides, which take their own sampleType or detect their execution-sample event type."
            },
            "minDeltaPct": {
              "type": "number",
              "description": "Noise floor in percentage points for per-frame changes (default: 1.0)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of changed frames per list (default: 25)"
            },
            "inclusiveFrames": {
              "type": "boolean",
              "description": "Also compare inclusive (total-time) frame shares (default: false)"
            },
            "allowMixedSamplers": {
              "type": "boolean",
              "description": "With a pprof or OTLP side, compare samplers of different kinds (CPU against wall clock) instead of refusing; no change is then called significant. Allocation against CPU or wall is always refused. Two JFR sides are never refused (they get a note), so this has no effect there. Default: false"
            }
          },
          "required": ["baseline", "candidate"]
        }
        """,
            side.strip(),
            side.strip());

    return new McpServerFeatures.SyncToolSpecification(
        Tool.builder()
            .name("profile_compare")
            .description(
                "Compares a candidate profile against a baseline where either side may be a JFR"
                    + " recording, a pprof profile or an OTLP profiles file: per-frame self-time"
                    + " shares (and inclusive"
                    + " shares on request), normalised to each side's total, with jfr_compare's"
                    + " significance rule and findings. When a pprof or OTLP side is involved,"
                    + " samplers"
                    + " that measure different things (CPU against wall clock) are refused unless"
                    + " allowMixedSamplers, and allocation against CPU or wall always; two JFR"
                    + " sides get jfr_compare's note instead, as jfr-shell compare does. Event rates"
                    + " only when both sides are JFR. The top-level sampleType applies to pprof"
                    + " and OTLP sides only; a JFR side takes its event type from its own"
                    + " sampleType, or detects it. Open each side with jfr_open, pprof_open or"
                    + " otlp_open first.")
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, args) -> handleProfileCompare(args.arguments()));
  }

  public CallToolResult handleProfileCompare(Map<String, Object> args) {
    double minDeltaPct =
        args.get("minDeltaPct") instanceof Number n
            ? n.doubleValue()
            : ProfileCompare.DEFAULT_MIN_DELTA_PCT;
    int limit = args.get("limit") instanceof Number n ? n.intValue() : ProfileCompare.DEFAULT_LIMIT;
    boolean inclusive = Boolean.TRUE.equals(args.get("inclusiveFrames"));
    boolean allowMixed = Boolean.TRUE.equals(args.get("allowMixedSamplers"));
    String sampleType = args.get("sampleType") instanceof String s ? s : null;
    if (limit <= 0) {
      return resultFactory.error("limit must be positive");
    }
    try {
      Side baseline = side(args.get("baseline"), "baseline");
      Side candidate = side(args.get("candidate"), "candidate");
      if (baseline.format.equals(candidate.format) && baseline.id == candidate.id) {
        return resultFactory.error(
            "Baseline and candidate are the same "
                + baseline.format
                + " session ("
                + baseline.id
                + "). Open the second file and pass both session ids.");
      }
      RecordingProfile base = baseline.read(sampleType, inclusive);
      RecordingProfile cand = candidate.read(sampleType, inclusive);
      CompareResult result =
          ProfileCompare.compare(
              base,
              cand,
              new CompareOptions(
                  null,
                  minDeltaPct,
                  limit,
                  inclusive,
                  CompareOptions.MixedSamplers.forPair(base.format(), cand.format(), allowMixed)));
      return resultFactory.success(
          result.toMap(
              baseline.describe(result.baseline()), candidate.describe(result.candidate())));
    } catch (IllegalArgumentException e) {
      LOG.warn("profile_compare error: {}", e.getMessage());
      return resultFactory.error(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to compare profiles: {}", e.getMessage(), e);
      return resultFactory.error("Failed to compare profiles: " + e.getMessage());
    }
  }

  /** One side, resolved to its open session. */
  private final class Side {
    final String format;
    final int id;
    final String alias;
    final String sampleType;
    final SessionRegistry.SessionInfo jfr;
    final SamplingSessionRegistry.SessionInfo<PprofSession> pprof;
    final SamplingSessionRegistry.SessionInfo<OtlpSession> otlp;

    Side(
        String format,
        String sampleType,
        SessionRegistry.SessionInfo jfr,
        SamplingSessionRegistry.SessionInfo<PprofSession> pprof,
        SamplingSessionRegistry.SessionInfo<OtlpSession> otlp) {
      this.format = format;
      this.sampleType = sampleType;
      this.jfr = jfr;
      this.pprof = pprof;
      this.otlp = otlp;
      if (jfr != null) {
        this.id = jfr.id();
        this.alias = jfr.alias();
      } else if (pprof != null) {
        this.id = pprof.id();
        this.alias = pprof.alias();
      } else {
        this.id = otlp.id();
        this.alias = otlp.alias();
      }
    }

    RecordingProfile read(String defaultSampleType, boolean inclusive) throws Exception {
      if (jfr != null) {
        // The top-level sampleType is for pprof sides; a JFR side takes only its own.
        return jfrReader.profile(
            new AnalysisTarget(jfr.id(), jfr.recordingPath(), jfr.session()),
            sampleType,
            inclusive);
      }
      ProfileSelection selection =
          new ProfileSelection(sampleType != null ? sampleType : defaultSampleType, inclusive);
      if (otlp != null) {
        return OtlpProfileReader.read(otlp.session().getData(), otlp.path(), selection);
      }
      return PprofProfileReader.read(pprof.session().getProfile(), pprof.path(), selection);
    }

    /** The core's description, with this side's format, session id and alias in front. */
    Map<String, Object> describe(RecordingProfile profile) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("format", format);
      map.put("sessionId", id);
      if (alias != null) {
        map.put("alias", alias);
      }
      map.putAll(CompareResult.describe(profile));
      return map;
    }
  }

  private Side side(Object value, String which) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          which + " is required: an object {format: jfr|pprof|otlp, sessionId}");
    }
    Object formatValue = map.get("format");
    if (!(formatValue instanceof String format) || format.isBlank()) {
      throw new IllegalArgumentException(which + ".format is required: jfr, pprof or otlp");
    }
    String sessionId = map.get("sessionId") == null ? null : String.valueOf(map.get("sessionId"));
    String sampleType = map.get("sampleType") instanceof String s && !s.isBlank() ? s : null;
    return switch (format.toLowerCase(Locale.ROOT)) {
      case "jfr" -> new Side("jfr", sampleType, jfrSessions.getOrCurrent(sessionId), null, null);
      case "pprof" ->
          new Side("pprof", sampleType, null, pprofSessions.getOrCurrent(sessionId), null);
      case "otlp" -> new Side("otlp", sampleType, null, null, otlpSessions.getOrCurrent(sessionId));
      default ->
          throw new IllegalArgumentException(
              which + ".format must be jfr, pprof or otlp, not " + format);
    };
  }
}
