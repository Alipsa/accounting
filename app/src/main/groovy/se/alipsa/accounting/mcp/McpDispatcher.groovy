package se.alipsa.accounting.mcp

import groovy.json.JsonOutput

/**
 * Routes JSON-RPC 2.0 requests for the local MCP server.
 */
final class McpDispatcher {

  static final String PROTOCOL_VERSION = '2025-11-25'
  static final String COMPATIBLE_PROTOCOL_VERSION = '2025-06-18'
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
          return successResponse(request.id, initializeResult(paramsAsMap(request.params)))
        case 'tools/list':
          return successResponse(request.id, toolsListResult())
        case 'tools/call':
          return successResponse(request.id, toolsCallResult(paramsAsMap(request.params)))
        case 'resources/list':
          return successResponse(request.id, resourcesListResult())
        case 'resources/read':
          return successResponse(request.id, resourceReadResult(paramsAsMap(request.params)))
        case 'prompts/list':
          return successResponse(request.id, promptsListResult())
        case 'prompts/get':
          return successResponse(request.id, promptGetResult(paramsAsMap(request.params)))
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

  static Map<String, Object> busyError(Object id) {
    errorResponse(id, -32001, 'MCP server is busy; retry the request shortly.')
  }

  static Map<String, Object> operationTimeoutError(Object id) {
    errorResponse(id, -32002, 'MCP operation timed out; a started operation may still be completing.')
  }

  static Map<String, Object> internalError(Object id) {
    errorResponse(id, INTERNAL_ERROR, 'Internal MCP server error.')
  }

  private static boolean validRequest(Map<String, Object> request) {
    request?.jsonrpc == JSONRPC_VERSION && request.method instanceof String &&
        (request.containsKey('id') || ((String) request.method).startsWith('notifications/') ||
            request.method == '$/cancelRequest')
  }

  private static Map<String, Object> initializeResult(Map<String, Object> params) {
    String requestedVersion = requiredString(params, 'protocolVersion')
    if (!(requestedVersion in [PROTOCOL_VERSION, COMPATIBLE_PROTOCOL_VERSION])) {
      throw new IllegalArgumentException(
          "Unsupported MCP protocol version ${requestedVersion}; supported versions are ${PROTOCOL_VERSION} and ${COMPATIBLE_PROTOCOL_VERSION}.")
    }
    [
        protocolVersion: requestedVersion,
        capabilities   : [
            tools: [:],
            resources: [:],
            prompts: [:]
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

  private static Map<String, Object> resourcesListResult() {
    [resources: [
        [uri: 'accounting://workflow', name: 'Accounting workflow guide', mimeType: 'text/markdown'],
        [uri: 'accounting://trusted-sources', name: 'Trusted accounting sources', mimeType: 'text/markdown']
    ]]
  }

  private static Map<String, Object> resourceReadResult(Map<String, Object> params) {
    String uri = requiredString(params, 'uri')
    String text = uri == 'accounting://workflow'
        ? 'Use get_company_info and read tools first. Prepare vouchers with set_active_voucher_draft; the user must save in the desktop application.'
        : uri == 'accounting://trusted-sources'
            ? 'Prioritize https://www.skatteverket.se/ and https://www.bokforingstips.se/ for Swedish bookkeeping questions.'
            : null
    if (text == null) {
      throw new IllegalArgumentException("Unknown resource: ${uri}")
    }
    [contents: [[uri: uri, mimeType: 'text/markdown', text: text]]]
  }

  private static Map<String, Object> promptsListResult() {
    [prompts: ['voucher_guidance', 'vat_handling', 'correction_guidance', 'year_end_guidance'].collect { String name ->
      [name: name, description: "Alipsa Accounting ${name.replace('_', ' ')}"]
    }]
  }

  private static Map<String, Object> promptGetResult(Map<String, Object> params) {
    String name = requiredString(params, 'name')
    if (!(name in ['voucher_guidance', 'vat_handling', 'correction_guidance', 'year_end_guidance'])) {
      throw new IllegalArgumentException("Unknown prompt: ${name}")
    }
    [description: "Alipsa Accounting ${name.replace('_', ' ')}", messages: [[role: 'user', content: [type: 'text', text:
        'Use accounting context tools and never say a voucher is saved until the user saves it in the GUI.']]]]
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
