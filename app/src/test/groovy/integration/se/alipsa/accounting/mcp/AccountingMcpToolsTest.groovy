package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)

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
  void getTrialBalanceReturnsStructuredRows() {
    Map<String, Object> result = tools.callTool('get_trial_balance', [
        'company_id': (Object) 1L,
        'fiscal_year_id': (Object) fiscalYearId
    ])
    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('rows'))
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
  void listVatPeriodsReturnsPeriodsForFiscalYear() {
    Map<String, Object> result = tools.callTool('list_vat_periods', ['fiscal_year_id': (Object) fiscalYearId])
    assertTrue((boolean) result.get('ok'))
    List periods = (List) result.get('vat_periods')
    assertFalse(periods.isEmpty())
  }

  @Test
  void getVatReportReturnsReportStructureWithHash() {
    Map<String, Object> listResult = tools.callTool('list_vat_periods', ['fiscal_year_id': (Object) fiscalYearId])
    List periods = (List) listResult.get('vat_periods')
    Map firstPeriod = (Map) periods.first()
    long vatPeriodId = ((Number) firstPeriod.get('id')).longValue()

    Map<String, Object> result = tools.callTool('get_vat_report', ['vat_period_id': (Object) vatPeriodId])
    assertTrue((boolean) result.get('ok'))
    assertNotNull(result.get('output_vat_total'))
    assertNotNull(result.get('net_vat_to_pay'))
    assertNotNull(result.get('report_hash'), 'get_vat_report skall returnera report_hash')
  }
}
