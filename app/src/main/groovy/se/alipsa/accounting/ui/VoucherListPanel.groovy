package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.event.ActionListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

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
final class VoucherListPanel extends JPanel implements PropertyChangeListener {

  private static final int SEARCH_DEBOUNCE_MILLIS = 250

  private final VoucherService voucherService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final ActiveCompanyManager activeCompanyManager
  private final VoucherEditor.Dependencies editorDependencies
  private final Timer searchDebounceTimer
  private final JComboBox<Object> fiscalYearComboBox = new JComboBox<>()
  private final JComboBox<String> statusComboBox = new JComboBox<>(
      ([I18n.instance.getString('voucherListPanel.filter.all')] + VoucherStatus.values()*.name()) as String[]
  )
  private final JTextField searchField = new JTextField(18)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final VoucherTableModel tableModel = new VoucherTableModel()
  private final JTable voucherTable = new JTable(tableModel)
  private JLabel fiscalYearLabel
  private JLabel statusLabel
  private JLabel searchLabel
  private JButton refreshButton
  private JButton newButton
  private JButton openButton
  private JButton correctionButton
  private String allFilterValue
  private boolean reloadingFilters = false

  VoucherListPanel(
      VoucherService voucherService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      AttachmentService attachmentService,
      AuditLogService auditLogService,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.voucherService = voucherService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.attachmentService = attachmentService
    this.auditLogService = auditLogService
    this.activeCompanyManager = activeCompanyManager
    editorDependencies = new VoucherEditor.Dependencies(
        voucherService,
        fiscalYearService,
        accountService,
        attachmentService,
        auditLogService
    )
    allFilterValue = I18n.instance.getString('voucherListPanel.filter.all')
    searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MILLIS, { reloadVouchers() } as ActionListener)
    searchDebounceTimer.repeats = false
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadFiscalYears()
    installFilterListeners()
    reloadVouchers()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { refreshData() }
    }
  }

  private void applyLocale() {
    allFilterValue = I18n.instance.getString('voucherListPanel.filter.all')
    rebuildStatusFilter()
    reloadFiscalYears()
    fiscalYearLabel.text = I18n.instance.getString('voucherListPanel.label.fiscalYear')
    statusLabel.text = I18n.instance.getString('voucherListPanel.label.status')
    searchLabel.text = I18n.instance.getString('voucherListPanel.label.search')
    refreshButton.text = I18n.instance.getString('voucherListPanel.button.refresh')
    newButton.text = I18n.instance.getString('voucherListPanel.button.newVoucher')
    openButton.text = I18n.instance.getString('voucherListPanel.button.openVoucher')
    correctionButton.text = I18n.instance.getString('voucherListPanel.button.createCorrection')
    voucherTable.tableHeader.repaint()
  }

  private void rebuildStatusFilter() {
    int selectedIndex = statusComboBox.selectedIndex
    statusComboBox.removeAllItems()
    statusComboBox.addItem(allFilterValue)
    VoucherStatus.values().each { VoucherStatus status ->
      statusComboBox.addItem(status.name())
    }
    if (selectedIndex >= 0 && selectedIndex < statusComboBox.itemCount) {
      statusComboBox.selectedIndex = selectedIndex
    }
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
    fiscalYearLabel = new JLabel(I18n.instance.getString('voucherListPanel.label.fiscalYear'))
    filters.add(fiscalYearLabel)
    filters.add(fiscalYearComboBox)
    statusLabel = new JLabel(I18n.instance.getString('voucherListPanel.label.status'))
    filters.add(statusLabel)
    filters.add(statusComboBox)
    searchLabel = new JLabel(I18n.instance.getString('voucherListPanel.label.search'))
    filters.add(searchLabel)
    filters.add(searchField)

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    refreshButton = new JButton(I18n.instance.getString('voucherListPanel.button.refresh'))
    refreshButton.addActionListener {
      refreshData()
    }
    newButton = new JButton(I18n.instance.getString('voucherListPanel.button.newVoucher'))
    newButton.addActionListener { openEditor(null) }
    openButton = new JButton(I18n.instance.getString('voucherListPanel.button.openVoucher'))
    openButton.addActionListener { openSelectedVoucher() }
    correctionButton = new JButton(I18n.instance.getString('voucherListPanel.button.createCorrection'))
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
      fiscalYearComboBox.addItem(allFilterValue)
      fiscalYearService.listFiscalYears(activeCompanyManager.companyId).each { FiscalYear fiscalYear ->
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
    List<Voucher> vouchers = voucherService.listVouchers(activeCompanyManager.companyId, fiscalYearId, status, searchField.text)
    tableModel.setRows(vouchers)
    showInfo(I18n.instance.format('voucherListPanel.message.showing', vouchers.size()))
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
    selected == null || selected == allFilterValue ? null : VoucherStatus.valueOf(selected)
  }

  private Voucher selectedVoucher() {
    int selectedRow = voucherTable.selectedRow
    selectedRow < 0 ? null : tableModel.rowAt(selectedRow)
  }

  private void openSelectedVoucher() {
    Voucher selected = selectedVoucher()
    if (selected == null) {
      showError(I18n.instance.getString('voucherListPanel.error.selectVoucher'))
      return
    }
    openEditor(selected.id)
  }

  private void openEditor(Long voucherId) {
    VoucherEditor.showDialog(ownerFrame(), editorDependencies, activeCompanyManager.companyId, voucherId, {
      refreshData()
    } as Runnable)
  }

  private void correctSelectedVoucher() {
    Voucher selected = selectedVoucher()
    if (selected == null) {
      showError(I18n.instance.getString('voucherListPanel.error.selectBooked'))
      return
    }
    try {
      Voucher correction = voucherService.createCorrectionVoucher(selected.id, null)
      reloadVouchers()
      showInfo(I18n.instance.format('voucherListPanel.message.correctionCreated', correction.voucherNumber))
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: I18n.instance.getString('voucherListPanel.error.correctionFailed'))
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

    final int columnCount = 8

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherListPanel.table.number')
        case 1: return I18n.instance.getString('voucherListPanel.table.date')
        case 2: return I18n.instance.getString('voucherListPanel.table.description')
        case 3: return I18n.instance.getString('voucherListPanel.table.status')
        case 4: return I18n.instance.getString('voucherListPanel.table.debit')
        case 5: return I18n.instance.getString('voucherListPanel.table.credit')
        case 6: return I18n.instance.getString('voucherListPanel.table.corrects')
        case 7: return I18n.instance.getString('voucherListPanel.table.hash')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      Voucher voucher = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return voucher.voucherNumber
              ?: I18n.instance.format('voucherListPanel.table.draft', voucher.id)
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
