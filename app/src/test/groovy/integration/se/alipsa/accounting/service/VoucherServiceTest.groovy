package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.sql.Date
import java.time.LocalDate

class VoucherServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
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
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
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
  void voucherIsCreatedWithRunningNumber() {
    Voucher first = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        balancedLines(100.00G)
    )

    Voucher second = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 16),
        'Försäljning januari 2',
        balancedLines(250.00G)
    )

    assertEquals(VoucherStatus.ACTIVE, first.status)
    assertEquals(1, first.runningNumber)
    assertEquals('A-1', first.voucherNumber)
    assertEquals(100.00G, first.debitTotal())
    assertEquals(100.00G, first.creditTotal())

    assertEquals(2, second.runningNumber)

    VoucherSeries series = voucherService.listSeries(fiscalYear.id).find { VoucherSeries item ->
      item.seriesCode == 'A'
    }
    assertEquals(3, series.nextRunningNumber)
  }

  @Test
  void voucherLinesHaveNonNullAccountId() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Kontroll av account_id',
        balancedLines(100.00G)
    )

    voucher.lines.each { VoucherLine line ->
      assertNotNull(line.accountId, "accountId ska inte vara null för rad ${line.accountNumber}")
    }
  }

  @Test
  void unbalancedVoucherDoesNotAdvanceNumberSeries() {
    Voucher first = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Första verifikationen',
        balancedLines(100.00G)
    )

    Executable action = {
      voucherService.createVoucher(
          fiscalYear.id,
          'A',
          LocalDate.of(2026, 1, 16),
          'Obalanserad verifikation',
          [
              new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 100.00G, 0.00G),
              new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 90.00G)
          ]
      )
    } as Executable

    assertThrows(IllegalArgumentException, action)

    Voucher second = voucherService.createVoucher(
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
  void activeVoucherCanBeUpdatedAndCorrected() {
    Voucher active = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        balancedLines(100.00G)
    )

    Voucher updated = voucherService.updateVoucher(
        active.id,
        LocalDate.of(2026, 1, 16),
        'Ändrad text',
        balancedLines(120.00G)
    )

    assertEquals(LocalDate.of(2026, 1, 16), updated.accountingDate)
    assertEquals('Ändrad text', updated.description)
    assertEquals(120.00G, updated.debitTotal())

    Voucher correction = voucherService.createCorrectionVoucher(active.id)
    Voucher originalAfterCorrection = voucherService.findVoucher(active.id)

    assertEquals(VoucherStatus.ACTIVE, originalAfterCorrection.status)
    assertEquals(VoucherStatus.CORRECTION, correction.status)
    assertEquals(active.id, correction.originalVoucherId)
    assertEquals(2, correction.runningNumber)
    assertEquals(0.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '1510' }.debitAmount)
    assertEquals(120.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '1510' }.creditAmount)
    assertEquals(120.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '3010' }.debitAmount)
    assertEquals(0.00G, correction.lines.find { VoucherLine line -> line.accountNumber == '3010' }.creditAmount)
  }

  @Test
  void lockedPeriodRejectsVoucherCreation() {
    AccountingPeriod january = accountingPeriodService.listPeriods(fiscalYear.id).first()
    accountingPeriodService.lockPeriod(january.id, 'Avstämd period.')

    Executable action = {
      voucherService.createVoucher(
          fiscalYear.id,
          'A',
          LocalDate.of(2026, 1, 15),
          'Försäljning i låst period',
          balancedLines(100.00G)
      )
    } as Executable

    assertThrows(IllegalStateException, action)
    assertTrue(voucherService.listSeries(fiscalYear.id).isEmpty())
  }

  @Test
  void voucherCanBeCancelled() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 2, 15),
        'Verifikation att makulera',
        balancedLines(100.00G)
    )

    Voucher cancelled = voucherService.cancelVoucher(voucher.id)

    assertEquals(VoucherStatus.CANCELLED, cancelled.status)
  }

  @Test
  void creationRejectsInvalidDateUnknownAccountAndInactiveAccount() {
    Executable outsideFiscalYear = {
      voucherService.createVoucher(
          fiscalYear.id,
          'A',
          LocalDate.of(2025, 12, 31),
          'Utanför räkenskapsåret',
          balancedLines(100.00G)
      )
    } as Executable
    Executable unknownAccount = {
      voucherService.createVoucher(
          fiscalYear.id,
          'A',
          LocalDate.of(2026, 3, 1),
          'Okänt konto',
          [
              new VoucherLine(null, null, 0, null, '9999', null, 'Okänt konto', 100.00G, 0.00G),
              new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 100.00G)
          ]
      )
    } as Executable

    deactivateAccount('1510')
    Executable inactiveAccount = {
      voucherService.createVoucher(
          fiscalYear.id,
          'A',
          LocalDate.of(2026, 3, 2),
          'Inaktivt konto',
          balancedLines(100.00G)
      )
    } as Executable

    assertThrows(IllegalArgumentException, outsideFiscalYear)
    assertThrows(IllegalArgumentException, unknownAccount)
    assertThrows(IllegalArgumentException, inactiveAccount)
  }

  @Test
  void differentSeriesKeepIndependentRunningNumbers() {
    Voucher firstInA = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 4, 1),
        'Serie A',
        balancedLines(100.00G)
    )
    Voucher firstInB = voucherService.createVoucher(
        fiscalYear.id,
        'B',
        LocalDate.of(2026, 4, 2),
        'Serie B',
        balancedLines(200.00G)
    )
    Voucher secondInA = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 4, 3),
        'Serie A igen',
        balancedLines(300.00G)
    )

    assertEquals('A-1', firstInA.voucherNumber)
    assertEquals('B-1', firstInB.voucherNumber)
    assertEquals('A-2', secondInA.voucherNumber)
  }

  @Test
  void correctionIsBlockedIfOriginalPeriodWasLockedAfterCreation() {
    Voucher active = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 10),
        'Försäljning i januari',
        balancedLines(100.00G)
    )
    AccountingPeriod january = accountingPeriodService.listPeriods(fiscalYear.id).first()
    accountingPeriodService.lockPeriod(january.id, 'Januari är låst.')

    Executable action = {
      voucherService.createCorrectionVoucher(active.id)
    } as Executable

    IllegalStateException exception = assertThrows(IllegalStateException, action)
    assertEquals('Perioden är låst och verifikationen kan inte ändras.', exception.message)
  }

  @Test
  void voucherLifecycleIsLoggedInAuditTrail() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 5, 1),
        'Audit verifikation',
        balancedLines(100.00G)
    )

    Voucher correctable = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 5, 2),
        'Audit bokförd',
        balancedLines(200.00G)
    )
    Voucher correction = voucherService.createCorrectionVoucher(correctable.id)

    List<AuditLogEntry> voucherEntries = auditLogService.listEntriesForVoucher(voucher.id)
    List<AuditLogEntry> correctableEntries = auditLogService.listEntriesForVoucher(correctable.id)
    List<AuditLogEntry> correctionEntries = auditLogService.listEntriesForVoucher(correction.id)

    assertTrue(voucherEntries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.CREATE_VOUCHER })
    assertTrue(correctableEntries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.CREATE_VOUCHER })
    assertTrue(correctionEntries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.CORRECTION_VOUCHER })
    assertEquals([], auditLogService.validateIntegrity())
  }

  @Test
  void multiCompanyBookingResolvesCorrectAccountId() {
    CompanyService companyService = new CompanyService(databaseService)
    Company company2 = companyService.save(
        new Company(null, 'Second AB', '556123-4567', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    long fy2Id = databaseService.withTransaction { Sql sql ->
      List<List<Object>> keys = sql.executeInsert('''
          insert into fiscal_year (
              company_id, name, start_date, end_date, created_at
          ) values (?, ?, ?, ?, current_timestamp)
      ''', [
          company2.id, '2026',
          Date.valueOf(LocalDate.of(2026, 1, 1)),
          Date.valueOf(LocalDate.of(2026, 12, 31))
      ])
      long id = ((Number) keys.first().first()).longValue()
      accountingPeriodService.createPeriods(sql, id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
      VatService.ensurePeriodsForFiscalYear(sql, id)
      insertAccountForCompany(sql, company2.id, '1510', 'Kundfordringar B2', 'ASSET', 'DEBIT')
      insertAccountForCompany(sql, company2.id, '3010', 'Försäljning B2', 'INCOME', 'CREDIT')
      id
    }

    Voucher v1 = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 1, 15),
        'Försäljning bolag 1', balancedLines(100.00G)
    )
    Voucher v2 = voucherService.createVoucher(
        fy2Id, 'A', LocalDate.of(2026, 1, 15),
        'Försäljning bolag 2', balancedLines(200.00G)
    )

    Map<String, Long> company1Accounts = resolveAccountIds(1L)
    Map<String, Long> company2Accounts = resolveAccountIds(company2.id)

    v1.lines.each { VoucherLine line ->
      assertEquals(company1Accounts[line.accountNumber], line.accountId,
          "Bolag 1 rad ${line.accountNumber} ska referera till bolag 1:s konto")
    }
    v2.lines.each { VoucherLine line ->
      assertEquals(company2Accounts[line.accountNumber], line.accountId,
          "Bolag 2 rad ${line.accountNumber} ska referera till bolag 2:s konto")
    }

    assertTrue(company1Accounts['1510'] != company2Accounts['1510'],
        'Bolag 1 och 2 ska ha olika account_id för samma kontonummer')
  }

  private Map<String, Long> resolveAccountIds(long companyId) {
    databaseService.withSql { Sql sql ->
      Map<String, Long> result = [:]
      sql.eachRow(
          'select id, account_number from account where company_id = ?',
          [companyId]
      ) { row ->
        result[row.getString('account_number')] = ((Number) row.getLong('id')).longValue()
      }
      result
    }
  }

  private static void insertAccountForCompany(
      Sql sql,
      long companyId,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
    sql.executeInsert('''
        insert into account (
            company_id, account_number, account_name, account_class,
            normal_balance_side, vat_code, active, manual_review_required,
            classification_note, created_at, updated_at
        ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [companyId, accountNumber, accountName, accountClass, normalBalanceSide])
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

  private static List<VoucherLine> balancedLines(BigDecimal amount) {
    [
        new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', amount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, amount)
    ]
  }

  private void deactivateAccount(String accountNumber) {
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('update account set active = false, updated_at = current_timestamp where account_number = ?', [accountNumber])
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
