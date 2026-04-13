package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ClosingServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private AttachmentService attachmentService
  private ReportIntegrityService reportIntegrityService
  private ClosingService closingService
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
    attachmentService = new AttachmentService(databaseService, auditLogService)
    reportIntegrityService = new ReportIntegrityService(voucherService, attachmentService, auditLogService)
    closingService = new ClosingService(
        databaseService,
        accountingPeriodService,
        fiscalYearService,
        voucherService,
        reportIntegrityService,
        Clock.fixed(Instant.parse('2026-04-12T10:00:00Z'), ZoneOffset.UTC)
    )
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void yearEndClosingCreatesClosingVoucherAndNextYearOpeningBalances() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear('2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    databaseService.withTransaction { Sql sql ->
      def accountId = sql.firstRow('select id from account where account_number = ?', ['1930']).get('id')
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, ?, ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, accountId, 500.00G])
    }
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )
    voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 2, 10),
        'Kostnad',
        [
            new VoucherLine(null, null, 0, '4010', null, 'Varukostnad', 300.00G, 0.00G),
            new VoucherLine(null, null, 0, '1930', null, 'Bank', 0.00G, 300.00G)
        ]
    )
    accountingPeriodService.listPeriods(fiscalYear.id).each { period ->
      accountingPeriodService.lockPeriod(period.id, 'Bokslutstest')
    }

    def result = closingService.closeFiscalYear(fiscalYear.id)

    assertEquals('YE-1', result.closingVoucher.voucherNumber)
    assertEquals(2, result.resultAccountCount)
    assertEquals(3, result.openingBalanceCount)
    assertEquals(5, result.closingEntryCount)
    assertTrue(result.closedFiscalYear.closed)
    assertEquals('2027', result.nextFiscalYear.name)

    Voucher closingVoucher = voucherService.findVoucher(result.closingVoucher.id)
    assertNotNull(closingVoucher)
    assertEquals(LocalDate.of(2026, 12, 31), closingVoucher.accountingDate)
    assertEquals(1000.00G, lineForAccount(closingVoucher, '3010').debitAmount)
    assertEquals(300.00G, lineForAccount(closingVoucher, '4010').creditAmount)
    assertEquals(700.00G, lineForAccount(closingVoucher, '2099').creditAmount)

    databaseService.withSql { Sql sql ->
      assertEquals(1000.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '1510'))
      assertEquals(200.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '1930'))
      assertEquals(700.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '2099'))
    }

    List<AuditLogEntry> auditEntries = auditLogService.listEntries()
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && entry.fiscalYearId == fiscalYear.id
    })
    assertEquals(5, closingService.listClosingEntries(fiscalYear.id).size())
  }

  @Test
  void previewWarnsOnDeadlineAndBlocksOpenPeriods() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear('2024', LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
    seedClosingAccounts()

    def preview = closingService.previewClosing(fiscalYear.id)

    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('Alla perioder måste vara låsta') })
    assertTrue(preview.warnings.any { String warning -> warning.contains('Bokslutsfristen passerade') })
  }

  @Test
  void reClosingAnAlreadyClosedYearIsBlocked() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear('2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    accountingPeriodService.listPeriods(fiscalYear.id).each { period ->
      accountingPeriodService.lockPeriod(period.id, 'Bokslutstest')
    }

    closingService.closeFiscalYear(fiscalYear.id)

    def preview = closingService.previewClosing(fiscalYear.id)

    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('redan stängt') })
    IllegalStateException exception = assertThrows(IllegalStateException) {
      closingService.closeFiscalYear(fiscalYear.id)
    }
    assertTrue(exception.message.contains('redan stängt'))
  }

  private void seedClosingAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '1930', 'Företagskonto', 'ASSET', 'DEBIT')
      insertAccount(sql, '2099', 'Årets resultat', 'EQUITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      insertAccount(sql, '4010', 'Varukostnad', 'EXPENSE', 'DEBIT')
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

  private static VoucherLine lineForAccount(Voucher voucher, String accountNumber) {
    voucher.lines.find { VoucherLine line -> line.accountNumber == accountNumber }
  }

  private static BigDecimal openingBalanceFor(Sql sql, long fiscalYearId, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select ob.amount
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
           and a.account_number = ?
    ''', [fiscalYearId, accountNumber]) as GroovyRowResult
    new BigDecimal(row.get('amount').toString())
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
