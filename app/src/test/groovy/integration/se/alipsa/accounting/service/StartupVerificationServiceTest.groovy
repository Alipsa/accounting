package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class StartupVerificationServiceTest {

  @TempDir
  Path tempDir

  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
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
  void startupVerificationReportsTamperedReportArchive() {
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    ReportArchiveService reportArchiveService = new ReportArchiveService(databaseService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(attachmentService, auditLogService)

    def fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    def archive = reportArchiveService.archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, fiscalYear.id, null, fiscalYear.startDate, fiscalYear.endDate),
        'PDF',
        'report'.bytes
    )
    Files.writeString(reportArchiveService.resolveStoredPath(archive), 'tampered')

    StartupVerificationReport report = new StartupVerificationService(
        databaseService,
        reportIntegrityService,
        reportArchiveService,
        attachmentService
    ).verify()

    assertFalse(report.ok)
    assertTrue(report.errors.any { String error -> error.contains('Rapportarkiv') })
  }
}
