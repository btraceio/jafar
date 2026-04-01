package io.jafar.shell.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A node in a flamegraph call tree. Each node tracks a method name and its sample count. */
public final class FlameNode {
  public final String name;
  public long value;
  public final Map<String, FlameNode> children = new LinkedHashMap<>();

  public FlameNode(String name) {
    this.name = name;
  }

  /**
   * Add a call path rooted at this node. Each call increments {@code value} for this node and every
   * child along the path. Iterative to avoid stack overflow on deep call stacks.
   */
  public void addPath(List<String> frames) {
    FlameNode current = this;
    current.value++;
    for (String frame : frames) {
      current = current.children.computeIfAbsent(frame, FlameNode::new);
      current.value++;
    }
  }
}
