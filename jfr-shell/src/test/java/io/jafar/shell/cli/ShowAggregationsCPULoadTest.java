package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShowAggregationsCPULoadTest {

  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      out.append(s).append('\n');
    }

    String text() {
      return out.toString();
    }

    void clear() {
      out.setLength(0);
    }
  }

  @Test
  void statsAndQuantilesOnCPULoad() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    BufferIO io = new BufferIO();
    CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, r -> {});

    dispatcher.dispatch("show events/jdk.CPULoad/machineTotal | stats()");
    String statsOut = io.text();
    assertTrue(statsOut.contains("avg"), "Expected avg in stats output\n" + statsOut);
    assertTrue(statsOut.contains("stddev"), "Expected stddev in stats output\n" + statsOut);

    io.clear();
    dispatcher.dispatch("show events/jdk.CPULoad/machineTotal | quantiles(0.5,0.9)");
    String qOut = io.text();
    assertTrue(qOut.contains("p50"), "Expected p50 in quantiles output\n" + qOut);
    assertTrue(qOut.contains("p90"), "Expected p90 in quantiles output\n" + qOut);
    // sanity: extract a numeric and ensure it's within [0,1.5]
    Pattern p = Pattern.compile("\\|\\s*p50\\s*\\|\\s*([0-9]+\\.?[0-9]*)\\s*\\|");
    Matcher m = p.matcher(qOut);
    if (m.find()) {
      double v = Double.parseDouble(m.group(1));
      assertTrue(v >= 0.0 && v <= 1.5, "p50 outside expected range: " + v);
    }
  }
}
