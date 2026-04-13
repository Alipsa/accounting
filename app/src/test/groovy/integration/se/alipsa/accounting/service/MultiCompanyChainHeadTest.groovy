package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.sql.Date
import java.time.LocalDate

class MultiCompanyChainHeadTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private ReportDataService reportDataService
  private ReportArchiveService reportArchiveService
  private ReportExportService reportExportService
  private JournoReportService journoReportService
  private AuditLogService auditLogService
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
    reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    reportArchiveService = new ReportArchiveService(databaseService)
    reportExportService = new ReportExportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(voucherService, new AttachmentService(databaseService, auditLogService), auditLogService),
        auditLogService,
        databaseService
    )
    journoReportService = new JournoReportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(voucherService, new AttachmentService(databaseService, auditLogService), auditLogService),
        new CompanySettingsService(databaseService),
        auditLogService,
        databaseService
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
  void secondCompanyCanBookVoucherAndLogAuditEvents() {
    Company company2 = companyService.save(
        new Company(null, 'Second AB', '556123-4567', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    assertNotNull(company2.id)
    assertEquals(2L, company2.id)

    long fyId = databaseService.withTransaction { Sql sql ->
      List<List<Object>> keys = sql.executeInsert('''
          insert into fiscal_year (
              company_id,
              name,
              start_date,
              end_date,
              created_at
          ) values (?, ?, ?, ?, current_timestamp)
      ''', [
          company2.id,
          '2026',
          Date.valueOf(LocalDate.of(2026, 1, 1)),
          Date.valueOf(LocalDate.of(2026, 12, 31))
      ])
      long id = ((Number) keys.first().first()).longValue()
      accountingPeriodService.createPeriods(sql, id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
      VatService.ensurePeriodsForFiscalYear(sql, id)
      insertAccount(sql, company2.id, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, company2.id, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      id
    }

    def voucher = voucherService.createAndBook(
        fyId,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning i bolag 2',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 100.00G)
        ]
    )

    assertNotNull(voucher.voucherNumber)
    assertEquals('BOOKED', voucher.status.name())

    AuditLogEntry entry = auditLogService.logImport('SIE-import för bolag 2', 'test', company2.id)
    assertNotNull(entry)

    assertEquals([], voucherService.validateIntegrity())
    assertEquals([], auditLogService.validateIntegrity())
  }

  @Test
  void reportExportsFromSecondCompanyLandInCorrectAuditChain() {
    Company company2 = companyService.save(
        new Company(null, 'Second AB', '556123-4567', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    long fyId = databaseService.withTransaction { Sql sql ->
      List<List<Object>> keys = sql.executeInsert('''
          insert into fiscal_year (
              company_id,
              name,
              start_date,
              end_date,
              created_at
          ) values (?, ?, ?, ?, current_timestamp)
      ''', [
          company2.id,
          '2026',
          Date.valueOf(LocalDate.of(2026, 1, 1)),
          Date.valueOf(LocalDate.of(2026, 12, 31))
      ])
      long id = ((Number) keys.first().first()).longValue()
      accountingPeriodService.createPeriods(sql, id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
      VatService.ensurePeriodsForFiscalYear(sql, id)
      insertAccount(sql, company2.id, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, company2.id, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      id
    }

    voucherService.createAndBook(
        fyId,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning i bolag 2',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 100.00G)
        ]
    )

    ReportSelection selection = new ReportSelection(
        ReportType.VOUCHER_LIST,
        fyId,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    reportExportService.exportCsv(selection)
    journoReportService.generatePdf(selection)

    assertEquals([], auditLogService.validateIntegrity())

    int exportCount = databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from audit_log where company_id = ? and event_type = ?',
          [company2.id, AuditLogService.EXPORT]
      ) as GroovyRowResult
      ((Number) row.get('total')).intValue()
    }
    assertEquals(2, exportCount)
  }

  private static void insertAccount(
      Sql sql,
      long companyId,
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
    ''', [companyId, accountNumber, accountName, accountClass, normalBalanceSide])
  }
}
