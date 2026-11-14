package se.alipsa.accounting.mcp

/**
 * Shared argument readers for MCP tool handlers. Centralised so every tool validates and
 * trims its required arguments the same way.
 */
final class McpArgs {

  private McpArgs() {
  }

  static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("Argument ${key} must be a number.")
    }
    ((Number) value).longValue()
  }

  static String requiredString(Map<String, Object> args, String key) {
    String value = args.get(key) as String
    if (!value?.trim()) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    value.trim()
  }
}
