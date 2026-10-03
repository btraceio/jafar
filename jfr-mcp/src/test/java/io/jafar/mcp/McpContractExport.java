package io.jafar.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the MCP guidance contract JSON to a path, for consumers outside the test suite.
 *
 * <p>Run via {@code ./gradlew :jfr-mcp:exportMcpContract}. The output is byte-identical to the
 * committed snapshot ({@code src/test/resources/mcp-contract.json}), so the jafar-perf plugin in
 * btraceio/agent-plugins (weekly Tool-drift check, scripts/check-tool-references.js) can validate
 * its skills against the in-development server rather than waiting for a published release — the
 * drift is caught before it ships, not by the weekly cron after.
 */
public final class McpContractExport {

  public static void main(String[] args) throws Exception {
    Path out = args.length > 0 ? Path.of(args[0]) : Path.of("build/mcp-contract/mcp-contract.json");
    Files.createDirectories(out.toAbsolutePath().getParent());
    try (GuidanceContractContext ignored = GuidanceContractContext.create()) {
      Files.writeString(out, McpContractJson.render(new GuidanceSurfaces()));
    }
    System.out.println("Wrote MCP guidance contract to " + out.toAbsolutePath());
  }

  private McpContractExport() {}
}
