package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Help/usage text agreement (D5): the generic help's {@code connect} row uses the {@code NAME}
 * placeholder (the alias is an arbitrary name, not a number), matching the dispatcher help, {@code
 * help connect}, and {@code doc/cli/Usage.md}.
 */
class ShellHelpTextTest {

  @Test
  void connectRowUsesNamePlaceholderAlignedWithSiblingRows() {
    StringWriter out = new StringWriter();
    Shell.printHelp(new PrintWriter(out, true));
    String rendered = out.toString();
    assertTrue(rendered.contains("connect [<pid>] [--alias NAME]"), rendered);
    assertFalse(rendered.contains("[--alias N]"), rendered);
  }
}
