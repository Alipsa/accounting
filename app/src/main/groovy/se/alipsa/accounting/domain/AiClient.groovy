package se.alipsa.accounting.domain

/** A supported AI CLI target and its workspace layout. */
enum AiClient {

  CLAUDE('claude', '.mcp.json', '.claude/skills/accounting/accounting-mcp.md', false),
  CODEX('codex', '.codex/config.toml', 'AGENTS.md', false),
  KIMI('kimi', '.kimi-code/mcp.json', 'AGENTS.md', true),
  VIBE('vibe', '.vibe/config.toml', 'AGENTS.md', true)

  final String binaryName
  final String configRelativePath
  final String instructionsRelativePath
  final boolean experimental

  AiClient(String binaryName, String configRelativePath, String instructionsRelativePath, boolean experimental) {
    this.binaryName = binaryName
    this.configRelativePath = configRelativePath
    this.instructionsRelativePath = instructionsRelativePath
    this.experimental = experimental
  }
}
