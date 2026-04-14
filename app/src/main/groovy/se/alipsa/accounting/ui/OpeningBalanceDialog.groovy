package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.OpeningBalance
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.support.I18n

import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * Captures opening balance for one balance account and fiscal year.
 */
final class OpeningBalanceDialog extends JDialog {

  private final AccountService accountService
  private final Account account
  private final Runnable onSave

  private final JComboBox<FiscalYear> fiscalYearComboBox
  private final JTextField amountField = new JTextField(14)
  private final JLabel currentAmountLabel = new JLabel('')
  private final JTextArea validationArea = new JTextArea(3, 36)

  OpeningBalanceDialog(
      Frame owner,
      AccountService accountService,
      FiscalYearService fiscalYearService,
      Account account,
      Runnable onSave
  ) {
    super(owner, I18n.instance.getString('openingBalanceDialog.title'), true)
    this.accountService = accountService
    this.account = account
    this.onSave = onSave

    List<FiscalYear> fiscalYears = fiscalYearService.listFiscalYears(CompanyService.LEGACY_COMPANY_ID)
    fiscalYearComboBox = new JComboBox<>(fiscalYears as FiscalYear[])
    buildUi()
    refreshCurrentAmount()
  }

  static void showDialog(
      Frame owner,
      AccountService accountService,
      FiscalYearService fiscalYearService,
      Account account,
      Runnable onSave
  ) {
    if (!account.isBalanceAccount()) {
      throw new IllegalArgumentException('Opening balances may only be set on balance accounts.')
    }
    OpeningBalanceDialog dialog = new OpeningBalanceDialog(owner, accountService, fiscalYearService, account, onSave)
    dialog.setVisible(true)
  }

  private void buildUi() {
    setLayout(new java.awt.BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    String accountClassLabel = I18n.instance.format('openingBalanceDialog.label.accountClass',
        account.accountClass ?: I18n.instance.getString('openingBalanceDialog.label.notClassified'))
    add(new JLabel(
        "<html><b>${escapeHtml(account.accountNumber)} ${escapeHtml(account.accountName)}</b><br/>" +
            "${escapeHtml(accountClassLabel)}</html>"
    ), java.awt.BorderLayout.NORTH)
    add(buildFormPanel(), java.awt.BorderLayout.CENTER)
    add(buildFooterPanel(), java.awt.BorderLayout.SOUTH)

    fiscalYearComboBox.addActionListener {
      refreshCurrentAmount()
    }

    pack()
    setResizable(false)
    setLocationRelativeTo(owner)
  }

  private JPanel buildFormPanel() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 8, 0), 0, 0
    )

    panel.add(new JLabel(I18n.instance.getString('openingBalanceDialog.label.fiscalYear')), labelConstraints)
    panel.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel(I18n.instance.getString('openingBalanceDialog.label.currentBalance')), labelConstraints)
    panel.add(currentAmountLabel, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    panel.add(new JLabel(I18n.instance.getString('openingBalanceDialog.label.amount')), labelConstraints)
    panel.add(amountField, fieldConstraints)

    panel
  }

  private JPanel buildFooterPanel() {
    validationArea.editable = false
    validationArea.lineWrap = true
    validationArea.wrapStyleWord = true
    validationArea.foreground = new Color(153, 27, 27)
    validationArea.background = background
    validationArea.visible = false

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton cancelButton = new JButton(I18n.instance.getString('openingBalanceDialog.button.cancel'))
    cancelButton.addActionListener { dispose() }
    JButton saveButton = new JButton(I18n.instance.getString('openingBalanceDialog.button.save'))
    saveButton.enabled = fiscalYearComboBox.itemCount > 0
    saveButton.addActionListener { saveRequested() }
    actions.add(cancelButton)
    actions.add(saveButton)

    JPanel panel = new JPanel(new java.awt.BorderLayout(0, 8))
    panel.add(validationArea, java.awt.BorderLayout.CENTER)
    panel.add(actions, java.awt.BorderLayout.SOUTH)
    panel
  }

  private void refreshCurrentAmount() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      currentAmountLabel.text = I18n.instance.getString('openingBalanceDialog.error.noFiscalYears')
      amountField.text = ''
      return
    }

    OpeningBalance openingBalance = accountService.getOpeningBalance(fiscalYear.id, account.accountNumber)
    currentAmountLabel.text = openingBalance.amount.toPlainString()
    amountField.text = openingBalance.amount == BigDecimal.ZERO ? '' : openingBalance.amount.toPlainString()
  }

  private void saveRequested() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      showValidation(I18n.instance.getString('openingBalanceDialog.error.createFiscalYear'))
      return
    }

    try {
      BigDecimal amount = parseAmount(amountField.text)
      accountService.saveOpeningBalance(fiscalYear.id, account.accountNumber, amount)
      onSave.run()
      dispose()
    } catch (IllegalArgumentException exception) {
      showValidation(exception.message ?: I18n.instance.getString('openingBalanceDialog.error.invalidAmountDefault'))
    }
  }

  private FiscalYear selectedFiscalYear() {
    fiscalYearComboBox.selectedItem as FiscalYear
  }

  private static BigDecimal parseAmount(String value) {
    String normalized = value?.trim()
    if (!normalized) {
      return BigDecimal.ZERO
    }
    try {
      new BigDecimal(normalized.replace(',', '.'))
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(I18n.instance.getString('openingBalanceDialog.error.invalidAmount'))
    }
  }

  private void showValidation(String message) {
    validationArea.text = message
    validationArea.visible = true
    pack()
  }

  private static String escapeHtml(String text) {
    if (text == null) {
      return ''
    }
    text
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
  }
}
