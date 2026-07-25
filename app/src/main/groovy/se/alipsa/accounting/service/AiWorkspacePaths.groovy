package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient

import java.nio.file.Path

/** Resolves the per-client files inside an AI workspace. */
final class AiWorkspacePaths {

  private AiWorkspacePaths() {
  }

  static Path configFile(Path workspace, AiClient client) { workspace.resolve(client.configRelativePath) }

  static Path instructionsFile(Path workspace, AiClient client) { workspace.resolve(client.instructionsRelativePath) }

  static Path assistantProfileFile(Path workspace, AiClient client) {
    client == AiClient.CLAUDE ? workspace.resolve('CLAUDE.md') : instructionsFile(workspace, client)
  }

  static Path settingsLocalFile(Path workspace) {
    workspace.resolve('.claude/settings.local.json')
  }

  static Path wrapperScript(Path workspace, AiClient client, String launchId, boolean windows) {
    workspace.resolve(".launch-${client.binaryName}-${launchId}.${windows ? 'cmd' : 'sh'}")
  }
}
