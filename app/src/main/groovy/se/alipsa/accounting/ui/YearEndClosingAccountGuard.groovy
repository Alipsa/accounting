package se.alipsa.accounting.ui

import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.event.DocumentListener

/**
 * Keeps the year-end closing account field and the Execute button in sync.
 * Any manual edit of the field disables Execute until the preview is refreshed,
 * and execution always uses the account from the last completed preview rather
 * than the raw field text, which may be mid-edit.
 */
final class YearEndClosingAccountGuard {

  private final JTextField accountField
  private final JButton executeButton
  private String previewedAccountNumber

  YearEndClosingAccountGuard(JTextField accountField, JButton executeButton) {
    this.accountField = accountField
    this.executeButton = executeButton
    accountField.document.addDocumentListener([
        insertUpdate: { executeButton.enabled = false },
        removeUpdate: { executeButton.enabled = false },
        changedUpdate: { executeButton.enabled = false }
    ] as DocumentListener)
  }

  void previewCompleted(String accountNumber) {
    previewedAccountNumber = accountNumber
    accountField.text = accountNumber
  }

  String accountForExecution() {
    previewedAccountNumber
  }
}
