package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.widgets.tree.GuideStyle;
import dev.tamboui.widgets.tree.TreeNode;
import dev.tamboui.widgets.tree.TreeState;
import dev.tamboui.widgets.tree.TreeWidget;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Smoke test to verify TamboUI inline rendering API works. */
class TamboUIApiTest {

  @Test
  void tableRendersToBuffer() {
    Table table =
        Table.builder()
            .header(Row.from("Name", "Age"))
            .rows(List.of(Row.from("Alice", "30"), Row.from("Bob", "25")))
            .widths(Constraint.percentage(50), Constraint.fill())
            .columnSpacing(1)
            .build();

    Buffer buffer = Buffer.empty(Rect.of(40, 6));
    Frame frame = Frame.forTesting(buffer);
    frame.renderStatefulWidget(table, buffer.area(), new TableState());

    String output = buffer.toAnsiStringTrimmed();
    assertNotNull(output);
    assertFalse(output.isBlank());
    assertTrue(output.contains("Name"));
    assertTrue(output.contains("Alice"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void treeRendersToBuffer() {
    // Use TreeNode with children (auto-expanded)
    TreeNode<Object> child1 = TreeNode.of("sampledThread: Thread");
    TreeNode<Object> child2 = TreeNode.of("stackTrace: StackTrace");
    TreeNode<Object> root = TreeNode.of("jdk.ExecutionSample", child1, child2).expanded();

    TreeWidget<TreeNode<Object>> tree =
        TreeWidget.<TreeNode<Object>>builder()
            .roots(List.of(root))
            .children(n -> (List<TreeNode<Object>>) (List<?>) n.children())
            .isLeaf(n -> n.children().isEmpty())
            .expansionState(
                TreeNode::isExpanded,
                (n, expanded) -> {
                  if (expanded != n.isExpanded()) n.toggleExpanded();
                })
            .guideStyle(GuideStyle.UNICODE)
            .simpleNodeRenderer(n -> Paragraph.builder().text(Text.raw(n.label())).build())
            .build();

    Buffer buffer = Buffer.empty(Rect.of(60, 10));
    Frame frame = Frame.forTesting(buffer);
    frame.renderStatefulWidget(tree, buffer.area(), new TreeState());

    String output = buffer.toAnsiStringTrimmed();
    assertNotNull(output);
    assertFalse(output.isBlank());
    assertTrue(output.contains("jdk.ExecutionSample"));
    assertTrue(output.contains("sampledThread"));
  }
}
