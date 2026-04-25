package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import groovy.json.JsonSlurper

import org.junit.jupiter.api.Test

class McpDispatcherTest {

  private static final String JSONRPC_VERSION = '2.0'

  @Test
  void initializeReturnsMcpProtocolMetadata() {
    McpDispatcher dispatcher = new McpDispatcher()

    Map<String, Object> response = dispatchToMap(dispatcher, [
        jsonrpc: JSONRPC_VERSION,
        id     : 1,
        method : 'initialize',
        params : [
            protocolVersion: '2024-11-05',
            capabilities   : [:],
            clientInfo     : [
                name   : 'test-client',
                version: '1'
            ]
        ]
    ])

    assertEquals(JSONRPC_VERSION, response.jsonrpc)
    assertEquals(1, response.id)
    Map<String, Object> result = (Map<String, Object>) response.result
    Map<String, Object> serverInfo = (Map<String, Object>) result.serverInfo
    assertEquals(McpDispatcher.PROTOCOL_VERSION, result.protocolVersion)
    assertEquals('alipsa-accounting', serverInfo.name)
    assertEquals([tools: [:]], result.capabilities)
  }

  @Test
  void notificationsAreIgnoredWithoutAResponse() {
    McpDispatcher dispatcher = new McpDispatcher()

    Object response = dispatcher.dispatch([
        jsonrpc: JSONRPC_VERSION,
        method : 'notifications/initialized'
    ])

    assertNull(response)
  }

  @Test
  void unknownMethodReturnsJsonRpcMethodNotFound() {
    McpDispatcher dispatcher = new McpDispatcher()

    Map<String, Object> response = dispatchToMap(dispatcher, [
        jsonrpc: JSONRPC_VERSION,
        id     : 7,
        method : 'resources/list'
    ])

    Map<String, Object> error = (Map<String, Object>) response.error
    assertEquals(-32601, error.code)
    assertEquals(7, response.id)
  }

  @Test
  void toolsListReturnsRegisteredTools() {
    McpDispatcher dispatcher = new McpDispatcher(new FakeTools())

    Map<String, Object> response = dispatchToMap(dispatcher, [
        jsonrpc: JSONRPC_VERSION,
        id     : 2,
        method : 'tools/list'
    ])

    Map<String, Object> result = (Map<String, Object>) response.result
    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.tools
    assertEquals(1, tools.size())
    assertEquals('ping', tools[0].name)
  }

  @Test
  void toolsCallEmbedsTextContentAndStructuredContent() {
    McpDispatcher dispatcher = new McpDispatcher(new FakeTools())

    Map<String, Object> response = dispatchToMap(dispatcher, [
        jsonrpc: JSONRPC_VERSION,
        id     : 3,
        method : 'tools/call',
        params : [
            name     : 'ping',
            arguments: [
                message: 'hello'
            ]
        ]
    ])

    Map<String, Object> result = (Map<String, Object>) response.result
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.content
    assertEquals([ok: true, echo: 'hello'], result.structuredContent)
    assertEquals('text', content[0].type)
    Map<String, Object> contentJson = (Map<String, Object>) new JsonSlurper().parseText((String) content[0].text)
    assertEquals(true, contentJson.ok)
    assertEquals('hello', contentJson.echo)
  }

  @Test
  void invalidToolCallParamsReturnInvalidParams() {
    McpDispatcher dispatcher = new McpDispatcher()

    Map<String, Object> response = dispatchToMap(dispatcher, [
        jsonrpc: JSONRPC_VERSION,
        id     : 4,
        method : 'tools/call',
        params : [:]
    ])

    Map<String, Object> error = (Map<String, Object>) response.error
    assertEquals(-32602, error.code)
  }

  private static final class FakeTools extends AccountingMcpTools {

    @Override
    List<Map<String, Object>> listTools() {
      Map<String, Object> messageSchema = [type: 'string']
      Map<String, Object> inputSchema = [
          type      : 'object',
          properties: [
              message: messageSchema
          ]
      ]
      Map<String, Object> tool = [
          name       : 'ping',
          description: 'Returns the supplied message.',
          inputSchema: inputSchema
      ]
      [tool]
    }

    @Override
    Map<String, Object> callTool(String name, Map<String, Object> arguments) {
      [
          ok  : true,
          echo: arguments.message
      ]
    }
  }

  private static Map<String, Object> dispatchToMap(McpDispatcher dispatcher, Map<String, Object> request) {
    (Map<String, Object>) dispatcher.dispatch(request)
  }
}
