package se.alipsa.accounting.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * NDJSON stdin/stdout loop for MCP over stdio.
 */
final class McpServer {

  private final BufferedReader input
  private final PrintWriter output
  private final McpDispatcher dispatcher
  private final JsonSlurper jsonSlurper = new JsonSlurper()

  McpServer(
      InputStream input = System.in,
      OutputStream output = System.out,
      McpDispatcher dispatcher = new McpDispatcher()
  ) {
    this.input = new BufferedReader(new InputStreamReader(input, 'UTF-8'))
    this.output = new PrintWriter(new OutputStreamWriter(output, 'UTF-8'), true)
    this.dispatcher = dispatcher
  }

  void run() {
    String line
    while ((line = input.readLine()) != null) {
      if (!line.trim()) {
        continue
      }
      writeResponse(handleLine(line))
    }
  }

  private Object handleLine(String line) {
    try {
      Object parsed = jsonSlurper.parseText(line)
      if (!(parsed instanceof Map)) {
        return McpDispatcher.parseError(null, 'Request must be a JSON object.')
      }
      dispatcher.dispatch((Map<String, Object>) parsed)
    } catch (Exception exception) {
      McpDispatcher.parseError(null, 'Parse error.')
    }
  }

  private void writeResponse(Object response) {
    if (response == null) {
      return
    }
    output.println(JsonOutput.toJson(response))
    output.flush()
  }
}
