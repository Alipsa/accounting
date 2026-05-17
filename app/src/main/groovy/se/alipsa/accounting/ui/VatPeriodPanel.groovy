package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.support.AmountFormatter
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/**
 * Lists VAT periods and previews/report/transfer actions for the selected period.
 */
final class VatPeriodPanel extends JPanel implements PropertyChangeListener {

  private final VatService vatService
  private final FiscalYearService fiscalYearService
  private final ActiveCompanyManager activeCompanyManager
  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel summaryLabel = new JLabel(I18n.instance.getString('vatPeriodPanel.summary.initial'))
  private final JLabel transferDefaultsLabel = new JLabel(
      I18n.instance.format('vatPeriodPanel.transferDefaults',
          VatService.DEFAULT_TRANSFER_SERIES, VatService.DEFAULT_SETTLEMENT_ACCOUNT)
  )
  private final VatPeriodTableModel periodTableModel = new VatPeriodTableModel()
  private final VatReportTableModel reportTableModel = new VatReportTableModel()
  private final JTable periodTable = new JTable(periodTableModel)
  private final JTable reportTable = new JTable(reportTableModel)
  private JLabel fiscalYearLabel
  private JButton refreshButton
  private JButton reportButton
  private JButton transferButton
  private boolean fiscalYearListenerInstalled = false

  @PackageScope
  Closure<Boolean> bulkActionConfirmation = { String message, String title ->
    JOptionPane.showConfirmDialog(
        this,
        message,
        title,
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE
    ) == JOptionPane.YES_OPTION
  }

