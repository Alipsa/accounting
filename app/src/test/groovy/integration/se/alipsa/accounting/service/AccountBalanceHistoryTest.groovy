package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

final class AccountBalanceHistoryTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private AccountService accountService
  private FiscalYear fiscalYear
  private VoucherService voucherService

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYear = new FiscalYearService(databaseService, accountingPeriodService, auditLogService).createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2030', LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31))
    accountService = new AccountService(databaseService)
    voucherService = new VoucherService(databaseService, auditLogService)
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
  }

  @AfterEach
  void tearDown() {
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void calculatesBalanceBeforeVoucherWithoutIncludingLaterVouchers() {
    createVoucher(LocalDate.of(2030, 1, 10), 100.00G)
    Voucher current = createVoucher(LocalDate.of(2030, 2, 10), 200.00G)
    createVoucher(LocalDate.of(2030, 3, 10), 300.00G)

    assertEquals(100.00G, accountService.calculateAccountBalanceBeforeVoucher(
        CompanyService.LEGACY_COMPANY_ID, fiscalYear.id, '1510', current))
    assertEquals(['1510': 100.00G], accountService.calculateAccountBalancesBeforeVoucher(
        CompanyService.LEGACY_COMPANY_ID, fiscalYear.id, ['1510'], current))
  }

  private Voucher createVoucher(LocalDate date, BigDecimal amount) {
    voucherService.createVoucher(fiscalYear.id, 'A', date, 'Test', [
        new VoucherLine(null, null, 0, null, '1510', 'Kundfordringar', '', amount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', 'Försäljning', '', 0.00G, amount)
    ])
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
    sql.executeInsert('''
        insert into account (
            company_id, account_number, account_name, account_class, normal_balance_side,
            vat_code, active, manual_review_required, classification_note, created_at, updated_at
        ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [CompanyService.LEGACY_COMPANY_ID, accountNumber, accountName, accountClass, normalBalanceSide])
  }
}
