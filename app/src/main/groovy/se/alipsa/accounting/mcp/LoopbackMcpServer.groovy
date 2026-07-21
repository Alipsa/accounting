package se.alipsa.accounting.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

import se.alipsa.accounting.service.UserPreferencesService

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Local, token-protected Streamable HTTP endpoint for MCP clients. */
final class LoopbackMcpServer implements Closeable {

  static final int PORT = 48652
  static final String PATH = '/mcp'
  static final String ENDPOINT = "http://127.0.0.1:${PORT}${PATH}"

  private final UserPreferencesService preferences
  private final McpDispatcher dispatcher
  private final JsonSlurper jsonSlurper = new JsonSlurper()
  private final Set<String> sessions = ConcurrentHashMap.newKeySet()
  private final SecureRandom random = new SecureRandom()
  private final ExecutorService executor = Executors.newFixedThreadPool(8)
  private HttpServer server

  LoopbackMcpServer(UserPreferencesService preferences, McpDispatcher dispatcher = new McpDispatcher()) {
    this.preferences = preferences
    this.dispatcher = dispatcher
  }

  synchronized void start() {
    if (server != null) {
      return
    }
    preferences.ensureMcpToken()
    HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, PORT), 0)
    created.createContext(PATH, new McpHandler())
    created.executor = executor
    created.start()
    server = created
  }

  boolean isRunning() {
    server != null
  }

  @Override
  synchronized void close() {
    if (server != null) {
      server.stop(0)
      server = null
    }
    sessions.clear()
    executor.shutdownNow()
  }

  private final class McpHandler implements HttpHandler {
    @Override
    void handle(HttpExchange exchange) throws IOException {
      try {
        if (!authorized(exchange)) {
          send(exchange, 401, [error: 'Unauthorized'])
          return
        }
        if (exchange.requestMethod == 'GET') {
          send(exchange, 405, [error: 'This MCP endpoint does not use server-sent events.'])
          return
        }
        if (exchange.requestMethod != 'POST') {
          send(exchange, 405, [error: 'Method not allowed'])
          return
        }
        Object parsed = jsonSlurper.parse(exchange.requestBody)
        if (!(parsed instanceof Map)) {
          send(exchange, 400, McpDispatcher.parseError(null, 'Request must be a JSON object.'))
          return
        }
        Map<String, Object> request = (Map<String, Object>) parsed
        boolean initialize = request.method == 'initialize'
        if (!initialize && !sessions.contains(exchange.requestHeaders.getFirst('Mcp-Session-Id'))) {
          send(exchange, 400, McpDispatcher.parseError(request.id, 'A valid Mcp-Session-Id is required.'))
          return
        }
        Object response = dispatcher.dispatch(request)
        if (initialize && response instanceof Map && ((Map) response).get('error') == null) {
          String sessionId = newSessionId()
          sessions.add(sessionId)
          exchange.responseHeaders.set('Mcp-Session-Id', sessionId)
        }
        if (response == null) {
          exchange.sendResponseHeaders(202, -1)
          return
        }
        send(exchange, 200, response)
      } catch (Exception exception) {
        send(exchange, 400, McpDispatcher.parseError(null, 'Invalid MCP request.'))
      } finally {
        exchange.close()
      }
    }
  }

  private boolean authorized(HttpExchange exchange) {
    String expected = preferences.ensureMcpToken()
    String authorization = exchange.requestHeaders.getFirst('Authorization')
    String host = exchange.requestHeaders.getFirst('Host')
    String origin = exchange.requestHeaders.getFirst('Origin')
    boolean validHost = host == '127.0.0.1:48652' || host == 'localhost:48652'
    boolean validOrigin = origin == null || origin == 'http://127.0.0.1:48652' || origin == 'http://localhost:48652'
    validHost && validOrigin && authorization == "Bearer ${expected}"
  }

  private String newSessionId() {
    byte[] bytes = new byte[24]
    random.nextBytes(bytes)
    bytes.encodeBase64Url().toString().replace('=', '')
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JsonOutput.toJson(body).getBytes('UTF-8')
    exchange.responseHeaders.set('Content-Type', 'application/json; charset=utf-8')
    exchange.sendResponseHeaders(status, bytes.length)
    exchange.responseBody.write(bytes)
  }
}
