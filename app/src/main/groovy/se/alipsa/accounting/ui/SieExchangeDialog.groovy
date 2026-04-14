package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.SieExportResult
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.SieImportResult
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingWorker
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 * Modal dialog for SIE import/export and import job inspection.
 */
final class SieExchangeDialog extends JDialog {

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm')

  private final SieImportExportService sieImportExportService
  private final FiscalYearService fiscalYearService

  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JTextArea summaryArea = new JTextArea(5, 50)
  private final JTextArea errorLogArea = new JTextArea(8, 50)
  private final ImportJobTableModel importJobTableModel = new ImportJobTableModel()
  private final JTable importJobTable = new JTable(importJobTableModel)
  private final JButton importButton = new JButton(I18n.instance.getString('sieExchangeDialog.button.import'))
  private final JButton exportButton = new JButton(I18n.instance.getString('sieExchangeDialog.button.export'))
  private boolean workInProgress

  SieExchangeDialog(Frame owner, SieImportExportService sieImportExportService, FiscalYearService fiscalYearService) {
    super(owner, I18n.instance.getString('sieExchangeDialog.title'), true)
    this.sieImportExportService = sieImportExportService
    this.fiscalYearService = fiscalYearService
    buildUi()
    reloadFiscalYears()
    reloadJobs()
    showInfo(I18n.instance.getString('sieExchangeDialog.status.initial'))
  }

  static void showDialog(Frame owner, SieImportExportService sieImportExportService, FiscalYearService fiscalYearService) {
    SieExchangeDialog dialog = new SieExchangeDialog(owner, sieImportExportService, fiscalYearService)
    dialog.visible = true
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    add(buildToolbar(), BorderLayout.NORTH)
    add(buildCenter(), BorderLayout.CENTER)
    add(buildActions(), BorderLayout.SOUTH)

    importJobTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    importJobTable.selectionModel.addListSelectionListener {
      if (!it.valueIsAdjusting) {
        showSelectedJob()
      }
    }

    pack()
    setMinimumSize(size)
    setLocationRelativeTo(owner)
  }

  private JPanel buildToolbar() {
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

    panel.add(new JLabel(I18n.instance.getString('sieExchangeDialog.label.fiscalYearExport')), labelConstraints)
    panel.add(fiscalYearComboBox, fieldConstraints)
    panel
  }

