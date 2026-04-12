package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

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

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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
@CompileStatic
final class ReportPanel extends JPanel {

  private static final String FULL_YEAR_OPTION = 'Hela året'

  private final ReportDataService reportDataService
  private final JournoReportService journoReportService
  private final ReportExportService reportExportService
  private final ReportArchiveService reportArchiveService
  private final FiscalYearService fiscalYearService
  private final AccountingPeriodService accountingPeriodService
  private final VoucherEditor.Dependencies voucherEditorDependencies
  private final JComboBox<ReportType> reportTypeComboBox = new JComboBox<>(ReportType.values())
  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JComboBox<Object> accountingPeriodComboBox = new JComboBox<>()
  private final JTextField startDateField = new JTextField(10)
  private final JTextField endDateField = new JTextField(10)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel summaryLabel = new JLabel('Välj rapport och urval för att visa förhandsgranskning.')
  private final PreviewTableModel previewTableModel = new PreviewTableModel()
  private final JTable previewTable = new JTable(previewTableModel)
  private final ArchiveTableModel archiveTableModel = new ArchiveTableModel()
  private final JTable archiveTable = new JTable(archiveTableModel)
  private final JButton exportCsvButton = new JButton('Exportera CSV')
  private final JButton generatePdfButton = new JButton('Skapa PDF')
  private final JButton openVoucherButton = new JButton('Öppna verifikation')
  private final JButton openArchiveButton = new JButton('Öppna arkivfil')
  private ReportResult currentReport
  private boolean pdfGenerationInProgress

  ReportPanel(
      ReportDataService reportDataService,
      JournoReportService journoReportService,
      ReportExportService reportExportService,
      ReportArchiveService reportArchiveService,
      FiscalYearService fiscalYearService,
      AccountingPeriodService accountingPeriodService,
      VoucherEditor.Dependencies voucherEditorDependencies
  ) {
    this.reportDataService = reportDataService
    this.journoReportService = journoReportService
    this.reportExportService = reportExportService
    this.reportArchiveService = reportArchiveService
    this.fiscalYearService = fiscalYearService
    this.accountingPeriodService = accountingPeriodService
    this.voucherEditorDependencies = voucherEditorDependencies
    buildUi()
    reloadFiscalYears()
    reloadArchives()
    reloadReport()
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

    filters.add(new JLabel('Rapport'), labelConstraints)
    filters.add(reportTypeComboBox, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    filters.add(new JLabel('Räkenskapsår'), labelConstraints)
    filters.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    filters.add(new JLabel('Period'), labelConstraints)
    filters.add(accountingPeriodComboBox, fieldConstraints)

    labelConstraints.gridx = 0
    labelConstraints.gridy = 1
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = 1
    filters.add(new JLabel('Från'), labelConstraints)
    filters.add(startDateField, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    filters.add(new JLabel('Till'), labelConstraints)
    filters.add(endDateField, fieldConstraints)

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    JButton previewButton = new JButton('Förhandsgranska')
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

    fiscalYearComboBox.addActionListener {
      reloadAccountingPeriods()
    }
    accountingPeriodComboBox.addActionListener {
      applySelectedPeriodDates()
    }

    panel.add(filters, BorderLayout.NORTH)
    panel.add(summaryLabel, BorderLayout.CENTER)
    panel.add(actions, BorderLayout.SOUTH)
    panel
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
    FiscalYear selected = selectedFiscalYear()
    fiscalYearComboBox.removeAllItems()
    fiscalYearService.listFiscalYears().each { FiscalYear fiscalYear ->
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
    accountingPeriodComboBox.addItem(FULL_YEAR_OPTION)
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
      showInfo("Förhandsgranskning uppdaterad för ${currentReport.title}.")
    } catch (IllegalArgumentException exception) {
      currentReport = null
      previewTableModel.clear()
      summaryLabel.text = 'Rapporten kan inte visas med valt urval.'
      updateActionButtons()
      showError(exception.message)
    } catch (IllegalStateException exception) {
      currentReport = null
      previewTableModel.clear()
      summaryLabel.text = 'Rapporten kunde inte byggas.'
      updateActionButtons()
      showError(exception.message)
    }
  }

  private void exportCsv() {
    try {
      ReportArchive archive = reportExportService.exportCsv(currentSelection())
      reloadArchives()
      showInfo("CSV exporterades och arkiverades som ${archive.fileName}.")
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
    showInfo('PDF skapas, vänta...')
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
          showInfo("PDF skapades och arkiverades som ${archive.fileName}.")
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('PDF-genereringen avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: 'PDF kunde inte skapas.')
        }
      }
    }.execute()
  }

  private void reloadArchives() {
    archiveTableModel.setRows(reportArchiveService.listArchives(100))
    updateActionButtons()
  }

  private void openSelectedVoucher() {
    Long voucherId = selectedVoucherId()
    if (voucherId == null) {
      return
    }
    VoucherEditor.showDialog(ownerFrame(), voucherEditorDependencies, voucherId, {
      reloadReport()
    } as Runnable)
  }

  private void openSelectedArchive() {
    ReportArchive archive = selectedArchive()
    if (archive == null) {
      showError('Välj en arkiverad rapport att öppna.')
      return
    }
    try {
      Path path = reportArchiveService.resolveStoredPath(archive)
      if (Desktop.isDesktopSupported()) {
        Desktop.desktop.open(path.toFile())
      } else {
        showInfo("Arkivfilen finns på ${path.toAbsolutePath()}.")
      }
    } catch (Exception exception) {
      showError(exception.message ?: 'Rapportfilen kunde inte öppnas.')
    }
  }

  private ReportSelection currentSelection() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      throw new IllegalArgumentException('Välj ett räkenskapsår.')
    }
    LocalDate startDate = parseDate(startDateField.text, 'Från-datum')
    LocalDate endDate = parseDate(endDateField.text, 'Till-datum')
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
      openVoucherButton.toolTipText = 'Öppna verifikationen för vald rapportrad.'
    } else if (previewTable.selectedRow >= 0) {
      openVoucherButton.toolTipText = 'Den valda raden är en sammanfattning och kan inte öppnas som verifikation.'
    } else {
      openVoucherButton.toolTipText = 'Välj en rapportrad med koppling till en verifikation för drill-down.'
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
      throw new IllegalArgumentException("${label} måste anges som yyyy-MM-dd.")
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

    private static final List<String> COLUMNS = ['Tid', 'Rapport', 'Format', 'Intervall', 'Fil']
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
      ReportArchive row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.createdAt
        case 1:
          return row.reportType.label
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
