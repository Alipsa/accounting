package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridLayout

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Collects a series code (required) and name (optional), then resolves it via
 * {@link VoucherService#ensureSeries}. If the code already exists, ensureSeries returns the
 * existing row - this dialog treats that as success, not an error.
 */
final class NewVoucherSeriesDialog extends JDialog {

  private static final String CODE_PATTERN = /[A-Z0-9]{1,8}/

  private final VoucherService voucherService
  private final long fiscalYearId
  private final JTextField codeField = new JTextField(8)
  private final JTextField nameField = new JTextField(24)
  private final JLabel errorLabel = new JLabel(' ')
  private VoucherSeries result

  NewVoucherSeriesDialog(Frame owner, VoucherService voucherService, long fiscalYearId) {
    super(owner, I18n.instance.getString('newVoucherSeriesDialog.title'), true)
    this.voucherService = voucherService
    this.fiscalYearId = fiscalYearId
    buildUi()
  }

  /** Shows the dialog modally and returns the created/matched series, or null if cancelled. */
  static VoucherSeries showDialog(Frame owner, VoucherService voucherService, long fiscalYearId) {
    NewVoucherSeriesDialog dialog = new NewVoucherSeriesDialog(owner, voucherService, fiscalYearId)
    dialog.setVisible(true)
    dialog.result
  }

  /** Trims and uppercases rawCode; returns null if the result isn't 1-8 chars of A-Z/0-9. */
  static String normalizeCode(String rawCode) {
    String code = rawCode?.trim()?.toUpperCase(Locale.ROOT)
    (code && code ==~ CODE_PATTERN) ? code : null
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    JPanel formPanel = new JPanel(new GridLayout(2, 2, 8, 8))
    formPanel.add(new JLabel(I18n.instance.getString('newVoucherSeriesDialog.label.code')))
    formPanel.add(codeField)
    formPanel.add(new JLabel(I18n.instance.getString('newVoucherSeriesDialog.label.name')))
    formPanel.add(nameField)
    add(formPanel, BorderLayout.CENTER)

    errorLabel.foreground = new Color(153, 27, 27)
    add(errorLabel, BorderLayout.NORTH)

    add(buildButtonPanel(), BorderLayout.SOUTH)

    pack()
    setResizable(false)
    setLocationRelativeTo(owner)
  }

  private JPanel buildButtonPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton cancelButton = new JButton(I18n.instance.getString('newVoucherSeriesDialog.button.cancel'))
    cancelButton.addActionListener { dispose() }
    panel.add(cancelButton)

    JButton okButton = new JButton(I18n.instance.getString('newVoucherSeriesDialog.button.ok'))
    okButton.addActionListener { okRequested() }
    panel.add(okButton)
    panel
  }

  private void okRequested() {
    String code = normalizeCode(codeField.text)
    if (code == null) {
      errorLabel.text = I18n.instance.getString('newVoucherSeriesDialog.error.invalidCode')
      pack()
      return
    }
    try {
      result = voucherService.ensureSeries(fiscalYearId, code, nameField.text?.trim() ?: null)
      dispose()
    } catch (IllegalArgumentException exception) {
      errorLabel.text = exception.message
      pack()
    }
  }
}
