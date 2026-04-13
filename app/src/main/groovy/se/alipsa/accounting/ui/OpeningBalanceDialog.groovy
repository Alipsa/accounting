package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.OpeningBalance
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.FiscalYearService

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
    super(owner, 'Ingående balans', true)
    this.accountService = accountService
    this.account = account
    this.onSave = onSave

    List<FiscalYear> fiscalYears = fiscalYearService.listFiscalYears()
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

    add(new JLabel(
        "<html><b>${escapeHtml(account.accountNumber)} ${escapeHtml(account.accountName)}</b><br/>" +
            "Kontoklass: ${escapeHtml(account.accountClass ?: 'Ej klassad')}</html>"
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

    panel.add(new JLabel('Räkenskapsår'), labelConstraints)
    panel.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel('Nuvarande saldo'), labelConstraints)
    panel.add(currentAmountLabel, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    panel.add(new JLabel('Belopp'), labelConstraints)
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
    JButton cancelButton = new JButton('Avbryt')
    cancelButton.addActionListener { dispose() }
    JButton saveButton = new JButton('Spara')
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
      currentAmountLabel.text = 'Inga räkenskapsår finns ännu.'
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
      showValidation('Skapa ett räkenskapsår innan ingående balans registreras.')
      return
    }

    try {
      BigDecimal amount = parseAmount(amountField.text)
      accountService.saveOpeningBalance(fiscalYear.id, account.accountNumber, amount)
      onSave.run()
      dispose()
    } catch (IllegalArgumentException exception) {
      showValidation(exception.message ?: 'Ogiltigt belopp för ingående balans.')
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
      throw new IllegalArgumentException('Beloppet måste vara ett giltigt tal.')
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
