package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VatService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.math.RoundingMode
import java.text.DecimalFormat

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/**
 * Lists VAT periods and previews/report/transfer actions for the selected period.
 */
@CompileStatic
final class VatPeriodPanel extends JPanel {

  private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat('#,##0.00')

  private final VatService vatService
  private final FiscalYearService fiscalYearService
  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel summaryLabel = new JLabel('Välj en momsperiod för att visa rapporten.')
  private final JLabel transferDefaultsLabel = new JLabel(
      "Momsöverföring bokförs i serie ${VatService.DEFAULT_TRANSFER_SERIES} mot konto ${VatService.DEFAULT_SETTLEMENT_ACCOUNT}."
  )
  private final VatPeriodTableModel periodTableModel = new VatPeriodTableModel()
  private final VatReportTableModel reportTableModel = new VatReportTableModel()
  private final JTable periodTable = new JTable(periodTableModel)
  private final JTable reportTable = new JTable(reportTableModel)
  private boolean fiscalYearListenerInstalled = false

  VatPeriodPanel(VatService vatService, FiscalYearService fiscalYearService) {
    this.vatService = vatService
    this.fiscalYearService = fiscalYearService
    buildUi()
    reloadFiscalYears()
    reloadPeriods()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    add(buildToolbar(), BorderLayout.NORTH)
    add(buildTables(), BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)

    periodTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    reportTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    periodTable.selectionModel.addListSelectionListener { ListSelectionEvent event ->
      if (!event.valueIsAdjusting) {
        reloadReportPreview()
      }
    }
  }

  private JPanel buildToolbar() {
    JPanel panel = new JPanel(new BorderLayout(0, 8))

    JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    filters.add(new JLabel('Räkenskapsår'))
    filters.add(fiscalYearComboBox)
    JButton refreshButton = new JButton('Uppdatera')
    refreshButton.addActionListener {
      reloadFiscalYears()
      reloadPeriods()
    }
    filters.add(refreshButton)

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    JButton reportButton = new JButton('Rapportera vald period')
    reportButton.addActionListener {
      reportSelectedPeriod()
    }
    JButton transferButton = new JButton('Bokför momsöverföring')
    transferButton.addActionListener {
      bookTransferForSelectedPeriod()
    }
    actions.add(reportButton)
    actions.add(transferButton)

    panel.add(filters, BorderLayout.NORTH)
    panel.add(transferDefaultsLabel, BorderLayout.CENTER)
    panel.add(actions, BorderLayout.SOUTH)
    panel
  }

