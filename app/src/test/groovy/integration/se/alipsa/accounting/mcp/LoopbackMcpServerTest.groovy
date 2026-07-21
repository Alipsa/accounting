package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.UserPreferencesService

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.prefs.Preferences

class LoopbackMcpServerTest {

  private final Preferences preferencesNode = Preferences.userRoot().node("accounting-test-${UUID.randomUUID()}")
  private final UserPreferencesService preferences = new UserPreferencesService(preferencesNode)
  private LoopbackMcpServer server

  @AfterEach
  void tearDown() {
    server?.close()
    preferencesNode.removeNode()
  }

  @Test
  void rejectsUnauthenticatedRequestsAndIssuesSessionsForInitialize() {
    server = new LoopbackMcpServer(preferences)
    server.start()
    HttpClient client = HttpClient.newHttpClient()
    HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(URI.create(LoopbackMcpServer.ENDPOINT))
        .POST(HttpRequest.BodyPublishers.ofString('{}')).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(401, rejected.statusCode())

    String request = JsonOutput.toJson([jsonrpc: '2.0', id: 1, method: 'initialize',
        params: [protocolVersion: McpDispatcher.PROTOCOL_VERSION]])
    HttpResponse<String> initialized = client.send(requestBuilder(request).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(200, initialized.statusCode())
    assertNotNull(initialized.headers().firstValue('Mcp-Session-Id').orElse(null))
    Map response = (Map) new JsonSlurper().parseText(initialized.body())
    assertEquals(McpDispatcher.PROTOCOL_VERSION, ((Map) response.get('result')).get('protocolVersion'))
  }

  private HttpRequest.Builder requestBuilder(String body) {
    HttpRequest.newBuilder(URI.create(LoopbackMcpServer.ENDPOINT))
        .header('Authorization', "Bearer ${preferences.ensureMcpToken()}")
        .POST(HttpRequest.BodyPublishers.ofString(body))
  }
}
