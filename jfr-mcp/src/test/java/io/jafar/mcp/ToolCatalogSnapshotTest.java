package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Pins the full MCP guidance contract — tool names, descriptions, input schemas, prompts and
 * resources — to the committed {@code src/test/resources/mcp-contract.json}.
 *
 * <p>Tool descriptions are prompts: they are the only thing an agent routes by, and a change to one
 * changes agent behaviour in ways no unit test notices. The snapshot turns every such change into a
 * visible diff that must be consciously accepted, the same discipline applied to prompt text
 * anywhere else. It is also the artifact the weekly Tool-drift check of the jafar-perf plugin
 * (btraceio/agent-plugins, scripts/check-tool-references.js) diffs its skills against.
 *
 * <p>To accept a change, review the diff, update any skill file in btraceio/agent-plugins
 * (plugins/jafar-perf) that names the changed tool or parameter, then run:
 *
 * <pre>./gradlew :jfr-mcp:test --tests ToolCatalogSnapshotTest -Dmcp.updateSnapshot=true</pre>
 */
class ToolCatalogSnapshotTest {

  private static final Path SNAPSHOT = Path.of("src/test/resources/mcp-contract.json");

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void guidanceContractMatchesCommittedSnapshot() throws Exception {
    String current;
    try (GuidanceContractContext ignored = GuidanceContractContext.create()) {
      current = McpContractJson.render(new GuidanceSurfaces());
    }

    if (Boolean.getBoolean("mcp.updateSnapshot")) {
      String committed = Files.exists(SNAPSHOT) ? Files.readString(SNAPSHOT) : "";
      if (!current.equals(committed)) {
        Files.createDirectories(SNAPSHOT.getParent());
        Files.writeString(SNAPSHOT, current);
        System.out.println("Updated " + SNAPSHOT + " — review the diff before committing.");
      }
      return;
    }

    assertTrue(
        Files.exists(SNAPSHOT),
        "snapshot missing: " + SNAPSHOT + " — run this test with -Dmcp.updateSnapshot=true");
    String committed = Files.readString(SNAPSHOT);

    assertEquals(
        McpContractJson.parse(committed),
        McpContractJson.parse(current),
        () ->
            "The MCP guidance contract changed. Every MCP client and the agent-plugins jafar-perf"
                + " skills experience this diff. Review it, update any jafar-perf skill that names the"
                + " changed tool or parameter, then accept it with"
                + " -Dmcp.updateSnapshot=true.\n"
                + previewDiff(committed, current));
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void exportedContractMatchesCommittedSnapshot(@TempDir Path tempDir) throws Exception {
    Path output = tempDir.resolve("mcp-contract.json");
    McpContractExport.main(new String[] {output.toString()});

    assertEquals(
        McpContractJson.parse(Files.readString(SNAPSHOT)),
        McpContractJson.parse(Files.readString(output)),
        "exportMcpContract must write the same semantic artifact as the committed snapshot");
  }

  private static String previewDiff(String committed, String current) {
    // The full contract is large; surface WHICH entries differ (tools, prompts, resources) so the
    // failure message names them without scrolling through the whole JSON.
    var committedByKind = new java.util.HashMap<String, String>();
    var committedTree = McpContractJson.parse(committed);
    for (var kind : java.util.List.of("tools", "prompts", "resources")) {
      var key = "tools".equals(kind) ? "name" : ("resources".equals(kind) ? "uri" : "name");
      for (var entry : committedTree.path(kind)) {
        committedByKind.put(kind + ":" + entry.path(key).asString(), entry.toString());
      }
    }
    var changed = new java.util.ArrayList<String>();
    var currentTree = McpContractJson.parse(current);
    for (var kind : java.util.List.of("tools", "prompts", "resources")) {
      var key = "tools".equals(kind) ? "name" : ("resources".equals(kind) ? "uri" : "name");
      for (var entry : currentTree.path(kind)) {
        String name = kind + ":" + entry.path(key).asString();
        String old = committedByKind.remove(name);
        if (old == null) {
          changed.add(name + " (added)");
        } else if (!entry.toString().equals(old)) {
          changed.add(name + " (modified)");
        }
      }
    }
    for (String name : new java.util.TreeSet<>(committedByKind.keySet())) {
      changed.add(name + " (removed)");
    }
    return changed.isEmpty()
        ? "no top-level entry differs by toString comparison — compare raw JSON"
        : "changed entries: " + String.join(", ", changed);
  }
}
