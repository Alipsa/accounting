package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class AccountingMcpToolsTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private AccountingMcpTools tools
  private long fiscalYearId

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()

    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService periodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, periodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        new se.alipsa.accounting.service.AttachmentService(databaseService, auditLogService),
        auditLogService
    )

    tools = new AccountingMcpTools(
        new CompanyService(databaseService),
        fiscalYearService,
        new AccountService(databaseService),
        voucherService,
        new VatService(databaseService, voucherService, auditLogService),
        new se.alipsa.accounting.service.ClosingService(
            databaseService,
            periodService,
            fiscalYearService,
            voucherService,
            reportIntegrityService
        ),
        new ReportDataService(databaseService),
        new SieImportExportService(
            databaseService,
            periodService,
            voucherService,
            new CompanyService(databaseService),
            reportIntegrityService,
            auditLogService,
            fiscalYearService
        )
    )

    databaseService.withTransaction { groovy.sql.Sql sql ->
      sql.executeInsert("""
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (?, '1930', 'Företagskonto', 'ASSET', 'DEBIT',
              true, false, current_timestamp, current_timestamp)
      """, [CompanyService.LEGACY_COMPANY_ID])

      sql.executeInsert("""
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (?, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT',
              true, false, current_timestamp, current_timestamp)
      """, [CompanyService.LEGACY_COMPANY_ID])
    }

    se.alipsa.accounting.domain.FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
    )
    fiscalYearId = year.id
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
  void getCompanyInfoReturnsLegacyCompany() {
    Map<String, Object> result = tools.callTool('get_company_info', ['company_id': (Object) 1L])
    assertTrue((boolean) result.get('ok'))
    Map company = (Map) result.get('company')
    assertEquals(1L, ((Number) company.get('id')).longValue())
  }

  @Test
  void getCompanyInfoUnknownIdReturnsError() {
    Map<String, Object> result = tools.callTool('get_company_info', ['company_id': (Object) 9999L])
    assertFalse((boolean) result.get('ok'))
    assertNotNull(result.get('error'))
  }

  @Test
  void listFiscalYearsReturnsCreatedYear() {
    Map<String, Object> result = tools.callTool('list_fiscal_years', ['company_id': (Object) 1L])
    assertTrue((boolean) result.get('ok'))
    List years = (List) result.get('fiscal_years')
    assertEquals(1, years.size())
    Map year = (Map) years.first()
    assertEquals('2026', year.get('name'))
    assertFalse((boolean) year.get('closed'))
  }

  @Test
  void listAccountsReturnsActiveAccounts() {
    Map<String, Object> result = tools.callTool('list_accounts', ['company_id': (Object) 1L])
    assertTrue((boolean) result.get('ok'))
    List accounts = (List) result.get('accounts')
    assertEquals(2, accounts.size())
    assertTrue(((List<Map>) accounts).any { Map a -> a.get('account_number') == '1930' })
  }

  @Test
  void listVouchersReturnsEmptyWhenNonePosted() {
    Map<String, Object> result = tools.callTool('list_vouchers', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertTrue((boolean) result.get('ok'))
    List vouchers = (List) result.get('vouchers')
    assertTrue(vouchers.isEmpty())
  }

  @Test
  void listVouchersRejectsCrossCompanyFiscalYear() {
    Map<String, Object> result = tools.callTool('list_vouchers', [
        'company_id': (Object) 9999L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertFalse((boolean) result.get('ok'))
    assertTrue(((String) result.get('error')).contains('does not belong to company'))
  }

  @Test
  void getTrialBalanceReturnsStructuredRows() {
    Map<String, Object> result = tools.callTool('get_trial_balance', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('rows'))
  }

  @Test
  void getTrialBalanceRejectsCrossCompanyFiscalYear() {
    Map<String, Object> result = tools.callTool('get_trial_balance', [
        'company_id': (Object) 9999L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertFalse((boolean) result.get('ok'))
    assertTrue(((String) result.get('error')).contains('does not belong to company'))
  }

  @Test
  void getGeneralLedgerReturnsStructuredRows() {
    Map<String, Object> result = tools.callTool('get_general_ledger', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('rows'))
  }

  @Test
  void getGeneralLedgerRejectsCrossCompanyFiscalYear() {
    Map<String, Object> result = tools.callTool('get_general_ledger', [
        'company_id': (Object) 9999L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertFalse((boolean) result.get('ok'))
    assertTrue(((String) result.get('error')).contains('does not belong to company'))
  }

  @Test
  void getGeneralLedgerClampsNegativeLimit() {
    Map<String, Object> result = tools.callTool('get_general_ledger', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId,
        'limit': (Object) -5
    ])
    assertTrue((boolean) result.get('ok'))
    List rows = (List) result.get('rows')
    assertTrue(rows.size() <= 1)
  }

  @Test
  void listVatPeriodsReturnsPeriodsForFiscalYear() {
    Map<String, Object> result = tools.callTool('list_vat_periods', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertTrue((boolean) result.get('ok'))
    List periods = (List) result.get('vat_periods')
    assertFalse(periods.isEmpty())
  }

  @Test
  void listVatPeriodsRejectsCrossCompanyFiscalYear() {
    Map<String, Object> result = tools.callTool('list_vat_periods', [
        'company_id': (Object) 9999L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertFalse((boolean) result.get('ok'))
    assertTrue(((String) result.get('error')).contains('does not belong to company'))
  }

  @Test
  void getVatReportReturnsReportStructureWithHash() {
    Map<String, Object> listResult = tools.callTool('list_vat_periods', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    List periods = (List) listResult.get('vat_periods')
    Map firstPeriod = (Map) periods.first()
    long vatPeriodId = ((Number) firstPeriod.get('id')).longValue()

    Map<String, Object> result = tools.callTool('get_vat_report', [
        'company_id': (Object) 1L,
        'vat_period_id': (Object) vatPeriodId
    ])
    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('output_vat_total'))
    assertNotNull(result.get('net_vat_to_pay'))
    assertNotNull(result.get('report_hash'), 'get_vat_report skall returnera report_hash')
  }

  @Test
  void getVatReportRejectsCrossCompanyVatPeriod() {
    Map<String, Object> listResult = tools.callTool('list_vat_periods', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    List periods = (List) listResult.get('vat_periods')
    Map firstPeriod = (Map) periods.first()
    long vatPeriodId = ((Number) firstPeriod.get('id')).longValue()

    Map<String, Object> result = tools.callTool('get_vat_report', [
        'company_id': (Object) 9999L,
        'vat_period_id': (Object) vatPeriodId
    ])
    assertFalse((boolean) result.get('ok'))
    assertTrue(((String) result.get('error')).contains('does not belong to company'))
  }

  @Test
  void getVatReportHashChangesWhenContentChanges() {
    Map firstPeriod = firstVatPeriod()
    long vatPeriodId = ((Number) firstPeriod.get('id')).longValue()
    LocalDate periodStart = LocalDate.parse((String) firstPeriod.get('start_date'))

    Map<String, Object> before = tools.callTool('get_vat_report', [
        'company_id': (Object) 1L,
        'vat_period_id': (Object) vatPeriodId
    ])
    assertTrue((boolean) before.get('ok'))
    String hashBefore = (String) before.get('report_hash')

    // Insert VAT-coded accounts and post a voucher that affects VAT
    long salesAccountId
    long vatAccountId
    databaseService.withTransaction { groovy.sql.Sql sql ->
      List<List<Object>> keys = sql.executeInsert("""
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, vat_code, created_at, updated_at
          ) values (?, '3000', 'Försäljning varor', 'INCOME', 'CREDIT',
              true, false, 'OUTPUT_25', current_timestamp, current_timestamp)
      """, [CompanyService.LEGACY_COMPANY_ID])
      salesAccountId = ((Number) keys.first().first()).longValue()

      List<List<Object>> keys2 = sql.executeInsert("""
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, vat_code, created_at, updated_at
          ) values (?, '2610', 'Utgående moms', 'LIABILITY', 'CREDIT',
              true, false, 'OUTPUT_25', current_timestamp, current_timestamp)
      """, [CompanyService.LEGACY_COMPANY_ID])
      vatAccountId = ((Number) keys2.first().first()).longValue()
    }

    voucherService.createVoucher(
        fiscalYearId,
        'A',
        periodStart.plusDays(5),
        'Försäljning med moms',
        [
            new VoucherLine(null, null, 0, salesAccountId, '3000', 'Försäljning varor', null, 0.00G, 1000.00G),
            new VoucherLine(null, null, 1, vatAccountId, '2610', 'Utgående moms', null, 0.00G, 250.00G),
            new VoucherLine(null, null, 2, null, '1930', 'Företagskonto', null, 1250.00G, 0.00G)
        ]
    )

    Map<String, Object> after = tools.callTool('get_vat_report', [
        'company_id': (Object) 1L,
        'vat_period_id': (Object) vatPeriodId
    ])
    assertTrue((boolean) after.get('ok'))
    String hashAfter = (String) after.get('report_hash')

    assertNotEquals(hashBefore, hashAfter, 'VAT report hash skall ändras när bokföring påverkar momsperioden')
  }

  @Test
  void bookVatTransferWithoutReportHashIsRejected() {
    long vatPeriodId = firstVatPeriodId()

    Map<String, Object> result = tools.callTool('book_vat_transfer', [
        company_id: (Object) 1L,
        vat_period_id: (Object) vatPeriodId
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('report_hash') })
  }

  @Test
  void bookVatTransferWithTamperedReportHashIsRejected() {
    postVatSaleInFirstPeriod()
    long vatPeriodId = firstVatPeriodId()

    Map<String, Object> result = tools.callTool('book_vat_transfer', [
        company_id: (Object) 1L,
        vat_period_id: (Object) vatPeriodId,
        report_hash: (Object) 'wrong-hash'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('report_hash') })
  }

  @Test
  void bookVatTransferWithReportHashBooksVoucherAndLocksPeriod() {
    postVatSaleInFirstPeriod()
    long vatPeriodId = firstVatPeriodId()
    Map<String, Object> report = tools.callTool('get_vat_report', [
        company_id: (Object) 1L,
        vat_period_id: (Object) vatPeriodId
    ])
    assertTrue((boolean) report.get('ok'))

    Map<String, Object> result = tools.callTool('book_vat_transfer', [
        company_id: (Object) 1L,
        vat_period_id: (Object) vatPeriodId,
        report_hash: (Object) report.get('report_hash')
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    assertNotNull(result.get('voucher_id'))
    assertTrue(result.get('voucher_number').toString().startsWith('M'))
    assertEquals('LOCKED', ((Map) firstVatPeriod()).get('status'))
  }

  @Test
  void previewYearEndReturnsFiscalYearInfoAndToken() {
    insertClosingAccount()

    Map<String, Object> result = tools.callTool('preview_year_end', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('net_result'))
    assertNotNull(result.get('blocking_issues'))
    assertNotNull(result.get('warnings'))
    assertNotNull(result.get('preview_token'), 'preview_year_end skall returnera preview_token')
  }

  @Test
  void previewYearEndOmitsTokenWhenBlockingIssuesExist() {
    Map<String, Object> result = tools.callTool('preview_year_end', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertTrue((boolean) result.get('ok'))
    assertFalse((boolean) result.get('ready_to_close'))
    assertNull(result.get('preview_token'), 'Blockerad årsstängning skall inte ha en preview_token')
  }

  @Test
  void closeFiscalYearWithoutPreviewTokenIsRejected() {
    insertClosingAccount()

    Map<String, Object> result = tools.callTool('close_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('preview_token') })
  }

  @Test
  void closeFiscalYearWithTamperedPreviewTokenIsRejected() {
    insertClosingAccount()

    Map<String, Object> result = tools.callTool('close_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        preview_token: (Object) 'wrong-token'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('preview_token') })
  }

  @Test
  void closeFiscalYearChecksOwnershipBeforePreviewToken() {
    Map<String, Object> result = tools.callTool('close_fiscal_year', [
        company_id: (Object) 9999L,
        fiscal_year_id: (Object) fiscalYearId,
        preview_token: (Object) 'wrong-token'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('does not belong to company') })
  }

  @Test
  void closeFiscalYearUnknownFiscalYearReturnsGracefulError() {
    Map<String, Object> result = tools.callTool('close_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) 999999L,
        preview_token: (Object) 'wrong-token'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertFalse(errors.isEmpty())
  }

  @Test
  void closeFiscalYearWithPreviewTokenClosesYear() {
    insertClosingAccount()
    Map<String, Object> preview = tools.callTool('preview_year_end', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])
    assertTrue((boolean) preview.get('ok'), "Expected ok but got: ${preview.get('errors')}")
    assertTrue((boolean) preview.get('ready_to_close'), "Expected ready but got blockers: ${preview.get('blocking_issues')}")

    Map<String, Object> result = tools.callTool('close_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        preview_token: (Object) preview.get('preview_token')
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    assertEquals(fiscalYearId, ((Number) result.get('closed_fiscal_year_id')).longValue())
    assertNotNull(result.get('next_fiscal_year_id'))
    assertTrue(fiscalYearService.findById(fiscalYearId).closed)
  }

  @Test
  void previewVoucherValidatesBalance() {
    Map<String, Object> result = tools.callTool('preview_voucher', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        series_code: (Object) 'A',
        accounting_date: (Object) '2026-03-01',
        description: (Object) 'Test',
        lines: (Object) [
            [account_number: '1930', debit: 1000.00G, credit: 0.00G],
            [account_number: '2440', debit: 0.00G, credit: 500.00G]
        ]
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('obalanserad') })
    assertNull(result.get('preview_token'), 'Ogiltigt förslag skall inte ha en preview_token')
  }

  @Test
  void previewVoucherResolvesAccountsAndReturnsTokenWhenValid() {
    Map<String, Object> result = tools.callTool('preview_voucher', balancedVoucherArgs('Balanserad verifikation', 1000.00G))

    assertTrue((boolean) result.get('ok'))
    assertTrue(((List) result.get('errors')).isEmpty())
    assertEquals(2, ((List) result.get('lines')).size())
    assertEquals('1930', ((Map) ((List) result.get('lines')).first()).get('account_number'))
    assertNotNull(result.get('preview_token'), 'Giltigt förslag skall ha en preview_token')
  }

  @Test
  void previewVoucherRejectsDateOutsideFiscalYear() {
    Map<String, Object> result = tools.callTool('preview_voucher', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        series_code: (Object) 'A',
        accounting_date: (Object) '2025-12-31',
        description: (Object) 'Fel datum',
        lines: (Object) [
            [account_number: '1930', debit: 100.00G, credit: 0.00G],
            [account_number: '2440', debit: 0.00G, credit: 100.00G]
        ]
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('utanför') })
  }

  @Test
  void postVoucherCreatesVoucherAndReturnsId() {
    Map<String, Object> result = previewAndPost(balancedVoucherArgs('Kontantinsättning', 500.00G))

    assertTrue((boolean) result.get('ok'), "Expected ok but got errors: ${result.get('errors')}")
    assertNotNull(result.get('voucher_id'))
    assertEquals('2026-03-01', result.get('accounting_date'))
  }

  @Test
  void postVoucherWithoutPreviewTokenIsRejected() {
    Map<String, Object> result = tools.callTool('post_voucher', balancedVoucherArgs('Ingen token', 500.00G))

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('preview_token') })
  }

  @Test
  void postVoucherWithTamperedPayloadIsRejected() {
    Map<String, Object> validArgs = balancedVoucherArgs('Ska postas', 500.00G)
    Map<String, Object> preview = tools.callTool('preview_voucher', validArgs)
    String token = (String) preview.get('preview_token')
    Map<String, Object> tampered = new LinkedHashMap<>(validArgs)
    tampered.put('description', (Object) 'Ändrad beskrivning')
    tampered.put('preview_token', (Object) token)

    Map<String, Object> result = tools.callTool('post_voucher', tampered)

    assertFalse((boolean) result.get('ok'))
  }

  @Test
  void postVoucherAcceptsEquivalentNumericRepresentations() {
    Map<String, Object> previewArgs = [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        series_code: (Object) 'A',
        accounting_date: (Object) '2026-03-01',
        description: (Object) 'Olika numerisk form',
        lines: (Object) [
            [account_number: '1930', debit: 500, credit: 0],
            [account_number: '2440', debit: 0, credit: 500]
        ]
    ]
    Map<String, Object> preview = tools.callTool('preview_voucher', previewArgs)
    assertTrue((boolean) preview.get('ok'))
    Map<String, Object> postArgs = balancedVoucherArgs('Olika numerisk form', 500.00G)
    postArgs.put('preview_token', (Object) preview.get('preview_token'))

    Map<String, Object> result = tools.callTool('post_voucher', postArgs)

    assertTrue((boolean) result.get('ok'), "Expected ok but got errors: ${result.get('errors')}")
    assertNotNull(result.get('voucher_id'))
  }

  @Test
  void postVoucherRevalidatesClosedFiscalYearAfterPreview() {
    Map<String, Object> validArgs = balancedVoucherArgs('Stängt år efter preview', 500.00G)
    Map<String, Object> preview = tools.callTool('preview_voucher', validArgs)
    assertTrue((boolean) preview.get('ok'))
    fiscalYearService.closeFiscalYear(fiscalYearId)
    Map<String, Object> argsWithToken = new LinkedHashMap<>(validArgs)
    argsWithToken.put('preview_token', (Object) preview.get('preview_token'))

    Map<String, Object> result = tools.callTool('post_voucher', argsWithToken)

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('stängt') })
  }

  @Test
  void postVoucherWithoutTokenIsRejectedRegardlessOfBalance() {
    Map<String, Object> result = tools.callTool('post_voucher', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        series_code: (Object) 'A',
        accounting_date: (Object) '2026-03-01',
        description: (Object) 'Obalanserad',
        lines: (Object) [
            [account_number: '1930', debit: 500.00G, credit: 0.00G],
            [account_number: '2440', debit: 0.00G, credit: 200.00G]
        ]
    ])

    assertFalse((boolean) result.get('ok'))
  }

  @Test
  void createCorrectionVoucherCreatesReversingVoucher() {
    Map<String, Object> posted = previewAndPost(balancedVoucherArgs('Original att korrigera', 300.00G))
    assertTrue((boolean) posted.get('ok'))
    long originalId = ((Number) posted.get('voucher_id')).longValue()

    Map<String, Object> correction = tools.callTool('create_correction_voucher', [
        original_voucher_id: (Object) originalId,
        description: (Object) 'Korrigering av felaktig post'
    ])

    assertTrue((boolean) correction.get('ok'), "Expected ok but got: ${correction.get('errors')}")
    assertNotNull(correction.get('voucher_id'))
    assertNotEquals(originalId, ((Number) correction.get('voucher_id')).longValue())
  }

  @Test
  void previewSieImportReturnsTokenForImportableFile() {
    Path sieFile = writeSimpleSie(tempDir.resolve('mcp-preview.sie'), 2027, '1510', 'Kundfordringar')

    Map<String, Object> result = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors') ?: result.get('blocking_issues')}")
    assertEquals(false, result.get('fiscal_year_exists'))
    assertEquals('Testbolaget AB', result.get('company_name_in_file'))
    assertEquals(1, result.get('account_count'))
    assertNotNull(result.get('import_token'))
  }

  @Test
  void previewSieImportReportsBlockingIssuesForExistingBookedYear() {
    previewAndPost(balancedVoucherArgs('Bokning före import', 100.00G))
    Path sieFile = writeSimpleSie(tempDir.resolve('blocked-existing.sie'), 2026, '1510', 'Kundfordringar')

    Map<String, Object> result = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])

    assertFalse((boolean) result.get('ok'))
    assertNull(result.get('import_token'))
    assertTrue(((List<String>) result.get('blocking_issues')).any { String issue -> issue.contains('innehåller redan') })
  }

  @Test
  void previewSieImportReplacementReturnsPurgeSummary() {
    previewAndPost(balancedVoucherArgs('Bokning att ersätta', 100.00G))
    Path sieFile = writeSimpleSie(tempDir.resolve('replace-preview.sie'), 2026, '1510', 'Kundfordringar')

    Map<String, Object> result = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        replace_existing: (Object) true
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('blocking_issues')}")
    Map purgeSummary = (Map) result.get('purge_summary')
    assertNotNull(purgeSummary)
    assertTrue(((Number) purgeSummary.get('voucher_count')).intValue() > 0)
    assertNotNull(result.get('import_token'))
  }

  @Test
  void importSieRejectsMissingAndTamperedTokens() {
    Path sieFile = writeSimpleSie(tempDir.resolve('missing-token.sie'), 2027, '1510', 'Kundfordringar')

    Map<String, Object> missing = tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])
    Map<String, Object> tampered = tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        import_token: (Object) 'wrong-token'
    ])

    assertFalse((boolean) missing.get('ok'))
    assertTrue(((List<String>) missing.get('errors')).any { String error -> error.contains('import_token') })
    assertFalse((boolean) tampered.get('ok'))
    assertTrue(((List<String>) tampered.get('errors')).any { String error -> error.contains('Ogiltig import_token') })
  }

  @Test
  void importSieWithTokenImportsAndDuplicateSecondImportReturnsDuplicate() {
    Path sieFile = writeSimpleSie(tempDir.resolve('mcp-import.sie'), 2027, '1510', 'Kundfordringar')
    Map<String, Object> preview = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])
    assertTrue((boolean) preview.get('ok'), "Preview failed: ${preview}")

    Map<String, Object> first = tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        import_token: (Object) preview.get('import_token')
    ])
    Map<String, Object> duplicatePreview = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])
    Map<String, Object> second = tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        import_token: (Object) duplicatePreview.get('import_token')
    ])

    assertTrue((boolean) first.get('ok'), "Expected ok but got: ${first.get('errors')}")
    assertNotNull(first.get('fiscal_year_id'))
    assertTrue((boolean) duplicatePreview.get('is_duplicate'))
    assertTrue((boolean) second.get('ok'), "Expected duplicate ok but got: ${second.get('errors')}")
    assertTrue((boolean) second.get('duplicate'))
  }

  @Test
  void importSieReplacementRejectsWhenPurgeSummaryChangesAfterPreview() {
    Path sieFile = writeSimpleSie(tempDir.resolve('replace-changed.sie'), 2026, '1510', 'Kundfordringar')
    Map<String, Object> preview = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        replace_existing: (Object) true
    ])
    assertTrue((boolean) preview.get('ok'), "Preview failed: ${preview}")
    previewAndPost(balancedVoucherArgs('Ny bokning efter preview', 25.00G))

    Map<String, Object> result = tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        replace_existing: (Object) true,
        import_token: (Object) preview.get('import_token')
    ])

    assertFalse((boolean) result.get('ok'))
    assertTrue(((List<String>) result.get('errors')).any { String error -> error.contains('förändrats') })
  }

  @Test
  void exportSieCreatesDefaultTimestampedFileAndProtectsExistingOutput() {
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))
    Path explicitPath = tempDir.resolve('export.sie')

    Map<String, Object> defaultExport = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId
    ])
    Map<String, Object> explicitExport = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId,
        output_path: (Object) explicitPath.toString()
    ])
    Map<String, Object> blocked = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId,
        output_path: (Object) explicitPath.toString()
    ])
    Map<String, Object> overwrite = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId,
        output_path: (Object) explicitPath.toString(),
        overwrite: (Object) true
    ])

    assertTrue((boolean) defaultExport.get('ok'), "Default export failed: ${defaultExport.get('errors')}")
    Path defaultPath = Path.of((String) defaultExport.get('file_path'))
    assertTrue(Files.exists(defaultPath))
    assertTrue(defaultPath.fileName.toString() ==~ /AlipsaAccounting-.+-\d{12}\.sie/)
    assertTrue((boolean) explicitExport.get('ok'), "Explicit export failed: ${explicitExport.get('errors')}")
    assertFalse((boolean) blocked.get('ok'))
    assertTrue((boolean) blocked.get('file_exists'))
    assertTrue((boolean) overwrite.get('ok'), "Overwrite failed: ${overwrite.get('errors')}")
  }

  @Test
  void listImportJobsClampsLimitToFifty() {
    Path sieFile = writeSimpleSie(tempDir.resolve('job-list.sie'), 2027, '1510', 'Kundfordringar')
    Map<String, Object> preview = tools.callTool('preview_sie_import', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString()
    ])
    tools.callTool('import_sie', [
        company_id: (Object) 1L,
        file_path: (Object) sieFile.toString(),
        import_token: (Object) preview.get('import_token')
    ])

    Map<String, Object> result = tools.callTool('list_import_jobs', [
        company_id: (Object) 1L,
        limit: (Object) 100
    ])

    assertTrue((boolean) result.get('ok'))
    assertTrue(((List) result.get('import_jobs')).size() <= 50)
    assertFalse(((List) result.get('import_jobs')).isEmpty())
  }

  private Map<String, Object> previewAndPost(Map<String, Object> postArgs) {
    Map<String, Object> previewResult = tools.callTool('preview_voucher', postArgs)
    assertTrue((boolean) previewResult.get('ok'), "Preview failed: ${previewResult.get('errors')}")
    String token = (String) previewResult.get('preview_token')
    Map<String, Object> argsWithToken = new LinkedHashMap<>(postArgs)
    argsWithToken.put('preview_token', (Object) token)
    tools.callTool('post_voucher', argsWithToken)
  }

  private Map firstVatPeriod() {
    Map<String, Object> listResult = tools.callTool('list_vat_periods', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])
    List periods = (List) listResult.get('vat_periods')
    (Map) periods.first()
  }

  private long firstVatPeriodId() {
    ((Number) firstVatPeriod().get('id')).longValue()
  }

  private void postVatSaleInFirstPeriod() {
    Map firstPeriod = firstVatPeriod()
    LocalDate periodStart = LocalDate.parse((String) firstPeriod.get('start_date'))
    long salesAccountId = insertAccount('3000', 'Försäljning varor', 'INCOME', 'CREDIT', 'OUTPUT_25')
    long vatAccountId = insertAccount('2610', 'Utgående moms', 'LIABILITY', 'CREDIT', 'OUTPUT_25')
    long bankAccountId = accountId('1930')
    insertAccount('2650', 'Momsredovisningskonto', 'LIABILITY', 'CREDIT', null)

    voucherService.createVoucher(
        fiscalYearId,
        'A',
        periodStart.plusDays(5),
        'Försäljning med moms',
        [
            new VoucherLine(null, null, 0, salesAccountId, '3000', 'Försäljning varor', null, 0.00G, 1000.00G),
            new VoucherLine(null, null, 1, vatAccountId, '2610', 'Utgående moms', null, 0.00G, 250.00G),
            new VoucherLine(null, null, 2, bankAccountId, '1930', 'Företagskonto', null, 1250.00G, 0.00G)
        ]
    )
  }

  private void insertClosingAccount() {
    insertAccount('2099', 'Årets resultat', 'EQUITY', 'CREDIT', null)
  }

  private long insertAccount(
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide,
      String vatCode
  ) {
    databaseService.withTransaction { groovy.sql.Sql sql ->
      List<List<Object>> keys = sql.executeInsert("""
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, vat_code, created_at, updated_at
          ) values (?, ?, ?, ?, ?, true, false, ?, current_timestamp, current_timestamp)
      """, [CompanyService.LEGACY_COMPANY_ID, accountNumber, accountName, accountClass, normalBalanceSide, vatCode])
      ((Number) keys.first().first()).longValue()
    }
  }

  private long accountId(String accountNumber) {
    databaseService.withSql { groovy.sql.Sql sql ->
      Map row = sql.firstRow(
          'select id from account where company_id = ? and account_number = ?',
          [CompanyService.LEGACY_COMPANY_ID, accountNumber]
      ) as Map
      ((Number) row.id).longValue()
    }
  }

  private Map<String, Object> balancedVoucherArgs(String description, BigDecimal amount) {
    [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        series_code: (Object) 'A',
        accounting_date: (Object) '2026-03-01',
        description: (Object) description,
        lines: (Object) [
            [account_number: '1930', debit: amount, credit: 0.00G],
            [account_number: '2440', debit: 0.00G, credit: amount]
        ]
    ]
  }

  private Path writeSimpleSie(Path filePath, int year, String accountNumber, String accountName) {
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN ${year}0101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 ${year}0101 ${year}1231
#KONTO ${accountNumber} "${accountName}"
#IB 0 ${accountNumber} 100.00
"""
    filePath
  }
}
