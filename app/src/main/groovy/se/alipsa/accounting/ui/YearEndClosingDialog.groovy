package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.YearEndClosingPreview
import se.alipsa.accounting.service.YearEndClosingResult

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
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingWorker

/**
 * Runs preview checks and confirmation for year-end closing.
 */
@CompileStatic
final class YearEndClosingDialog extends JDialog {

  private final ClosingService closingService
  private final FiscalYear fiscalYear
  private final Runnable onClosed

  private final JTextField closingAccountField = new JTextField(8)
  private final JLabel nextFiscalYearLabel = new JLabel('-')
  private final JTextArea summaryArea = new JTextArea(8, 48)
  private final JTextArea messageArea = new JTextArea(6, 48)
  private final JButton previewButton = new JButton('Förhandskontroll')
  private final JButton executeButton = new JButton('Genomför bokslut')
  private boolean workInProgress

  YearEndClosingDialog(Frame owner, ClosingService closingService, FiscalYear fiscalYear, Runnable onClosed) {
    super(owner, 'Årsbokslut', true)
    this.closingService = closingService
    this.fiscalYear = fiscalYear
    this.onClosed = onClosed ?: ({ } as Runnable)
    closingAccountField.text = ClosingService.DEFAULT_CLOSING_ACCOUNT
    buildUi()
    reloadPreview()
  }

  static void showDialog(Frame owner, ClosingService closingService, FiscalYear fiscalYear, Runnable onClosed) {
    YearEndClosingDialog dialog = new YearEndClosingDialog(owner, closingService, fiscalYear, onClosed)
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
    closingAccountField.addActionListener { reloadPreview() }
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

    panel.add(new JLabel('Räkenskapsår'), labelConstraints)
    panel.add(new JLabel("${fiscalYear.name} (${fiscalYear.startDate} - ${fiscalYear.endDate})"), fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel('Nästa räkenskapsår'), labelConstraints)
    panel.add(nextFiscalYearLabel, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    panel.add(new JLabel('Resultatkonto'), labelConstraints)
    panel.add(closingAccountField, fieldConstraints)

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
    JButton closeButton = new JButton('Stäng')
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
    summaryArea.text = 'Kör förhandskontroller...'
    messageArea.text = ''
    new SwingWorker<YearEndClosingPreview, Void>() {
      @Override
      protected YearEndClosingPreview doInBackground() {
        closingService.previewClosing(fiscalYear.id, closingAccountField.text)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          renderPreview(get())
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          renderMessage(new Color(153, 27, 27), 'Förhandskontrollen avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          renderMessage(new Color(153, 27, 27), cause.message ?: 'Förhandskontrollen kunde inte slutföras.')
        }
      }
    }.execute()
  }

  private void renderPreview(YearEndClosingPreview preview) {
    nextFiscalYearLabel.text = preview.nextFiscalYear == null
        ? 'Kan inte skapas automatiskt'
        : preview.nextFiscalYearWillBeCreated
        ? "${preview.nextFiscalYear.name} (skapas nu)"
        : "${preview.nextFiscalYear.name} (finns redan)"

    List<String> rows = [
        "Intäkter: ${preview.incomeTotal.toPlainString()}".toString(),
        "Kostnader: ${preview.expenseTotal.toPlainString()}".toString(),
        "Årets resultat: ${preview.netResult.toPlainString()}".toString(),
        "Resultatkonton att stänga: ${preview.resultAccountCount}".toString()
    ]
    summaryArea.text = rows.join('\n')

    if (!preview.blockingIssues.isEmpty()) {
      executeButton.enabled = false
      renderMessage(new Color(153, 27, 27), preview.blockingIssues.collect { String row -> "• ${row}" }.join('\n'))
      return
    }

    executeButton.enabled = true
    if (!preview.warnings.isEmpty()) {
      renderMessage(new Color(146, 64, 14), preview.warnings.collect { String row -> "• ${row}" }.join('\n'))
    } else {
      renderMessage(new Color(22, 101, 52), 'Förhandskontrollerna är godkända.')
    }
  }

  private void executeRequested() {
    int choice = javax.swing.JOptionPane.showConfirmDialog(
        this,
        "Genomför årsbokslut för ${fiscalYear.name} och skapa nästa års ingående balanser?",
        'Bekräfta årsbokslut',
        javax.swing.JOptionPane.OK_CANCEL_OPTION
    )
    if (choice != javax.swing.JOptionPane.OK_OPTION) {
      return
    }

    setWorkingState(true)
    summaryArea.text = 'Genomför årsbokslut...'
    new SwingWorker<YearEndClosingResult, Void>() {
      @Override
      protected YearEndClosingResult doInBackground() {
        closingService.closeFiscalYear(fiscalYear.id, closingAccountField.text)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          YearEndClosingResult result = get()
          renderResult(result)
          executeButton.enabled = false
          onClosed.run()
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          renderMessage(new Color(153, 27, 27), 'Årsbokslutet avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          renderMessage(new Color(153, 27, 27), cause.message ?: 'Årsbokslutet kunde inte genomföras.')
        }
      }
    }.execute()
  }

  private void renderResult(YearEndClosingResult result) {
    List<String> rows = [
        "Räkenskapsåret ${result.closedFiscalYear.name} är nu stängt.".toString(),
        "Nästa räkenskapsår: ${result.nextFiscalYear.name}".toString(),
        result.closingVoucher == null
            ? 'Bokslutsverifikation: ingen behövdes'
            : "Bokslutsverifikation: ${result.closingVoucher.voucherNumber}".toString(),
        "Skapade ingående balanser: ${result.openingBalanceCount}".toString(),
        "Registrerade bokslutsposter: ${result.closingEntryCount}".toString()
    ]
    summaryArea.text = rows.join('\n')
    if (!result.warnings.isEmpty()) {
      renderMessage(new Color(146, 64, 14), result.warnings.collect { String row -> "• ${row}" }.join('\n'))
    } else {
      renderMessage(new Color(22, 101, 52), 'Årsbokslutet genomfördes utan varningar.')
    }
  }

  private void renderMessage(Color color, String text) {
    messageArea.foreground = color
    messageArea.text = text ?: ''
  }

  private void setWorkingState(boolean working) {
    workInProgress = working
    previewButton.enabled = !working
    executeButton.enabled = !working
    closingAccountField.enabled = !working
  }
}
