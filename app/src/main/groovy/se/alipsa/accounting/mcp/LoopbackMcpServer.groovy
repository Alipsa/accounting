package se.alipsa.accounting.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

import se.alipsa.accounting.service.UserPreferencesService

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local, token-protected Streamable HTTP endpoint for MCP clients. */
final class LoopbackMcpServer implements Closeable {

  static final int PORT = 48652
  static final String PATH = '/mcp'
  static final String ENDPOINT = "http://127.0.0.1:${PORT}${PATH}"
  private static final String LOOPBACK_HOST = "127.0.0.1:${PORT}"
  private static final String LOCALHOST_HOST = "localhost:${PORT}"

  private final UserPreferencesService preferences
  private final McpDispatcher dispatcher
  private final JsonSlurper jsonSlurper = new JsonSlurper()
  private final Set<String> sessions = ConcurrentHashMap.newKeySet()
  private final SecureRandom random = new SecureRandom()
  private final ExecutorService executor = Executors.newFixedThreadPool(8)
  private final McpOperationCoordinator operationCoordinator
  private HttpServer server

  LoopbackMcpServer(UserPreferencesService preferences, McpDispatcher dispatcher = new McpDispatcher(), McpUiGuard uiGuard = null) {
    this.preferences = preferences
    this.dispatcher = dispatcher
    this.operationCoordinator = new McpOperationCoordinator(uiGuard ?: new McpUiGuard() {
      @Override void beginWrite() { }
      @Override void endWrite() { }
    })
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
      server.stop(5)
      server = null
    }
    sessions.clear()
    executor.shutdown()
    executor.awaitTermination(30, TimeUnit.SECONDS)
    operationCoordinator.close()
  }

  private final class McpHandler implements HttpHandler {
    @Override
    void handle(HttpExchange exchange) throws IOException {
      try {
        if (!authorized(exchange)) {
          send(exchange, 401, [error: 'Unauthorized'])
          return
        }
        if (exchange.requestMethod == 'DELETE') {
          String sessionId = exchange.requestHeaders.getFirst('Mcp-Session-Id')
          if (sessionId == null || !sessions.remove(sessionId)) {
            send(exchange, 400, McpDispatcher.parseError(null, 'A valid Mcp-Session-Id is required.'))
          } else {
            exchange.sendResponseHeaders(204, -1)
          }
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
        Object response = operationCoordinator.dispatch(dispatcher, request)
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
    boolean validHost = host == LOOPBACK_HOST || host == LOCALHOST_HOST
    boolean validOrigin = origin == null || origin == "http://${LOOPBACK_HOST}" || origin == "http://${LOCALHOST_HOST}"
    byte[] expectedHeader = "Bearer ${expected}".getBytes('UTF-8')
    byte[] suppliedHeader = authorization == null ? new byte[0] : authorization.getBytes('UTF-8')
    validHost && validOrigin && MessageDigest.isEqual(expectedHeader, suppliedHeader)
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
