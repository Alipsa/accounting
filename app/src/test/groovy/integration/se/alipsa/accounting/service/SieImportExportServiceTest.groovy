package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.domain.ImportJobStatus
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class SieImportExportServiceTest {

  @TempDir
  Path tempDir

  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void exportedSieCanBeImportedIntoFreshTestEnvironment() {
    Path exportPath = tempDir.resolve('roundtrip.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    SieImportExportService targetService = createSieService(targetDatabaseService)

    def result = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)

    assertFalse(result.duplicate)
    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertEquals(fixture.accountCount, result.accountsCreated)
    assertEquals(fixture.openingBalanceCount, result.openingBalanceCount)
    assertEquals(fixture.voucherCount, result.voucherCount)
    assertEquals(fixture.lineCount, result.lineCount)
    assertEquals(fixture.openingBalanceCount, countRows(targetDatabaseService, 'opening_balance'))
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(fixture.accountCount, countRows(targetDatabaseService, 'account'))
  }

  @Test
  void duplicateFileHashCreatesDuplicateImportJobWithoutReimporting() {
    Path exportPath = tempDir.resolve('duplicate.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    SieImportExportService targetService = createSieService(targetDatabaseService)

    def firstImport = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    def secondImport = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    List<ImportJob> jobs = targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 10)

    assertEquals(ImportJobStatus.SUCCESS, firstImport.job.status)
    assertTrue(secondImport.duplicate)
    assertEquals(ImportJobStatus.DUPLICATE, secondImport.job.status)
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(2, jobs.size())
    assertEquals(ImportJobStatus.DUPLICATE, jobs.first().status)
    assertEquals(ImportJobStatus.SUCCESS, jobs.last().status)
  }

  @Test
  void exportIsBlockedWhenIntegrityValidationFindsCriticalProblems() {
    switchHome(tempDir.resolve('source-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    Path exportPath = tempDir.resolve('blocked.sie')

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('update voucher_line set debit_amount = ? where voucher_id = 1 and account_id = (select id from account where account_number = ?)', [999.00G, '1510'])
    }

    IllegalStateException exception = assertThrows(IllegalStateException) {
      services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath)
    }

    assertTrue(exception.message.contains('integritetskontrollerna'))
  }

  @Test
  void malformedSieFileIsRejectedAndRecordedAsFailedJob() {
    switchHome(tempDir.resolve('malformed-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path malformed = tempDir.resolve('malformed.sie')
    malformed.toFile().text = 'this is not a sie file'

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      service.importFile(CompanyService.LEGACY_COMPANY_ID, malformed)
    }

    assertTrue(exception.message.contains('SIE'))
    assertEquals(ImportJobStatus.FAILED, service.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
  }

  @Test
  void oversizedSieFileIsRejectedBeforeReadingContent() {
    switchHome(tempDir.resolve('oversized-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path oversized = tempDir.resolve('oversized.sie')
    oversized.toFile().createNewFile()
    new RandomAccessFile(oversized.toFile(), 'rw').withCloseable { RandomAccessFile file ->
      file.setLength(50L * 1024L * 1024L + 1L)
    }

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      service.importFile(CompanyService.LEGACY_COMPANY_ID, oversized)
    }

    assertTrue(exception.message.contains('Max 50 MB'))
    assertTrue(service.listImportJobs(CompanyService.LEGACY_COMPANY_ID).isEmpty())
  }

  @Test
  void importIntoFiscalYearWithLockedPeriodsIsRejectedAndRecordedAsFailedJob() {
    Path exportPath = tempDir.resolve('locked-period.sie')
    createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('locked-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(targetDatabaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(targetDatabaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(targetDatabaseService, accountingPeriodService, auditLogService)
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    accountingPeriodService.lockPeriod(accountingPeriodService.listPeriods(fiscalYear.id).first().id, 'låst för test')
    SieImportExportService targetService = createSieService(targetDatabaseService)

    IllegalStateException exception = assertThrows(IllegalStateException) {
      targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    }

    assertTrue(exception.message.contains('låsta perioder'))
    assertEquals(ImportJobStatus.FAILED, targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
  }

  @Test
  void importClassifiesGroup21AccountsAsLiabilities() {
    switchHome(tempDir.resolve('group21-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path filePath = writeSimpleSie(tempDir.resolve('group21.sie'), '2120', 'Periodiseringsfonder')

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select account_class as accountClass, manual_review_required as manualReviewRequired from account where account_number = ?',
          ['2120']
      ) as GroovyRowResult
      assertEquals('LIABILITY', row.get('accountClass'))
      assertEquals(false, row.get('manualReviewRequired'))
    }
  }

  @Test
  void importPreservesTrustedExistingAccountClassification() {
    switchHome(tempDir.resolve('preserve-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2120', 'Egen klassning', 'EQUITY', 'CREDIT')
    }
    SieImportExportService service = createSieService(databaseService)
    Path filePath = writeSimpleSie(tempDir.resolve('preserve.sie'), '2120', 'Periodiseringsfonder')

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 manual_review_required as manualReviewRequired
            from account
           where account_number = ?
      ''', ['2120']) as GroovyRowResult
      assertEquals('Periodiseringsfonder', row.get('accountName'))
      assertEquals('EQUITY', row.get('accountClass'))
      assertEquals('CREDIT', row.get('normalBalanceSide'))
      assertEquals(false, row.get('manualReviewRequired'))
    }
  }

  private ExportFixture createExportFixture(Path home, Path exportPath) {
    switchHome(home)
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath)
    new ExportFixture(services.accountCount, services.openingBalanceCount, services.voucherCount, services.lineCount)
  }

  private SeededServices seedEnvironment(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT')
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '1510', 100.00G])
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '2010', 100.00G])
    }

    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 1250.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 1000.00G),
            new VoucherLine(null, null, 0, '2611', null, 'Utgående moms', 0.00G, 250.00G)
        ]
    )

    new SeededServices(
        createSieService(databaseService),
        fiscalYear,
        4,
        2,
        1,
        3
    )
  }

  private SieImportExportService createSieService(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        voucherService,
        new AttachmentService(databaseService, auditLogService),
        auditLogService
    )
    new SieImportExportService(
        databaseService,
        accountingPeriodService,
        voucherService,
        new CompanyService(databaseService),
        reportIntegrityService,
        auditLogService
    )
  }

  private Path writeSimpleSie(Path filePath, String accountNumber, String accountName) {
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO ${accountNumber} "${accountName}"
#IB 0 ${accountNumber} 100.00
"""
    filePath
  }

  private static int countRows(DatabaseService databaseService, String tableName) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('select count(*) as total from ' + tableName) as GroovyRowResult
      ((Number) row.get('total')).intValue()
    }
  }

  private static void insertAccount(Sql sql, String accountNumber, String accountName, String accountClass, String normalBalanceSide) {
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
        ) values (1, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide])
  }

  private void switchHome(Path home) {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, home.toString())
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static final class ExportFixture {

    final int accountCount
    final int openingBalanceCount
    final int voucherCount
    final int lineCount

    private ExportFixture(int accountCount, int openingBalanceCount, int voucherCount, int lineCount) {
      this.accountCount = accountCount
      this.openingBalanceCount = openingBalanceCount
      this.voucherCount = voucherCount
      this.lineCount = lineCount
    }
  }

  private static final class SeededServices {

    final SieImportExportService sieService
    final FiscalYear fiscalYear
    final int accountCount
    final int openingBalanceCount
    final int voucherCount
    final int lineCount

    private SeededServices(
        SieImportExportService sieService,
        FiscalYear fiscalYear,
        int accountCount,
        int openingBalanceCount,
        int voucherCount,
        int lineCount
    ) {
      this.sieService = sieService
      this.fiscalYear = fiscalYear
      this.accountCount = accountCount
      this.openingBalanceCount = openingBalanceCount
      this.voucherCount = voucherCount
      this.lineCount = lineCount
    }
  }
}
