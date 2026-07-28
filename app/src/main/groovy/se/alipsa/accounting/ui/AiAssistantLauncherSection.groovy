package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.mcp.LoopbackMcpServer
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.I18n

import java.awt.FlowLayout
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.TitledBorder

/** Settings UI for launching an AI CLI configured for the local MCP server. */
final class AiAssistantLauncherSection {

  private static final Logger log = Logger.getLogger(AiAssistantLauncherSection.name)

  private final UserPreferencesService preferences
  private final AiWorkspaceService workspaceService
  private final AiAssistantLauncher launcher
  private final BackgroundTaskRunner tasks
  private final ErrorDisplay errorDisplay
  private final JPanel panel = new JPanel()
  private final TitledBorder border = BorderFactory.createTitledBorder('')
  private final JLabel binaryLabel = new JLabel()
  private final JTextField binaryField = new JTextField(24)
  private final JButton detectBinary = new JButton()
  private final JLabel terminalLabel = new JLabel()
  private final JComboBox<TerminalAdapterKind> terminalKind = new JComboBox<>(TerminalAdapterKind.forCurrentOs() as TerminalAdapterKind[])
  private final JTextField terminalPath = new JTextField(24)
  private final JButton detectTerminal = new JButton()
  private final JLabel clientLabel = new JLabel()
  private final JComboBox<AiClient> client = new JComboBox<>(AiClient.values())
  private final JButton launchButton = new JButton()
  private boolean mcpAvailable

  AiAssistantLauncherSection(UserPreferencesService preferences, AiWorkspaceService workspaceService, AiAssistantLauncher launcher) {
    this(preferences, workspaceService, launcher, new SwingBackgroundTaskRunner(), null)
  }

  AiAssistantLauncherSection(UserPreferencesService preferences, AiWorkspaceService workspaceService, AiAssistantLauncher launcher,
      BackgroundTaskRunner tasks) {
    this(preferences, workspaceService, launcher, tasks, null)
  }

  AiAssistantLauncherSection(UserPreferencesService preferences, AiWorkspaceService workspaceService, AiAssistantLauncher launcher,
      BackgroundTaskRunner tasks, ErrorDisplay errorDisplay) {
    this.preferences = preferences
    this.workspaceService = workspaceService
    this.launcher = launcher
    this.tasks = tasks
    this.errorDisplay = errorDisplay ?: new SwingErrorDisplay(panel)
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = border
    launchButton.name = 'aiLauncher.launchButton'
    AiClient storedClient = preferences.aiClient
    if (storedClient != null) { client.selectedItem = storedClient }
    buildRows()
    autoDetectBlankFields()
    applyLocale()
    updateLaunchButtonState()
  }

  JPanel getPanel() { panel }

  @PackageScope
  JButton getDetectBinaryButton() { detectBinary }

  @PackageScope
  JButton getDetectTerminalButton() { detectTerminal }

  @PackageScope
  JTextField getBinaryField() { binaryField }

  @PackageScope
  JTextField getTerminalPathField() { terminalPath }

  @PackageScope
  JComboBox<TerminalAdapterKind> getTerminalKindCombo() { terminalKind }

  void setMcpAvailable(boolean available) { mcpAvailable = available; updateLaunchButtonState() }

  void applyLocale() {
    border.title = I18n.instance.getString('aiLauncher.section.title')
    updateSelectedClientFields()
    detectBinary.text = I18n.instance.getString('aiLauncher.button.detect')
    terminalLabel.text = I18n.instance.getString('aiLauncher.label.terminalAdapter')
    detectTerminal.text = I18n.instance.getString('aiLauncher.button.detect')
    clientLabel.text = I18n.instance.getString('aiLauncher.label.client')
    launchButton.text = I18n.instance.getString('aiLauncher.button.launch')
  }

  private static String displayName(AiClient value) {
    String name = I18n.instance.getString("aiClient.${value.name()}")
    value.experimental ? name + I18n.instance.getString('aiLauncher.experimentalSuffix') : name
  }

