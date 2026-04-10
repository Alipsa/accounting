package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class VoucherServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private FiscalYear fiscalYear
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    accountingPeriodService = new AccountingPeriodService(databaseService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService)
    voucherService = new VoucherService(databaseService)
    fiscalYear = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )
    insertTestAccounts()
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void balancedVoucherIsBookedWithRunningNumberAndHashChain() {
    Voucher first = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        balancedLines(100.00G)
    )

    Voucher second = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 16),
        'Försäljning januari 2',
        balancedLines(250.00G)
    )

    assertEquals(VoucherStatus.BOOKED, first.status)
    assertEquals(1, first.runningNumber)
    assertEquals('A-1', first.voucherNumber)
    assertNull(first.previousHash)
    assertNotNull(first.contentHash)
    assertEquals(64, first.contentHash.length())
    assertEquals(100.00G, first.debitTotal())
    assertEquals(100.00G, first.creditTotal())

    assertEquals(2, second.runningNumber)
    assertEquals(first.contentHash, second.previousHash)

    VoucherSeries series = voucherService.listSeries(fiscalYear.id).find { VoucherSeries item ->
      item.seriesCode == 'A'
    }
    assertEquals(3, series.nextRunningNumber)
  }

  @Test
  void unbalancedBookingDoesNotAdvanceNumberSeries() {
    Voucher first = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Första verifikationen',
        balancedLines(100.00G)
    )

    Executable action = {
      voucherService.createAndBook(
          fiscalYear.id,
          'A',
          LocalDate.of(2026, 1, 16),
          'Obalanserad verifikation',
          [
              new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 100.00G, 0.00G),
              new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 90.00G)
          ]
      )
    } as Executable

    assertThrows(IllegalArgumentException, action)

    Voucher second = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 17),
        'Andra verifikationen',
        balancedLines(50.00G)
    )

    assertEquals(1, first.runningNumber)
    assertEquals(2, second.runningNumber)
  }

  @Test
  void bookedVoucherCannotBeUpdatedButCanBeCorrected() {
    Voucher booked = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        balancedLines(100.00G)
    )

    Executable updateAction = {
      voucherService.updateDraft(
          booked.id,
          LocalDate.of(2026, 1, 16),
          'Ändrad text',
          balancedLines(120.00G)
      )
    } as Executable

    assertThrows(IllegalStateException, updateAction)

    Voucher correction = voucherService.createCorrectionVoucher(booked.id)
    Voucher originalAfterCorrection = voucherService.findVoucher(booked.id)

    assertEquals(VoucherStatus.BOOKED, originalAfterCorrection.status)
    assertEquals(VoucherStatus.CORRECTION, correction.status)
    assertEquals(booked.id, correction.originalVoucherId)
    assertEquals(2, correction.runningNumber)
    assertEquals(booked.contentHash, correction.previousHash)
    assertEquals(0.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '1510' }.debitAmount)
    assertEquals(100.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '1510' }.creditAmount)
    assertEquals(100.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '3010' }.debitAmount)
    assertEquals(0.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '3010' }.creditAmount)
  }

  @Test
  void lockedPeriodRejectsBookingBeforeNumberAllocation() {
    AccountingPeriod january = accountingPeriodService.listPeriods(fiscalYear.id).first()
    accountingPeriodService.lockPeriod(january.id, 'Avstämd period.')
    Voucher draft = voucherService.createDraft(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning i låst period',
        balancedLines(100.00G)
    )

    Executable action = {
      voucherService.bookDraft(draft.id)
    } as Executable

    assertThrows(IllegalStateException, action)
    assertEquals(VoucherStatus.DRAFT, voucherService.findVoucher(draft.id).status)
    assertEquals(1, voucherService.listSeries(fiscalYear.id).first().nextRunningNumber)
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
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
        ) values (?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide])
  }

  private static List<VoucherLine> balancedLines(BigDecimal amount) {
    [
        new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', amount, 0.00G),
        new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, amount)
    ]
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
