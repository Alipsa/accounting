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
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

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
            new se.alipsa.accounting.service.ReportIntegrityService()
        ),
        new ReportDataService(databaseService)
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
    Map<String, Object> listResult = tools.callTool('list_vat_periods', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    List periods = (List) listResult.get('vat_periods')
    Map firstPeriod = (Map) periods.first()
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

  private Map<String, Object> previewAndPost(Map<String, Object> postArgs) {
    Map<String, Object> previewResult = tools.callTool('preview_voucher', postArgs)
    assertTrue((boolean) previewResult.get('ok'), "Preview failed: ${previewResult.get('errors')}")
    String token = (String) previewResult.get('preview_token')
    Map<String, Object> argsWithToken = new LinkedHashMap<>(postArgs)
    argsWithToken.put('preview_token', (Object) token)
    tools.callTool('post_voucher', argsWithToken)
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
}
