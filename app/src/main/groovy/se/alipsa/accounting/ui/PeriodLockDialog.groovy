package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.support.I18n

import java.awt.*

import javax.swing.*

/**
 * Confirms and documents a period lock action.
 */
final class PeriodLockDialog extends JDialog {

  private final AccountingPeriodService accountingPeriodService
  private final AccountingPeriod period
  private final Runnable onLock

  private final JTextArea reasonArea = new JTextArea(4, 36)
  private final JTextArea validationArea = new JTextArea(3, 36)

  PeriodLockDialog(
      Frame owner,
      AccountingPeriodService accountingPeriodService,
      AccountingPeriod period,
      Runnable onLock
  ) {
    super(owner, I18n.instance.getString('periodLockDialog.title'), true)
    this.accountingPeriodService = accountingPeriodService
    this.period = period
    this.onLock = onLock
    buildUi()
  }

  static void showDialog(
      Frame owner,
      AccountingPeriodService accountingPeriodService,
      AccountingPeriod period,
      Runnable onLock
  ) {
    PeriodLockDialog dialog = new PeriodLockDialog(owner, accountingPeriodService, period, onLock)
    dialog.setVisible(true)
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    String header = I18n.instance.format('periodLockDialog.header',
        escapeHtml(period.periodName), period.startDate, period.endDate)
    String description = I18n.instance.getString('periodLockDialog.description')
    add(new JLabel("<html><b>${header}</b><br/>${description}</html>"), BorderLayout.NORTH)

    reasonArea.setLineWrap(true)
    reasonArea.setWrapStyleWord(true)
    reasonArea.text = period.lockReason ?: ''
    reasonArea.editable = !period.locked
    add(new JScrollPane(reasonArea), BorderLayout.CENTER)

    validationArea.setEditable(false)
    validationArea.setLineWrap(true)
    validationArea.setWrapStyleWord(true)
    validationArea.setForeground(new Color(153, 27, 27))
    validationArea.setBackground(background)
    validationArea.setVisible(false)

    JPanel southPanel = new JPanel(new BorderLayout(0, 8))
    southPanel.add(validationArea, BorderLayout.CENTER)
    southPanel.add(buildButtonPanel(), BorderLayout.SOUTH)
    add(southPanel, BorderLayout.SOUTH)

    pack()
    setResizable(false)
    setLocationRelativeTo(owner)
  }

  private JPanel buildButtonPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    String closeLabel = period.locked
        ? I18n.instance.getString('periodLockDialog.button.close')
        : I18n.instance.getString('periodLockDialog.button.cancel')
    JButton closeButton = new JButton(closeLabel)
    closeButton.addActionListener { dispose() }
    panel.add(closeButton)

    if (!period.locked) {
      JButton lockButton = new JButton(I18n.instance.getString('periodLockDialog.button.lock'))
      lockButton.addActionListener { lockRequested() }
      panel.add(lockButton)
    }

    panel
  }

  private void lockRequested() {
    String reason = reasonArea.text?.trim()
    if (!reason) {
      validationArea.text = ValidationSupport.summaryText([
          ValidationSupport.fieldError(
              I18n.instance.getString('periodLockDialog.error.lockReasonLabel'),
              I18n.instance.getString('periodLockDialog.error.lockReasonRequired'))
      ])
      validationArea.visible = true
      pack()
      return
    }
    accountingPeriodService.lockPeriod(period.id, reason)
    onLock.run()
    dispose()
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
