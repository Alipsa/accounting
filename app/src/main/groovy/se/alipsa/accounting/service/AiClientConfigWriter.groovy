package se.alipsa.accounting.service

import groovy.json.JsonOutput

import se.alipsa.accounting.domain.AiClient

/** Produces the exact project-local MCP config for each supported client. */
final class AiClientConfigWriter {

  private AiClientConfigWriter() {
  }

  static String configContent(AiClient client, String endpoint, String token) {
    switch (client) {
      case AiClient.CLAUDE:
      case AiClient.KIMI:
        return bearerJson(endpoint, token)
      case AiClient.CODEX:
        return """[mcp_servers.accounting]
url = "${endpoint}"
bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"
""".toString()
      case AiClient.VIBE:
        return """[[mcp_servers]]
name = "accounting"
transport = "http"
url = "${endpoint}"
headers = { Authorization = "Bearer ${token}" }
""".toString()
      default:
        throw new IllegalArgumentException("Unknown AI client: ${client}")
    }
  }

  private static String bearerJson(String endpoint, String token) {
    JsonOutput.toJson([mcpServers: [accounting: [type: 'http', url: endpoint,
        headers: [Authorization: "Bearer ${token}".toString()]]]]) + '\n'
  }
}
