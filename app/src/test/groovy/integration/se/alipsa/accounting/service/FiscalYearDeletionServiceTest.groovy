package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FiscalYearDeletionServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private FiscalYearDeletionService deletionService
  private VoucherService voucherService
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
    voucherService = new VoucherService(databaseService, auditLogService)
    RetentionPolicyService retentionPolicyService = new RetentionPolicyService(
        Clock.fixed(Instant.parse('2030-01-01T00:00:00Z'), ZoneOffset.UTC)
    )
    deletionService = new FiscalYearDeletionService(
        databaseService, retentionPolicyService, auditLogService, fiscalYearService
    )
  }

  @AfterEach
  void tearDown() {
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void deleteFiscalYearOlderThanSevenYearsSucceeds() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )

    FiscalYearDeletionResult result = deletionService.deleteFiscalYear(year.id)

    assertNotNull(result)
    assertNull(fiscalYearService.findById(year.id))
  }

  @Test
  void deleteFiscalYearWithinRetentionWindowIsRejected() {
    RetentionPolicyService strictPolicy = new RetentionPolicyService(
        Clock.fixed(Instant.parse('2025-01-01T00:00:00Z'), ZoneOffset.UTC)
    )
    FiscalYearDeletionService strictService = new FiscalYearDeletionService(
        databaseService, strictPolicy, auditLogService, fiscalYearService
    )
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )

    assertThrows(IllegalStateException) {
      strictService.deleteFiscalYear(year.id)
    }
    assertNotNull(fiscalYearService.findById(year.id))
  }

  @Test
  void deleteFiscalYearRemovesVouchersAndReportArchives() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    seedAccount(CompanyService.LEGACY_COMPANY_ID)
    voucherService.createVoucher(
        year.id, 'A', LocalDate.of(2020, 3, 15), 'Test voucher',
        [
            new VoucherLine(null, null, 0, null, '1910', null, null, 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 100.00G)
        ]
    )
    new ReportArchiveService(databaseService).archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, year.id, null, year.startDate, year.endDate),
        'PDF',
        'data'.bytes
    )

    FiscalYearReplacementPlan plan = deletionService.previewDeletion(year.id)

    assertEquals(1, plan.summary.voucherCount)
    assertEquals(1, plan.summary.reportArchiveCount)

    deletionService.deleteFiscalYear(year.id)

    assertNull(fiscalYearService.findById(year.id))
    databaseService.withSql { Sql sql ->
      assertEquals(0, countRows(sql, 'voucher', 'fiscal_year_id', year.id))
      assertEquals(0, countRows(sql, 'report_archive', 'fiscal_year_id', year.id))
    }
  }

  @Test
  void deleteFiscalYearDeletesAuditLogRowsPermanently() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    seedAccount(CompanyService.LEGACY_COMPANY_ID)
    Voucher voucher = voucherService.createVoucher(
        year.id, 'A', LocalDate.of(2020, 6, 1), 'Voucher for audit test',
        [
            new VoucherLine(null, null, 0, null, '1910', null, null, 50.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 50.00G)
        ]
    )

    deletionService.deleteFiscalYear(year.id)

    databaseService.withSql { Sql sql ->
      GroovyRowResult archivedRow = sql.firstRow('''
          select count(*) as total
            from audit_log
           where company_id = ?
             and archived = true
      ''', [CompanyService.LEGACY_COMPANY_ID]) as GroovyRowResult
      assertEquals(0, ((Number) archivedRow.get('total')).intValue())
      assertEquals(0, countRows(sql, 'audit_log', 'fiscal_year_id', year.id))
      assertEquals(0, countRows(sql, 'audit_log', 'voucher_id', voucher.id))
    }
  }

  @Test
  void remainingAuditChainValidatesAfterFiscalYearDeletion() {
    FiscalYear year2020 = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    FiscalYear year2021 = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2021',
        LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31)
    )
    seedAccount(CompanyService.LEGACY_COMPANY_ID)
    auditLogService.logImport('Before deletion', null, CompanyService.LEGACY_COMPANY_ID)
    voucherService.createVoucher(
        year2020.id, 'A', LocalDate.of(2020, 6, 1), 'Deleted-year voucher',
        [
            new VoucherLine(null, null, 0, null, '1910', null, null, 10.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 10.00G)
        ]
    )
    Voucher survivingVoucher = voucherService.createVoucher(
        year2021.id, 'A', LocalDate.of(2021, 6, 1), 'Surviving-year voucher',
        [
            new VoucherLine(null, null, 0, null, '1910', null, null, 20.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 20.00G)
        ]
    )

    deletionService.deleteFiscalYear(year2020.id)

    assertEquals([], auditLogService.validateIntegrity())
    databaseService.withSql { Sql sql ->
      assertEquals(1, countRows(sql, 'audit_log', 'voucher_id', survivingVoucher.id))
    }
  }

  @Test
  void deleteFiscalYearDeletesStoredFilesFromService() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    seedAccount(CompanyService.LEGACY_COMPANY_ID)
    Voucher voucher = voucherService.createVoucher(
        year.id, 'A', LocalDate.of(2020, 3, 15), 'Voucher with attachment',
        [
            new VoucherLine(null, null, 0, null, '1910', null, null, 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 100.00G)
        ]
    )
    Path source = tempDir.resolve('receipt.txt')
    Files.writeString(source, 'receipt')
    new AttachmentService(databaseService, auditLogService).addAttachment(voucher.id, source)
    new ReportArchiveService(databaseService).archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, year.id, null, year.startDate, year.endDate),
        'PDF',
        'data'.bytes
    )

    FiscalYearDeletionResult result = deletionService.deleteFiscalYear(year.id)

    assertEquals(2, result.deletedFiles.size())
    assertEquals([], result.failedFiles)
    result.deletedFiles.each { String path ->
      assertFalse(Files.exists(Path.of(path)))
    }
  }

  @Test
  void fileDeletionFailureIsReported() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    new ReportArchiveService(databaseService).archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, year.id, null, year.startDate, year.endDate),
        'PDF',
        'data'.bytes
    )
    Path archivedPath = databaseService.withSql { Sql sql ->
      String storagePath = sql.firstRow(
          'select storage_path as storagePath from report_archive where fiscal_year_id = ?',
          [year.id]
      ).get('storagePath') as String
      AppPaths.reportsDirectory().resolve(storagePath)
    }
    Files.delete(archivedPath)
    Files.createDirectories(archivedPath)
    Files.writeString(archivedPath.resolve('locked-child.txt'), 'still here')

    FiscalYearDeletionResult result = deletionService.deleteFiscalYear(year.id)

    assertEquals(1, result.failedFiles.size())
    assertEquals(archivedPath.toAbsolutePath().normalize().toString(), result.failedFiles.first().resolvedPath)
    assertTrue(Files.exists(archivedPath))
  }

  @Test
  void pathTraversalStoragePathIsReportedAndNotDeleted() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    new ReportArchiveService(databaseService).archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, year.id, null, year.startDate, year.endDate),
        'PDF',
        'data'.bytes
    )
    Path outsideFile = AppPaths.reportsDirectory().resolve('../../outside.txt').toAbsolutePath().normalize()
    Files.writeString(outsideFile, 'must remain')
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate(
          'update report_archive set storage_path = ? where fiscal_year_id = ?',
          ['../../outside.txt', year.id]
      )
    }

    FiscalYearDeletionResult result = deletionService.deleteFiscalYear(year.id)

    assertEquals(1, result.failedFiles.size())
    assertEquals('../../outside.txt', result.failedFiles.first().storagePath)
    assertTrue(result.failedFiles.first().message.contains('utanför arkivkatalogen'))
    assertTrue(Files.exists(outsideFile))
    assertEquals('must remain', Files.readString(outsideFile))
  }

  @Test
  void deleteFiscalYearNullifiesCrossYearReferences() {
    FiscalYear year2020 = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )
    FiscalYear year2021 = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2021',
        LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31)
    )
    seedAccount(CompanyService.LEGACY_COMPANY_ID)
    databaseService.withTransaction { Sql sql ->
      long accountId = sql.firstRow('''
          select id from account
           where company_id = ? and account_number = '3010'
      ''', [CompanyService.LEGACY_COMPANY_ID]).get('id') as long

      sql.executeInsert('''
          insert into closing_entry (fiscal_year_id, next_fiscal_year_id, entry_type, account_id, amount, created_at)
          values (?, ?, 'OPENING_BALANCE', ?, 1000.00, current_timestamp)
      ''', [year2020.id, year2021.id, accountId])
    }

    deletionService.deleteFiscalYear(year2020.id)

    assertNull(fiscalYearService.findById(year2020.id))
    assertNotNull(fiscalYearService.findById(year2021.id))

    databaseService.withSql { Sql sql ->
      GroovyRowResult ceRow = sql.firstRow('''
          select count(*) as total
            from closing_entry
           where next_fiscal_year_id = ?
      ''', [year2020.id]) as GroovyRowResult
      assertEquals(0, ((Number) ceRow.get('total')).intValue())
    }
  }

  @Test
  void companyDeletionSucceedsAfterAllFiscalYearsAreDeleted() {
    CompanyService companyService = new CompanyService(databaseService, auditLogService)
    Company company = companyService.save(
        new Company(null, 'Delete After Years AB', '556111-2222', 'SEK', 'sv-SE',
            VatPeriodicity.MONTHLY, true, null, null)
    )
    FiscalYear year = fiscalYearService.createFiscalYear(
        company.id, '2020',
        LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)
    )

    deletionService.deleteFiscalYear(year.id)
    companyService.deleteCompany(company.id)

    assertNull(companyService.findById(company.id))
  }

  private void seedAccount(long companyId) {
    databaseService.withTransaction { Sql sql ->
      GroovyRowResult existing = sql.firstRow(
          "select count(*) as total from account where company_id = ? and account_number = '1910'",
          [companyId]
      ) as GroovyRowResult
      if (((Number) existing.get('total')).intValue() > 0) {
        return
      }
      sql.executeInsert('''
          insert into account (company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at)
          values (?, '1910', 'Kassa', 'ASSET', 'DEBIT', true, false,
              current_timestamp, current_timestamp)
      ''', [companyId])
      sql.executeInsert('''
          insert into account (company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at)
          values (?, '3010', 'Försäljning', 'INCOME', 'CREDIT', true, false,
              current_timestamp, current_timestamp)
      ''', [companyId])
    }
  }

  private static int countRows(Sql sql, String table, String column, long value) {
    GroovyRowResult row = sql.firstRow(
        "select count(*) as total from ${table} where ${column} = ?" as String,
        [value]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue()
  }
}
