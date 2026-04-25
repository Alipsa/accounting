package se.alipsa.accounting.mcp

import groovy.json.JsonOutput

/**
 * Routes JSON-RPC 2.0 requests for the minimal MCP stdio server.
 */
final class McpDispatcher {

  static final String PROTOCOL_VERSION = '2025-11-25'
  private static final String JSONRPC_VERSION = '2.0'
  private static final int PARSE_ERROR = -32700
  private static final int INVALID_REQUEST = -32600
  private static final int METHOD_NOT_FOUND = -32601
  private static final int INVALID_PARAMS = -32602
  private static final int INTERNAL_ERROR = -32603

  private final AccountingMcpTools tools

  McpDispatcher(AccountingMcpTools tools = new AccountingMcpTools()) {
    this.tools = tools
  }

  Object dispatch(Map<String, Object> request) {
    if (!validRequest(request)) {
      return errorResponse(request?.get('id'), INVALID_REQUEST, 'Invalid JSON-RPC request.')
    }

    String method = (String) request.method
    if (method.startsWith('notifications/') || method == '$/cancelRequest') {
      return null
    }

    try {
      switch (method) {
        case 'initialize':
          return successResponse(request.id, initializeResult())
        case 'tools/list':
          return successResponse(request.id, toolsListResult())
        case 'tools/call':
          return successResponse(request.id, toolsCallResult(paramsAsMap(request.params)))
        default:
          return errorResponse(request.id, METHOD_NOT_FOUND, "Method not found: ${method}")
      }
    } catch (IllegalArgumentException exception) {
      return errorResponse(request.id, INVALID_PARAMS, exception.message ?: 'Invalid params.')
    } catch (Exception exception) {
      return errorResponse(request.id, INTERNAL_ERROR, exception.message ?: exception.class.simpleName)
    }
  }

  static Map<String, Object> parseError(Object id = null, String message = 'Parse error.') {
    errorResponse(id, PARSE_ERROR, message)
  }

  private static boolean validRequest(Map<String, Object> request) {
    request?.jsonrpc == JSONRPC_VERSION && request.method instanceof String &&
        (request.containsKey('id') || ((String) request.method).startsWith('notifications/') ||
            request.method == '$/cancelRequest')
  }

  private static Map<String, Object> initializeResult() {
    [
        protocolVersion: PROTOCOL_VERSION,
        capabilities   : [
            tools: [:]
        ],
        serverInfo     : [
            name   : 'alipsa-accounting',
            version: McpDispatcher.package?.implementationVersion ?: 'dev'
        ]
    ]
  }

  private Map<String, Object> toolsListResult() {
    [
        tools: tools.listTools()
    ]
  }

  private Map<String, Object> toolsCallResult(Map<String, Object> params) {
    String name = requiredString(params, 'name')
    Map<String, Object> arguments = params.arguments instanceof Map ? (Map<String, Object>) params.arguments : [:]
    Map<String, Object> result = tools.callTool(name, arguments)
    String text = JsonOutput.toJson(result)
    [
        content          : [
            [
                type: 'text',
                text: text
            ]
        ],
        structuredContent: result
    ]
  }

  private static Map<String, Object> paramsAsMap(Object params) {
    if (params == null) {
      return [:]
    }
    if (!(params instanceof Map)) {
      throw new IllegalArgumentException('Params must be an object.')
    }
    (Map<String, Object>) params
  }

  private static String requiredString(Map<String, Object> params, String name) {
    Object value = params.get(name)
    if (!(value instanceof String) || !((String) value).trim()) {
      throw new IllegalArgumentException("${name} must be a non-empty string.")
    }
    (String) value
  }

  private static Map<String, Object> successResponse(Object id, Map<String, Object> result) {
    [
        jsonrpc: JSONRPC_VERSION,
        id     : id,
        result : result
    ]
  }

  private static Map<String, Object> errorResponse(Object id, int code, String message) {
    [
        jsonrpc: JSONRPC_VERSION,
        id     : id,
        error  : [
            code   : code,
            message: message
        ]
    ]
  }
}
