package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.event.ActionListener

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Lists vouchers and opens registration or correction flows.
 */
@CompileStatic
final class VoucherListPanel extends JPanel {

  private static final String ALL = 'Alla'
  private static final int SEARCH_DEBOUNCE_MILLIS = 250

  private final VoucherService voucherService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final VoucherEditor.Dependencies editorDependencies
  private final Timer searchDebounceTimer
  private final JComboBox<Object> fiscalYearComboBox = new JComboBox<>()
  private final JComboBox<String> statusComboBox = new JComboBox<>(
      ([ALL] + VoucherStatus.values()*.name()) as String[]
  )
  private final JTextField searchField = new JTextField(18)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final VoucherTableModel tableModel = new VoucherTableModel()
  private final JTable voucherTable = new JTable(tableModel)
  private boolean reloadingFilters = false

  VoucherListPanel(
      VoucherService voucherService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      AttachmentService attachmentService,
      AuditLogService auditLogService
  ) {
    this.voucherService = voucherService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.attachmentService = attachmentService
    this.auditLogService = auditLogService
    editorDependencies = new VoucherEditor.Dependencies(
        voucherService,
        fiscalYearService,
        accountService,
        attachmentService,
        auditLogService
    )
    searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MILLIS, { reloadVouchers() } as ActionListener)
    searchDebounceTimer.repeats = false
    buildUi()
    reloadFiscalYears()
    installFilterListeners()
    reloadVouchers()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    add(buildToolbar(), BorderLayout.NORTH)
    add(new JScrollPane(voucherTable), BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)
    voucherTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
  }

  private JPanel buildToolbar() {
    JPanel panel = new JPanel(new BorderLayout(0, 8))
    JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    filters.add(new JLabel('Räkenskapsår'))
    filters.add(fiscalYearComboBox)
    filters.add(new JLabel('Status'))
    filters.add(statusComboBox)
    filters.add(new JLabel('Sök'))
    filters.add(searchField)

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    JButton refreshButton = new JButton('Uppdatera')
    refreshButton.addActionListener {
      refreshData()
    }
    JButton newButton = new JButton('Ny verifikation...')
    newButton.addActionListener { openEditor(null) }
    JButton openButton = new JButton('Öppna/visa...')
    openButton.addActionListener { openSelectedVoucher() }
    JButton correctionButton = new JButton('Skapa korrigering')
    correctionButton.addActionListener { correctSelectedVoucher() }

    actions.add(refreshButton)
    actions.add(newButton)
    actions.add(openButton)
    actions.add(correctionButton)

    panel.add(filters, BorderLayout.NORTH)
    panel.add(actions, BorderLayout.SOUTH)
    panel
  }

  private JPanel buildFooter() {
    feedbackArea.editable = false
    feedbackArea.lineWrap = true
    feedbackArea.wrapStyleWord = true
    feedbackArea.opaque = false
    JPanel panel = new JPanel(new BorderLayout())
    panel.add(feedbackArea, BorderLayout.CENTER)
    panel
  }

  private void installFilterListeners() {
    fiscalYearComboBox.addActionListener {
      if (!reloadingFilters) {
        reloadVouchers()
      }
    }
    statusComboBox.addActionListener {
      reloadVouchers()
    }
    searchField.document.addDocumentListener(new DocumentListener() {
      @Override
      void insertUpdate(DocumentEvent event) {
        scheduleVoucherReload()
      }

      @Override
      void removeUpdate(DocumentEvent event) {
        scheduleVoucherReload()
      }

      @Override
      void changedUpdate(DocumentEvent event) {
        scheduleVoucherReload()
      }
    })
    searchField.addActionListener {
      reloadVouchers()
    }
  }

  private void reloadFiscalYears() {
    reloadingFilters = true
    try {
      Object selected = fiscalYearComboBox.selectedItem
      fiscalYearComboBox.removeAllItems()
      fiscalYearComboBox.addItem(ALL)
      fiscalYearService.listFiscalYears().each { FiscalYear fiscalYear ->
        fiscalYearComboBox.addItem(fiscalYear)
      }
      if (selected != null) {
        selectFiscalYearItem(selected)
      }
    } finally {
      reloadingFilters = false
    }
  }

  private void selectFiscalYearItem(Object selected) {
    for (int index = 0; index < fiscalYearComboBox.itemCount; index++) {
      Object item = fiscalYearComboBox.getItemAt(index)
      if (item == selected || item.toString() == selected.toString()) {
        fiscalYearComboBox.selectedIndex = index
        return
      }
    }
  }

  private void reloadVouchers() {
    if (searchDebounceTimer.running) {
      searchDebounceTimer.stop()
    }
    Long fiscalYearId = selectedFiscalYearId()
    VoucherStatus status = selectedStatus()
    List<Voucher> vouchers = voucherService.listVouchers(fiscalYearId, status, searchField.text)
    tableModel.setRows(vouchers)
    showInfo("Visar ${vouchers.size()} verifikationer.")
  }

  private void scheduleVoucherReload() {
    searchDebounceTimer.restart()
  }

  private void refreshData() {
    reloadFiscalYears()
    reloadVouchers()
  }

  private Long selectedFiscalYearId() {
    Object selected = fiscalYearComboBox.selectedItem
    selected instanceof FiscalYear ? ((FiscalYear) selected).id : null
  }

  private VoucherStatus selectedStatus() {
    String selected = statusComboBox.selectedItem as String
    selected == null || selected == ALL ? null : VoucherStatus.valueOf(selected)
  }

  private Voucher selectedVoucher() {
    int selectedRow = voucherTable.selectedRow
    selectedRow < 0 ? null : tableModel.rowAt(selectedRow)
  }

  private void openSelectedVoucher() {
    Voucher selected = selectedVoucher()
    if (selected == null) {
      showError('Välj en verifikation först.')
      return
    }
    openEditor(selected.id)
  }

  private void openEditor(Long voucherId) {
    VoucherEditor.showDialog(ownerFrame(), editorDependencies, voucherId, {
      refreshData()
    } as Runnable)
  }

  private void correctSelectedVoucher() {
    Voucher selected = selectedVoucher()
    if (selected == null) {
      showError('Välj en bokförd verifikation först.')
      return
    }
    try {
      Voucher correction = voucherService.createCorrectionVoucher(selected.id, null)
      reloadVouchers()
      showInfo("Korrigering skapad som ${correction.voucherNumber}.")
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: 'Korrigeringen kunde inte skapas på originalets bokföringsdatum.')
    }
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
  }

  private void showInfo(String message) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = message
  }

  private void showError(String message) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = message
  }

  private static String shortHash(String hash) {
    hash == null || hash.length() <= 12 ? hash ?: '' : hash.substring(0, 12)
  }

  private static final class VoucherTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = [
        'Nummer',
        'Datum',
        'Text',
        'Status',
        'Debet',
        'Kredit',
        'Korrigerar',
        'Hash'
    ]
    private List<Voucher> rows = []

    void setRows(List<Voucher> rows) {
      this.rows = new ArrayList<>(rows)
      fireTableDataChanged()
    }

    Voucher rowAt(int rowIndex) {
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
      Voucher voucher = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return voucher.voucherNumber ?: "Utkast ${voucher.id}"
        case 1:
          return voucher.accountingDate
        case 2:
          return voucher.description
        case 3:
          return voucher.status.name()
        case 4:
          return voucher.debitTotal().toPlainString()
        case 5:
          return voucher.creditTotal().toPlainString()
        case 6:
          return voucher.originalVoucherId ?: ''
        case 7:
          return shortHash(voucher.contentHash)
        default:
          return ''
      }
    }
  }
}
