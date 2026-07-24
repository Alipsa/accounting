package se.alipsa.accounting.ui

import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.UserPreferencesService

import java.awt.Component

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/** Groups AI launching controls with the less frequently needed MCP connection details. */
final class AiAssistantPanel {

  final AiAssistantLauncherSection launcherSection
  final McpSettingsSection mcpSettingsSection
  final JPanel panel = new JPanel()

  AiAssistantPanel(UserPreferencesService preferences, AiWorkspaceService workspaceService) {
    launcherSection = new AiAssistantLauncherSection(preferences, workspaceService, new AiAssistantLauncher())
    mcpSettingsSection = new McpSettingsSection(preferences, workspaceService)
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = BorderFactory.createEmptyBorder(24, 24, 24, 24)
    launcherSection.panel.alignmentX = Component.LEFT_ALIGNMENT
    mcpSettingsSection.panel.alignmentX = Component.LEFT_ALIGNMENT
    panel.add(launcherSection.panel)
    panel.add(Box.createVerticalStrut(24))
    panel.add(mcpSettingsSection.panel)
    panel.add(Box.createVerticalGlue())
  }
}