  private void buildRows() {
    client.addActionListener { updateSelectedClientFields() }
    JPanel clientRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); clientRow.add(clientLabel); clientRow.add(client); panel.add(clientRow)
    binaryField.name = 'aiLauncher.binaryField'
    detectBinary.name = 'aiLauncher.detectBinary'
    detectBinary.addActionListener {
      AiClient selected = client.selectedItem as AiClient
      tasks.run({ workspaceService.detectBinaryPath(selected) }, this.&onBinaryDetected, this.&showDetectionError)
    }
    JPanel binaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); binaryRow.add(binaryLabel); binaryRow.add(binaryField); binaryRow.add(detectBinary); panel.add(binaryRow)
    TerminalAdapterKind stored = preferences.terminalAdapterKind
    if (stored != null && TerminalAdapterKind.forCurrentOs().contains(stored)) { terminalKind.selectedItem = stored }
    terminalPath.name = 'aiLauncher.terminalPath'
    terminalPath.text = currentTerminalPathPreference() ?: ''
    // Each adapter kind has its own binary (cmd.exe vs. wt.exe, ...): switching kinds must not
    // silently keep showing - and launching through - the previous kind's path.
    terminalKind.addActionListener { onTerminalKindChanged() }
    detectTerminal.name = 'aiLauncher.detectTerminal'
    detectTerminal.addActionListener {
      TerminalAdapterKind selected = terminalKind.selectedItem as TerminalAdapterKind
      tasks.run({ workspaceService.detectTerminalPath(selected) }, { Path found -> onTerminalPathDetected(selected, found) }, this.&showDetectionError)
    }
    JPanel terminalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); terminalRow.add(terminalLabel); terminalRow.add(terminalKind); terminalRow.add(terminalPath); terminalRow.add(detectTerminal); panel.add(terminalRow)
    launchButton.addActionListener { onLaunch() }
    JPanel launchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); launchRow.add(launchButton); panel.add(launchRow)
  }

  private void onBinaryDetected(Path found) {
    AiClient selected = client.selectedItem as AiClient
    if (found != null) {
      binaryField.text = found.toString()
      preferences.setAiBinaryPath(selected, found.toString())
    } else {
      showError(I18n.instance.format('aiLauncher.detection.notFound', displayName(selected)))
    }
  }

  private void onTerminalKindChanged() {
    terminalPath.text = currentTerminalPathPreference() ?: ''
  }

  private String currentTerminalPathPreference() {
    TerminalAdapterKind selected = terminalKind.selectedItem as TerminalAdapterKind
    selected != null ? preferences.getTerminalPath(selected) : null
  }

  private void onTerminalPathDetected(TerminalAdapterKind kind, Path found) {
    if (found != null) {
      terminalPath.text = found.toString()
      preferences.terminalAdapterKind = kind
      preferences.setTerminalPath(kind, found.toString())
    } else {
      showError(I18n.instance.format('aiLauncher.detection.notFound', kind.defaultBinaryName))
    }
  }

  private void autoDetectBlankFields() {
    List<AiClient> blank = AiClient.values().findAll { AiClient value -> !preferences.getAiBinaryPath(value)?.trim() }
    boolean blankTerminal = !terminalPath.text?.trim()
    tasks.run({
      Map<AiClient, Path> binaries = [:]
      blank.each { AiClient value -> Path found = workspaceService.detectBinaryPath(value); if (found != null) { binaries[value] = found } }
      new Tuple2<Map<AiClient, Path>, Tuple2<TerminalAdapterKind, Path>>(binaries, blankTerminal ? workspaceService.detectTerminalAdapter() : null)
    }, { Tuple2<Map<AiClient, Path>, Tuple2<TerminalAdapterKind, Path>> found ->
      found.v1.each { AiClient value, Path path -> if (!preferences.getAiBinaryPath(value)?.trim()) { preferences.setAiBinaryPath(value, path.toString()) } }
      updateSelectedClientFields()
      if (found.v2 != null && !terminalPath.text?.trim()) {
        terminalKind.selectedItem = found.v2.v1
        terminalPath.text = found.v2.v2.toString()
        preferences.terminalAdapterKind = found.v2.v1
        preferences.setTerminalPath(found.v2.v1, found.v2.v2.toString())
      }
    }, this.&showDetectionError)
  }

  private void updateLaunchButtonState() {
    launchButton.enabled = mcpAvailable
    launchButton.toolTipText = mcpAvailable ? null : I18n.instance.getString('aiLauncher.error.mcpNotRunning')
  }

  private void onLaunch() {
    try {
      if (!mcpAvailable) { showError(I18n.instance.getString('aiLauncher.error.mcpNotRunning')); return }
      AiClient selected = client.selectedItem as AiClient
      String binary = binaryField.text?.trim()
      TerminalAdapterKind adapter = terminalKind.selectedItem as TerminalAdapterKind
      String terminal = terminalPath.text?.trim()
      if (!binary) { showError(I18n.instance.format('aiLauncher.error.binaryMissing', displayName(selected))); return }
      if (adapter == null || !terminal) { showError(I18n.instance.getString('aiLauncher.error.terminalAdapterMissing')); return }
      launchButton.enabled = false
      String token = preferences.ensureMcpToken()
      tasks.run({ doLaunch(selected, binary, adapter, terminal, token) }, { String error ->
        updateLaunchButtonState(); if (error != null) { showError(error) }
      }) { Exception exception -> updateLaunchButtonState(); showError(I18n.instance.format('aiLauncher.error.launchFailed', exception.message ?: exception.class.simpleName)) }
    } catch (Exception exception) {
      // Anything thrown here runs synchronously on the EDT, before the background task even
      // starts. Left uncaught, this vanishes silently on a packaged Windows build with no
      // console attached - the click just looks like it did nothing.
      log.log(Level.WARNING, 'Unexpected failure while starting the AI assistant launch.', exception)
      updateLaunchButtonState()
      showError(I18n.instance.format('aiLauncher.error.launchFailed', exception.message ?: exception.class.simpleName))
    }
  }

  @PackageScope
  String doLaunch(AiClient selected, String binary, TerminalAdapterKind adapter, String terminal, String token) {
    launcher.validatePreflight(adapter)
    Path binaryPath = Paths.get(binary)
    if (!workspaceService.isValidExecutable(binaryPath)) { return I18n.instance.format('aiLauncher.error.binaryNotExecutable', displayName(selected), binary) }
    Path terminalExecutable = Paths.get(terminal)
    if (!workspaceService.isValidExecutable(terminalExecutable)) { return I18n.instance.format('aiLauncher.error.terminalNotExecutable', terminal) }
    preferences.setAiBinaryPath(selected, binary); preferences.terminalAdapterKind = adapter; preferences.setTerminalPath(adapter, terminal)
    workspaceService.refreshClientFiles(selected, LoopbackMcpServer.ENDPOINT, token)
    launcher.launch(selected, binaryPath, adapter, terminalExecutable, token)
    preferences.aiClient = selected
    null
  }

  private void showDetectionError(Exception exception) { showError(I18n.instance.format('aiLauncher.error.detectionFailed', exception.message ?: exception.class.simpleName)) }
  private void showError(String text) { errorDisplay.showError(I18n.instance.getString('aiLauncher.error.title'), text) }

  private static final class SwingErrorDisplay implements ErrorDisplay {
    private final JPanel panel

    SwingErrorDisplay(JPanel panel) { this.panel = panel }

    @Override
    void showError(String title, String message) {
      JOptionPane.showMessageDialog(panel, message, title, JOptionPane.ERROR_MESSAGE)
    }
  }

  private void updateSelectedClientFields() {
    AiClient selected = client.selectedItem as AiClient
    if (selected == null) { return }
    binaryLabel.text = I18n.instance.format('aiLauncher.label.binaryPath', displayName(selected))
    binaryField.text = preferences.getAiBinaryPath(selected) ?: ''
  }
}
