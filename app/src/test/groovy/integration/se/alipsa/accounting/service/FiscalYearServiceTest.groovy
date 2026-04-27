package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

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
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID,
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
    fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID,
        '2025/2026',
        LocalDate.of(2025, 7, 1),
        LocalDate.of(2026, 6, 30)
    )

    Executable action = {
      fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID,
          '2026 kalenderår',
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2026, 12, 31)
      )
    } as Executable

    assertThrows(IllegalArgumentException, action)
  }

  @Test
  void yearCloseAndReopenUpdateLockStatus() {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )

    assertTrue(!accountingPeriodService.isDateLocked(CompanyService.LEGACY_COMPANY_ID, LocalDate.of(2026, 1, 15)))

    FiscalYear closedYear = fiscalYearService.closeFiscalYear(year.id)

    assertTrue(closedYear.closed)
    assertTrue(accountingPeriodService.isDateLocked(CompanyService.LEGACY_COMPANY_ID, LocalDate.of(2026, 1, 15)))

    FiscalYear reopenedYear = fiscalYearService.reopenFiscalYear(year.id)
    List<AuditLogEntry> auditEntries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)

    assertTrue(!reopenedYear.closed)
    assertTrue(!accountingPeriodService.isDateLocked(CompanyService.LEGACY_COMPANY_ID, LocalDate.of(2026, 1, 15)))
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && entry.fiscalYearId == year.id
    })
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.REOPEN_FISCAL_YEAR && entry.fiscalYearId == year.id
    })
  }

  @Test
  void lockingAnAlreadyLockedPeriodIsRejected() {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID,
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
  void reopenFiscalYearWithClosingEntriesIsRejected() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )
    databaseService.withTransaction { Sql sql ->
      List<List<Object>> accountKeys = sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (?, '3010', 'Försäljning', 'INCOME', 'CREDIT',
              true, false, current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID])
      long accountId = ((Number) accountKeys.first().first()).longValue()
      sql.executeInsert('''
          insert into closing_entry (fiscal_year_id, entry_type, account_id, amount, created_at)
          values (?, 'RESULT_CLOSING', ?, 1000.00, current_timestamp)
      ''', [year.id, accountId])
    }
    fiscalYearService.closeFiscalYear(year.id)

    IllegalStateException exception = assertThrows(IllegalStateException) {
      fiscalYearService.reopenFiscalYear(year.id)
    }
    assertTrue(exception.message.contains('bokslutsposter'))
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
