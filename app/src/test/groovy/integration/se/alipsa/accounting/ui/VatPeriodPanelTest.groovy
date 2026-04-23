package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.awt.Component
import java.awt.Container
import java.math.RoundingMode
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class VatPeriodPanelTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private VatService vatService
  private ActiveCompanyManager activeCompanyManager
  private FiscalYear fiscalYear
  private String previousHome
  private Locale previousLocale

  @BeforeEach
  void setUp() {
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    companyService = new CompanyService(databaseService)
    AuditLogService auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    vatService = new VatService(databaseService, voucherService, auditLogService)
    activeCompanyManager = new ActiveCompanyManager(companyService, fiscalYearService)
    fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )
    insertTestAccounts()
  }

  @AfterEach
  void tearDown() {
    I18n.instance.setLocale(previousLocale)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void loadsPeriodsAndPreviewForSelectedPeriod() {
    bookVatFixtures()

    VatPeriodPanel panel = onEdt {
      new VatPeriodPanel(vatService, fiscalYearService, activeCompanyManager)
    }

    JTable periodTable = findTable(panel, 'Period')
    JTable reportTable = findTable(panel, 'Kod')

    assertEquals(12, onEdt { periodTable.rowCount })
    assertEquals('OPEN', onEdt { periodTable.getValueAt(0, 3) })
    assertEquals(3, onEdt { reportTable.rowCount })
    assertEquals('OUTPUT_25', onEdt { reportTable.getValueAt(0, 0) })
    assertEquals('100000', digitsOnly(onEdt { reportTable.getValueAt(0, 2).toString() }))
  }

  @Test
  void reportAndTransferButtonsUpdateStatusAndFeedback() {
    bookVatFixtures()

    VatPeriodPanel panel = onEdt {
      new VatPeriodPanel(vatService, fiscalYearService, activeCompanyManager)
    }

    JTable periodTable = findTable(panel, 'Period')
    JButton reportButton = findButton(panel, 'Rapportera vald period')
    JButton transferButton = findButton(panel, 'Bokför momsöverföring')
    JTextArea feedbackArea = findFeedbackArea(panel)

    onEdt {
      reportButton.doClick()
    }
    assertEquals('REPORTED', onEdt { periodTable.getValueAt(0, 3) })
    assertTrue(onEdt { feedbackArea.text }.contains('är rapporterad'))

    onEdt {
      transferButton.doClick()
    }
    assertEquals('LOCKED', onEdt { periodTable.getValueAt(0, 3) })
    assertNotNull(onEdt { periodTable.getValueAt(0, 5) })
    assertTrue(onEdt { feedbackArea.text }.contains('Momsöverföring bokfördes'))
  }

  private List<Voucher> bookVatFixtures() {
    [
        bookSaleVoucher(),
        bookPurchaseVoucher(),
        bookEuAcquisitionVoucher()
    ]
  }

  private Voucher bookSaleVoucher() {
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        saleLines(1000.00G)
    )
  }

  private Voucher bookPurchaseVoucher() {
    BigDecimal vatAmount = (200.00G * 0.25G).setScale(2, RoundingMode.HALF_UP)
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 18),
        'Leverantörsfaktura',
        [
            new VoucherLine(null, null, 0, null, '4010', null, 'Varuinköp', 200.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2641', null, 'Ingående moms', vatAmount, 0.00G),
            new VoucherLine(null, null, 0, null, '2440', null, 'Leverantörsskuld', 0.00G, 250.00G)
        ]
    )
  }

  private Voucher bookEuAcquisitionVoucher() {
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 25),
        'EU-förvärv',
        [
            new VoucherLine(null, null, 0, null, '4515', null, 'EU-varuinköp', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2645', null, 'Beräknad ingående moms', 25.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2614', null, 'Beräknad utgående moms', 0.00G, 25.00G),
            new VoucherLine(null, null, 0, null, '2440', null, 'Leverantörsskuld', 0.00G, 100.00G)
        ]
    )
  }

  private static List<VoucherLine> saleLines(BigDecimal baseAmount) {
    BigDecimal vatAmount = (baseAmount * 0.25G).setScale(2, RoundingMode.HALF_UP)
    [
        new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', baseAmount + vatAmount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, baseAmount),
        new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, vatAmount)
    ]
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null)
      insertAccount(sql, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT', null)
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccount(sql, '2614', 'Beräknad utgående moms', 'LIABILITY', 'CREDIT', VatCode.EU_ACQUISITION_GOODS.name())
      insertAccount(sql, '2641', 'Debiterad ingående moms', 'ASSET', 'DEBIT', VatCode.INPUT_25.name())
      insertAccount(sql, '2645', 'Beräknad ingående moms', 'ASSET', 'DEBIT', VatCode.EU_ACQUISITION_GOODS.name())
      insertAccount(sql, '2650', 'Redovisningskonto för moms', 'LIABILITY', 'CREDIT', null)
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccount(sql, '4010', 'Varuinköp', 'EXPENSE', 'DEBIT', VatCode.INPUT_25.name())
      insertAccount(sql, '4515', 'Inköp av varor från annat EU-land', 'EXPENSE', 'DEBIT', VatCode.EU_ACQUISITION_GOODS.name())
    }
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide,
      String vatCode
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
        ) values (1, ?, ?, ?, ?, ?, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide, vatCode])
  }

  private static JTable findTable(Container root, String firstColumnName) {
    findComponent(root, JTable) { JTable table ->
      table.columnCount > 0 && table.getColumnName(0) == firstColumnName
    } as JTable
  }

  private static JButton findButton(Container root, String text) {
    findComponent(root, JButton) { JButton button ->
      button.text == text
    } as JButton
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

  private static String digitsOnly(String value) {
    value.replaceAll(/\D/, '')
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
