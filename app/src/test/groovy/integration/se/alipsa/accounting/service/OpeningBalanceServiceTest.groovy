package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class OpeningBalanceServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private OpeningBalanceService openingBalanceService
  private AccountService accountService
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
    openingBalanceService = new OpeningBalanceService(databaseService)
    accountService = new AccountService(databaseService)
    seedAccounts()
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
  void transferFromPreviousFiscalYearCopiesClosingBalancesAndMarksTransferred() {
    FiscalYear previousYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    FiscalYear nextYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    accountService.saveOpeningBalance(previousYear.id, '1930', 500.00G)
    voucherService.createVoucher(
        previousYear.id,
        'A',
        LocalDate.of(2025, 3, 1),
        'Sale',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Receivable', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 1000.00G)
        ]
    )
    voucherService.createVoucher(
        previousYear.id,
        'A',
        LocalDate.of(2025, 4, 1),
        'Bank payment',
        [
            new VoucherLine(null, null, 0, null, '4010', null, 'Expense', 300.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 0.00G, 300.00G)
        ]
    )

    int transferred = openingBalanceService.transferFromPreviousFiscalYear(previousYear.id, nextYear.id)

    assertEquals(2, transferred)
    databaseService.withSql { Sql sql ->
      assertEquals(1000.00G, openingBalanceFor(sql, nextYear.id, '1510'))
      assertEquals(200.00G, openingBalanceFor(sql, nextYear.id, '1930'))
      assertEquals(OpeningBalanceService.ORIGIN_TRANSFERRED, openingBalanceOriginFor(sql, nextYear.id, '1510'))
      assertEquals(previousYear.id, openingBalanceSourceFor(sql, nextYear.id, '1510'))
    }
  }

  @Test
  void refreshTransferredBalancesUpdatesAutoManagedRowsButPreservesManualOverrides() {
    FiscalYear previousYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    FiscalYear nextYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    accountService.saveOpeningBalance(previousYear.id, '1930', 500.00G)
    voucherService.createVoucher(
        previousYear.id,
        'A',
        LocalDate.of(2025, 3, 1),
        'Sale',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Receivable', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 1000.00G)
        ]
    )
    openingBalanceService.transferFromPreviousFiscalYear(previousYear.id, nextYear.id)
    openingBalanceService.saveManualOpeningBalance(nextYear.id, '1510', 999.00G)

    voucherService.createVoucher(
        previousYear.id,
        'A',
        LocalDate.of(2025, 5, 1),
        'Late bank adjustment',
        [
            new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 50.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 50.00G)
        ]
    )

    List<OpeningBalanceService.OpeningBalanceDrift> drift = openingBalanceService.detectDrift(nextYear.id)
    assertEquals(1, drift.size())
    assertEquals('1930', drift.first().accountNumber)

    int refreshed = openingBalanceService.refreshTransferredBalances(nextYear.id)

    assertEquals(1, refreshed)
    databaseService.withSql { Sql sql ->
      assertEquals(999.00G, openingBalanceFor(sql, nextYear.id, '1510'))
      assertEquals(OpeningBalanceService.ORIGIN_MANUAL, openingBalanceOriginFor(sql, nextYear.id, '1510'))
      assertEquals(550.00G, openingBalanceFor(sql, nextYear.id, '1930'))
      assertEquals(OpeningBalanceService.ORIGIN_TRANSFERRED, openingBalanceOriginFor(sql, nextYear.id, '1930'))
    }
  }

  private void seedAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Receivables', 'ASSET', 'DEBIT')
      insertAccount(sql, '1930', 'Bank', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Sales', 'INCOME', 'CREDIT')
      insertAccount(sql, '4010', 'Expense', 'EXPENSE', 'DEBIT')
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

  private static String openingBalanceOriginFor(Sql sql, long fiscalYearId, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select ob.origin_type as originType
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
           and a.account_number = ?
    ''', [fiscalYearId, accountNumber]) as GroovyRowResult
    row.get('originType') as String
  }

  private static Long openingBalanceSourceFor(Sql sql, long fiscalYearId, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select ob.source_fiscal_year_id as sourceFiscalYearId
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
           and a.account_number = ?
    ''', [fiscalYearId, accountNumber]) as GroovyRowResult
    row.get('sourceFiscalYearId') as Long
  }
}
