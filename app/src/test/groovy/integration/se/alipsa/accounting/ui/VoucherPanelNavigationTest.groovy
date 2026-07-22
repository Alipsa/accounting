package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker

import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

final class VoucherPanelNavigationTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private AttachmentService attachmentService
  private ActiveCompanyManager activeCompanyManager
  private FiscalYear fiscalYear
  private VoucherPanel panel
  private List<List<Integer>> moves
  private int dateFocusRequests

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    attachmentService = new AttachmentService(databaseService, auditLogService)
    fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2030',
        LocalDate.of(2030, 1, 1),
        LocalDate.of(2030, 12, 31)
    )
    insertTestAccounts()
    activeCompanyManager = new ActiveCompanyManager(new CompanyService(databaseService), fiscalYearService)
    moves = []
    dateFocusRequests = 0
    panel = buildPanel()
    installPanelHooks()
  }

  @AfterEach
  void tearDown() {
    if (panel != null) {
      panel.dispose()
      onEdt { null }
    }
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void advancesFromAccountColumnToDebitForDebitAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 0)

    assertEquals([[0, 2]], moves)
  }

  @Test
  void saveButtonUsesAnIconInsteadOfAFontDependentSymbol() {
    JButton saveButton = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.save')
    }

    assertNotNull(saveButton.icon)
    assertEquals(6, saveButton.margin.left)
    assertEquals(6, saveButton.margin.right)
  }

  @Test
  void unsavedVoucherHasAVisibleStatusLabel() {
    JLabel unsaved = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.unsaved')
    }

    assertTrue(unsaved.visible)
  }

  @Test
  void draftValidationFromWorkerThreadPreservesActionableDateError() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      panel.mcpVoucherDraftAccess.setVoucherDraft([
          accounting_date: 'not-a-date',
          description: 'AI draft',
          lines: [[account_number: '1930', debit: 100G, credit: 0G]]
      ])
    }
    assertTrue(exception.message.contains('accounting_date'))
  }

  @Test
  void advancesFromAccountColumnToCreditForCreditAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 0)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesToDebitAfterCreditOnPreviousRowRegardlessOfAccountNormalSide() {
    setRows('CREDIT', 'CREDIT')
    panel.lineTableModel.rows[0].credit = '3144'

    panel.advanceFromCell(1, 0)

    assertEquals([[1, 2]], moves)
    assertEquals('3144', panel.lineTableModel.rows[1].debit)
  }

  @Test
  void advancesToCreditAfterDebitOnPreviousRowRegardlessOfAccountNormalSide() {
    setRows('DEBIT', 'DEBIT')
    panel.lineTableModel.rows[0].debit = '3144'

    panel.advanceFromCell(1, 0)

    assertEquals([[1, 3]], moves)
    assertEquals('3144', panel.lineTableModel.rows[1].credit)
  }

  @Test
  void advancesFromDescriptionColumnToDebitForDebitAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 1)

    assertEquals([[0, 2]], moves)
  }

  @Test
  void advancesFromDescriptionColumnToCreditForCreditAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 1)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesFromDebitColumnToCredit() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 2)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesFromCreditColumnToNextRowAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 3)

    assertEquals([[1, 0]], moves)
  }

  @Test
  void advancesFromTextColumnToNextRowAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 4)

    assertEquals([[1, 0]], moves)
  }

  @Test
  void duplicateCreatesUnsavedDraftWithCopiedVoucherFields() {
    Voucher source = voucherService.createVoucher(
        fiscalYear.id,
        'B',
        LocalDate.of(2030, 3, 15),
        'Leverantörsfaktura',
        [
            voucherLine('1510', 'Kundfordringar', 'Rad ett', 125.50G, 0.00G),
            voucherLine('3010', 'Försäljning', 'Rad två', 0.00G, 125.50G)
        ]
    )
    Path attachment = tempDir.resolve('underlag.txt')
    Files.writeString(attachment, 'bilaga')
    attachmentService.addAttachment(source.id, attachment)

    // Rebuild after creating the fixture so constructor navigation sees the saved voucher.
    panel?.dispose()
    panel = buildPanel()
    installPanelHooks()
    onEdt {
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev'))
    }

    JTable attachmentTable = tableWithFirstColumn(
        panel,
        I18n.instance.getString('voucherEditor.table.attachment.fileName')
    )
    JTable auditLogTable = tableWithFirstColumn(
        panel,
        I18n.instance.getString('voucherEditor.table.auditLog.time')
    )
    JTabbedPane tabs = findComponent(panel, JTabbedPane) { true }
    onEdt { tabs.selectedIndex = 1 }
    assertEquals(1, onEdt { attachmentTable.rowCount })
    onEdt { tabs.selectedIndex = 2 }
    assertTrue(onEdt { auditLogTable.rowCount > 0 })

    onEdt {
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.duplicate'))
    }

    assertEquals(1, dateFocusRequests)
    assertEquals(0, onEdt { attachmentTable.rowCount })
    assertEquals(0, onEdt { auditLogTable.rowCount })
    assertTrue(onEdt { findFeedbackArea(panel).text }.contains(
        I18n.instance.getString('voucherPanel.message.duplicateCreated')
    ))
    assertEquals('1510', onEdt { panel.lineTableModel.rows[0].accountNumber })
    assertEquals('Kundfordringar', onEdt { panel.lineTableModel.rows[0].accountName })
    assertEquals('Rad ett', onEdt { panel.lineTableModel.rows[0].description })
    assertEquals('3010', onEdt { panel.lineTableModel.rows[1].accountNumber })
    assertEquals('Försäljning', onEdt { panel.lineTableModel.rows[1].accountName })
    assertEquals('Rad två', onEdt { panel.lineTableModel.rows[1].description })

    JCheckBox advanceAfterSave = findComponent(panel, JCheckBox) { JCheckBox checkBox ->
      checkBox.text == I18n.instance.getString('voucherPanel.checkbox.advanceAfterSave')
    }
    assertTrue(onEdt { advanceAfterSave.selected })
    onEdt {
      advanceAfterSave.selected = false
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.save'))
    }

    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }
    assertEquals('B-2', onEdt { voucherJumpField.text })
    onEdt {
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev'))
    }
    assertEquals('B-1', onEdt { voucherJumpField.text })

    List<Voucher> vouchers = voucherService.listVouchers(CompanyService.LEGACY_COMPANY_ID, fiscalYear.id)
    assertEquals(2, vouchers.size())
    Voucher duplicate = vouchers.find { Voucher voucher -> voucher.id != source.id }
    assertNotNull(duplicate)
    assertEquals('B', duplicate.seriesCode)
    assertEquals('B-2', duplicate.voucherNumber)
    assertEquals('Leverantörsfaktura', duplicate.description)
    assertEquals(LocalDate.of(2030, 3, 15), duplicate.accountingDate)
    assertEquals(2, duplicate.lines.size())
    assertEquals('1510', duplicate.lines[0].accountNumber)
    assertEquals('Kundfordringar', duplicate.lines[0].accountName)
    assertEquals('Rad ett', duplicate.lines[0].description)
    assertEquals(125.50G, duplicate.lines[0].debitAmount)
    assertEquals(0.00G, duplicate.lines[0].creditAmount)
    assertEquals('3010', duplicate.lines[1].accountNumber)
    assertEquals('Försäljning', duplicate.lines[1].accountName)
    assertEquals('Rad två', duplicate.lines[1].description)
    assertEquals(0.00G, duplicate.lines[1].debitAmount)
    assertEquals(125.50G, duplicate.lines[1].creditAmount)
    assertEquals([], attachmentService.listAttachments(duplicate.id))
  }

  @Test
  void blankVoucherUsesFiscalYearStartThenMostRecentlySavedVoucherDate() {
    DatePicker datePicker = findComponent(panel, DatePicker) { true }
    assertEquals(LocalDate.of(2030, 1, 1), onEdt { datePicker.date })

    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2030, 3, 15),
        'Första verifikatet',
        [voucherLine('1510', 'Kundfordringar', 'Rad', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Rad', 0.00G, 100.00G)]
    )
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2030, 2, 20),
        'Senast sparade verifikatet',
        [voucherLine('1510', 'Kundfordringar', 'Rad', 200.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Rad', 0.00G, 200.00G)]
    )

    panel?.dispose()
    panel = buildPanel()
    installPanelHooks()
    datePicker = findComponent(panel, DatePicker) { true }

    assertEquals(LocalDate.of(2030, 2, 20), onEdt { datePicker.date })
  }

  @Test
  void savedVoucherIsNotExposedAsAnUnsavedDraft() {
    voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Saved voucher',
        [voucherLine('1510', 'Kundfordringar', 'Rad', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Rad', 0.00G, 100.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    JButton previous = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.prev')
    }
    onEdt { previous.doClick() }
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
  }

  @Test
  void preservesUnsavedDraftWhenNavigatingToSavedVouchersAndBack() {
    voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Saved voucher',
        [voucherLine('1510', 'Kundfordringar', 'Saved line', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Saved line', 0.00G, 100.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    JTextField description = findComponent(panel, JTextField) { JTextField field -> field.columns == 30 }
    onEdt {
      description.text = 'Unsaved draft'
      panel.lineTableModel.rows[0].accountNumber = '1510'
      panel.lineTableModel.rows[0].accountName = 'Kundfordringar'
      panel.lineTableModel.rows[0].debit = '250'
      panel.lineTableModel.rows[1].accountNumber = '3010'
      panel.lineTableModel.rows[1].accountName = 'Försäljning'
      panel.lineTableModel.rows[1].credit = '250'
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev'))
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.next'))
    }

    assertEquals('Unsaved draft', onEdt { description.text })
    assertEquals('1510', onEdt { panel.lineTableModel.rows[0].accountNumber })
    assertEquals('250,00', onEdt { panel.lineTableModel.rows[0].debit })
    assertEquals('3010', onEdt { panel.lineTableModel.rows[1].accountNumber })
    assertEquals('250,00', onEdt { panel.lineTableModel.rows[1].credit })
  }

  @Test
  void navigatingToASavedVoucherPopulatesNormalBalanceSideForEachLine() {
    voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Saved voucher',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    installPanelHooks()

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev')) }

    assertEquals('DEBIT', onEdt { panel.lineTableModel.rows[0].normalBalanceSide })
    assertEquals('CREDIT', onEdt { panel.lineTableModel.rows[1].normalBalanceSide })
  }

  @Test
  void firstAndLastNavigationButtonsJumpToOldestAndNewestVoucher() {
    Voucher oldest = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 2, 1), 'Oldest',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    Voucher newest = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 1), 'Newest',
        [voucherLine('1510', 'Kundfordringar', '', 200.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 200.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }
    assertEquals(oldest.voucherNumber, onEdt { voucherJumpField.text })

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.last')) }
    assertEquals(newest.voucherNumber, onEdt { voucherJumpField.text })
  }

  @Test
  void printableVoucherBuildsDraftFromUnsavedEditorState() {
    JTextField description = findComponent(panel, JTextField) { JTextField field -> field.columns == 30 }
    onEdt {
      description.text = 'Unsaved printable draft'
      panel.lineTableModel.rows[0].accountNumber = '1510'
      panel.lineTableModel.rows[0].accountName = 'Kundfordringar'
      panel.lineTableModel.rows[0].debit = '125'
      panel.lineTableModel.rows[1].accountNumber = '3010'
      panel.lineTableModel.rows[1].accountName = 'Försäljning'
      panel.lineTableModel.rows[1].credit = '125'
    }

    Voucher printable = onEdt { panel.printableVoucher() }

    assertEquals(I18n.instance.getString('voucherPanel.print.draft'), printable.voucherNumber)
    assertEquals('Unsaved printable draft', printable.description)
    assertEquals(2, printable.lines.size())
    assertEquals(125G, printable.debitTotal())
    assertEquals(125G, printable.creditTotal())
  }

  @Test
  void correctionLabelUsesTheOriginalVoucherNumberInsteadOfItsId() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Original',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id)
    panel?.dispose()
    panel = buildPanel()

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev')) }

    JLabel corrects = findComponent(panel, JLabel) { JLabel label ->
      label.visible && label.text.startsWith(I18n.instance.getString('voucherPanel.label.corrects'))
    }
    assertEquals("${I18n.instance.getString('voucherPanel.label.corrects')} ${original.voucherNumber}", corrects.text)
  }

  @Test
  void printableVoucherHtmlContainsHeaderLinesAndTotals() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2030, 4, 5),
        'Kundfaktura <test>',
        [
            voucherLine('1510', 'Kundfordringar', 'Rad & ett', 125.50G, 0.00G),
            voucherLine('3010', 'Försäljning', 'Rad två', 0.00G, 125.50G)
        ]
    )
    Locale previousLocale = I18n.instance.locale

    try {
      I18n.instance.setLocale(Locale.forLanguageTag('sv'))
      String html = VoucherPrintDocument.buildHtml(voucher, Locale.forLanguageTag('sv-SE'))

      assertTrue(html.contains('Verifikation A-1'))
      assertTrue(html.contains('2030-04-05'))
      assertTrue(html.contains('Kundfaktura &lt;test&gt;'))
      assertTrue(html.contains('Rad &amp; ett'))
      assertTrue(html.contains('1510'))
      assertTrue(html.contains('3010'))
      assertTrue(html.contains('125,50'))
      assertTrue(html.contains('Summa'))
    } finally {
      I18n.instance.setLocale(previousLocale)
    }
  }

  private void setSingleLine(String normalBalanceSide) {
    VoucherPanel.LineEntry line = new VoucherPanel.LineEntry(normalBalanceSide: normalBalanceSide)
    panel.lineTableModel.rows.clear()
    panel.lineTableModel.rows.add(line)
  }

  private void setRows(String firstNormalBalanceSide, String secondNormalBalanceSide) {
    panel.lineTableModel.rows.clear()
    panel.lineTableModel.rows.add(new VoucherPanel.LineEntry(normalBalanceSide: firstNormalBalanceSide))
    panel.lineTableModel.rows.add(new VoucherPanel.LineEntry(normalBalanceSide: secondNormalBalanceSide))
  }

  private VoucherPanel buildPanel() {
    new VoucherPanel(
        voucherService,
        new AccountService(databaseService),
        accountingPeriodService,
        attachmentService,
        auditLogService,
        activeCompanyManager
    )
  }

  private void installPanelHooks() {
    panel.cursorMover = { int row, int col ->
      moves << [row, col]
      null
    } as Closure
    panel.dateFocusRequester = {
      dateFocusRequests++
      null
    } as Closure
  }

  private static VoucherLine voucherLine(
      String accountNumber,
      String accountName,
      String description,
      BigDecimal debitAmount,
      BigDecimal creditAmount
  ) {
    new VoucherLine(null, null, 0, null, accountNumber, accountName, description, debitAmount, creditAmount)
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            active,
            manual_review_required,
            classification_note,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [CompanyService.LEGACY_COMPANY_ID, accountNumber, accountName, accountClass, normalBalanceSide])
  }

  private static void clickButtonWithTooltip(Container root, String tooltip) {
    JButton button = findComponent(root, JButton) { JButton candidate ->
      candidate.toolTipText == tooltip
    } as JButton
    button.doClick()
  }

  private static JTable tableWithFirstColumn(Container root, String firstColumnName) {
    findComponent(root, JTable) { JTable table ->
      table.columnCount > 0 && table.getColumnName(0) == firstColumnName
    } as JTable
  }

  private static JTextArea findFeedbackArea(Container root) {
    findComponent(root, JTextArea) { JTextArea textArea ->
      !textArea.editable
    } as JTextArea
  }

  private static <T extends Component> T findComponent(Container root, Class<T> type, Closure<Boolean> predicate) {
    List<Component> components = allComponents(root)
    T component = components.find { Component child ->
      type.isInstance(child) && predicate.call(type.cast(child))
    } as T
    assertNotNull(component, "Hittade ingen komponent av typen ${type.simpleName}.")
    component
  }

  private static List<Component> allComponents(Container root) {
    List<Component> components = []
    root.components.each { Component component ->
      components << component
      if (component instanceof Container) {
        components.addAll(allComponents(component as Container))
      }
    }
    components
  }

  private static <T> T onEdt(Closure<T> work) {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call()
    }
    AtomicReference<T> result = new AtomicReference<>()
    AtomicReference<Throwable> failure = new AtomicReference<>()
    SwingUtilities.invokeAndWait {
      try {
        result.set(work.call())
      } catch (Throwable throwable) {
        failure.set(throwable)
      }
    }
    if (failure.get() != null) {
      throw failure.get()
    }
    result.get()
  }
}