  private JPanel buildTables() {
    JPanel summaryPanel = new JPanel(new BorderLayout())
    summaryPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0))
    summaryPanel.add(summaryLabel, BorderLayout.CENTER)

    JSplitPane splitPane = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(periodTable),
        new JScrollPane(reportTable)
    )
    splitPane.resizeWeight = 0.4d

    JPanel container = new JPanel(new BorderLayout(0, 8))
    container.add(summaryPanel, BorderLayout.NORTH)
    container.add(splitPane, BorderLayout.CENTER)
    container
  }

  private JTextArea buildFooter() {
    feedbackArea.editable = false
    feedbackArea.lineWrap = true
    feedbackArea.wrapStyleWord = true
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.opaque = false
    feedbackArea
  }

  private void reloadFiscalYears() {
    FiscalYear selected = selectedFiscalYear()
    fiscalYearComboBox.removeAllItems()
    fiscalYearService.listFiscalYears().each { FiscalYear fiscalYear ->
      fiscalYearComboBox.addItem(fiscalYear)
    }
    if (selected != null) {
      selectFiscalYear(selected.id)
    }
    if (!fiscalYearListenerInstalled) {
      fiscalYearComboBox.addActionListener {
        reloadPeriods()
      }
      fiscalYearListenerInstalled = true
    }
  }

  private void reloadPeriods() {
    FiscalYear fiscalYear = selectedFiscalYear()
    List<VatPeriod> periods = fiscalYear == null ? [] : vatService.listPeriods(fiscalYear.id)
    periodTableModel.setRows(periods)
    if (!periods.isEmpty()) {
      periodTable.setRowSelectionInterval(0, 0)
    } else {
      reportTableModel.setRows([])
      summaryLabel.text = 'Inga momsperioder tillgängliga för valt räkenskapsår.'
    }
  }

  private void reloadReportPreview() {
    VatPeriod period = selectedPeriod()
    if (period == null) {
      reportTableModel.setRows([])
      summaryLabel.text = 'Välj en momsperiod för att visa rapporten.'
      return
    }
    VatService.VatReport report = vatService.calculateReport(period.id)
    reportTableModel.setRows(report.rows)
    summaryLabel.text = "Bas: ${formatAmount(report.rows.sum(BigDecimal.ZERO) { VatService.VatReportRow row -> row.baseAmount } as BigDecimal)}" +
        "  Utgående: ${formatAmount(report.outputVatTotal)}" +
        "  Ingående: ${formatAmount(report.inputVatTotal)}" +
        "  Netto: ${formatAmount(report.netVatToPay)}"
  }

  private void reportSelectedPeriod() {
    VatPeriod period = selectedPeriod()
    if (period == null) {
      showError('Välj en momsperiod att rapportera.')
      return
    }
    try {
      VatPeriod reportedPeriod = vatService.reportPeriod(period.id)
      reloadPeriods()
      selectPeriod(reportedPeriod.id)
      showInfo("Momsperiod ${reportedPeriod.periodName} är rapporterad.")
    } catch (IllegalArgumentException exception) {
      showError(exception.message)
    } catch (IllegalStateException exception) {
      showError(exception.message)
    }
  }

  private void bookTransferForSelectedPeriod() {
    VatPeriod period = selectedPeriod()
    if (period == null) {
      showError('Välj en momsperiod för momsöverföring.')
      return
    }
    try {
      vatService.bookTransfer(period.id)
      reloadPeriods()
      selectPeriod(period.id)
      showInfo("Momsöverföring bokfördes för period ${period.periodName}.")
    } catch (IllegalArgumentException exception) {
      showError(exception.message)
    } catch (IllegalStateException exception) {
      showError(exception.message)
    }
  }

  private FiscalYear selectedFiscalYear() {
    fiscalYearComboBox.selectedItem as FiscalYear
  }

  private VatPeriod selectedPeriod() {
    int row = periodTable.selectedRow
    row < 0 ? null : periodTableModel.rowAt(row)
  }

  private void selectFiscalYear(Long fiscalYearId) {
    if (fiscalYearId == null) {
      return
    }
    for (int index = 0; index < fiscalYearComboBox.itemCount; index++) {
      FiscalYear fiscalYear = fiscalYearComboBox.getItemAt(index)
      if (fiscalYear?.id == fiscalYearId) {
        fiscalYearComboBox.selectedIndex = index
        return
      }
    }
  }

  private void selectPeriod(Long vatPeriodId) {
    if (vatPeriodId == null) {
      return
    }
    int index = periodTableModel.indexOf(vatPeriodId)
    if (index >= 0) {
      periodTable.setRowSelectionInterval(index, index)
    }
  }

  private void showInfo(String message) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = message
  }

  private void showError(String message) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = message ?: 'Ett oväntat fel uppstod.'
  }

  private static String formatAmount(BigDecimal amount) {
    AMOUNT_FORMAT.format((amount ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
  }

  private static final class VatPeriodTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Period', 'Från', 'Till', 'Status', 'Rapporterad', 'Transfer']
    private List<VatPeriod> rows = []

    void setRows(List<VatPeriod> rows) {
      this.rows = rows ?: []
      fireTableDataChanged()
    }

    VatPeriod rowAt(int rowIndex) {
      rows[rowIndex]
    }

    int indexOf(Long vatPeriodId) {
      rows.findIndexOf { VatPeriod period -> period.id == vatPeriodId }
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    int getColumnCount() {
      COLUMNS.size()
    }

    @Override
    String getColumnName(int column) {
      COLUMNS[column]
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      VatPeriod row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.periodName
        case 1:
          return row.startDate
        case 2:
          return row.endDate
        case 3:
          return row.status
        case 4:
          return row.reportedAt
        case 5:
          return row.transferVoucherId
        default:
          return ''
      }
    }
  }

  private static final class VatReportTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Kod', 'Benämning', 'Bas', 'Utgående moms', 'Ingående moms']
    private List<VatService.VatReportRow> rows = []

    void setRows(List<VatService.VatReportRow> rows) {
      this.rows = rows ?: []
      fireTableDataChanged()
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    int getColumnCount() {
      COLUMNS.size()
    }

    @Override
    String getColumnName(int column) {
      COLUMNS[column]
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      VatService.VatReportRow row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.vatCode.name()
        case 1:
          return row.label
        case 2:
          return formatAmount(row.baseAmount)
        case 3:
          return formatAmount(row.outputVatAmount)
        case 4:
          return formatAmount(row.inputVatAmount)
        default:
          return ''
      }
    }
  }
}
