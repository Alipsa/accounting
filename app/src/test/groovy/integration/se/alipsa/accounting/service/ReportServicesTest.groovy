package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class ReportServicesTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private ReportDataService reportDataService
  private ReportArchiveService reportArchiveService
  private ReportExportService reportExportService
  private JournoReportService journoReportService
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
    voucherService = new VoucherService(databaseService, auditLogService)
    reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    reportArchiveService = new ReportArchiveService(databaseService)
    reportExportService = new ReportExportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(voucherService, new AttachmentService(databaseService, auditLogService), auditLogService),
        auditLogService
    )
    journoReportService = new JournoReportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(voucherService, new AttachmentService(databaseService, auditLogService), auditLogService),
        new CompanySettingsService(databaseService),
        auditLogService
    )
    fiscalYear = fiscalYearService.createFiscalYear('2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    insertTestAccounts()
    bookFixtures()
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void csvExportUsesSameRowsAsPreviewAndArchivesTheArtifact() {
    ReportSelection selection = new ReportSelection(
        ReportType.VOUCHER_LIST,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    ReportResult preview = reportDataService.generate(selection)
    byte[] expectedCsv = reportExportService.renderCsv(preview)
    ReportArchive archive = reportExportService.exportCsv(selection)
    byte[] archivedCsv = reportArchiveService.readArchive(archive.id)

    assertArrayEquals(expectedCsv, archivedCsv)
    assertEquals('CSV', archive.reportFormat)
    assertEquals(ReportType.VOUCHER_LIST, archive.reportType)
    assertEquals(
        '''Datum;Verifikation;Serie;Text;Status;Debet;Kredit
2026-01-10;A-1;A;Försäljning januari;BOOKED;1250.00;1250.00
2026-01-18;A-2;A;Leverantörsfaktura;BOOKED;250.00;250.00
''',
        new String(archivedCsv, StandardCharsets.UTF_8)
    )
    assertTrue(Files.isRegularFile(reportArchiveService.resolveStoredPath(archive)))
  }

  @Test
  void pdfGenerationProducesArchivedPdfFile() {
    ReportSelection selection = new ReportSelection(
        ReportType.INCOME_STATEMENT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    ReportArchive archive = journoReportService.generatePdf(selection)
    byte[] pdf = reportArchiveService.readArchive(archive.id)

    assertEquals('PDF', archive.reportFormat)
    assertEquals(ReportType.INCOME_STATEMENT, archive.reportType)
    assertTrue(new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith('%PDF-'))
    assertTrue(Files.size(reportArchiveService.resolveStoredPath(archive)) > 500L)
  }

  private void bookFixtures() {
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 10),
        'Försäljning januari',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 1250.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 1000.00G),
            new VoucherLine(null, null, 0, '2611', null, 'Utgående moms', 0.00G, 250.00G)
        ]
    )
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 18),
        'Leverantörsfaktura',
        [
            new VoucherLine(null, null, 0, '4010', null, 'Varuinköp', 200.00G, 0.00G),
            new VoucherLine(null, null, 0, '2641', null, 'Ingående moms', 50.00G, 0.00G),
            new VoucherLine(null, null, 0, '2440', null, 'Leverantörsskuld', 0.00G, 250.00G)
        ]
    )
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null)
      insertAccount(sql, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT', null)
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccount(sql, '2641', 'Debiterad ingående moms', 'ASSET', 'DEBIT', VatCode.INPUT_25.name())
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccount(sql, '4010', 'Varuinköp', 'EXPENSE', 'DEBIT', VatCode.INPUT_25.name())
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

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
