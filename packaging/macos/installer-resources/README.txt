macOS packaging resources for Alipsa Accounting.
Code signing and notarization are future release steps handled outside the Gradle build.

The MCP skill is bundled inside the release zip as skill/accounting-mcp.md alongside AlipsaAccounting.app.
To enable it in Claude Code or Codex, extract the zip and link or copy that skill directory to:

- Claude Code: ~/.claude/skills/accounting
- Codex: ~/.agents/skills/accounting
