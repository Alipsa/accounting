package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient

class AiClientConfigWriterTest {

  private static final String ENDPOINT = 'http://127.0.0.1:48652/mcp'
  private static final String TOKEN = 'test-token'

  @Test
  void writesExpectedConfigForEveryClient() {
    String claude = AiClientConfigWriter.configContent(AiClient.CLAUDE, ENDPOINT, TOKEN)
    String codex = AiClientConfigWriter.configContent(AiClient.CODEX, ENDPOINT, TOKEN)
    String kimi = AiClientConfigWriter.configContent(AiClient.KIMI, ENDPOINT, TOKEN)
    String vibe = AiClientConfigWriter.configContent(AiClient.VIBE, ENDPOINT, TOKEN)

    assertTrue(claude.contains('"mcpServers"'))
    assertTrue(claude.contains('Bearer test-token'))
    assertTrue(kimi.contains('Bearer test-token'))
    assertTrue(codex.contains('[mcp_servers.accounting]'))
    assertTrue(codex.contains('bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"'))
    assertFalse(codex.contains(TOKEN))
    assertTrue(vibe.contains('[[mcp_servers]]'))
    assertTrue(vibe.contains('headers = { Authorization = "Bearer test-token" }'))
  }

  // Kimi has no separate settings file like Claude's .claude/settings.local.json, so read-only
  // tools must be pre-approved inline in its own MCP config via "autoApprove" - otherwise every
  // read prompts for approval, unlike Claude.
  @Test
  void kimiConfigAutoApprovesReadOnlyToolsButClaudesDoesNot() {
    String kimi = AiClientConfigWriter.configContent(AiClient.KIMI, ENDPOINT, TOKEN)
    String claude = AiClientConfigWriter.configContent(AiClient.CLAUDE, ENDPOINT, TOKEN)

    assertTrue(kimi.contains('"autoApprove"'))
    AiWorkspaceMcpSettings.READ_ONLY_TOOLS.each { String tool -> assertTrue(kimi.contains("\"${tool}\"")) }
    assertFalse(kimi.contains('mcp__accounting__'))
    assertFalse(claude.contains('"autoApprove"'))
  }
}
