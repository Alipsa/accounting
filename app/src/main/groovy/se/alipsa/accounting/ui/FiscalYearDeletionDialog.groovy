package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.FiscalYearDeletionResult
import se.alipsa.accounting.service.FiscalYearDeletionService
import se.alipsa.accounting.service.FiscalYearPurgeSummary
import se.alipsa.accounting.service.FiscalYearReplacementPlan
import se.alipsa.accounting.service.StoredFileDeletionFailure
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.ExecutionException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingWorker

/**
 * Confirmation dialog for permanent fiscal year deletion.
 */
final class FiscalYearDeletionDialog extends JDialog {

  private final FiscalYearDeletionService deletionService
  private final FiscalYear fiscalYear
  private final Runnable onDeleted

  private final JTextArea summaryArea = new JTextArea(8, 48)
  private final JTextArea messageArea = new JTextArea(4, 48)
  private final JButton previewButton = new JButton(I18n.instance.getString('fiscalYearDeletionDialog.button.preview'))
  private final JButton executeButton = new JButton(I18n.instance.getString('fiscalYearDeletionDialog.button.execute'))
  private boolean workInProgress
  private FiscalYearPurgeSummary lastPreviewSummary

  FiscalYearDeletionDialog(
      Frame owner,
      FiscalYearDeletionService deletionService,
      FiscalYear fiscalYear,
      Runnable onDeleted
  ) {
    super(owner, I18n.instance.getString('fiscalYearDeletionDialog.title'), true)
    this.deletionService = deletionService
    this.fiscalYear = fiscalYear
    this.onDeleted = onDeleted ?: ({ } as Runnable)
    buildUi()
    reloadPreview()
  }

  static void showDialog(
      Frame owner,
      FiscalYearDeletionService deletionService,
      FiscalYear fiscalYear,
      Runnable onDeleted
  ) {
    FiscalYearDeletionDialog dialog = new FiscalYearDeletionDialog(
        owner, deletionService, fiscalYear, onDeleted)
    dialog.visible = true
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    add(buildHeader(), BorderLayout.NORTH)
    add(buildCenter(), BorderLayout.CENTER)
    add(buildActions(), BorderLayout.SOUTH)

    summaryArea.editable = false
    summaryArea.lineWrap = true
    summaryArea.wrapStyleWord = true
    summaryArea.background = background

    messageArea.editable = false
    messageArea.lineWrap = true
    messageArea.wrapStyleWord = true
    messageArea.background = background

    previewButton.addActionListener { reloadPreview() }
    executeButton.addActionListener { executeRequested() }

    pack()
    setMinimumSize(size)
    setLocationRelativeTo(owner)
  }

  private JPanel buildHeader() {
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

    panel.add(new JLabel(I18n.instance.getString('fiscalYearDeletionDialog.label.fiscalYear')), labelConstraints)
    panel.add(new JLabel("${fiscalYear.name} (${fiscalYear.startDate} - ${fiscalYear.endDate})"), fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel(I18n.instance.getString('fiscalYearDeletionDialog.label.endDate')), labelConstraints)
    panel.add(new JLabel(fiscalYear.endDate.toString()), fieldConstraints)

    panel
  }

  private JPanel buildCenter() {
    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(new JScrollPane(summaryArea), BorderLayout.CENTER)
    panel.add(new JScrollPane(messageArea), BorderLayout.SOUTH)
    panel
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton closeButton = new JButton(I18n.instance.getString('fiscalYearDeletionDialog.button.close'))
    closeButton.addActionListener {
      if (!workInProgress) {
        dispose()
      }
    }
    panel.add(previewButton)
    panel.add(executeButton)
    panel.add(closeButton)
    panel
  }

