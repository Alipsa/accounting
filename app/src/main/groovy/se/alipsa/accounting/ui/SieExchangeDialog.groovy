package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.SieExportResult
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.SieImportResult

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
@CompileStatic
final class SieExchangeDialog extends JDialog {

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm')

  private final SieImportExportService sieImportExportService
  private final FiscalYearService fiscalYearService

  private final JComboBox<FiscalYear> fiscalYearComboBox = new JComboBox<>()
  private final JTextArea summaryArea = new JTextArea(5, 50)
  private final JTextArea errorLogArea = new JTextArea(8, 50)
  private final ImportJobTableModel importJobTableModel = new ImportJobTableModel()
  private final JTable importJobTable = new JTable(importJobTableModel)
  private final JButton importButton = new JButton('Importera SIE...')
  private final JButton exportButton = new JButton('Exportera SIE...')
  private boolean workInProgress

  SieExchangeDialog(Frame owner, SieImportExportService sieImportExportService, FiscalYearService fiscalYearService) {
    super(owner, 'SIE och Datautbyte', true)
    this.sieImportExportService = sieImportExportService
    this.fiscalYearService = fiscalYearService
    buildUi()
    reloadFiscalYears()
    reloadJobs()
    showInfo('Välj en SIE-fil för import eller ett räkenskapsår för export.')
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

    panel.add(new JLabel('Räkenskapsår för export'), labelConstraints)
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
    resultPanel.add(new JLabel('Resultat'), BorderLayout.NORTH)
    resultPanel.add(new JScrollPane(summaryArea), BorderLayout.CENTER)
    resultPanel.add(new JScrollPane(errorLogArea), BorderLayout.SOUTH)

    JPanel jobsPanel = new JPanel(new BorderLayout(0, 8))
    jobsPanel.add(new JLabel('Senaste importjobb'), BorderLayout.NORTH)
    jobsPanel.add(new JScrollPane(importJobTable), BorderLayout.CENTER)

    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resultPanel, jobsPanel)
    splitPane.resizeWeight = 0.45d
    splitPane
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton closeButton = new JButton('Stäng')
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
    fiscalYearService.listFiscalYears().each { FiscalYear fiscalYear ->
      fiscalYearComboBox.addItem(fiscalYear)
    }
    if (selected != null) {
      selectFiscalYear(selected.id)
    }
  }

  private void reloadJobs() {
    importJobTableModel.setRows(sieImportExportService.listImportJobs())
  }

  private void importRequested() {
    JFileChooser chooser = new JFileChooser(defaultExchangeDirectory())
    chooser.fileFilter = new FileNameExtensionFilter('SIE-filer (*.sie, *.si, *.se)', 'sie', 'si', 'se')
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path selectedPath = chooser.selectedFile.toPath()
    setWorkingState(true)
    showInfo("Importerar ${selectedPath.fileName}...")
    new SwingWorker<SieImportResult, Void>() {
      @Override
      protected SieImportResult doInBackground() {
        sieImportExportService.importFile(selectedPath)
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
          showError('Importen avbröts.', null)
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          reloadJobs()
          showError(cause.message ?: 'SIE-filen kunde inte importeras.', null)
        }
      }
    }.execute()
  }

  private void exportRequested() {
    FiscalYear fiscalYear = fiscalYearComboBox.selectedItem as FiscalYear
    if (fiscalYear == null) {
      showError('Välj ett räkenskapsår för export.', null)
      return
    }
    JFileChooser chooser = new JFileChooser(defaultExchangeDirectory())
    chooser.fileFilter = new FileNameExtensionFilter('SIE-filer (*.sie)', 'sie')
    chooser.selectedFile = new File("${sanitizeFilePart(fiscalYear.name ?: fiscalYear.startDate.toString())}.sie")
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path targetPath = ensureSieExtension(chooser.selectedFile.toPath())
    setWorkingState(true)
    showInfo("Exporterar ${fiscalYear.name}...")
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
              "Export klar: ${result.fiscalYear.name}\n" +
                  "Fil: ${result.filePath}\n" +
                  "Checksumma: ${result.checksumSha256}\n" +
                  "Konton: ${result.accountCount}, ingående balanser: ${result.openingBalanceCount}, verifikationer: ${result.voucherCount}"
          )
          errorLogArea.text = ''
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('Exporten avbröts.', null)
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: 'SIE-filen kunde inte exporteras.', null)
        }
      }
    }.execute()
  }

  private void renderImportResult(SieImportResult result) {
    String summary = result.job.summary ?: 'Importen avslutades.'
    if (result.duplicate) {
      showInfo(summary)
    } else {
      showInfo(
          "${summary}\n" +
              "Checksumma: ${result.job.checksumSha256}\n" +
              "Räkenskapsår: ${result.fiscalYear?.name ?: '-'}"
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
        "Fil: ${job.fileName}".toString(),
        "Status: ${job.status.name()}".toString(),
        "Start: ${formatTimestamp(job.startedAt)}".toString(),
        "Slut: ${formatTimestamp(job.completedAt)}".toString(),
        job.fiscalYearId == null ? null : "Räkenskapsår: ${job.fiscalYearId}".toString(),
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
    summaryArea.text = message ?: 'Ett oväntat fel uppstod.'
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

    private static final List<String> COLUMNS = ['Start', 'Fil', 'Status', 'År', 'Sammanfattning']
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
    int getColumnCount() {
      COLUMNS.size()
    }

    @Override
    String getColumnName(int column) {
      COLUMNS[column]
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
