package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class MultiCompanyIsolationTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private VatService vatService
  private ReportDataService reportDataService
  private ReportArchiveService reportArchiveService
  private ReportIntegrityService reportIntegrityService
  private JournoReportService journoReportService
  private SieImportExportService sieService
  private ClosingService closingService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    companyService = new CompanyService(databaseService)
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    vatService = new VatService(databaseService, voucherService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    reportIntegrityService = new ReportIntegrityService(voucherService, attachmentService, auditLogService)
    reportArchiveService = new ReportArchiveService(databaseService)
    reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    journoReportService = new JournoReportService(
        reportDataService,
        reportArchiveService,
        reportIntegrityService,
        companyService,
        auditLogService,
        databaseService
    )
    sieService = new SieImportExportService(
        databaseService,
        accountingPeriodService,
        voucherService,
        companyService,
        reportIntegrityService,
        auditLogService
    )
    closingService = new ClosingService(
        databaseService,
        accountingPeriodService,
        fiscalYearService,
        voucherService,
        reportIntegrityService
    )
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void sameBASAccountNumbersCanExistInBothCompanies() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedAccounts(companyB.id)

    Voucher vA = voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning A', balancedLines(500.00G))
    Voucher vB = voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning B', balancedLines(800.00G))

    assertEquals(500.00G, vA.debitTotal())
    assertEquals(800.00G, vB.debitTotal())

    Map<String, Long> idsA = resolveAccountIds(CompanyService.LEGACY_COMPANY_ID)
    Map<String, Long> idsB = resolveAccountIds(companyB.id)
    assertTrue(idsA['1510'] != idsB['1510'], 'Account 1510 ska ha olika id per företag')

    vA.lines.each { VoucherLine line ->
      assertEquals(idsA[line.accountNumber], line.accountId)
    }
    vB.lines.each { VoucherLine line ->
      assertEquals(idsB[line.accountNumber], line.accountId)
    }
  }

  @Test
  void numberSeriesAreIsolatedPerCompany() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedAccounts(companyB.id)

    Voucher v1A = voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 10), 'Första i A', balancedLines(100.00G))
    Voucher v1B = voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 10), 'Första i B', balancedLines(200.00G))
    Voucher v2A = voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 11), 'Andra i A', balancedLines(300.00G))

    assertEquals('A-1', v1A.voucherNumber)
    assertEquals('A-1', v1B.voucherNumber)
    assertEquals('A-2', v2A.voucherNumber)
  }

  @Test
  void vatPeriodsAreIsolatedPerCompany() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedVatAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedVatAccounts(companyB.id)

    voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning A', saleLines(1000.00G))
    voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning B', saleLines(2000.00G))

    List<VatPeriod> periodsA = vatService.listPeriods(fyA.id)
    List<VatPeriod> periodsB = vatService.listPeriods(fyB.id)
    VatService.VatReport reportA = vatService.calculateReport(periodsA.first().id)
    VatService.VatReport reportB = vatService.calculateReport(periodsB.first().id)

    assertEquals(12, periodsA.size())
    assertEquals(12, periodsB.size())
    assertEquals(250.00G, reportA.outputVatTotal)
    assertEquals(500.00G, reportB.outputVatTotal)

    vatService.reportPeriod(periodsA.first().id)
    vatService.bookTransfer(periodsA.first().id)

    VatPeriod lockedA = vatService.findPeriod(periodsA.first().id)
    VatPeriod openB = vatService.findPeriod(periodsB.first().id)
    assertEquals(VatService.LOCKED, lockedA.status)
    assertEquals(VatService.OPEN, openB.status)
  }

  @Test
  void reportsAreIsolatedPerCompany() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedAccounts(companyB.id)

    voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning A', balancedLines(500.00G))
    voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning B', balancedLines(800.00G))

    ReportSelection selA = new ReportSelection(
        ReportType.VOUCHER_LIST, fyA.id, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
    ReportSelection selB = new ReportSelection(
        ReportType.VOUCHER_LIST, fyB.id, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))

    ReportArchive archiveA = journoReportService.generatePdf(selA)
    ReportArchive archiveB = journoReportService.generatePdf(selB)

    assertNotNull(archiveA)
    assertNotNull(archiveB)
    assertTrue(archiveA.id != archiveB.id)

    int archiveCountA = countReportArchives(CompanyService.LEGACY_COMPANY_ID)
    int archiveCountB = countReportArchives(companyB.id)
    assertEquals(1, archiveCountA)
    assertEquals(1, archiveCountB)
  }

  @Test
  void sieExportIsIsolatedPerCompany() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedAccounts(companyB.id)

    voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning A', balancedLines(500.00G))
    voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning B', balancedLines(800.00G))

    Path sieA = tempDir.resolve('export-a.sie')
    Path sieB = tempDir.resolve('export-b.sie')
    SieExportResult resultA = sieService.exportFiscalYear(fyA.id, sieA)
    SieExportResult resultB = sieService.exportFiscalYear(fyB.id, sieB)

    assertNotNull(resultA.checksumSha256)
    assertNotNull(resultB.checksumSha256)
    assertEquals(1, resultA.voucherCount)
    assertEquals(1, resultB.voucherCount)

    String contentA = sieA.toFile().getText('CP437')
    String contentB = sieB.toFile().getText('CP437')
    assertTrue(contentA.contains('Default company'))
    assertTrue(contentB.contains('Beta AB'))
    assertTrue(!contentA.contains('Beta AB'))
    assertTrue(!contentB.contains('Default company'))
  }

  @Test
  void closingInOneCompanyDoesNotAffectOther() {
    Company companyB = createSecondCompany()
    FiscalYear fyA = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear fyB = fiscalYearService.createFiscalYear(
        companyB.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts(CompanyService.LEGACY_COMPANY_ID)
    seedClosingAccounts(companyB.id)

    voucherService.createAndBook(
        fyA.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning A',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ])
    voucherService.createAndBook(
        fyB.id, 'A', LocalDate.of(2026, 1, 15), 'Försäljning B',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 2000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 2000.00G)
        ])

    accountingPeriodService.listPeriods(fyA.id).each { period ->
      accountingPeriodService.lockPeriod(period.id, 'Bokslut A')
    }

    YearEndClosingResult resultA = closingService.closeFiscalYear(fyA.id)

    assertTrue(resultA.closedFiscalYear.closed)
    assertEquals('2027', resultA.nextFiscalYear.name)

    FiscalYear fyBAfterClosing = fiscalYearService.findById(fyB.id)
    assertTrue(!fyBAfterClosing.closed)

    List<FiscalYear> companyBYears = fiscalYearService.listFiscalYears(companyB.id)
    assertEquals(1, companyBYears.size())
    assertEquals('2026', companyBYears.first().name)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from closing_entry where fiscal_year_id = ?',
          [fyB.id]) as GroovyRowResult
      assertEquals(0, ((Number) row.get('total')).intValue())
    }
  }

  private Company createSecondCompany() {
    companyService.save(
        new Company(null, 'Beta AB', '556111-2222', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null))
  }

  private void seedAccounts(long companyId) {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, companyId, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null)
      insertAccount(sql, companyId, '3010', 'Försäljning', 'INCOME', 'CREDIT', null)
    }
  }

  private void seedVatAccounts(long companyId) {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, companyId, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null)
      insertAccount(sql, companyId, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccount(sql, companyId, '2650', 'Redovisningskonto för moms', 'LIABILITY', 'CREDIT', null)
      insertAccount(sql, companyId, '3010', 'Försäljning', 'INCOME', 'CREDIT', VatCode.OUTPUT_25.name())
    }
  }

  private void seedClosingAccounts(long companyId) {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, companyId, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null)
      insertAccount(sql, companyId, '1930', 'Företagskonto', 'ASSET', 'DEBIT', null)
      insertAccount(sql, companyId, '2099', 'Årets resultat', 'EQUITY', 'CREDIT', null)
      insertAccount(sql, companyId, '3010', 'Försäljning', 'INCOME', 'CREDIT', null)
      insertAccount(sql, companyId, '4010', 'Varukostnad', 'EXPENSE', 'DEBIT', null)
    }
  }

  private static void insertAccount(
      Sql sql,
      long companyId,
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
        ) values (?, ?, ?, ?, ?, ?, true, false, null, current_timestamp, current_timestamp)
    ''', [companyId, accountNumber, accountName, accountClass, normalBalanceSide, vatCode])
  }

  private static List<VoucherLine> balancedLines(BigDecimal amount) {
    [
        new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', amount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, amount)
    ]
  }

  private static List<VoucherLine> saleLines(BigDecimal baseAmount) {
    BigDecimal vatAmount = (baseAmount * 0.25G).setScale(2, java.math.RoundingMode.HALF_UP)
    [
        new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', baseAmount + vatAmount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, baseAmount),
        new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, vatAmount)
    ]
  }

  private Map<String, Long> resolveAccountIds(long companyId) {
    databaseService.withSql { Sql sql ->
      Map<String, Long> result = [:]
      sql.eachRow(
          'select id, account_number from account where company_id = ?',
          [companyId]
      ) { row ->
        result[row.getString('account_number')] = ((Number) row.getLong('id')).longValue()
      }
      result
    }
  }

  private int countReportArchives(long companyId) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from report_archive where company_id = ?',
          [companyId]) as GroovyRowResult
      ((Number) row.get('total')).intValue()
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
