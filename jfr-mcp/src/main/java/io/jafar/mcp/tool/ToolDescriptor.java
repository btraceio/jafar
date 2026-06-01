package io.jafar.mcp.tool;

/** Static MCP tool metadata: name, description, and JSON input schema. */
public record ToolDescriptor(String name, String description, String inputSchemaJson) {}
