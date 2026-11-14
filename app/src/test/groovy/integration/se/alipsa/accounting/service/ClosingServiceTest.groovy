package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.LegalForm
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.IncomeStatementRowType
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
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
    reportIntegrityService = new ReportIntegrityService(attachmentService, auditLogService)
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
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
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
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 2, 10),
        'Kostnad',
        [
            new VoucherLine(null, null, 0, null, '4010', null, 'Varukostnad', 300.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 0.00G, 300.00G)
        ]
    )
    def result = closingService.closeFiscalYear(fiscalYear.id)

    assertEquals('YE-1', result.closingVoucher.voucherNumber)
    assertEquals(2, result.resultAccountsWithBalanceCount)
    assertEquals(3, result.openingBalanceCount)
    assertEquals(4, result.closingEntryCount)
    assertTrue(result.closedFiscalYear.closed)
    assertEquals('2027', result.nextFiscalYear.name)

    Voucher closingVoucher = voucherService.findVoucher(result.closingVoucher.id)
    assertNotNull(closingVoucher)
    assertEquals(LocalDate.of(2026, 12, 31), closingVoucher.accountingDate)
    assertEquals(2, closingVoucher.lines.size())
    assertEquals(700.00G, lineForAccount(closingVoucher, '8999').debitAmount)
    assertNull(lineForAccount(closingVoucher, '3010'))
    assertNull(lineForAccount(closingVoucher, '4010'))
    assertEquals(700.00G, lineForAccount(closingVoucher, '2099').creditAmount)

    databaseService.withSql { Sql sql ->
      assertEquals(1000.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '1510'))
      assertEquals(200.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '1930'))
      assertEquals(700.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '2099'))
    }

    List<AuditLogEntry> auditEntries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)
    assertTrue(auditEntries.any { AuditLogEntry entry ->
      entry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && entry.fiscalYearId == fiscalYear.id
    })
    assertEquals(4, closingService.listClosingEntries(fiscalYear.id).size())
  }

  @Test
  void previewWarnsOnDeadlineWithoutRequiringLockedPeriods() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2024', LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
    seedClosingAccounts()

    def preview = closingService.previewClosing(fiscalYear.id)

    assertEquals([], preview.blockingIssues)
    assertTrue(preview.warnings.any { String warning -> warning.contains('Bokslutsfristen passerade') })
  }

  @Test
  void previewBlocksWhenAnAccountWithActivityIsUnclassified() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '8314', 'Resultat från övriga värdepapper', null, null)
    }
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 3, 1),
        'Oklassificerad post',
        [
            new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '8314', null, 'Oklassificerat', 0.00G, 100.00G)
        ]
    )

    def preview = closingService.previewClosing(fiscalYear.id)

    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('8314') })

    IllegalStateException exception = assertThrows(IllegalStateException) {
      closingService.closeFiscalYear(fiscalYear.id)
    }
    assertTrue(exception.message.contains('8314'))
  }

  @Test
  void reClosingAnAlreadyClosedYearIsBlocked() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    closingService.closeFiscalYear(fiscalYear.id)

    def preview = closingService.previewClosing(fiscalYear.id)

    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('redan stängt') })
    IllegalStateException exception = assertThrows(IllegalStateException) {
      closingService.closeFiscalYear(fiscalYear.id)
    }
    assertTrue(exception.message.contains('redan stängt'))
  }

  @Test
  void yearEndClosingReplacesTransferredOpeningBalancesWhenNextYearHasNoVouchers() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear nextFiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2027', LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))
    seedClosingAccounts()
    new OpeningBalanceService(databaseService).transferFromPreviousFiscalYear(fiscalYear.id, nextFiscalYear.id)
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )
    def result = closingService.closeFiscalYear(fiscalYear.id)

    assertEquals(nextFiscalYear.id, result.nextFiscalYear.id)
    databaseService.withSql { Sql sql ->
      assertEquals(1000.00G, openingBalanceFor(sql, nextFiscalYear.id, '1510'))
      assertEquals(OpeningBalanceService.ORIGIN_YEAR_END_CLOSE, openingBalanceOriginFor(sql, nextFiscalYear.id, '1510'))
    }
  }

  @Test
  void yearEndClosingIsBlockedWhenNextYearContainsManualOpeningBalances() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear nextFiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2027', LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))
    seedClosingAccounts()
    accountService().saveOpeningBalance(nextFiscalYear.id, '1510', 123.00G)
    def preview = closingService.previewClosing(fiscalYear.id)

    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('manuellt justerade ingående balanser') })
  }

  @Test
  void previewReusesExistingNextYearEvenWhenItsLengthDiffers() {
    FiscalYear shortenedFirstYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2019', LocalDate.of(2019, 1, 17), LocalDate.of(2019, 12, 31))
    FiscalYear existingNextYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020', LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
    seedClosingAccounts()

    def preview = closingService.previewClosing(shortenedFirstYear.id)

    assertEquals([], preview.blockingIssues)
    assertFalse(preview.nextFiscalYearWillBeCreated)
    assertEquals(existingNextYear.id, preview.nextFiscalYear.id)
  }

  @Test
  void previewPlansFullTwelveMonthYearAfterShortenedFirstYear() {
    FiscalYear shortenedFirstYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2019', LocalDate.of(2019, 1, 17), LocalDate.of(2019, 12, 31))
    seedClosingAccounts()

    def preview = closingService.previewClosing(shortenedFirstYear.id)

    assertEquals([], preview.blockingIssues)
    assertTrue(preview.nextFiscalYearWillBeCreated)
    assertEquals(LocalDate.of(2020, 1, 1), preview.nextFiscalYear.startDate)
    assertEquals(LocalDate.of(2020, 12, 31), preview.nextFiscalYear.endDate)
  }

  @Test
  void previewPlansFullYearAfterNonCalendarFiscalYear() {
    FiscalYear brokenFiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2019/2020', LocalDate.of(2019, 7, 1), LocalDate.of(2020, 6, 30))
    seedClosingAccounts()

    def preview = closingService.previewClosing(brokenFiscalYear.id)

    assertEquals([], preview.blockingIssues)
    assertTrue(preview.nextFiscalYearWillBeCreated)
    assertEquals(LocalDate.of(2020, 7, 1), preview.nextFiscalYear.startDate)
    assertEquals(LocalDate.of(2021, 6, 30), preview.nextFiscalYear.endDate)
  }

  @Test
  void previewPlansTwelveMonthsAfterLeapYear() {
    FiscalYear leapYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020', LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
    seedClosingAccounts()

    def preview = closingService.previewClosing(leapYear.id)

    assertEquals([], preview.blockingIssues)
    assertTrue(preview.nextFiscalYearWillBeCreated)
    assertEquals(LocalDate.of(2021, 1, 1), preview.nextFiscalYear.startDate)
    assertEquals(LocalDate.of(2021, 12, 31), preview.nextFiscalYear.endDate)
  }

  @Test
  void yearEndClosingAfterImportedResultTransferBooksResultOnce() {
    // Real shape from issue #119: the imported year already contains the source system's
    // year-end transfer booked as D 8999 / K 2099.
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    FiscalYear nextFiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2027', LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))
    seedClosingAccounts()
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 3, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 12, 31),
        'Årets resultat',
        [
            new VoucherLine(null, null, 0, null, '8999', null, 'Årets resultat', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2099', null, 'Årets resultat', 0.00G, 1000.00G)
        ]
    )

    def preview = closingService.previewClosing(fiscalYear.id)
    assertEquals(1000.00G, preview.netResult)
    assertEquals(0.00G, preview.expenseTotal)
    assertEquals(1000.00G, preview.transferredResult)
    assertEquals(0.00G, preview.remainingResult)
    assertTrue(preview.warnings.any { String warning -> warning.contains('förts över via konto 8999') })

    def result = closingService.closeFiscalYear(fiscalYear.id)

    assertNull(result.closingVoucher)
    databaseService.withSql { Sql sql ->
      // 2099 carries the result exactly once in the next year's opening balances...
      assertEquals(1000.00G, openingBalanceFor(sql, nextFiscalYear.id, '1510'))
      assertEquals(1000.00G, openingBalanceFor(sql, nextFiscalYear.id, '2099'))
      GroovyRowResult zeroedAccounts = sql.firstRow('''
          select count(*) as total
            from opening_balance ob
            join account a on a.id = ob.account_id
           where ob.fiscal_year_id = ?
             and a.account_number in ('3010', '8999')
      ''', [nextFiscalYear.id]) as GroovyRowResult
      assertEquals(0, ((Number) zeroedAccounts.get('total')).intValue())
      // ...and the opening balances balance: debit-normal classes sum to credit-normal classes.
      GroovyRowResult balanceCheck = sql.firstRow('''
          select coalesce(sum(case when a.account_class in ('ASSET', 'EXPENSE') then ob.amount else -ob.amount end), 0) as difference
            from opening_balance ob
            join account a on a.id = ob.account_id
           where ob.fiscal_year_id = ?
      ''', [nextFiscalYear.id]) as GroovyRowResult
      assertEquals(0.00G, new BigDecimal(balanceCheck.get('difference').toString()))
    }
  }

  @Test
  void yearEndClosingReusesExistingNextYearWithDifferentLength() {
    FiscalYear shortenedFirstYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2019', LocalDate.of(2019, 1, 17), LocalDate.of(2019, 12, 31))
    FiscalYear existingNextYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2020', LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
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
      ''', [shortenedFirstYear.id, accountId, 500.00G])
    }
    voucherService.createVoucher(
        shortenedFirstYear.id,
        'A',
        LocalDate.of(2019, 3, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G)
        ]
    )

    def result = closingService.closeFiscalYear(shortenedFirstYear.id)

    assertEquals(existingNextYear.id, result.nextFiscalYear.id)
    assertTrue(result.closedFiscalYear.closed)
    databaseService.withSql { Sql sql ->
      assertEquals(1000.00G, openingBalanceFor(sql, existingNextYear.id, '1510'))
      assertEquals(500.00G, openingBalanceFor(sql, existingNextYear.id, '1930'))
    }
  }

  @ParameterizedTest
  @CsvSource(['1000, 0', '-1000, 0', '1000, 400', '-1000, -400', '1000, 1200', '1000, 1000', '0, 0'])
  void closingPreservesReportsWithMisgroupedTransferAccount(BigDecimal earned, BigDecimal transferred) {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    // An explicit subgroup must never turn the transfer account into an operating expense.
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update account set account_subgroup = 'FINANCIAL_COSTS' where account_number = '8999'")
    }
    postSignedTransfer(year, '1930', '3010', earned)
    if (transferred != 0G) {
      postSignedTransfer(year, '8999', '2099', transferred)
    }
    ReportDataService reports = new ReportDataService(databaseService)
    assertEquals(earned, reports.generate(new ReportSelection(ReportType.INCOME_STATEMENT, year.id)).templateModel.result)

    if (earned != 0G) {
      assertYearExport(year, earned, transferred)
    }
    def result = closingService.closeFiscalYear(year.id)
    BigDecimal remaining = earned - transferred
    assertEquals(earned, result.netResult)
    assertEquals(transferred, result.transferredResult)
    assertEquals(remaining, result.remainingResult)
    if (remaining == 0G) {
      assertNull(result.closingVoucher)
    } else {
      Voucher closing = voucherService.findVoucher(result.closingVoucher.id)
      assertEquals(2, closing.lines.size())
      VoucherLine transfer = lineForAccount(closing, '8999')
      assertEquals(remaining, transfer.debitAmount - transfer.creditAmount)
      assertEquals(1, closingService.listClosingEntries(year.id).count { entry -> entry.entryType == ClosingService.RESULT_CLOSING })
    }
    assertEquals(earned, reports.generate(new ReportSelection(ReportType.INCOME_STATEMENT, year.id)).templateModel.result)
    def nextReport = reports.generate(new ReportSelection(ReportType.INCOME_STATEMENT, result.nextFiscalYear.id))
    List<IncomeStatementRow> rows = nextReport.templateModel.typedRows as List<IncomeStatementRow>
    assertEquals(earned == 0G ? null : earned, rows.find { IncomeStatementRow row -> row.rowType == IncomeStatementRowType.GRAND_TOTAL }.previousYearToDateAmount)
    databaseService.withSql { Sql sql ->
      assertEquals(0, ((Number) sql.firstRow('''
          select count(*) as total from opening_balance ob join account a on a.id = ob.account_id
           where ob.fiscal_year_id = ? and a.account_class in ('INCOME', 'EXPENSE')
      ''', [result.nextFiscalYear.id]).get('total')).intValue())
      if (earned != 0G) {
        assertEquals(earned, openingBalanceFor(sql, result.nextFiscalYear.id, '2099'))
      }
    }
    assertTrue(auditLogService.validateIntegrity(CompanyService.LEGACY_COMPANY_ID).isEmpty())
    if (earned != 0G) {
      assertYearExport(year, earned, earned)
    }
  }

  @ParameterizedTest
  @CsvSource(['8998, true, EXPENSE, DEBIT', '8999, false, EXPENSE, DEBIT',
      '8999, true, EQUITY, CREDIT', '8999, true, EXPENSE, CREDIT'])
  void previewBlocksInvalidTransferAccountBeforeWritingAnything(String number, boolean active, String accountClass, String side) {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    postSignedTransfer(year, '1930', '3010', 100.00G)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('''
          update account set account_number = ?, active = ?, account_class = ?, normal_balance_side = ?
           where account_number = '8999'
      ''', [number, active, accountClass, side])
    }
    assertTrue(closingService.previewClosing(year.id).blockingIssues.any { String issue -> issue.contains('8999') })
    assertThrows(IllegalStateException) { closingService.closeFiscalYear(year.id) }
    assertFalse(fiscalYearService.findById(year.id).closed)
    assertEquals(1, voucherService.listVouchers(CompanyService.LEGACY_COMPANY_ID, year.id).size())
  }

  @Test
  void closingSupportsSelectedEquityAccount() {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2019', 'Årets resultat, delägare 1', 'EQUITY', 'CREDIT')
    }
    postSignedTransfer(year, '1930', '3010', 100.00G)

    def result = closingService.closeFiscalYear(year.id, '2019')

    assertEquals(100.00G, lineForAccount(result.closingVoucher, '2019').creditAmount)
    assertNull(lineForAccount(result.closingVoucher, '2099'))
    databaseService.withSql { Sql sql ->
      assertEquals(100.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '2019'))
    }
  }

  @ParameterizedTest
  @CsvSource(['AKTIEBOLAG, 2099', 'ENSKILD_FIRMA, 2019', 'HANDELSBOLAG_KB, 2019', ', 2099'])
  void closingDefaultsToTheCompanyLegalForm(String legalForm, String expectedAccount) {
    CompanyService companies = new CompanyService(databaseService)
    Company company = companies.findById(CompanyService.LEGACY_COMPANY_ID)
    company.legalForm = LegalForm.fromDatabaseValue(legalForm)
    companies.save(company)
    FiscalYear year = fiscalYearService.createFiscalYear(company.id, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2019', 'Årets resultat, delägare 1', 'EQUITY', 'CREDIT')
    }
    postSignedTransfer(year, '1930', '3010', 100.00G)

    assertEquals(expectedAccount, closingService.defaultClosingAccount(year.id))
    assertEquals(expectedAccount, closingService.previewClosing(year.id).closingAccountNumber)
    String overrideAccount = expectedAccount == '2019' ? '2099' : '2019'
    assertEquals(overrideAccount, closingService.previewClosing(year.id, overrideAccount).closingAccountNumber)

    def result = closingService.closeFiscalYear(year.id)

    assertEquals(100.00G, lineForAccount(result.closingVoucher, expectedAccount).creditAmount)
    assertNull(lineForAccount(result.closingVoucher, overrideAccount))
    databaseService.withSql { Sql sql ->
      assertEquals(100.00G, openingBalanceFor(sql, result.nextFiscalYear.id, expectedAccount))
    }
  }

  @Test
  void missingLegalFormDefaultAccountBlocksClosingInsteadOfFallingBack() {
    CompanyService companies = new CompanyService(databaseService)
    Company company = companies.findById(CompanyService.LEGACY_COMPANY_ID)
    company.legalForm = LegalForm.ENSKILD_FIRMA
    companies.save(company)
    FiscalYear year = fiscalYearService.createFiscalYear(company.id, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    postSignedTransfer(year, '1930', '3010', 100.00G)

    def preview = closingService.previewClosing(year.id)

    assertEquals('2019', preview.closingAccountNumber)
    assertTrue(preview.blockingIssues.any { String issue -> issue.contains('2019') })
    assertThrows(IllegalStateException) { closingService.closeFiscalYear(year.id) }
    def result = closingService.closeFiscalYear(year.id, '2099')
    assertEquals(100.00G, lineForAccount(result.closingVoucher, '2099').creditAmount)
  }

  @ParameterizedTest
  @CsvSource(['1000, 300, 0', '1000, 300, 400', '1000, 300, 700', '1000, 300, 900',
      '300, 1000, -400', '300, 1000, -700'])
  void previewSeparatesOperatingResultFromPriorTransfers(BigDecimal income, BigDecimal expense, BigDecimal transferred) {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    postSignedTransfer(year, '1930', '3010', income)
    postSignedTransfer(year, '4010', '1930', expense)
    postSignedTransfer(year, '8999', '2099', transferred)

    def preview = closingService.previewClosing(year.id)

    assertEquals(income, preview.incomeTotal)
    assertEquals(expense, preview.expenseTotal)
    assertEquals(income - expense, preview.netResult)
    assertEquals(transferred, preview.transferredResult)
    assertEquals(income - expense - transferred, preview.remainingResult)
    assertTrue(preview.blockingIssues.isEmpty())
  }

  @Test
  void closingComputesResultFromEntryDirectionWhenClassificationContradictsNormalSide() {
    // Regression for PR #141: an income account with a debit normal side must not flip the
    // sign of the result; the transfer is derived from the debit/credit direction per entry.
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '3740', 'Övriga inkomster', 'INCOME', 'DEBIT')
    }
    postSignedTransfer(year, '1510', '3740', 1000.00G)

    def preview = closingService.previewClosing(year.id)

    assertEquals(1000.00G, preview.netResult)
    assertEquals(1000.00G, preview.remainingResult)
    assertTrue(preview.warnings.any { String warning -> warning.contains('3740') })

    def result = closingService.closeFiscalYear(year.id)

    assertEquals(1000.00G, lineForAccount(result.closingVoucher, '8999').debitAmount)
    assertEquals(0.00G, lineForAccount(result.closingVoucher, '8999').creditAmount)
    assertEquals(1000.00G, lineForAccount(result.closingVoucher, '2099').creditAmount)
    databaseService.withSql { Sql sql ->
      GroovyRowResult netRow = sql.firstRow('''
          select coalesce(sum(vl.debit_amount - vl.credit_amount), 0) as net
            from voucher v
            join voucher_line vl on vl.voucher_id = v.id
            join account a on a.id = vl.account_id
           where v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
             and a.account_class in ('INCOME', 'EXPENSE')
      ''', [year.id]) as GroovyRowResult
      assertEquals(0.00G, new BigDecimal(netRow.get('net').toString()))
      assertEquals(1000.00G, openingBalanceFor(sql, result.nextFiscalYear.id, '2099'))
    }
  }

  @Test
  void fullyReversedActivityDoesNotExportZeroResultBalances() {
    FiscalYear year = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    seedClosingAccounts()
    postSignedTransfer(year, '1930', '3010', 100.00G)
    postSignedTransfer(year, '1930', '3010', -100.00G)

    assertYearExport(year, 0.00G, 0.00G)
  }

  private void postSignedTransfer(FiscalYear year, String debitAccount, String creditAccount, BigDecimal amount) {
    if (amount == 0G) {
      return
    }
    voucherService.createVoucher(year.id, 'A', year.endDate, 'Testtransaktion', [
        new VoucherLine(null, null, 0, null, debitAccount, null, null, amount.max(0.00G), (-amount).max(0.00G)),
        new VoucherLine(null, null, 0, null, creditAccount, null, null, (-amount).max(0.00G), amount.max(0.00G))
    ])
  }

  private void assertYearExport(FiscalYear year, BigDecimal earned, BigDecimal transferred) {
    CompanyService companies = new CompanyService(databaseService)
    companies.save(new Company(CompanyService.LEGACY_COMPANY_ID, 'Test AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null))
    SieImportExportService sie = new SieImportExportService(databaseService, accountingPeriodService, voucherService,
        companies, reportIntegrityService, auditLogService, fiscalYearService)
    Path target = tempDir.resolve('closed.sie')
    sie.exportFiscalYear(year.id, target)
    String content = target.toFile().getText('IBM437')
    String sales = content.readLines().find { String line -> line.startsWith('#RES 0 3010 ') }
    String transfer = content.readLines().find { String line -> line.startsWith('#RES 0 8999 ') }
    if (earned == 0G) {
      assertNull(sales)
    } else {
      assertNotNull(sales)
      assertEquals(-earned, new BigDecimal(sales.split(/\s+/)[3]))
    }
    if (transferred == 0G) {
      assertNull(transfer)
    } else {
      assertNotNull(transfer)
      assertEquals(transferred, new BigDecimal(transfer.split(/\s+/)[3]))
    }
  }

  private void seedClosingAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '1930', 'Företagskonto', 'ASSET', 'DEBIT')
      insertAccount(sql, '2099', 'Årets resultat', 'EQUITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      insertAccount(sql, '4010', 'Varukostnad', 'EXPENSE', 'DEBIT')
      insertAccount(sql, '8999', 'Årets resultat', 'EXPENSE', 'DEBIT')
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

  private AccountService accountService() {
    new AccountService(databaseService)
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
