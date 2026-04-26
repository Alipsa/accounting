Windows installer resources for Alipsa Accounting.
Signing is handled as a separate release step outside the Gradle build.

The MCP skill is bundled inside the release zip as skill/accounting-mcp.md alongside the installer.
To enable it in Claude Code or Codex, extract the zip and create a junction or copy that skill directory to:

- Claude Code: %USERPROFILE%\.claude\skills\accounting
- Codex: %USERPROFILE%\.agents\skills\accounting