  VatPeriodPanel(VatService vatService, FiscalYearService fiscalYearService, ActiveCompanyManager activeCompanyManager) {
    this.vatService = vatService
    this.fiscalYearService = fiscalYearService
    this.activeCompanyManager = activeCompanyManager
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadFiscalYears()
    reloadPeriods()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater {
        reloadFiscalYears()
        reloadPeriods()
      }
    }
  }

  private void applyLocale() {
    fiscalYearLabel.text = I18n.instance.getString('vatPeriodPanel.label.fiscalYear')
    refreshButton.text = I18n.instance.getString('vatPeriodPanel.button.refresh')
    reportButton.text = I18n.instance.getString('vatPeriodPanel.button.report')
    transferButton.text = I18n.instance.getString('vatPeriodPanel.button.bookTransfer')
    transferDefaultsLabel.text = I18n.instance.format('vatPeriodPanel.transferDefaults',
        VatService.DEFAULT_TRANSFER_SERIES, VatService.DEFAULT_SETTLEMENT_ACCOUNT)
    periodTableModel.fireTableStructureChanged()
    reportTableModel.fireTableStructureChanged()
    reloadReportPreview()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    add(buildToolbar(), BorderLayout.NORTH)
    add(buildTables(), BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)

    periodTable.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
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
    fiscalYearLabel = new JLabel(I18n.instance.getString('vatPeriodPanel.label.fiscalYear'))
    filters.add(fiscalYearLabel)
    filters.add(fiscalYearComboBox)
    refreshButton = new JButton(I18n.instance.getString('vatPeriodPanel.button.refresh'))
    refreshButton.addActionListener {
      reloadFiscalYears()
      reloadPeriods()
    }
    filters.add(refreshButton)

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    reportButton = new JButton(I18n.instance.getString('vatPeriodPanel.button.report'))
    reportButton.addActionListener {
      reportSelectedPeriod()
    }
    transferButton = new JButton(I18n.instance.getString('vatPeriodPanel.button.bookTransfer'))
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
    if (!activeCompanyManager.hasActiveCompany()) {
      fiscalYearComboBox.removeAllItems()
      periodTableModel.setRows([])
      reportTableModel.setRows([])
      return
    }
    FiscalYear selected = selectedFiscalYear()
    fiscalYearComboBox.removeAllItems()
    fiscalYearService.listFiscalYears(activeCompanyManager.companyId).each { FiscalYear fiscalYear ->
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
      summaryLabel.text = I18n.instance.getString('vatPeriodPanel.summary.noPeriods')
      summaryLabel.toolTipText = null
    }
  }

  private void reloadReportPreview() {
    List<VatPeriod> selected = selectedPeriods()
    VatPeriod period = selected.isEmpty() ? null : selected.first()
    if (period == null) {
      reportTableModel.setRows([])
      summaryLabel.text = I18n.instance.getString('vatPeriodPanel.summary.initial')
      summaryLabel.toolTipText = null
      return
    }
    VatService.VatReport report = vatService.calculateReport(period.id)
    reportTableModel.setRows(report.rows)
    String previewText = previewSummary(selected, period)
    summaryLabel.text = previewText + '  ' + I18n.instance.format('vatPeriodPanel.summary.base',
        formatAmount(report.rows.sum(BigDecimal.ZERO) { VatService.VatReportRow row -> row.baseAmount } as BigDecimal)) +
        "  ${I18n.instance.format('vatPeriodPanel.summary.output', formatAmount(report.outputVatTotal))}" +
        "  ${I18n.instance.format('vatPeriodPanel.summary.input', formatAmount(report.inputVatTotal))}" +
        "  ${I18n.instance.format('vatPeriodPanel.summary.net', formatAmount(report.netVatToPay))}"
    summaryLabel.toolTipText = previewText
  }

  private static String previewSummary(List<VatPeriod> selected, VatPeriod period) {
    if (selected.size() <= 1) {
      return I18n.instance.format('vatPeriodPanel.summary.preview', period.periodName)
    }
    I18n.instance.format('vatPeriodPanel.summary.previewMultiple', period.periodName, selected.size())
  }

  private void reportSelectedPeriod() {
    List<VatPeriod> periods = selectedPeriods()
    if (periods.isEmpty()) {
      showError(I18n.instance.getString('vatPeriodPanel.error.selectPeriodReport'))
      return
    }
    int successCount = 0
    List<String> errors = []
    List<VatPeriod> sortedPeriods = periods.toSorted { VatPeriod period -> period.periodIndex }
    if (!confirmBulkAction(
        sortedPeriods,
        'vatPeriodPanel.confirm.reportMultiple',
        'vatPeriodPanel.confirm.reportMultiple.title'
    )) {
      return
    }
    for (VatPeriod period : sortedPeriods) {
      try {
        vatService.reportPeriod(period.id)
        successCount++
      } catch (IllegalArgumentException exception) {
        errors << exception.message
        break
      } catch (IllegalStateException exception) {
        errors << exception.message
        break
      }
    }
    reloadPeriods()
    VatPeriod periodToSelect = selectedPeriodAfterProcessing(sortedPeriods, successCount)
    selectPeriod(periodToSelect.id)
    if (errors.isEmpty()) {
      showInfo(reportSuccessMessage(successCount, periodToSelect))
    } else if (successCount > 0) {
      showError(reportSuccessMessage(successCount, periodToSelect) + '\n' + errors.join('\n'))
    } else {
      showError(errors.join('\n'))
    }
  }

  private void bookTransferForSelectedPeriod() {
    List<VatPeriod> periods = selectedPeriods()
    if (periods.isEmpty()) {
      showError(I18n.instance.getString('vatPeriodPanel.error.selectPeriodTransfer'))
      return
    }
    int successCount = 0
    List<String> errors = []
    List<VatPeriod> sortedPeriods = periods.toSorted { VatPeriod period -> period.periodIndex }
    if (!confirmBulkAction(
        sortedPeriods,
        'vatPeriodPanel.confirm.transferMultiple',
        'vatPeriodPanel.confirm.transferMultiple.title'
    )) {
      return
    }
    for (VatPeriod period : sortedPeriods) {
      try {
        vatService.bookTransfer(period.id)
        successCount++
      } catch (IllegalArgumentException exception) {
        errors << exception.message
        break
      } catch (IllegalStateException exception) {
        errors << exception.message
        break
      }
    }
    reloadPeriods()
    VatPeriod periodToSelect = selectedPeriodAfterProcessing(sortedPeriods, successCount)
    selectPeriod(periodToSelect.id)
    if (errors.isEmpty()) {
      showInfo(transferSuccessMessage(successCount, periodToSelect))
    } else if (successCount > 0) {
      showError(transferSuccessMessage(successCount, periodToSelect) + '\n' + errors.join('\n'))
    } else {
      showError(errors.join('\n'))
    }
  }

  private static String reportSuccessMessage(int successCount, VatPeriod period) {
    successCount == 1
        ? I18n.instance.format('vatPeriodPanel.message.reported', period.periodName)
        : I18n.instance.format('vatPeriodPanel.message.reportedMultiple', successCount)
  }

  private static String transferSuccessMessage(int successCount, VatPeriod period) {
    successCount == 1
        ? I18n.instance.format('vatPeriodPanel.message.transferBooked', period.periodName)
        : I18n.instance.format('vatPeriodPanel.message.transferBookedMultiple', successCount)
  }

  private static VatPeriod selectedPeriodAfterProcessing(List<VatPeriod> sortedPeriods, int successCount) {
    if (successCount <= 0) {
      return sortedPeriods.first()
    }
    sortedPeriods[successCount - 1]
  }

  private boolean confirmBulkAction(List<VatPeriod> sortedPeriods, String messageKey, String titleKey) {
    if (sortedPeriods.size() <= 1) {
      return true
    }
    String periodNames = sortedPeriods.collect { VatPeriod period -> period.periodName }.join(', ')
    bulkActionConfirmation.call(
        I18n.instance.format(messageKey, sortedPeriods.size(), periodNames),
        I18n.instance.getString(titleKey)
    )
  }

  private FiscalYear selectedFiscalYear() {
    fiscalYearComboBox.selectedItem as FiscalYear
  }

  private List<VatPeriod> selectedPeriods() {
    int[] rows = periodTable.selectedRows
    rows.collect { int row -> periodTableModel.rowAt(row) }
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
    feedbackArea.text = message ?: I18n.instance.getString('vatPeriodPanel.error.unexpected')
  }

  private String formatAmount(BigDecimal amount) {
    AmountFormatter.format(amount, activeCompanyManager.companyLocale)
  }

  private static final class VatPeriodTableModel extends AbstractTableModel {

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

    final int columnCount = 6

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('vatPeriodPanel.table.period.period')
        case 1: return I18n.instance.getString('vatPeriodPanel.table.period.from')
        case 2: return I18n.instance.getString('vatPeriodPanel.table.period.to')
        case 3: return I18n.instance.getString('vatPeriodPanel.table.period.status')
        case 4: return I18n.instance.getString('vatPeriodPanel.table.period.reported')
        case 5: return I18n.instance.getString('vatPeriodPanel.table.period.transfer')
        default: return ''
      }
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

  private final class VatReportTableModel extends AbstractTableModel {

    private List<VatService.VatReportRow> rows = []

    void setRows(List<VatService.VatReportRow> rows) {
      this.rows = rows ?: []
      fireTableDataChanged()
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('vatPeriodPanel.table.report.code')
        case 1: return I18n.instance.getString('vatPeriodPanel.table.report.label')
        case 2: return I18n.instance.getString('vatPeriodPanel.table.report.base')
        case 3: return I18n.instance.getString('vatPeriodPanel.table.report.outputVat')
        case 4: return I18n.instance.getString('vatPeriodPanel.table.report.inputVat')
        default: return ''
      }
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
