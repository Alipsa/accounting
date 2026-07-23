package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

final class VoucherBalanceCachePreloaderTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
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
    voucherService = new VoucherService(databaseService, auditLogService)
    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name, account_class, normal_balance_side,
              vat_code, active, manual_review_required, classification_note, created_at, updated_at
          ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID, '1510', 'Kundfordringar', 'ASSET', 'DEBIT'])
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name, account_class, normal_balance_side,
              vat_code, active, manual_review_required, classification_note, created_at, updated_at
          ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID, '3010', 'Försäljning', 'INCOME', 'CREDIT'])
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
  void preloadsBalanceBeforeEachVoucherWithoutLeakingLaterVouchers() {
    Voucher first = createVoucher(LocalDate.of(2030, 1, 10), 100.00G)
    Voucher second = createVoucher(LocalDate.of(2030, 2, 10), 200.00G)
    Voucher third = createVoucher(LocalDate.of(2030, 3, 10), 300.00G)
    CountDownLatch completed = new CountDownLatch(1)
    AtomicReference<Map<Long, Map<String, BigDecimal>>> balances = new AtomicReference<>()

    new VoucherBalanceCachePreloader(new AccountService(databaseService)).preload(
        CompanyService.LEGACY_COMPANY_ID,
        fiscalYear.id,
        [first, second, third],
        1
    ) { Map<Long, Map<String, BigDecimal>> cache, int ignoredGeneration ->
      balances.set(cache)
      completed.countDown()
    }

    assertTrue(completed.await(5, TimeUnit.SECONDS), 'Timed out waiting for balance cache preload.')
    assertEquals(0.00G, balances.get()[first.id]['1510'])
    assertEquals(100.00G, balances.get()[second.id]['1510'])
    assertEquals(300.00G, balances.get()[third.id]['1510'])
    assertEquals(0.00G, balances.get()[first.id]['3010'])
    assertEquals(100.00G, balances.get()[second.id]['3010'])
    assertEquals(300.00G, balances.get()[third.id]['3010'])
  }

  private Voucher createVoucher(LocalDate date, BigDecimal amount) {
    voucherService.createVoucher(fiscalYear.id, 'A', date, 'Test', [
        new VoucherLine(null, null, 0, null, '1510', 'Kundfordringar', '', amount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', 'Försäljning', '', 0.00G, amount)
    ])
  }
}
