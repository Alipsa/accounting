package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.JournoReportService
import se.alipsa.accounting.service.ReportArchiveService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportExportService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ExecutionException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.table.AbstractTableModel

/**
 * Previews reports and exports them to PDF or CSV while keeping an archive.
 */
final class ReportPanel extends JPanel implements PropertyChangeListener {

  private final ReportDataService reportDataService
  private final JournoReportService journoReportService
  private final ReportExportService reportExportService
  private final ReportArchiveService reportArchiveService
  private final FiscalYearService fiscalYearService
  private final AccountingPeriodService accountingPeriodService
  private final VoucherEditor.Dependencies voucherEditorDependencies
  private final ActiveCompanyManager activeCompanyManager
  private final JComboBox<ReportType> reportTypeComboBox = new JComboBox<>(ReportType.values())
  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JComboBox<Object> accountingPeriodComboBox = new JComboBox<>()
  private final JTextField startDateField = new JTextField(10)
  private final JTextField endDateField = new JTextField(10)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel summaryLabel = new JLabel(I18n.instance.getString('reportPanel.summary.initial'))
  private final PreviewTableModel previewTableModel = new PreviewTableModel()
  private final JTable previewTable = new JTable(previewTableModel)
  private final ArchiveTableModel archiveTableModel = new ArchiveTableModel()
  private final JTable archiveTable = new JTable(archiveTableModel)
  private final JButton exportCsvButton = new JButton(I18n.instance.getString('reportPanel.button.exportCsv'))
  private final JButton generatePdfButton = new JButton(I18n.instance.getString('reportPanel.button.generatePdf'))
  private final JButton openVoucherButton = new JButton(I18n.instance.getString('reportPanel.button.openVoucher'))
  private final JButton openArchiveButton = new JButton(I18n.instance.getString('reportPanel.button.openArchive'))
  private JLabel reportLabel
  private JLabel fiscalYearLabel
  private JLabel periodLabel
  private JLabel fromLabel
  private JLabel toLabel
  private JButton previewButton
  private ReportResult currentReport
  private boolean pdfGenerationInProgress

