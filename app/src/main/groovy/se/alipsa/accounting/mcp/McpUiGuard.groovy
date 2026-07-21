package se.alipsa.accounting.mcp

/** Coordinates visible UI blocking around MCP operations that can change application state. */
interface McpUiGuard {
  void beginWrite()
  void endWrite()
}
