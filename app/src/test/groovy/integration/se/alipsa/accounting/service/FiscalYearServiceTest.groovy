package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FiscalYearServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
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
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void createFiscalYearGeneratesMonthlyPeriodsForBrokenYear() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        '2025/2026 brutet',
        LocalDate.of(2025, 7, 15),
        LocalDate.of(2026, 7, 14)
    )

    List<AccountingPeriod> periods = accountingPeriodService.listPeriods(year.id)

    assertEquals(13, periods.size())
    assertEquals(LocalDate.of(2025, 7, 15), periods.first().startDate)
    assertEquals(LocalDate.of(2025, 7, 31), periods.first().endDate)
    assertEquals(LocalDate.of(2026, 7, 1), periods.last().startDate)
    assertEquals(LocalDate.of(2026, 7, 14), periods.last().endDate)
  }

  @Test
  void overlappingFiscalYearsAreRejected() {
    fiscalYearService.createFiscalYear(
        '2025/2026',
        LocalDate.of(2025, 7, 1),
        LocalDate.of(2026, 6, 30)
    )

    Executable action = {
      fiscalYearService.createFiscalYear(
          '2026 kalenderår',
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2026, 12, 31)
      )
    } as Executable

    assertThrows(IllegalArgumentException, action)
  }

  @Test
  void periodLockAndYearCloseUpdateLockStatus() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )

    List<AccountingPeriod> periods = accountingPeriodService.listPeriods(year.id)
    AccountingPeriod january = periods.first()

    accountingPeriodService.lockPeriod(january.id, 'Månaden är avstämd.')
    accountingPeriodService.listPeriods(year.id).findAll { AccountingPeriod period -> period.id != january.id }.each { AccountingPeriod period ->
      accountingPeriodService.lockPeriod(period.id, 'Årsslut.')
    }

    assertTrue(accountingPeriodService.isDateLocked(LocalDate.of(2026, 1, 15)))
    assertTrue(accountingPeriodService.isDateLocked(LocalDate.of(2026, 2, 15)))

    FiscalYear closedYear = fiscalYearService.closeFiscalYear(year.id)
    List<AccountingPeriod> closedPeriods = accountingPeriodService.listPeriods(year.id)
    List<AuditLogEntry> auditEntries = auditLogService.listEntries()

    assertTrue(closedYear.closed)
    assertTrue(closedPeriods.every { AccountingPeriod period -> period.locked })
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.LOCK_PERIOD && entry.accountingPeriodId == january.id
    })
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && entry.fiscalYearId == year.id
    })
  }

  @Test
  void closingYearWithOpenPeriodsIsRejected() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )

    Executable action = {
      fiscalYearService.closeFiscalYear(year.id)
    } as Executable

    assertThrows(IllegalStateException, action)
  }

  @Test
  void lockingAnAlreadyLockedPeriodIsRejected() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )

    AccountingPeriod january = accountingPeriodService.listPeriods(year.id).first()
    accountingPeriodService.lockPeriod(january.id, 'Första låsningen.')

    Executable action = {
      accountingPeriodService.lockPeriod(january.id, 'Andra låsningen.')
    } as Executable

    assertThrows(IllegalStateException, action)
  }

  @Test
  void deleteFiscalYearReportsDependenciesBeforeDatabaseConstraintFailure() {
    fiscalYearService = new FiscalYearService(
        databaseService,
        accountingPeriodService,
        auditLogService,
        new RetentionPolicyService(Clock.fixed(Instant.parse('2030-01-01T00:00:00Z'), ZoneOffset.UTC))
    )
    FiscalYear year = fiscalYearService.createFiscalYear(
        '2020',
        LocalDate.of(2020, 1, 1),
        LocalDate.of(2020, 12, 31)
    )
    new ReportArchiveService(databaseService).archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, year.id, null, year.startDate, year.endDate),
        'PDF',
        'arkiv'.bytes
    )

    IllegalStateException exception = assertThrows(IllegalStateException) {
      fiscalYearService.deleteFiscalYear(year.id)
    }

    assertTrue(exception.message.contains('rapportarkiv'))
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
