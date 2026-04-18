package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

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
import java.time.LocalDateTime

class VoucherServiceCountTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
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
    seedAccounts()
  }

  private void seedAccounts() {
    databaseService.withTransaction { Sql sql ->
      [
          ['1930', 'Bank', 'ASSET', 'DEBIT'],
          ['3010', 'Income', 'INCOME', 'CREDIT']
      ].each { List<String> row ->
        sql.executeInsert('''
            insert into account (
                company_id, account_number, account_name, account_class,
                normal_balance_side, vat_code, active, manual_review_required,
                classification_note, created_at, updated_at
            ) values (1, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
        ''', [row[0], row[1], row[2], row[3]])
      }
    }
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
  void countVouchersReturnsZeroForEmptyYear() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))

    assertEquals(0, voucherService.countVouchers(CompanyService.LEGACY_COMPANY_ID, year.id))
  }

  @Test
  void countVouchersCountsActiveAndCorrectionVouchers() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))

    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'First', lines)
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 4, 1), 'Second', lines)

    assertEquals(2, voucherService.countVouchers(CompanyService.LEGACY_COMPANY_ID, year.id))
  }

  @Test
  void hasVouchersCreatedAfterReturnsTrueWhenVoucherExistsAfterTimestamp() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    LocalDateTime before = LocalDateTime.now().minusSeconds(1)

    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'Test', lines)

    assertTrue(voucherService.hasVouchersCreatedAfter(CompanyService.LEGACY_COMPANY_ID, before))
  }

  @Test
  void hasVouchersCreatedAfterReturnsFalseWhenNoVouchersAfterTimestamp() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'Test', lines)

    LocalDateTime future = LocalDateTime.now().plusSeconds(5)
    assertFalse(voucherService.hasVouchersCreatedAfter(CompanyService.LEGACY_COMPANY_ID, future))
  }
}