  private JSplitPane buildCenter() {
    summaryArea.editable = false
    summaryArea.lineWrap = true
    summaryArea.wrapStyleWord = true
    summaryArea.background = background

    errorLogArea.editable = false
    errorLogArea.lineWrap = true
    errorLogArea.wrapStyleWord = true

    JPanel resultPanel = new JPanel(new BorderLayout(0, 8))
    resultPanel.add(new JLabel(I18n.instance.getString('sieExchangeDialog.label.result')), BorderLayout.NORTH)
    resultPanel.add(new JScrollPane(summaryArea), BorderLayout.CENTER)
    resultPanel.add(new JScrollPane(errorLogArea), BorderLayout.SOUTH)

    JPanel jobsPanel = new JPanel(new BorderLayout(0, 8))
    jobsPanel.add(new JLabel(I18n.instance.getString('sieExchangeDialog.label.recentJobs')), BorderLayout.NORTH)
    jobsPanel.add(new JScrollPane(importJobTable), BorderLayout.CENTER)

    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resultPanel, jobsPanel)
    splitPane.resizeWeight = 0.45d
    splitPane
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton closeButton = new JButton(I18n.instance.getString('sieExchangeDialog.button.close'))
    closeButton.addActionListener {
      if (!workInProgress) {
        dispose()
      }
    }
    importButton.addActionListener { importRequested() }
    exportButton.addActionListener { exportRequested() }
    panel.add(importButton)
    panel.add(exportButton)
    panel.add(closeButton)
    panel
  }

  private void reloadFiscalYears() {
    FiscalYear selected = fiscalYearComboBox.selectedItem as FiscalYear
    fiscalYearComboBox.removeAllItems()
    fiscalYearService.listFiscalYears(CompanyService.LEGACY_COMPANY_ID).each { FiscalYear fiscalYear ->
      fiscalYearComboBox.addItem(fiscalYear)
    }
    if (selected != null) {
      selectFiscalYear(selected.id)
    }
  }

  private void reloadJobs() {
    importJobTableModel.setRows(sieImportExportService.listImportJobs(CompanyService.LEGACY_COMPANY_ID))
  }

  private void importRequested() {
    JFileChooser chooser = new JFileChooser(defaultExchangeDirectory())
    chooser.fileFilter = new FileNameExtensionFilter(
        I18n.instance.getString('sieExchangeDialog.fileFilter.sie'), 'sie', 'si', 'se')
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path selectedPath = chooser.selectedFile.toPath()
    setWorkingState(true)
    showInfo(I18n.instance.format('sieExchangeDialog.status.importing', selectedPath.fileName))
    new SwingWorker<SieImportResult, Void>() {
      @Override
      protected SieImportResult doInBackground() {
        sieImportExportService.importFile(CompanyService.LEGACY_COMPANY_ID, selectedPath)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          SieImportResult result = get()
          reloadFiscalYears()
          reloadJobs()
          renderImportResult(result)
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('sieExchangeDialog.status.importInterrupted'), null)
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          reloadJobs()
          showError(cause.message ?: I18n.instance.getString('sieExchangeDialog.status.importFailed'), null)
        }
      }
    }.execute()
  }

  private void exportRequested() {
    FiscalYear fiscalYear = fiscalYearComboBox.selectedItem as FiscalYear
    if (fiscalYear == null) {
      showError(I18n.instance.getString('sieExchangeDialog.error.selectFiscalYear'), null)
      return
    }
    JFileChooser chooser = new JFileChooser(defaultExchangeDirectory())
    chooser.fileFilter = new FileNameExtensionFilter(
        I18n.instance.getString('sieExchangeDialog.fileFilter.sieExport'), 'sie')
    chooser.selectedFile = new File("${sanitizeFilePart(fiscalYear.name ?: fiscalYear.startDate.toString())}.sie")
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path targetPath = ensureSieExtension(chooser.selectedFile.toPath())
    setWorkingState(true)
    showInfo(I18n.instance.format('sieExchangeDialog.status.exporting', fiscalYear.name))
    new SwingWorker<SieExportResult, Void>() {
      @Override
      protected SieExportResult doInBackground() {
        sieImportExportService.exportFiscalYear(fiscalYear.id, targetPath)
      }

      @Override
      protected void done() {
        setWorkingState(false)
        try {
          SieExportResult result = get()
          showInfo(
              I18n.instance.format('sieExchangeDialog.status.exportDone', result.fiscalYear.name) + '\n' +
                  I18n.instance.format('sieExchangeDialog.status.exportFile', result.filePath) + '\n' +
                  I18n.instance.format('sieExchangeDialog.status.exportChecksum', result.checksumSha256) + '\n' +
                  I18n.instance.format('sieExchangeDialog.status.exportCounts',
                      result.accountCount as Object, result.openingBalanceCount as Object,
                      result.voucherCount as Object)
          )
          errorLogArea.text = ''
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('sieExchangeDialog.status.exportInterrupted'), null)
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: I18n.instance.getString('sieExchangeDialog.status.exportFailed'), null)
        }
      }
    }.execute()
  }

  private void renderImportResult(SieImportResult result) {
    String summary = result.job.summary ?: I18n.instance.getString('sieExchangeDialog.status.importFinished')
    if (result.duplicate) {
      showInfo(summary)
    } else {
      showInfo(
          "${summary}\n" +
              I18n.instance.format('sieExchangeDialog.status.importChecksum', result.job.checksumSha256) + '\n' +
              I18n.instance.format('sieExchangeDialog.status.importFiscalYear', result.fiscalYear?.name ?: '-')
      )
    }
    errorLogArea.text = result.job.errorLog ?: ''
    selectJob(result.job.id)
  }

  private void showSelectedJob() {
    ImportJob job = selectedJob()
    if (job == null) {
      return
    }
    summaryArea.foreground = job.status.name() in ['FAILED'] ? new Color(153, 27, 27) : new Color(22, 101, 52)
    List<String> rows = [
        I18n.instance.format('sieExchangeDialog.job.file', job.fileName),
        I18n.instance.format('sieExchangeDialog.job.status', job.status.name()),
        I18n.instance.format('sieExchangeDialog.job.start', formatTimestamp(job.startedAt)),
        I18n.instance.format('sieExchangeDialog.job.end', formatTimestamp(job.completedAt)),
        job.fiscalYearId == null
            ? null
            : I18n.instance.format('sieExchangeDialog.job.fiscalYear', job.fiscalYearId as Object),
        job.summary
    ].findAll { Object row -> row != null && row.toString().trim() } as List<String>
    summaryArea.text = rows.join('\n')
    errorLogArea.text = job.errorLog ?: ''
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

  private void selectJob(Long jobId) {
    for (int index = 0; index < importJobTableModel.rowCount; index++) {
      ImportJob job = importJobTableModel.rowAt(index)
      if (job.id == jobId) {
        importJobTable.selectionModel.setSelectionInterval(index, index)
        return
      }
    }
  }

  private ImportJob selectedJob() {
    int row = importJobTable.selectedRow
    row < 0 ? null : importJobTableModel.rowAt(row)
  }

  private void setWorkingState(boolean working) {
    workInProgress = working
    importButton.enabled = !working
    exportButton.enabled = !working
    fiscalYearComboBox.enabled = !working
    importJobTable.enabled = !working
  }

  private void showInfo(String message) {
    summaryArea.foreground = new Color(22, 101, 52)
    summaryArea.text = message
  }

  private void showError(String message, String errorLog) {
    summaryArea.foreground = new Color(153, 27, 27)
    summaryArea.text = message ?: I18n.instance.getString('sieExchangeDialog.status.unexpectedError')
    errorLogArea.text = errorLog ?: ''
  }

  private static String formatTimestamp(java.time.LocalDateTime value) {
    value == null ? '-' : value.format(TIMESTAMP_FORMAT)
  }

  private static Path ensureSieExtension(Path path) {
    String fileName = path.fileName.toString().toLowerCase(Locale.ROOT)
    fileName.endsWith('.sie') ? path : path.resolveSibling("${path.fileName}.sie")
  }

  private static File defaultExchangeDirectory() {
    File directory = new File('.')
    directory.isDirectory() ? directory : new File(System.getProperty('user.home', '.'))
  }

  private static String sanitizeFilePart(String value) {
    value.replaceAll(/[^A-Za-z0-9._-]/, '_')
  }

  private static final class ImportJobTableModel extends AbstractTableModel {

    private List<ImportJob> rows = []

    void setRows(List<ImportJob> rows) {
      this.rows = rows ?: []
      fireTableDataChanged()
    }

    ImportJob rowAt(int rowIndex) {
      rows[rowIndex]
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    @SuppressWarnings('GetterMethodCouldBeProperty')
    int getColumnCount() {
      5
    }

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('sieExchangeDialog.table.start')
        case 1: return I18n.instance.getString('sieExchangeDialog.table.file')
        case 2: return I18n.instance.getString('sieExchangeDialog.table.status')
        case 3: return I18n.instance.getString('sieExchangeDialog.table.year')
        case 4: return I18n.instance.getString('sieExchangeDialog.table.summary')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      ImportJob row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return formatTimestamp(row.startedAt)
        case 1:
          return row.fileName
        case 2:
          return row.status.name()
        case 3:
          return row.fiscalYearId ?: '-'
        case 4:
          return row.summary
        default:
          return ''
      }
    }
  }
}
