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
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService
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
import javax.swing.SwingUtilities
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel

/**
 * Voucher registration dialog with row editing, account lookup and totals.
 */
final class VoucherEditor extends JDialog implements PropertyChangeListener {

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
  private final JButton addAttachmentButton = new JButton(I18n.instance.getString('voucherEditor.button.addAttachment'))
  private final JButton openAttachmentButton = new JButton(I18n.instance.getString('voucherEditor.button.openAttachment'))
  private JLabel fiscalYearLabel
  private JLabel seriesLabel
  private JLabel dateLabel
  private JLabel descriptionLabel
  private JButton addLineButton
  private JButton removeLineButton
  private JButton lookupButton
  private JButton cancelButton
  private JButton saveDraftButton
  private JButton bookButton
  private JButton voidDraftButton
  private JTabbedPane tabs
  private Voucher voucher
  private boolean readOnly

  VoucherEditor(
      Frame owner,
      Dependencies dependencies,
      Long voucherId,
      Runnable onSave
  ) {
    super(owner, '', true)
    this.voucherService = dependencies.voucherService
    this.fiscalYearService = dependencies.fiscalYearService
    this.accountService = dependencies.accountService
    this.attachmentService = dependencies.attachmentService
    this.auditLogService = dependencies.auditLogService
    this.onSave = onSave
    voucher = voucherId == null ? null : voucherService.findVoucher(voucherId)
    readOnly = voucher != null && voucher.status != VoucherStatus.DRAFT
    fiscalYearComboBox = new JComboBox<>(fiscalYearService.listFiscalYears(CompanyService.LEGACY_COMPANY_ID) as FiscalYear[])
    lineTableModel = new LineTableModel(accountService, !readOnly)
    lineTable = new JTable(lineTableModel)
    setTitle(voucherId == null
        ? I18n.instance.getString('voucherEditor.title.new')
        : I18n.instance.getString('voucherEditor.title.edit'))
    I18n.instance.addLocaleChangeListener(this)
    buildUi()
    loadVoucher()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    }
  }

  private void applyLocale() {
    fiscalYearLabel.text = I18n.instance.getString('voucherEditor.label.fiscalYear')
    seriesLabel.text = I18n.instance.getString('voucherEditor.label.series')
    dateLabel.text = I18n.instance.getString('voucherEditor.label.date')
    descriptionLabel.text = I18n.instance.getString('voucherEditor.label.description')
    addLineButton.text = I18n.instance.getString('voucherEditor.button.addLine')
    removeLineButton.text = I18n.instance.getString('voucherEditor.button.removeLine')
    lookupButton.text = I18n.instance.getString('voucherEditor.button.refreshAccountNames')
    addAttachmentButton.text = I18n.instance.getString('voucherEditor.button.addAttachment')
    openAttachmentButton.text = I18n.instance.getString('voucherEditor.button.openAttachment')
    cancelButton.text = I18n.instance.getString('voucherEditor.button.close')
    saveDraftButton.text = I18n.instance.getString('voucherEditor.button.saveDraft')
    bookButton.text = I18n.instance.getString('voucherEditor.button.book')
    voidDraftButton.text = I18n.instance.getString('voucherEditor.button.voidDraft')
    tabs.setTitleAt(0, I18n.instance.getString('voucherEditor.tab.lines'))
    tabs.setTitleAt(1, I18n.instance.getString('voucherEditor.tab.attachments'))
    tabs.setTitleAt(2, I18n.instance.getString('voucherEditor.tab.history'))
    lineTable.tableHeader.repaint()
    attachmentTable.tableHeader.repaint()
    auditLogTable.tableHeader.repaint()
    refreshTotals()
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

  @Override
  void dispose() {
    I18n.instance.removeLocaleChangeListener(this)
    super.dispose()
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
    tabs = new JTabbedPane()
    tabs.addTab(I18n.instance.getString('voucherEditor.tab.lines'), buildLinePanel())
    tabs.addTab(I18n.instance.getString('voucherEditor.tab.attachments'), buildAttachmentPanel())
    tabs.addTab(I18n.instance.getString('voucherEditor.tab.history'), buildAuditPanel())
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

    fiscalYearLabel = new JLabel(I18n.instance.getString('voucherEditor.label.fiscalYear'))
    panel.add(fiscalYearLabel, labelConstraints)
    panel.add(fiscalYearComboBox, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    fieldConstraints.weightx = 0.0d
    seriesLabel = new JLabel(I18n.instance.getString('voucherEditor.label.series'))
    panel.add(seriesLabel, labelConstraints)
    panel.add(seriesField, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    dateLabel = new JLabel(I18n.instance.getString('voucherEditor.label.date'))
    panel.add(dateLabel, labelConstraints)
    panel.add(dateField, fieldConstraints)

    labelConstraints.gridx = 0
    labelConstraints.gridy = 1
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = 1
    fieldConstraints.gridwidth = 5
    fieldConstraints.weightx = 1.0d
    descriptionLabel = new JLabel(I18n.instance.getString('voucherEditor.label.description'))
    panel.add(descriptionLabel, labelConstraints)
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
    addLineButton = new JButton(I18n.instance.getString('voucherEditor.button.addLine'))
    addLineButton.addActionListener {
      lineTableModel.addBlankRow()
    }
    removeLineButton = new JButton(I18n.instance.getString('voucherEditor.button.removeLine'))
    removeLineButton.addActionListener {
      removeSelectedLine()
    }
    lookupButton = new JButton(I18n.instance.getString('voucherEditor.button.refreshAccountNames'))
    lookupButton.addActionListener {
      lineTableModel.refreshAccountNames()
      refreshTotals()
    }
    addLineButton.enabled = !readOnly
    removeLineButton.enabled = !readOnly
    lookupButton.enabled = !readOnly
    actions.add(addLineButton)
    actions.add(removeLineButton)
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
    cancelButton = new JButton(I18n.instance.getString('voucherEditor.button.close'))
    cancelButton.addActionListener { dispose() }
    saveDraftButton = new JButton(I18n.instance.getString('voucherEditor.button.saveDraft'))
    saveDraftButton.enabled = !readOnly
    saveDraftButton.addActionListener { saveDraftRequested() }
    bookButton = new JButton(I18n.instance.getString('voucherEditor.button.book'))
    bookButton.enabled = !readOnly
    bookButton.addActionListener { bookRequested() }
    voidDraftButton = new JButton(I18n.instance.getString('voucherEditor.button.voidDraft'))
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
    setTitle((voucher.voucherNumber
        ?: I18n.instance.format('voucherEditor.title.draft', voucher.id)) as String)
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
      setTitle((voucher.voucherNumber
          ?: I18n.instance.format('voucherEditor.title.draft', voucher.id)) as String)
      refreshAttachmentAndHistory()
      showInfo(I18n.instance.getString('voucherEditor.message.draftSaved'))
      onSave.run()
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: I18n.instance.getString('voucherEditor.error.saveFailed'))
    }
  }

  private void bookRequested() {
    try {
      Voucher saved = saveDraft()
      voucher = voucherService.bookDraft(saved.id)
      onSave.run()
      dispose()
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: I18n.instance.getString('voucherEditor.error.bookFailed'))
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
      showError(exception.message ?: I18n.instance.getString('voucherEditor.error.voidFailed'))
    }
  }

  private Voucher saveDraft() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      throw new IllegalArgumentException(I18n.instance.getString('voucherEditor.error.createFiscalYear'))
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
      totalsLabel.text = I18n.instance.format('voucherEditor.totals',
          debit.setScale(2).toPlainString(),
          credit.setScale(2).toPlainString(),
          debit.subtract(credit).setScale(2).toPlainString())
    } catch (IllegalArgumentException ignored) {
      totalsLabel.text = I18n.instance.getString('voucherEditor.totals.invalid')
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
      showError(I18n.instance.getString('voucherEditor.error.saveBeforeAttachment'))
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
      showInfo(I18n.instance.format('voucherEditor.message.attachmentAdded', chooser.selectedFile.name))
    } catch (IllegalArgumentException | IllegalStateException exception) {
      showError(exception.message ?: I18n.instance.getString('voucherEditor.error.attachmentFailed'))
    }
  }

  private void openSelectedAttachment() {
    AttachmentMetadata selected = selectedAttachment()
    if (selected == null) {
      showError(I18n.instance.getString('voucherEditor.error.selectAttachment'))
      return
    }
    if (!Desktop.isDesktopSupported()) {
      showError(I18n.instance.getString('voucherEditor.error.desktopNotSupported'))
      return
    }
    Desktop desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) {
      showError(I18n.instance.getString('voucherEditor.error.openNotSupported'))
      return
    }
    try {
      desktop.open(attachmentService.resolveStoredPath(selected).toFile())
    } catch (IOException exception) {
      showError(exception.message ?: I18n.instance.getString('voucherEditor.error.attachmentOpenFailed'))
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
      throw new IllegalArgumentException(I18n.instance.getString('voucherEditor.error.dateFormat'))
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
      throw new IllegalArgumentException(I18n.instance.getString('voucherEditor.error.invalidAmount'))
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
            null,
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

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherEditor.table.line.account')
        case 1: return I18n.instance.getString('voucherEditor.table.line.accountName')
        case 2: return I18n.instance.getString('voucherEditor.table.line.lineDescription')
        case 3: return I18n.instance.getString('voucherEditor.table.line.debit')
        case 4: return I18n.instance.getString('voucherEditor.table.line.credit')
        default: return ''
      }
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
        Account account = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, normalized)
        account == null
            ? I18n.instance.getString('voucherEditor.table.line.unknownAccount')
            : account.accountName
      } catch (IllegalArgumentException ignored) {
        I18n.instance.getString('voucherEditor.table.line.invalidAccount')
      }
    }

    private static String amountText(BigDecimal amount) {
      amount == null || amount == BigDecimal.ZERO ? '' : amount.setScale(2).toPlainString()
    }
  }

  private static final class AttachmentTableModel extends AbstractTableModel {

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

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherEditor.table.attachment.fileName')
        case 1: return I18n.instance.getString('voucherEditor.table.attachment.type')
        case 2: return I18n.instance.getString('voucherEditor.table.attachment.size')
        case 3: return I18n.instance.getString('voucherEditor.table.attachment.checksum')
        case 4: return I18n.instance.getString('voucherEditor.table.attachment.saved')
        default: return ''
      }
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

    private List<AuditLogEntry> rows = []

    void setRows(List<AuditLogEntry> entries) {
      rows = new ArrayList<>(entries)
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
        case 0: return I18n.instance.getString('voucherEditor.table.auditLog.time')
        case 1: return I18n.instance.getString('voucherEditor.table.auditLog.event')
        case 2: return I18n.instance.getString('voucherEditor.table.auditLog.summary')
        case 3: return I18n.instance.getString('voucherEditor.table.auditLog.actor')
        case 4: return I18n.instance.getString('voucherEditor.table.auditLog.hash')
        default: return ''
      }
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
