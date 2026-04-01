package io.jafar.pprof.shell.cli.completion;

import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.completion.MetadataService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Metadata service for pprof completion. Provides field names from the active pprof session. */
public final class PprofMetadataService implements MetadataService {

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
   * Static field paths always available in a pprof sample, independent of value types. Includes
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
          "thread",
          "goroutine");

  private final SessionManager<PprofSession> sessions;

  @SuppressWarnings("unchecked")
  public PprofMetadataService(SessionManager<?> sessions) {
    this.sessions = (SessionManager<PprofSession>) sessions;
  }

  @Override
  public boolean hasActiveSession() {
    return getPprofSession() != null;
  }

  @Override
  public Path getFilePath() {
    PprofSession session = getPprofSession();
    return session != null ? session.getFilePath() : null;
  }

  @Override
  public Set<String> getAvailableTypes() {
    PprofSession session = getPprofSession();
    return session != null ? session.getAvailableTypes() : Collections.emptySet();
  }

  /**
   * Returns field names for pprof samples. The {@code typeName} parameter is ignored since pprof
   * has only one root type ({@code samples}). Returns static fields plus value type names from the
   * active session.
   */
  @Override
  public List<String> getFieldNames(String typeName) {
    PprofSession session = getPprofSession();
    if (session == null) {
      return STATIC_FIELDS;
    }
    List<String> fields = new ArrayList<>(STATIC_FIELDS);
    for (var vt : session.getProfile().sampleTypes()) {
      String name = vt.type();
      if (name != null && !name.isEmpty()) {
        fields.add(name);
      }
    }
    return Collections.unmodifiableList(fields);
  }

  /** Returns only the value type names from the active session (no structural fields). */
  public List<String> getValueTypeNames() {
    PprofSession session = getPprofSession();
    if (session == null) {
      return Collections.emptyList();
    }
    List<String> names = new ArrayList<>();
    for (var vt : session.getProfile().sampleTypes()) {
      String name = vt.type();
      if (name != null && !name.isEmpty()) {
        names.add(name);
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

  private PprofSession getPprofSession() {
    return sessions.current().map(ref -> ref.session).orElse(null);
  }
}
