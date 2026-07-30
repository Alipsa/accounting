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
        // Claude pre-approves read-only tools through .claude/settings.local.json instead - see
        // AiWorkspaceMcpSettings - so its own .mcp.json stays free of a client-specific extension.
        return bearerJson(endpoint, token, null)
      case AiClient.KIMI:
        // Kimi has no separate settings file for this; its own MCP config format supports an
        // inline "autoApprove" list of tool names on the server entry, so use that instead.
        return bearerJson(endpoint, token, AiWorkspaceMcpSettings.READ_ONLY_TOOLS)
      case AiClient.CODEX:
        // Codex has no separate settings file or inline server-level auto-approve list either;
        // each MCP tool gets pre-approved through its own [mcp_servers.accounting.tools.<name>]
        // table with approval_mode = "approve" - otherwise every read prompts, unlike Claude.
        return """[mcp_servers.accounting]
url = "${endpoint}"
bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"
${codexReadOnlyToolPermissions()}""".toString()
      case AiClient.VIBE:
        // Vibe's "http" transport is plain request/response with no session tracking, but
        // LoopbackMcpServer implements the actual MCP Streamable HTTP transport (initialize
        // handshake + Mcp-Session-Id on every later call, see LoopbackMcpServer.McpHandler) and
        // rejects any non-initialize request without a valid session. Using "http" here made
        // every tool call fail silently, so Vibe never had working tools despite the workspace
        // being configured.
        return """[[mcp_servers]]
name = "accounting"
transport = "streamable-http"
url = "${endpoint}"
headers = { Authorization = "Bearer ${token}" }
${vibeReadOnlyToolPermissions()}""".toString()
      default:
        throw new IllegalArgumentException("Unknown AI client: ${client}")
    }
  }

  private static String bearerJson(String endpoint, String token, List<String> autoApprove) {
    Map<String, Object> server = [type: 'http', url: endpoint,
        headers: [Authorization: "Bearer ${token}".toString()]]
    if (autoApprove) { server.autoApprove = autoApprove }
    JsonOutput.toJson([mcpServers: [accounting: server]]) + '\n'
  }

  // Vibe has neither Claude's separate settings file nor Kimi's inline server-level
  // "autoApprove" list; each MCP tool gets pre-approved through its own [tools.<name>] table,
  // keyed by "<mcp server name>_<tool name>" (see vibe's core.tools.mcp.tools published_name).
  private static String vibeReadOnlyToolPermissions() {
    StringBuilder permissions = new StringBuilder()
    AiWorkspaceMcpSettings.READ_ONLY_TOOLS.each { String tool ->
      permissions << "\n[tools.accounting_${tool}]\npermission = \"always\"\n"
    }
    permissions.toString()
  }

  private static String codexReadOnlyToolPermissions() {
    StringBuilder permissions = new StringBuilder()
    AiWorkspaceMcpSettings.READ_ONLY_TOOLS.each { String tool ->
      permissions << "\n[mcp_servers.accounting.tools.${tool}]\napproval_mode = \"approve\"\n"
    }
    permissions.toString()
  }
}
