package io.jafar.shell.cli;

import io.jafar.shell.core.FlameNode;

/** Renders a {@link FlameNode} call tree as an interactive HTML flamegraph. */
public final class FlameGraphRenderer {

  private FlameGraphRenderer() {}

  public static void render(FlameNode root, CommandDispatcher.IO io) {
    if (root == null || root.value == 0) {
      io.println("(no samples)");
      return;
    }
    try {
      FlameGraphHtmlRenderer.render(root, io);
    } catch (Exception e) {
      io.error("Failed to write flamegraph: " + e.getMessage());
    }
  }
}
