package io.jafar.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for JFR Shell MCP Server.
 *
 * <p>Exposes JFR analysis capabilities via Model Context Protocol (MCP) for integration with AI
 * assistants like Claude Desktop.
 *
 * <p>CRITICAL: Uses STDIO transport - ANY stdout pollution will corrupt the JSON-RPC protocol. All
 * logging MUST go to files only.
 */
@SpringBootApplication
public class JfrShellMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(JfrShellMcpApplication.class, args);
  }
}
