package se.alipsa.accounting.ui

import se.alipsa.accounting.mcp.LoopbackMcpServer
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.PurgeResult
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.I18n

import java.awt.FlowLayout

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.TitledBorder

/** Settings UI for configuring access to the local MCP endpoint. */
final class McpSettingsSection {

  private final UserPreferencesService userPreferencesService
  private final AiWorkspaceService aiWorkspaceService
  private final BackgroundTaskRunner backgroundTaskRunner
  private final JPanel panel = new JPanel()
  private final TitledBorder border
  private final JLabel endpointLabel = new JLabel()
  private final JLabel tokenLabel = new JLabel()
  private final JLabel statusCaptionLabel = new JLabel()
  private final JLabel statusLabel = new JLabel()
  private final JTextField endpointField
  private final JTextField tokenField
  private final JButton regenerateButton = new JButton()
  private Status status = Status.STARTING
  private String unavailableDetail

  McpSettingsSection(UserPreferencesService userPreferencesService, AiWorkspaceService aiWorkspaceService) {
    this(userPreferencesService, aiWorkspaceService, new SwingBackgroundTaskRunner())
  }

  McpSettingsSection(UserPreferencesService userPreferencesService, AiWorkspaceService aiWorkspaceService,
      BackgroundTaskRunner backgroundTaskRunner) {
    this.userPreferencesService = userPreferencesService
    this.aiWorkspaceService = aiWorkspaceService
    this.backgroundTaskRunner = backgroundTaskRunner
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    border = BorderFactory.createTitledBorder('')
    panel.border = border
    endpointField = new JTextField(LoopbackMcpServer.ENDPOINT, 30)
    endpointField.editable = false
    tokenField = new JTextField(userPreferencesService.ensureMcpToken(), 30)
    tokenField.editable = false
    buildRows()
    applyLocale()
  }

  JPanel getPanel() {
    panel
  }

  void setRunning() {
    status = Status.RUNNING
    updateStatusText()
  }

  void setUnavailable(String detail) {
    status = Status.UNAVAILABLE
    unavailableDetail = detail
    updateStatusText()
  }

  void applyLocale() {
    border.title = I18n.instance.getString('settings.section.mcp')
    endpointLabel.text = I18n.instance.getString('settings.label.mcpEndpoint')
    tokenLabel.text = I18n.instance.getString('settings.label.mcpToken')
    statusCaptionLabel.text = I18n.instance.getString('settings.label.mcpStatus')
    regenerateButton.text = I18n.instance.getString('settings.button.regenerateMcpToken')
    updateStatusText()
  }

  private void buildRows() {
    JPanel endpointRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    endpointRow.add(endpointLabel)
    endpointRow.add(endpointField)

    JPanel tokenRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    tokenRow.add(tokenLabel)
    tokenRow.add(tokenField)
    regenerateButton.addActionListener { regenerateToken() }
    tokenRow.add(regenerateButton)

    JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    statusRow.add(statusCaptionLabel)
    statusRow.add(statusLabel)

    panel.add(endpointRow)
    panel.add(tokenRow)
    panel.add(statusRow)
  }

  void regenerateToken() {
    backgroundTaskRunner.run({ aiWorkspaceService.purgeAllSecrets() }, { PurgeResult result ->
      if (!result.complete) {
        javax.swing.JOptionPane.showMessageDialog(panel,
            I18n.instance.format('settings.mcp.rotateFailed', result.failed.join(', ')),
            I18n.instance.getString('settings.mcp.rotateFailedTitle'), javax.swing.JOptionPane.ERROR_MESSAGE)
        return
      }
      tokenField.text = userPreferencesService.regenerateMcpToken()
    }) { Exception exception ->
      javax.swing.JOptionPane.showMessageDialog(panel,
          I18n.instance.format('settings.mcp.rotateError', exception.message ?: exception.class.simpleName),
          I18n.instance.getString('settings.mcp.rotateFailedTitle'), javax.swing.JOptionPane.ERROR_MESSAGE)
    }
  }

  private void updateStatusText() {
    switch (status) {
      case Status.RUNNING:
        statusLabel.text = I18n.instance.getString('settings.mcp.status.running')
        break
      case Status.UNAVAILABLE:
        statusLabel.text = I18n.instance.format('settings.mcp.status.unavailable', unavailableDetail ?: '')
        break
      default:
        statusLabel.text = I18n.instance.getString('settings.mcp.status.starting')
    }
  }

  private enum Status {
    STARTING, RUNNING, UNAVAILABLE
  }
}
