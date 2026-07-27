package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.stream.Stream

class ReportArchiveServiceTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private ReportArchiveService service
  private long fiscalYearId

  private RetentionPolicyService allowingRetentionPolicy

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    fiscalYearId = fiscalYear.id
    allowingRetentionPolicy = new RetentionPolicyService(
        Clock.fixed(Instant.parse('2100-01-01T00:00:00Z'), ZoneId.systemDefault()))
    service = new ReportArchiveService(databaseService, allowingRetentionPolicy)
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
  void archiveIsCreatedWithActiveStatus() {
    ReportArchive archive = service.archiveReport(selection(), 'PDF', 'content'.bytes)

    assertEquals('ACTIVE', archive.status)
    assertTrue(Files.isRegularFile(service.resolveStoredPath(archive)))
  }

  @Test
  void deleteArchiveRemovesFileAndMarksDeleted() {
    ReportArchive archive = service.archiveReport(selection(), 'PDF', 'content'.bytes)

    service.deleteArchive(archive.id)

    ReportArchive afterDelete = service.findArchive(archive.id)
    assertEquals('DELETED', afterDelete.status)
    assertFalse(Files.isRegularFile(service.resolveStoredPath(archive)))
  }

  @Test
  void failedFileDeletionLeavesPendingDeleteAndRetriesOnRecovery() {
    CapturingFileOperations fileOperations = new CapturingFileOperations()
    ReportArchiveService serviceWithFailingDelete = new ReportArchiveService(
        databaseService, allowingRetentionPolicy, fileOperations)
    ReportArchive archive = serviceWithFailingDelete.archiveReport(selection(), 'PDF', 'content'.bytes)

    serviceWithFailingDelete.deleteArchive(archive.id)

    ReportArchive pending = serviceWithFailingDelete.findArchive(archive.id)
    assertEquals('PENDING_DELETE', pending.status)
    assertEquals(1, fileOperations.deleteAttempts.size())

    fileOperations.failDeletes = false
    ReportArchiveRecoveryReport report = serviceWithFailingDelete.recoverOnStartup()

    ReportArchive afterRecovery = serviceWithFailingDelete.findArchive(archive.id)
    assertEquals('DELETED', afterRecovery.status)
    assertEquals(1, report.deletionsDone)
    assertTrue(report.warnings.isEmpty())
  }

  @Test
  void recoveryReportsWarningWhenFileStillCannotBeDeleted() {
    CapturingFileOperations fileOperations = new CapturingFileOperations()
    ReportArchiveService serviceWithFailingDelete = new ReportArchiveService(
        databaseService, allowingRetentionPolicy, fileOperations)
    ReportArchive archive = serviceWithFailingDelete.archiveReport(selection(), 'PDF', 'content'.bytes)
    serviceWithFailingDelete.deleteArchive(archive.id)

    ReportArchiveRecoveryReport report = serviceWithFailingDelete.recoverOnStartup()

    assertEquals(0, report.deletionsDone)
    assertEquals(1, report.warnings.size())
    assertEquals('PENDING_DELETE', serviceWithFailingDelete.findArchive(archive.id).status)
  }

  @Test
  void integrityScanChecksAllActiveArchives() {
    505.times { int index ->
      service.archiveReport(selection(), 'PDF', "content-${index}".bytes)
    }
    ReportArchive broken = service.archiveReport(selection(), 'PDF', 'broken'.bytes)
    Files.write(service.resolveStoredPath(broken), 'corrupted'.bytes)

    List<ReportArchive> failures = service.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)

    assertEquals(1, failures.size())
    assertEquals(broken.id, failures.first().id)
  }

  @Test
  void integrityScanIgnoresDeletedArchives() {
    ReportArchive archive = service.archiveReport(selection(), 'PDF', 'content'.bytes)
    service.deleteArchive(archive.id)

    List<ReportArchive> failures = service.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)

    assertTrue(failures.isEmpty())
  }

  private ReportSelection selection() {
    new ReportSelection(
        reportType: ReportType.VOUCHER_LIST,
        fiscalYearId: fiscalYearId,
        startDate: LocalDate.of(2026, 1, 1),
        endDate: LocalDate.of(2026, 12, 31)
    )
  }

  private static final class CapturingFileOperations implements ReportArchiveFileOperations {

    boolean failDeletes = true
    List<Path> deleteAttempts = []

    @Override
    void write(Path target, byte[] content) throws IOException {
      Files.write(target, content)
    }

    @Override
    boolean deleteIfExists(Path path) throws IOException {
      deleteAttempts << path
      if (failDeletes) {
        throw new IOException('simulated deletion failure')
      }
      Files.deleteIfExists(path)
    }

    @Override
    byte[] readAllBytes(Path path) throws IOException {
      Files.readAllBytes(path)
    }

    @Override
    boolean isRegularFile(Path path) {
      Files.isRegularFile(path)
    }

    @Override
    Stream<Path> walk(Path root) throws IOException {
      Files.walk(root)
    }
  }
}
