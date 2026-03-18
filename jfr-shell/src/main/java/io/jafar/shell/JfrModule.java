package io.jafar.shell;

import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jline.reader.Completer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell module for JFR analysis. Provides JFR file support for the Jafar shell.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class JfrModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(JfrModule.class);

  @Override
  public String getId() {
    return "jfr";
  }

  @Override
  public String getDisplayName() {
    return "JFR Analyzer";
  }

  @Override
  public Set<String> getSupportedExtensions() {
    return Set.of("jfr");
  }

  @Override
  public Session createSession(Path path, Object context) throws Exception {
    return new JFRSession(path, new io.jafar.shell.core.ParsingContext());
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new JfrQueryEvaluator();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Completer getCompleter(SessionManager<?> sessions, Object context) {
    CommandDispatcher dispatcher = context instanceof CommandDispatcher cd ? cd : null;
    return new ShellCompleter((SessionManager<JFRSession>) sessions, dispatcher);
  }

  @Override
  public List<String> getExamples() {
    return List.of(
        "show events/jdk.ExecutionSample | top(10)",
        "show events/jdk.GCPhasePause | stats(duration)",
        "show events/jdk.ObjectAllocationSample | groupBy(objectClass/name)",
        "show metadata",
        "show constants/jdk.types.Symbol | head(20)");
  }

  @Override
  public int getPriority() {
    return 0;
  }

  @Override
  public void initialize() {
    LOG.debug("JFR module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("JFR module shutdown");
  }
}