  private void reloadPreview() {
    setWorkingState(true)
    executeButton.enabled = false
    summaryArea.text = I18n.instance.getString('fiscalYearDeletionDialog.status.runningPreview')
    messageArea.text = ''
    new SwingWorker<FiscalYearReplacementPlan, Void>() {
      @Override
      protected FiscalYearReplacementPlan doInBackground() {
        deletionService.previewDeletion(fiscalYear.id)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          renderPreview(get())
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          renderMessage(new Color(153, 27, 27),
              I18n.instance.format('fiscalYearDeletionDialog.status.previewFailed', cause.message))
        }
      }
    }.execute()
  }

  private void renderPreview(FiscalYearReplacementPlan plan) {
    lastPreviewSummary = plan.summary
    FiscalYearPurgeSummary summary = plan.summary
    List<String> rows = [
        I18n.instance.format('fiscalYearDeletionDialog.summary.vouchers', summary.voucherCount as Object),
        I18n.instance.format('fiscalYearDeletionDialog.summary.attachments', summary.attachmentCount as Object),
        I18n.instance.format('fiscalYearDeletionDialog.summary.reports', summary.reportArchiveCount as Object),
        I18n.instance.format('fiscalYearDeletionDialog.summary.vatPeriods', summary.vatPeriodCount as Object),
        I18n.instance.format('fiscalYearDeletionDialog.summary.openingBalances', summary.openingBalanceCount as Object),
        I18n.instance.format('fiscalYearDeletionDialog.summary.auditLogEntries', summary.auditLogCount as Object)
    ]
    summaryArea.text = rows.join('\n')
    executeButton.enabled = true
    renderMessage(new Color(22, 101, 52),
        I18n.instance.getString('fiscalYearDeletionDialog.status.retentionOk'))
  }

  private void executeRequested() {
    int voucherCount = lastPreviewSummary?.voucherCount ?: 0
    int attachmentCount = lastPreviewSummary?.attachmentCount ?: 0

    int choice = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format('fiscalYearDeletionDialog.confirm.message',
            fiscalYear.name, voucherCount as Object, attachmentCount as Object),
        I18n.instance.getString('fiscalYearDeletionDialog.confirm.title'),
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    if (choice != JOptionPane.OK_OPTION) {
      return
    }

    setWorkingState(true)
    summaryArea.text = I18n.instance.getString('fiscalYearDeletionDialog.status.executing')
    messageArea.text = ''
    new SwingWorker<FiscalYearDeletionResult, Void>() {
      @Override
      protected FiscalYearDeletionResult doInBackground() {
        deletionService.deleteFiscalYear(fiscalYear.id)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          FiscalYearDeletionResult result = get()
          summaryArea.text = deletionResultText(result)
          renderMessage(new Color(22, 101, 52),
              I18n.instance.format('fiscalYearDeletionDialog.result.success', fiscalYear.name))
          executeButton.enabled = false
          previewButton.enabled = false
          onDeleted.run()
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          renderMessage(new Color(153, 27, 27),
              I18n.instance.format('fiscalYearDeletionDialog.status.executeFailed', cause.message))
        }
      }
    }.execute()
  }

  private String deletionResultText(FiscalYearDeletionResult result) {
    List<String> rows = [
        I18n.instance.format('fiscalYearDeletionDialog.result.success', fiscalYear.name),
        I18n.instance.format('fiscalYearDeletionDialog.result.deletedFiles', result.deletedFiles.size() as Object)
    ]
    if (!result.failedFiles.isEmpty()) {
      rows << I18n.instance.format('fiscalYearDeletionDialog.result.failedFiles', result.failedFiles.size() as Object)
      result.failedFiles.each { StoredFileDeletionFailure failure ->
        rows << "- ${failure.storagePath}: ${failure.message}".toString()
      }
    }
    rows.join('\n')
  }

  private void renderMessage(Color color, String text) {
    messageArea.foreground = color
    messageArea.text = text ?: ''
  }

  private void setWorkingState(boolean working) {
    workInProgress = working
    previewButton.enabled = !working
    executeButton.enabled = !working
  }
}
