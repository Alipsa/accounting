package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.support.AppPaths

import java.math.RoundingMode
import java.nio.file.Path
import java.time.LocalDate

class VatServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private CompanySettingsService companySettingsService
  private VoucherService voucherService
  private VatService vatService
  private FiscalYear fiscalYear
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    companySettingsService = new CompanySettingsService(databaseService)
    voucherService = new VoucherService(databaseService, auditLogService)
    vatService = new VatService(databaseService, voucherService)
    fiscalYear = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )
    insertTestAccounts()
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void vatReportSummarizesBaseAndTaxFromBookedVouchers() {
    bookVatFixtures()

    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    VatService.VatReport report = vatService.calculateReport(january.id)

    assertEquals(3, report.rows.size())
    assertVatRow(report, VatCode.OUTPUT_25, 1000.00G, 250.00G, 0.00G)
    assertVatRow(report, VatCode.INPUT_25, 200.00G, 0.00G, 50.00G)
    assertVatRow(report, VatCode.EU_ACQUISITION_GOODS, 100.00G, 25.00G, 25.00G)
    assertEquals(275.00G, report.outputVatTotal)
    assertEquals(75.00G, report.inputVatTotal)
    assertEquals(200.00G, report.netVatToPay)
  }

  @Test
  void vatReportComputesOutputVatWhenTaxLineIsMissing() {
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning utan momsrad',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )

    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    VatService.VatReport report = vatService.calculateReport(january.id)

    assertVatRow(report, VatCode.OUTPUT_25, 1000.00G, 250.00G, 0.00G)
    assertEquals(250.00G, report.outputVatTotal)
  }

  @Test
  void reportedPeriodBlocksRegularBookingsButAllowsCorrections() {
    Voucher saleVoucher = bookSaleVoucher()
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()

    VatPeriod reportedPeriod = vatService.reportPeriod(january.id)
    List<AuditLogEntry> auditEntries = auditLogService.listEntries()

    assertEquals(VatService.REPORTED, reportedPeriod.status)
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.VAT_PERIOD_REPORTED && entry.details.contains(reportedPeriod.reportHash)
    })
    Voucher draft = voucherService.createDraft(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 20),
        'Sen faktura',
        saleLines(250.00G)
    )

    Executable regularBooking = {
      voucherService.bookDraft(draft.id)
    } as Executable

    IllegalStateException bookingException = assertThrows(IllegalStateException, regularBooking)
    assertTrue(bookingException.message.contains('Momsperioden är rapporterad'))

    Voucher correction = voucherService.createCorrectionVoucher(saleVoucher.id)

    assertEquals(VoucherStatus.CORRECTION, correction.status)
    assertEquals(saleVoucher.id, correction.originalVoucherId)
  }

  @Test
  void bookingTransferLocksVatPeriodAndCreatesSettlementVoucher() {
    Voucher saleVoucher = bookVatFixtures().first()
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    vatService.reportPeriod(january.id)

    Voucher transferVoucher = vatService.bookTransfer(january.id)
    VatPeriod lockedPeriod = vatService.findPeriod(january.id)
    List<AuditLogEntry> auditEntries = auditLogService.listEntries()

    assertEquals(VatService.LOCKED, lockedPeriod.status)
    assertEquals(transferVoucher.id, lockedPeriod.transferVoucherId)
    assertEquals(LocalDate.of(2026, 1, 31), transferVoucher.accountingDate)
    assertEquals('Momsöverföring 2026-01', transferVoucher.description)
    assertTransferLine(transferVoucher, '2611', 250.00G, 0.00G)
    assertTransferLine(transferVoucher, '2614', 25.00G, 0.00G)
    assertTransferLine(transferVoucher, '2641', 0.00G, 50.00G)
    assertTransferLine(transferVoucher, '2645', 0.00G, 25.00G)
    assertTransferLine(transferVoucher, '2650', 0.00G, 200.00G)
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.VAT_PERIOD_LOCKED && entry.voucherId == transferVoucher.id
    })

    Executable correctionAction = {
      voucherService.createCorrectionVoucher(saleVoucher.id)
    } as Executable

    IllegalStateException exception = assertThrows(IllegalStateException, correctionAction)
    assertTrue(exception.message.contains('Momsperioden är låst'))
  }

  @Test
  void lockedPeriodStillValidatesAgainstOriginalReportedHash() {
    bookVatFixtures()
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    VatService.VatReport reportBeforeTransfer = vatService.calculateReport(january.id)
    vatService.reportPeriod(january.id)
    vatService.bookTransfer(january.id)

    List<String> problems = vatService.validateReport(january.id)
    VatService.VatReport reportAfterTransfer = vatService.calculateReport(january.id)

    assertEquals([], problems)
    assertEquals(reportBeforeTransfer.outputVatTotal, reportAfterTransfer.outputVatTotal)
    assertEquals(reportBeforeTransfer.inputVatTotal, reportAfterTransfer.inputVatTotal)
    assertEquals(reportBeforeTransfer.netVatToPay, reportAfterTransfer.netVatToPay)
    assertVatRow(reportAfterTransfer, VatCode.OUTPUT_25, 1000.00G, 250.00G, 0.00G)
    assertVatRow(reportAfterTransfer, VatCode.INPUT_25, 200.00G, 0.00G, 50.00G)
    assertVatRow(reportAfterTransfer, VatCode.EU_ACQUISITION_GOODS, 100.00G, 25.00G, 25.00G)
  }

  @Test
  void bookingTransferWithoutNetSettlementCreatesBalancedVoucherWithout2650() {
    bookSaleVoucher()
    bookPurchaseVoucher(1000.00G)
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    vatService.reportPeriod(january.id)

    Voucher transferVoucher = vatService.bookTransfer(january.id)

    assertEquals(2, transferVoucher.lines.size())
    assertTransferLine(transferVoucher, '2611', 250.00G, 0.00G)
    assertTransferLine(transferVoucher, '2641', 0.00G, 250.00G)
    assertTrue(transferVoucher.lines.every { VoucherLine line -> line.accountNumber != '2650' })
  }

  @Test
  void validateReportDetectsTamperedVoucherLinesAfterReporting() {
    bookVatFixtures()
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    vatService.reportPeriod(january.id)

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update voucher_line set credit_amount = 1200.00 where account_number = '3010'")
    }

    List<String> problems = vatService.validateReport(january.id)

    assertEquals(1, problems.size())
    assertTrue(problems.first().contains('avvikande rapporthash'))
  }

  @Test
  void transferInLockedAccountingPeriodShowsPedagogicError() {
    bookVatFixtures()
    VatPeriod january = vatService.listPeriods(fiscalYear.id).first()
    vatService.reportPeriod(january.id)
    accountingPeriodService.lockPeriod(accountingPeriodService.listPeriods(fiscalYear.id).first().id, 'Avstämd.')

    IllegalStateException exception = assertThrows(IllegalStateException) {
      vatService.bookTransfer(january.id)
    }

    assertTrue(exception.message.contains('redovisningsperioden är låst'))
  }

  @Test
  void annualVatSettingCreatesOneVatPeriodForWholeFiscalYear() {
    companySettingsService.save(
        new se.alipsa.accounting.domain.CompanySettings(
            null,
            'Enskild firma',
            '850101-1234',
            'SEK',
            'sv-SE',
            VatPeriodicity.ANNUAL
        )
    )
    FiscalYear annualYear = fiscalYearService.createFiscalYear(
        '2027',
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2027, 12, 31)
    )

    List<VatPeriod> periods = vatService.listPeriods(annualYear.id)

    assertEquals(1, periods.size())
    assertEquals(LocalDate.of(2027, 1, 1), periods.first().startDate)
    assertEquals(LocalDate.of(2027, 12, 31), periods.first().endDate)
    assertEquals('2027-01-01 - 2027-12-31', periods.first().periodName)
  }

  private List<Voucher> bookVatFixtures() {
    [
        bookSaleVoucher(),
        bookPurchaseVoucher(),
        bookEuAcquisitionVoucher()
    ]
  }

  private Voucher bookSaleVoucher() {
    bookSaleVoucher(1000.00G)
  }

  private Voucher bookSaleVoucher(BigDecimal baseAmount) {
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        saleLines(baseAmount)
    )
  }

  private Voucher bookPurchaseVoucher() {
    bookPurchaseVoucher(200.00G)
  }

  private Voucher bookPurchaseVoucher(BigDecimal baseAmount) {
    BigDecimal vatAmount = (baseAmount * 0.25G).setScale(2, RoundingMode.HALF_UP)
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 18),
        'Leverantörsfaktura',
        [
            new VoucherLine(null, null, 0, '4010', null, 'Varuinköp', baseAmount, 0.00G),
            new VoucherLine(null, null, 0, '2641', null, 'Ingående moms', vatAmount, 0.00G),
            new VoucherLine(null, null, 0, '2440', null, 'Leverantörsskuld', 0.00G, baseAmount + vatAmount)
        ]
    )
  }

  private Voucher bookEuAcquisitionVoucher() {
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 25),
        'EU-förvärv',
        [
            new VoucherLine(null, null, 0, '4515', null, 'EU-varuinköp', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, '2645', null, 'Beräknad ingående moms', 25.00G, 0.00G),
            new VoucherLine(null, null, 0, '2614', null, 'Beräknad utgående moms', 0.00G, 25.00G),
            new VoucherLine(null, null, 0, '2440', null, 'Leverantörsskuld', 0.00G, 100.00G)
        ]
    )
  }

  private static List<VoucherLine> saleLines(BigDecimal baseAmount) {
    BigDecimal vatAmount = (baseAmount * 0.25G).setScale(2, RoundingMode.HALF_UP)
    [
        new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', baseAmount + vatAmount, 0.00G),
        new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, baseAmount),
        new VoucherLine(null, null, 0, '2611', null, 'Utgående moms', 0.00G, vatAmount)
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
        ) values (?, ?, ?, ?, ?, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide, vatCode])
  }

  private static void assertVatRow(
      VatService.VatReport report,
      VatCode vatCode,
      BigDecimal expectedBase,
      BigDecimal expectedOutputVat,
      BigDecimal expectedInputVat
  ) {
    VatService.VatReportRow row = report.rows.find { VatService.VatReportRow item -> item.vatCode == vatCode }
    assertEquals(expectedBase, row.baseAmount)
    assertEquals(expectedOutputVat, row.outputVatAmount)
    assertEquals(expectedInputVat, row.inputVatAmount)
  }

  private static void assertTransferLine(Voucher voucher, String accountNumber, BigDecimal debitAmount, BigDecimal creditAmount) {
    VoucherLine line = voucher.lines.find { VoucherLine item -> item.accountNumber == accountNumber }
    assertEquals(debitAmount, line.debitAmount)
    assertEquals(creditAmount, line.creditAmount)
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
