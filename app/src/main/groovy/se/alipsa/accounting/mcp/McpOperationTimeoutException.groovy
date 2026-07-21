package se.alipsa.accounting.mcp

/** Signals that an MCP request exceeded its coordinator wait time. */
final class McpOperationTimeoutException extends RuntimeException {
  McpOperationTimeoutException(String message) {
    super(message)
  }
}
