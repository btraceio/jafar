package io.jafar.otlp.shell.cli.completion;

import io.jafar.otlp.shell.OtlpSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.completion.MetadataService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Metadata service for otlp completion. Provides field names from the active OTLP session. */
public final class OtlpMetadataService implements MetadataService {

  private static final List<String> ROOT_TYPES = List.of("samples");

  private static final List<String> OPERATORS =
      List.of(
          "count",
          "top",
          "groupBy",
          "stats",
          "head",
          "tail",
          "filter",
          "select",
          "sortBy",
          "stackprofile",
          "distinct");

  /**
   * Static field paths always available in an OTLP sample, independent of value types. Includes
   * representative stackTrace sub-field paths for frames 0 and 1.
   */
  private static final List<String> STATIC_FIELDS =
      List.of(
          "sampleType",
          "stackTrace",
          "stackTrace/0/name",
          "stackTrace/0/filename",
          "stackTrace/0/line",
          "stackTrace/1/name",
          "stackTrace/1/filename",
          "thread");

  private final SessionManager<OtlpSession> sessions;

  @SuppressWarnings("unchecked")
  public OtlpMetadataService(SessionManager<?> sessions) {
    this.sessions = (SessionManager<OtlpSession>) sessions;
  }

  @Override
  public boolean hasActiveSession() {
    return getOtlpSession() != null;
  }

  @Override
  public Path getFilePath() {
    OtlpSession session = getOtlpSession();
    return session != null ? session.getFilePath() : null;
  }

  @Override
  public Set<String> getAvailableTypes() {
    OtlpSession session = getOtlpSession();
    return session != null ? session.getAvailableTypes() : Collections.emptySet();
  }

  /**
   * Returns field names for OTLP samples. Returns static fields plus the sample type name from the
   * active session.
   */
  @Override
  public List<String> getFieldNames(String typeName) {
    OtlpSession session = getOtlpSession();
    if (session == null) {
      return STATIC_FIELDS;
    }
    List<String> fields = new ArrayList<>(STATIC_FIELDS);
    fields.addAll(getValueTypeNames());
    return Collections.unmodifiableList(fields);
  }

  /** Returns only the value type names from the active session (no structural fields). */
  public List<String> getValueTypeNames() {
    OtlpSession session = getOtlpSession();
    if (session == null) {
      return Collections.emptyList();
    }
    List<String> names = new ArrayList<>();
    for (var profile : session.getData().profiles()) {
      var st = profile.sampleType();
      if (st != null && !st.type().isEmpty() && !names.contains(st.type())) {
        names.add(st.type());
      }
    }
    return Collections.unmodifiableList(names);
  }

  @Override
  public List<String> getRootTypes() {
    return ROOT_TYPES;
  }

  @Override
  public List<String> getOperators() {
    return OPERATORS;
  }

  @Override
  public Set<String> getVariableNames() {
    return Collections.emptySet();
  }

  @Override
  public void invalidateCache() {
    // No caching — fields are derived directly from the active session on each call
  }

  private OtlpSession getOtlpSession() {
    return sessions.current().map(ref -> ref.session).orElse(null);
  }
}
