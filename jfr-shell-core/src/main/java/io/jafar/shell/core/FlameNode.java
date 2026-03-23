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
   * Add a call path rooted at this node. Each call increments {@code value} and recurses into the
   * appropriate child.
   */
  public void addPath(List<String> frames) {
    value++;
    if (!frames.isEmpty()) {
      children
          .computeIfAbsent(frames.get(0), FlameNode::new)
          .addPath(frames.subList(1, frames.size()));
    }
  }
}
