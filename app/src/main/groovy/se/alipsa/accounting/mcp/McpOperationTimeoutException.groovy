package se.alipsa.accounting.mcp

/** Signals that an MCP request exceeded its coordinator wait time. */
final class McpOperationTimeoutException extends RuntimeException {
  final boolean safeToRetry

  McpOperationTimeoutException(String message, boolean safeToRetry) {
    super(message)
    this.safeToRetry = safeToRetry
  }
}
