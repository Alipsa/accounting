Windows installer resources for Alipsa Accounting.
Signing is handled as a separate release step outside the Gradle build.

The MCP skill is distributed next to the release artifact as skill/accounting-mcp.md.
To enable it in Claude Code or Codex, create a junction or copy that skill directory to:

- Claude Code: %USERPROFILE%\.claude\skills\accounting
- Codex: %USERPROFILE%\.agents\skills\accounting
