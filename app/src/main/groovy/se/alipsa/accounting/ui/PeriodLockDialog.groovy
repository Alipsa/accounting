package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.service.AccountingPeriodService

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
    super(owner, 'Lås period', true)
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

    add(new JLabel(
        """<html><b>${escapeHtml(period.periodName)}</b> ${period.startDate} till ${period.endDate}<br/>
När perioden låses ska nya bokningar i datumintervallet blockeras.</html>"""
    ), BorderLayout.NORTH)

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
    JButton closeButton = new JButton(period.locked ? 'Stäng' : 'Avbryt')
    closeButton.addActionListener { dispose() }
    panel.add(closeButton)

    if (!period.locked) {
      JButton lockButton = new JButton('Lås period')
      lockButton.addActionListener { lockRequested() }
      panel.add(lockButton)
    }

    panel
  }

  private void lockRequested() {
    String reason = reasonArea.text?.trim()
    if (!reason) {
      validationArea.text = ValidationSupport.summaryText([
          ValidationSupport.fieldError('Låsorsak', 'måste anges för att dokumentera konsekvensen')
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
