package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.LocalDate
import java.time.format.DateTimeParseException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel

/**
 * Voucher registration dialog with row editing, account lookup and totals.
 */
final class VoucherEditor extends JDialog {

  static final class Dependencies {

    final VoucherService voucherService
    final FiscalYearService fiscalYearService
    final AccountService accountService
    final AttachmentService attachmentService
    final AuditLogService auditLogService

    Dependencies(
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
    }
  }

  private final VoucherService voucherService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final Runnable onSave
  private final JComboBox<FiscalYear> fiscalYearComboBox
  private final JTextField seriesField = new JTextField(6)
  private final JTextField dateField = new JTextField(10)
  private final JTextField descriptionField = new JTextField(42)
  private final JTextArea validationArea = new JTextArea(3, 48)
  private final JLabel totalsLabel = new JLabel('')
  private final LineTableModel lineTableModel
  private final JTable lineTable
  private final AttachmentTableModel attachmentTableModel = new AttachmentTableModel()
  private final JTable attachmentTable = new JTable(attachmentTableModel)
  private final AuditLogTableModel auditLogTableModel = new AuditLogTableModel()
  private final JTable auditLogTable = new JTable(auditLogTableModel)
  private final JButton addAttachmentButton = new JButton('Lägg till bilaga...')
  private final JButton openAttachmentButton = new JButton('Öppna bilaga...')
  private Voucher voucher
  private boolean readOnly

  VoucherEditor(
      Frame owner,
      Dependencies dependencies,
      Long voucherId,
      Runnable onSave
  ) {
    super(owner, voucherId == null ? 'Ny verifikation' : 'Verifikation', true)
    this.voucherService = dependencies.voucherService
    this.fiscalYearService = dependencies.fiscalYearService
    this.accountService = dependencies.accountService
    this.attachmentService = dependencies.attachmentService
    this.auditLogService = dependencies.auditLogService
    this.onSave = onSave
    voucher = voucherId == null ? null : voucherService.findVoucher(voucherId)
    readOnly = voucher != null && voucher.status != VoucherStatus.DRAFT
    fiscalYearComboBox = new JComboBox<>(fiscalYearService.listFiscalYears() as FiscalYear[])
    lineTableModel = new LineTableModel(accountService, !readOnly)
    lineTable = new JTable(lineTableModel)
    buildUi()
    loadVoucher()
  }

  static void showDialog(
      Frame owner,
      Dependencies dependencies,
      Long voucherId,
      Runnable onSave
  ) {
    VoucherEditor editor = new VoucherEditor(owner, dependencies, voucherId, onSave)
    editor.setVisible(true)
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    add(buildHeaderPanel(), BorderLayout.NORTH)
    add(buildCenterTabs(), BorderLayout.CENTER)
    add(buildFooterPanel(), BorderLayout.SOUTH)
    pack()
    setMinimumSize(new java.awt.Dimension(840, 520))
    setLocationRelativeTo(owner)
  }

  private JTabbedPane buildCenterTabs() {
    JTabbedPane tabs = new JTabbedPane()
    tabs.addTab('Rader', buildLinePanel())
    tabs.addTab('Bilagor', buildAttachmentPanel())
    tabs.addTab('Historik', buildAuditPanel())
    tabs
  }

