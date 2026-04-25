package se.alipsa.accounting.mcp

/**
 * Registry and implementation entrypoint for MCP tools.
 *
 * Phase 1 only exposes the protocol shell. Business tools are added in later phases.
 */
class AccountingMcpTools {

  List<Map<String, Object>> listTools() {
    []
  }

  Map<String, Object> callTool(String name, Map<String, Object> arguments) {
    throw new IllegalArgumentException("Unknown MCP tool: ${name} (${arguments.keySet().join(', ')})")
  }
}