  ReportPanel(
      ReportDataService reportDataService,
      JournoReportService journoReportService,
      ReportExportService reportExportService,
      ReportArchiveService reportArchiveService,
      FiscalYearService fiscalYearService,
      AccountingPeriodService accountingPeriodService,
      VoucherEditor.Dependencies voucherEditorDependencies,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.reportDataService = reportDataService
    this.journoReportService = journoReportService
    this.reportExportService = reportExportService
    this.reportArchiveService = reportArchiveService
    this.fiscalYearService = fiscalYearService
    this.accountingPeriodService = accountingPeriodService
    this.voucherEditorDependencies = voucherEditorDependencies
    this.activeCompanyManager = activeCompanyManager
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadFiscalYears()
    reloadArchives()
    reloadReport()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater {
        reloadFiscalYears()
        reloadArchives()
        reloadReport()
      }
    }
  }

  private void applyLocale() {
    reportLabel.text = I18n.instance.getString('reportPanel.label.report')
    fiscalYearLabel.text = I18n.instance.getString('reportPanel.label.fiscalYear')
    periodLabel.text = I18n.instance.getString('reportPanel.label.period')
    fromLabel.text = I18n.instance.getString('reportPanel.label.from')
    toLabel.text = I18n.instance.getString('reportPanel.label.to')
    previewButton.text = I18n.instance.getString('reportPanel.button.preview')
    exportCsvButton.text = I18n.instance.getString('reportPanel.button.exportCsv')
    generatePdfButton.text = I18n.instance.getString('reportPanel.button.generatePdf')
    openVoucherButton.text = I18n.instance.getString('reportPanel.button.openVoucher')
    openArchiveButton.text = I18n.instance.getString('reportPanel.button.openArchive')
    reloadAccountingPeriods()
    reloadReport()
    archiveTableModel.fireTableStructureChanged()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    add(buildToolbar(), BorderLayout.NORTH)
    add(buildCenter(), BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)

    previewTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    previewTable.selectionModel.addListSelectionListener {
      updateActionButtons()
    }
    archiveTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    archiveTable.selectionModel.addListSelectionListener {
      updateActionButtons()
    }
    reportTypeComboBox.addActionListener {
      updateActionButtons()
    }
  }

  private JPanel buildToolbar() {
    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(buildFilterGrid(), BorderLayout.NORTH)
    panel.add(summaryLabel, BorderLayout.CENTER)
    panel.add(buildActionButtons(), BorderLayout.SOUTH)
    fiscalYearComboBox.addActionListener {
      reloadAccountingPeriods()
    }
    accountingPeriodComboBox.addActionListener {
      applySelectedPeriodDates()
    }
    panel
  }

  private JPanel buildFilterGrid() {
    JPanel filters = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 8, 16), 0, 0
    )

    reportLabel = new JLabel(I18n.instance.getString('reportPanel.label.report'))
    filters.add(reportLabel, labelConstraints)
    filters.add(reportTypeComboBox, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    fiscalYearLabel = new JLabel(I18n.instance.getString('reportPanel.label.fiscalYear'))
    filters.add(fiscalYearLabel, labelConstraints)
    filters.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    periodLabel = new JLabel(I18n.instance.getString('reportPanel.label.period'))
    filters.add(periodLabel, labelConstraints)
    filters.add(accountingPeriodComboBox, fieldConstraints)

    labelConstraints.gridx = 0
    labelConstraints.gridy = 1
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = 1
    fromLabel = new JLabel(I18n.instance.getString('reportPanel.label.from'))
    filters.add(fromLabel, labelConstraints)
    filters.add(startDateField, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    toLabel = new JLabel(I18n.instance.getString('reportPanel.label.to'))
    filters.add(toLabel, labelConstraints)
    filters.add(endDateField, fieldConstraints)
    filters
  }

  private JPanel buildActionButtons() {
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    previewButton = new JButton(I18n.instance.getString('reportPanel.button.preview'))
    previewButton.addActionListener {
      reloadReport()
    }
    exportCsvButton.addActionListener {
      exportCsv()
    }
    generatePdfButton.addActionListener {
      generatePdf()
    }
    openVoucherButton.addActionListener {
      openSelectedVoucher()
    }
    openArchiveButton.addActionListener {
      openSelectedArchive()
    }
    actions.add(previewButton)
    actions.add(exportCsvButton)
    actions.add(generatePdfButton)
    actions.add(openVoucherButton)
    actions.add(openArchiveButton)
    actions
  }

  private JSplitPane buildCenter() {
    JSplitPane splitPane = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(previewTable),
        new JScrollPane(archiveTable)
    )
    splitPane.resizeWeight = 0.75d
    splitPane
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
    reloadAccountingPeriods()
  }

  private void reloadAccountingPeriods() {
    Object selected = accountingPeriodComboBox.selectedItem
    FiscalYear fiscalYear = selectedFiscalYear()
    accountingPeriodComboBox.removeAllItems()
    accountingPeriodComboBox.addItem(I18n.instance.getString('reportPanel.fullYearOption'))
    if (fiscalYear != null) {
      accountingPeriodService.listPeriods(fiscalYear.id).each { AccountingPeriod period ->
        accountingPeriodComboBox.addItem(period)
      }
    }
    if (selected instanceof AccountingPeriod) {
      selectAccountingPeriod(((AccountingPeriod) selected).id)
    } else {
      accountingPeriodComboBox.selectedIndex = 0
    }
    applySelectedPeriodDates()
  }

  private void applySelectedPeriodDates() {
    FiscalYear fiscalYear = selectedFiscalYear()
    Object periodSelection = accountingPeriodComboBox.selectedItem
    if (periodSelection instanceof AccountingPeriod) {
      AccountingPeriod period = (AccountingPeriod) periodSelection
      startDateField.text = period.startDate.toString()
      endDateField.text = period.endDate.toString()
    } else if (fiscalYear != null) {
      startDateField.text = fiscalYear.startDate.toString()
      endDateField.text = fiscalYear.endDate.toString()
    } else {
      startDateField.text = ''
      endDateField.text = ''
    }
  }

  private void reloadReport() {
    try {
      currentReport = reportDataService.generate(currentSelection())
      previewTableModel.setData(currentReport.tableHeaders, currentReport.tableRows, currentReport.rowVoucherIds)
      summaryLabel.text = "<html><b>${escapeHtml(currentReport.title)}</b><br/>${escapeHtml(currentReport.selectionLabel)}" +
          "<br/>${escapeHtml(currentReport.summaryLines.join(' | '))}</html>"
      updateActionButtons()
      showInfo(I18n.instance.format('reportPanel.message.previewUpdated', currentReport.title))
    } catch (IllegalArgumentException exception) {
      currentReport = null
      previewTableModel.clear()
      summaryLabel.text = I18n.instance.getString('reportPanel.summary.cannotShow')
      updateActionButtons()
      showError(exception.message)
    } catch (IllegalStateException exception) {
      currentReport = null
      previewTableModel.clear()
      summaryLabel.text = I18n.instance.getString('reportPanel.summary.buildFailed')
      updateActionButtons()
      showError(exception.message)
    }
  }

  private void exportCsv() {
    try {
      ReportArchive archive = reportExportService.exportCsv(currentSelection())
      reloadArchives()
      showInfo(I18n.instance.format('reportPanel.message.csvExported', archive.fileName))
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message)
    }
  }

  private void generatePdf() {
    ReportSelection selection
    try {
      selection = currentSelection()
    } catch (IllegalArgumentException exception) {
      showError(exception.message)
      return
    }
    pdfGenerationInProgress = true
    updateActionButtons()
    showInfo(I18n.instance.getString('reportPanel.message.pdfGenerating'))
    new SwingWorker<ReportArchive, Void>() {
      @Override
      protected ReportArchive doInBackground() {
        journoReportService.generatePdf(selection)
      }

      @Override
      protected void done() {
        pdfGenerationInProgress = false
        updateActionButtons()
        try {
          ReportArchive archive = get()
          reloadArchives()
          showInfo(I18n.instance.format('reportPanel.message.pdfCreated', archive.fileName))
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('reportPanel.error.pdfInterrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: I18n.instance.getString('reportPanel.error.pdfFailed'))
        }
      }
    }.execute()
  }

  private void reloadArchives() {
    archiveTableModel.setRows(reportArchiveService.listArchives(activeCompanyManager.companyId, 100))
    updateActionButtons()
  }

  private void openSelectedVoucher() {
    Long voucherId = selectedVoucherId()
    if (voucherId == null) {
      return
    }
    VoucherEditor.showDialog(ownerFrame(), voucherEditorDependencies, activeCompanyManager.companyId, voucherId, {
      reloadReport()
    } as Runnable)
  }

  private void openSelectedArchive() {
    ReportArchive archive = selectedArchive()
    if (archive == null) {
      showError(I18n.instance.getString('reportPanel.error.selectArchive'))
      return
    }
    try {
      Path path = reportArchiveService.resolveStoredPath(archive)
      if (Desktop.isDesktopSupported()) {
        Desktop.desktop.open(path.toFile())
      } else {
        showInfo(I18n.instance.format('reportPanel.message.archivePath', path.toAbsolutePath()))
      }
    } catch (Exception exception) {
      showError(exception.message ?: I18n.instance.getString('reportPanel.error.archiveOpenFailed'))
    }
  }

  private ReportSelection currentSelection() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      throw new IllegalArgumentException(I18n.instance.getString('reportPanel.error.selectFiscalYear'))
    }
    LocalDate startDate = parseDate(startDateField.text, I18n.instance.getString('reportPanel.label.from'))
    LocalDate endDate = parseDate(endDateField.text, I18n.instance.getString('reportPanel.label.to'))
    AccountingPeriod period = selectedAccountingPeriod()
    boolean usesSelectedPeriod = period != null && startDate == period.startDate && endDate == period.endDate
    new ReportSelection(
        reportTypeComboBox.selectedItem as ReportType,
        fiscalYear.id,
        usesSelectedPeriod ? period.id : null,
        startDate,
        endDate
    )
  }

  private Long selectedVoucherId() {
    int row = previewTable.selectedRow
    row < 0 ? null : previewTableModel.voucherIdAt(row)
  }

  private ReportArchive selectedArchive() {
    int row = archiveTable.selectedRow
    row < 0 ? null : archiveTableModel.rowAt(row)
  }

  private FiscalYear selectedFiscalYear() {
    fiscalYearComboBox.selectedItem as FiscalYear
  }

  private AccountingPeriod selectedAccountingPeriod() {
    Object selected = accountingPeriodComboBox.selectedItem
    selected instanceof AccountingPeriod ? (AccountingPeriod) selected : null
  }

  private void selectFiscalYear(Long fiscalYearId) {
    for (int index = 0; index < fiscalYearComboBox.itemCount; index++) {
      FiscalYear fiscalYear = fiscalYearComboBox.getItemAt(index)
      if (fiscalYear?.id == fiscalYearId) {
        fiscalYearComboBox.selectedIndex = index
        return
      }
    }
  }

  private void selectAccountingPeriod(Long accountingPeriodId) {
    if (accountingPeriodId == null) {
      accountingPeriodComboBox.selectedIndex = 0
      return
    }
    for (int index = 0; index < accountingPeriodComboBox.itemCount; index++) {
      Object item = accountingPeriodComboBox.getItemAt(index)
      if (item instanceof AccountingPeriod && ((AccountingPeriod) item).id == accountingPeriodId) {
        accountingPeriodComboBox.selectedIndex = index
        return
      }
    }
  }

  private void updateActionButtons() {
    exportCsvButton.enabled = currentReport?.reportType?.csvSupported ?: false
    generatePdfButton.enabled = currentReport != null && !pdfGenerationInProgress
    Long voucherId = selectedVoucherId()
    openVoucherButton.enabled = voucherId != null
    if (voucherId != null) {
      openVoucherButton.toolTipText = I18n.instance.getString('reportPanel.tooltip.openVoucher')
    } else if (previewTable.selectedRow >= 0) {
      openVoucherButton.toolTipText = I18n.instance.getString('reportPanel.tooltip.noVoucher')
    } else {
      openVoucherButton.toolTipText = I18n.instance.getString('reportPanel.tooltip.selectRow')
    }
    openArchiveButton.enabled = selectedArchive() != null
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
  }

  private static LocalDate parseDate(String value, String label) {
    try {
      return LocalDate.parse(value?.trim())
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(I18n.instance.format('reportPanel.error.dateFormat', label))
    }
  }

  private void showInfo(String message) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = message
  }

  private void showError(String message) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = message ?: I18n.instance.getString('reportPanel.error.unexpected')
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

  private static final class PreviewTableModel extends AbstractTableModel {

    private List<String> headers = []
    private List<List<String>> rows = []
    private List<Long> voucherIds = []

    void setData(List<String> headers, List<List<String>> rows, List<Long> voucherIds) {
      this.headers = headers ?: []
      this.rows = rows ?: []
      this.voucherIds = voucherIds ?: []
      fireTableStructureChanged()
    }

    void clear() {
      setData([], [], [])
    }

    Long voucherIdAt(int rowIndex) {
      rowIndex >= 0 && rowIndex < voucherIds.size() ? voucherIds[rowIndex] : null
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    int getColumnCount() {
      headers.size()
    }

    @Override
    String getColumnName(int column) {
      headers[column]
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      rows[rowIndex][columnIndex]
    }
  }

  private static final class ArchiveTableModel extends AbstractTableModel {

    private List<ReportArchive> rows = []

    void setRows(List<ReportArchive> rows) {
      this.rows = rows ?: []
      fireTableDataChanged()
    }

    ReportArchive rowAt(int rowIndex) {
      rows[rowIndex]
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('reportPanel.table.archive.time')
        case 1: return I18n.instance.getString('reportPanel.table.archive.report')
        case 2: return I18n.instance.getString('reportPanel.table.archive.format')
        case 3: return I18n.instance.getString('reportPanel.table.archive.interval')
        case 4: return I18n.instance.getString('reportPanel.table.archive.file')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      ReportArchive row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.createdAt
        case 1:
          return row.reportType.displayName
        case 2:
          return row.reportFormat
        case 3:
          return "${row.startDate} - ${row.endDate}"
        case 4:
          return row.fileName
        default:
          return ''
      }
    }
  }
}