  private JPanel buildHeaderPanel() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 8, 16), 0, 0
    )

    panel.add(new JLabel('Räkenskapsår'), labelConstraints)
    panel.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    fieldConstraints.weightx = 0.0d
    panel.add(new JLabel('Serie'), labelConstraints)
    panel.add(seriesField, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    panel.add(new JLabel('Datum'), labelConstraints)
    panel.add(dateField, fieldConstraints)

    labelConstraints.gridx = 0
    labelConstraints.gridy = 1
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = 1
    fieldConstraints.gridwidth = 5
    fieldConstraints.weightx = 1.0d
    panel.add(new JLabel('Text'), labelConstraints)
    panel.add(descriptionField, fieldConstraints)
    panel
  }

  private JPanel buildLinePanel() {
    lineTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    lineTable.putClientProperty('terminateEditOnFocusLost', Boolean.TRUE)
    lineTableModel.addTableModelListener { TableModelEvent event ->
      refreshTotals()
    }

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    JButton addButton = new JButton('Lägg till rad')
    addButton.addActionListener {
      lineTableModel.addBlankRow()
    }
    JButton removeButton = new JButton('Ta bort rad')
    removeButton.addActionListener {
      removeSelectedLine()
    }
    JButton lookupButton = new JButton('Uppdatera kontonamn')
    lookupButton.addActionListener {
      lineTableModel.refreshAccountNames()
      refreshTotals()
    }
    addButton.enabled = !readOnly
    removeButton.enabled = !readOnly
    lookupButton.enabled = !readOnly
    actions.add(addButton)
    actions.add(removeButton)
    actions.add(lookupButton)

    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(actions, BorderLayout.NORTH)
    panel.add(new JScrollPane(lineTable), BorderLayout.CENTER)
    panel
  }

  private JPanel buildAttachmentPanel() {
    attachmentTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    attachmentTable.selectionModel.addListSelectionListener {
      updateAttachmentButtons()
    }
    addAttachmentButton.addActionListener {
      addAttachmentRequested()
    }
    openAttachmentButton.addActionListener {
      openSelectedAttachment()
    }
    updateAttachmentButtons()

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    actions.add(addAttachmentButton)
    actions.add(openAttachmentButton)

    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(actions, BorderLayout.NORTH)
    panel.add(new JScrollPane(attachmentTable), BorderLayout.CENTER)
    panel
  }

  private JPanel buildAuditPanel() {
    auditLogTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    JPanel panel = new JPanel(new BorderLayout())
    panel.add(new JScrollPane(auditLogTable), BorderLayout.CENTER)
    panel
  }

  private JPanel buildFooterPanel() {
    validationArea.editable = false
    validationArea.lineWrap = true
    validationArea.wrapStyleWord = true
    validationArea.opaque = false
    validationArea.visible = false

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton cancelButton = new JButton('Stäng')
    cancelButton.addActionListener { dispose() }
    JButton saveDraftButton = new JButton('Spara utkast')
    saveDraftButton.enabled = !readOnly
    saveDraftButton.addActionListener { saveDraftRequested() }
    JButton bookButton = new JButton('Bokför')
    bookButton.enabled = !readOnly
    bookButton.addActionListener { bookRequested() }
    JButton voidDraftButton = new JButton('Makulera utkast')
    voidDraftButton.enabled = voucher != null && voucher.status == VoucherStatus.DRAFT
    voidDraftButton.addActionListener { cancelDraftRequested() }

    actions.add(cancelButton)
    actions.add(voidDraftButton)
    actions.add(saveDraftButton)
    actions.add(bookButton)

    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(totalsLabel, BorderLayout.NORTH)
    panel.add(validationArea, BorderLayout.CENTER)
    panel.add(actions, BorderLayout.SOUTH)
    panel
  }

  private void loadVoucher() {
    if (voucher == null) {
      seriesField.text = 'A'
      FiscalYear selected = selectedFiscalYear()
      if (selected != null) {
        LocalDate today = LocalDate.now()
        dateField.text = today.isBefore(selected.startDate) || today.isAfter(selected.endDate) ?
            selected.startDate.toString() : today.toString()
      }
      descriptionField.text = ''
      lineTableModel.addBlankRows(2)
      refreshAttachmentAndHistory()
      refreshTotals()
      return
    }

    selectFiscalYear(voucher.fiscalYearId)
    seriesField.text = voucher.seriesCode
    dateField.text = voucher.accountingDate.toString()
    descriptionField.text = voucher.description
    setTitle((voucher.voucherNumber ?: "Utkast ${voucher.id}") as String)
    fiscalYearComboBox.enabled = !readOnly
    seriesField.enabled = !readOnly
    dateField.enabled = !readOnly
    descriptionField.enabled = !readOnly
    lineTableModel.setRows(voucher.lines)
    refreshAttachmentAndHistory()
    refreshTotals()
  }

  private void saveDraftRequested() {
    try {
      Voucher saved = saveDraft()
      voucher = saved
      lineTableModel.setRows(saved.lines)
      setTitle((voucher.voucherNumber ?: "Utkast ${voucher.id}") as String)
      refreshAttachmentAndHistory()
      showInfo('Utkastet sparades.')
      onSave.run()
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: 'Verifikationen kunde inte sparas.')
    }
  }

  private void bookRequested() {
    try {
      Voucher saved = saveDraft()
      voucher = voucherService.bookDraft(saved.id)
      onSave.run()
      dispose()
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: 'Verifikationen kunde inte bokföras.')
    }
  }

  private void cancelDraftRequested() {
    if (voucher == null) {
      return
    }
    try {
      voucherService.cancelDraft(voucher.id)
      onSave.run()
      dispose()
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: 'Utkastet kunde inte makuleras.')
    }
  }

  private Voucher saveDraft() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      throw new IllegalArgumentException('Skapa ett räkenskapsår innan verifikationer registreras.')
    }
    LocalDate accountingDate = parseDate(dateField.text)
    List<VoucherLine> lines = lineTableModel.toVoucherLines()
    if (voucher == null) {
      return voucherService.createDraft(fiscalYear.id, seriesField.text, accountingDate, descriptionField.text, lines)
    }
    voucherService.updateDraft(voucher.id, accountingDate, descriptionField.text, lines)
  }

  private void removeSelectedLine() {
    int selectedRow = lineTable.selectedRow
    if (selectedRow >= 0) {
      lineTableModel.removeRow(selectedRow)
    }
  }

  private FiscalYear selectedFiscalYear() {
    fiscalYearComboBox.selectedItem as FiscalYear
  }

  private void selectFiscalYear(long fiscalYearId) {
    for (int index = 0; index < fiscalYearComboBox.itemCount; index++) {
      FiscalYear fiscalYear = fiscalYearComboBox.getItemAt(index)
      if (fiscalYear.id == fiscalYearId) {
        fiscalYearComboBox.selectedIndex = index
        return
      }
    }
  }

  private void refreshTotals() {
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = BigDecimal.ZERO
    try {
      lineTableModel.rows.each { LineEntry line ->
        debit = debit.add(parseAmount(line.debit))
        credit = credit.add(parseAmount(line.credit))
      }
      totalsLabel.text = "Debet: ${debit.setScale(2).toPlainString()}   " +
          "Kredit: ${credit.setScale(2).toPlainString()}   " +
          "Differens: ${debit.subtract(credit).setScale(2).toPlainString()}"
    } catch (IllegalArgumentException ignored) {
      totalsLabel.text = 'Summering kan inte visas innan alla belopp är giltiga.'
    }
  }

  private void refreshAttachmentAndHistory() {
    if (voucher == null) {
      attachmentTableModel.setRows([])
      auditLogTableModel.setRows([])
      updateAttachmentButtons()
      return
    }
    attachmentTableModel.setRows(attachmentService.listAttachments(voucher.id))
    auditLogTableModel.setRows(auditLogService.listEntriesForVoucher(voucher.id))
    updateAttachmentButtons()
  }

  private void addAttachmentRequested() {
    if (voucher == null) {
      showError('Spara verifikationen som utkast innan du lägger till bilagor.')
      return
    }
    javax.swing.JFileChooser chooser = new javax.swing.JFileChooser()
    int result = chooser.showOpenDialog(this)
    if (result != javax.swing.JFileChooser.APPROVE_OPTION || chooser.selectedFile == null) {
      return
    }
    try {
      attachmentService.addAttachment(voucher.id, chooser.selectedFile.toPath())
      refreshAttachmentAndHistory()
      showInfo("Bilagan ${chooser.selectedFile.name} registrerades.")
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: 'Bilagan kunde inte registreras.')
    }
  }

  private void openSelectedAttachment() {
    AttachmentMetadata selected = selectedAttachment()
    if (selected == null) {
      showError('Välj en bilaga först.')
      return
    }
    if (!Desktop.isDesktopSupported()) {
      showError('Systemet kan inte öppna bilagor från applikationen.')
      return
    }
    Desktop desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) {
      showError('Systemet saknar stöd för att öppna bilagor.')
      return
    }
    try {
      desktop.open(attachmentService.resolveStoredPath(selected).toFile())
    } catch (IOException exception) {
      showError(exception.message ?: 'Bilagan kunde inte öppnas.')
    }
  }

  private AttachmentMetadata selectedAttachment() {
    int selectedRow = attachmentTable.selectedRow
    selectedRow < 0 ? null : attachmentTableModel.rowAt(selectedRow)
  }

  private void updateAttachmentButtons() {
    addAttachmentButton.enabled = voucher != null
    openAttachmentButton.enabled = selectedAttachment() != null
  }

  private static LocalDate parseDate(String value) {
    try {
      LocalDate.parse(value?.trim())
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException('Datum måste anges som ÅÅÅÅ-MM-DD.')
    }
  }

  private static BigDecimal parseAmount(String value) {
    String normalized = value?.trim()
    if (!normalized) {
      return BigDecimal.ZERO.setScale(2)
    }
    try {
      new BigDecimal(normalized.replace(',', '.')).setScale(2)
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException('Belopp måste vara giltiga tal.')
    }
  }

  private void showInfo(String message) {
    validationArea.foreground = new Color(22, 101, 52)
    validationArea.text = message
    validationArea.visible = true
    pack()
  }

  private void showError(String message) {
    validationArea.foreground = new Color(153, 27, 27)
    validationArea.text = message
    validationArea.visible = true
    pack()
  }

  private static boolean hasText(String value) {
    value != null && !value.isBlank()
  }

  private static String shortHash(String hash) {
    hash == null || hash.length() <= 12 ? hash ?: '' : hash.substring(0, 12)
  }

  private static String formatFileSize(long bytes) {
    if (bytes < 1024) {
      return "${bytes} B"
    }
    if (bytes < 1024L * 1024L) {
      return String.format(Locale.ROOT, '%.1f kB', bytes / 1024.0d)
    }
    String.format(Locale.ROOT, '%.1f MB', bytes / (1024.0d * 1024.0d))
  }

  private static final class LineEntry {

    String accountNumber = ''
    String accountName = ''
    String description = ''
    String debit = ''
    String credit = ''
  }

  private static final class LineTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Konto', 'Kontonamn', 'Radtext', 'Debet', 'Kredit']

    private final AccountService accountService
    private final boolean editable
    private final List<LineEntry> rows = []

    LineTableModel(AccountService accountService, boolean editable) {
      this.accountService = accountService
      this.editable = editable
    }

    void setRows(List<VoucherLine> voucherLines) {
      rows.clear()
      voucherLines.each { VoucherLine line ->
        LineEntry entry = new LineEntry()
        entry.accountNumber = line.accountNumber ?: ''
        entry.accountName = line.accountName ?: ''
        entry.description = line.description ?: ''
        entry.debit = amountText(line.debitAmount)
        entry.credit = amountText(line.creditAmount)
        rows << entry
      }
      if (rows.isEmpty()) {
        addBlankRows(2)
        return
      }
      fireTableDataChanged()
    }

    void addBlankRow() {
      rows << new LineEntry()
      fireTableRowsInserted(rows.size() - 1, rows.size() - 1)
    }

    void addBlankRows(int count) {
      int start = rows.size()
      count.times {
        rows << new LineEntry()
      }
      fireTableRowsInserted(start, rows.size() - 1)
    }

    void removeRow(int rowIndex) {
      rows.remove(rowIndex)
      fireTableRowsDeleted(rowIndex, rowIndex)
    }

    void refreshAccountNames() {
      rows.each { LineEntry row ->
        row.accountName = lookupAccountName(row.accountNumber)
      }
      fireTableDataChanged()
    }

    List<VoucherLine> toVoucherLines() {
      rows.findAll { LineEntry row ->
        hasText(row.accountNumber) || hasText(row.description) || hasText(row.debit) || hasText(row.credit)
      }.collect { LineEntry row ->
        new VoucherLine(
            null,
            null,
            0,
            row.accountNumber,
            row.accountName,
            row.description,
            parseAmount(row.debit),
            parseAmount(row.credit)
        )
      }
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
      LineEntry row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.accountNumber
        case 1:
          return row.accountName
        case 2:
          return row.description
        case 3:
          return row.debit
        case 4:
          return row.credit
        default:
          return ''
      }
    }

    @Override
    boolean isCellEditable(int rowIndex, int columnIndex) {
      editable && columnIndex != 1
    }

    @Override
    void setValueAt(Object value, int rowIndex, int columnIndex) {
      LineEntry row = rows[rowIndex]
      String text = value?.toString() ?: ''
      switch (columnIndex) {
        case 0:
          row.accountNumber = text.trim()
          row.accountName = lookupAccountName(row.accountNumber)
          break
        case 2:
          row.description = text
          break
        case 3:
          row.debit = text.trim()
          if (hasText(row.debit)) {
            row.credit = ''
          }
          break
        case 4:
          row.credit = text.trim()
          if (hasText(row.credit)) {
            row.debit = ''
          }
          break
        default:
          break
      }
      fireTableRowsUpdated(rowIndex, rowIndex)
    }

    private String lookupAccountName(String accountNumber) {
      String normalized = accountNumber?.trim()
      if (!normalized) {
        return ''
      }
      try {
        Account account = accountService.findAccount(normalized)
        account == null ? 'Okänt konto' : account.accountName
      } catch (IllegalArgumentException ignored) {
        'Ogiltigt konto'
      }
    }

    private static String amountText(BigDecimal amount) {
      amount == null || amount == BigDecimal.ZERO ? '' : amount.setScale(2).toPlainString()
    }
  }

  private static final class AttachmentTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Filnamn', 'Typ', 'Storlek', 'Checksumma', 'Sparad']

    private List<AttachmentMetadata> rows = []

    void setRows(List<AttachmentMetadata> attachments) {
      rows = new ArrayList<>(attachments)
      fireTableDataChanged()
    }

    AttachmentMetadata rowAt(int rowIndex) {
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
      AttachmentMetadata attachment = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return attachment.originalFileName
        case 1:
          return attachment.contentType ?: ''
        case 2:
          return formatFileSize(attachment.fileSize)
        case 3:
          return shortHash(attachment.checksumSha256)
        case 4:
          return attachment.createdAt
        default:
          return ''
      }
    }
  }

  private static final class AuditLogTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Tid', 'Händelse', 'Sammanfattning', 'Aktör', 'Hash']

    private List<AuditLogEntry> rows = []

    void setRows(List<AuditLogEntry> entries) {
      rows = new ArrayList<>(entries)
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
      AuditLogEntry entry = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return entry.createdAt
        case 1:
          return entry.eventType
        case 2:
          return entry.summary
        case 3:
          return entry.actor
        case 4:
          return shortHash(entry.entryHash)
        default:
          return ''
      }
    }
  }
}
