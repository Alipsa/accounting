package se.alipsa.accounting.ui

import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.DataLocationMigrator
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Window
import java.nio.file.Path
import java.nio.file.Paths

import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField

/**
 * Lets the user point the application at a different data location (e.g. a shared/mounted
 * drive), optionally moving the existing local data there. Depends only on
 * {@link UserPreferencesService} and filesystem helpers, so it can run before the database is
 * initialized as well as from the running application's Settings tab.
 */
final class DataLocationDialog extends JDialog {

  private final UserPreferencesService preferences
  private final JTextField pathField = new JTextField(32)
  private final JRadioButton moveDataButton = new JRadioButton(I18n.instance.getString('dataLocationDialog.radio.move'))
  private final JRadioButton pointOnlyButton = new JRadioButton(I18n.instance.getString('dataLocationDialog.radio.pointOnly'))
  private final ButtonGroup modeGroup = new ButtonGroup()
  private boolean changed = false

  private DataLocationDialog(Window owner, UserPreferencesService preferences) {
    super(owner, I18n.instance.getString('dataLocationDialog.title'), ModalityType.APPLICATION_MODAL)
    this.preferences = preferences
    buildUi()
    pack()
    setLocationRelativeTo(owner)
  }

  /**
   * Shows the dialog modally. Returns {@code true} if the user applied a location change.
   */
  static boolean showDialog(Window owner, UserPreferencesService preferences) {
    DataLocationDialog dialog = new DataLocationDialog(owner, preferences)
    dialog.setVisible(true)
    dialog.changed
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    JPanel centerPanel = new JPanel()
    centerPanel.setLayout(new javax.swing.BoxLayout(centerPanel, javax.swing.BoxLayout.Y_AXIS))
    centerPanel.add(buildCurrentLocationLabel())
    centerPanel.add(javax.swing.Box.createVerticalStrut(12))
    centerPanel.add(buildPathRow())
    centerPanel.add(javax.swing.Box.createVerticalStrut(8))
    centerPanel.add(buildModeRow(moveDataButton))
    centerPanel.add(buildModeRow(pointOnlyButton))
    moveDataButton.setSelected(true)
    add(centerPanel, BorderLayout.CENTER)

    add(buildButtonPanel(), BorderLayout.SOUTH)
  }

  private JLabel buildCurrentLocationLabel() {
    String currentLocation = preferences.getDataLocation() ?: AppPaths.applicationHome().toString()
    JLabel currentLabel = new JLabel(I18n.instance.format('dataLocationDialog.label.current', currentLocation))
    currentLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
    currentLabel
  }

  private JPanel buildPathRow() {
    JPanel pathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    pathRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
    JButton browseButton = new JButton(I18n.instance.getString('dataLocationDialog.button.browse'))
    browseButton.addActionListener { browse() }
    pathRow.add(new JLabel(I18n.instance.getString('dataLocationDialog.label.newLocation')))
    pathRow.add(pathField)
    pathRow.add(browseButton)
    pathRow
  }

  private JPanel buildModeRow(JRadioButton button) {
    modeGroup.add(button)
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0))
    panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
    panel.add(button)
    panel
  }

  private JPanel buildButtonPanel() {
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton applyButton = new JButton(I18n.instance.getString('dataLocationDialog.button.apply'))
    applyButton.addActionListener { apply() }
    JButton cancelButton = new JButton(I18n.instance.getString('dataLocationDialog.button.cancel'))
    cancelButton.addActionListener { dispose() }
    buttonPanel.add(applyButton)
    buttonPanel.add(cancelButton)
    buttonPanel
  }

  private void browse() {
    JFileChooser chooser = new JFileChooser()
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    if (pathField.text?.trim()) {
      chooser.setCurrentDirectory(new File(pathField.text.trim()))
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      Path selected = chooser.getSelectedFile().toPath()
      pathField.text = selected.toString()
      if (DataLocationMigrator.looksLikeExistingData(selected)) {
        pointOnlyButton.setSelected(true)
      } else {
        moveDataButton.setSelected(true)
      }
    }
  }

  private void apply() {
    String text = pathField.text?.trim()
    if (!text) {
      showError(I18n.instance.getString('dataLocationDialog.error.noLocation'))
      return
    }
    Path target = Paths.get(text)
    boolean pointOnly = pointOnlyButton.isSelected()
    DataLocationMigrator.ValidationResult validation = pointOnly ?
        DataLocationMigrator.validateExistingLocation(target) : DataLocationMigrator.validateTarget(target)
    if (!validation.valid) {
      showError(validation.reason)
      return
    }
    if (pointOnly && !DataLocationMigrator.looksLikeExistingData(target)) {
      showError(I18n.instance.getString('dataLocationDialog.error.noExistingData'))
      return
    }

    int confirmation = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.getString('dataLocationDialog.warning.concurrency'),
        I18n.instance.getString('dataLocationDialog.warning.concurrencyTitle'),
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    if (confirmation != JOptionPane.OK_OPTION) {
      return
    }

    // Both modes are queued as a pending change rather than written to data.location
    // immediately: the running app still uses the old location until restart, so
    // data.location must keep reflecting whatever is actually active until
    // DataLocationResolver applies the pending change at the next startup. Writing it
    // early would corrupt the source of a later "move" queued before restarting.
    preferences.setPendingMigration(target.toString(), moveDataButton.isSelected())
    changed = true

    JOptionPane.showMessageDialog(
        this,
        I18n.instance.getString('dataLocationDialog.info.restartRequired'),
        I18n.instance.getString('dataLocationDialog.info.restartRequiredTitle'),
        JOptionPane.INFORMATION_MESSAGE
    )
    dispose()
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(
        this, message, I18n.instance.getString('dataLocationDialog.error.title'), JOptionPane.ERROR_MESSAGE)
  }
}
