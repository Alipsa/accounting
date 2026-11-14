package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.LegalForm
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingInstructionService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class YearEndLegalFormMcpToolsTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private AccountingMcpTools tools
  private long fiscalYearId
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService audit = new AuditLogService(databaseService)
    AccountingPeriodService periods = new AccountingPeriodService(databaseService, audit)
    companyService = new CompanyService(databaseService)
    fiscalYearService = new FiscalYearService(databaseService, periods, audit)
    voucherService = new VoucherService(databaseService, audit)
    ReportIntegrityService integrity = new ReportIntegrityService(new AttachmentService(databaseService, audit), audit)
    tools = new AccountingMcpTools(companyService, fiscalYearService, new AccountService(databaseService), voucherService,
        new VatService(databaseService, voucherService, audit),
        new ClosingService(databaseService, periods, fiscalYearService, voucherService, integrity),
        new ReportDataService(databaseService),
        new SieImportExportService(databaseService, periods, voucherService, companyService, integrity, audit, fiscalYearService),
        new AccountingInstructionService(databaseService))
    fiscalYearId = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).id
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1930', 'Företagskonto', 'ASSET', 'DEBIT')
      insertAccount(sql, '2019', 'Årets resultat, delägare 1', 'EQUITY', 'CREDIT')
      insertAccount(sql, '2099', 'Årets resultat', 'EQUITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      insertAccount(sql, '4010', 'Varukostnad', 'EXPENSE', 'DEBIT')
      insertAccount(sql, '8999', 'Årets resultat', 'EXPENSE', 'DEBIT')
    }
  }

  @AfterEach
  void tearDown() {
    try {
      databaseService?.shutdown()
    } finally {
      if (previousHome == null) {
        System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
      } else {
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
      }
    }
  }

  @ParameterizedTest
  @CsvSource(['AKTIEBOLAG, 2099,', 'ENSKILD_FIRMA, 2019,', 'HANDELSBOLAG_KB, 2019,', ', 2099,',
      'ENSKILD_FIRMA, 2099, 2099', 'AKTIEBOLAG, 2019, 2019'])
  void yearEndUsesLegalFormDefaultAndExplicitOverrides(String legalForm, String expectedAccount, String overrideAccount) {
    Company company = companyService.findById(CompanyService.LEGACY_COMPANY_ID)
    company.legalForm = LegalForm.fromDatabaseValue(legalForm)
    companyService.save(company)
    voucherService.createVoucher(fiscalYearId, 'A', LocalDate.of(2026, 3, 1), 'Försäljning', [
        new VoucherLine(null, null, 0, null, '1930', null, null, 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 100.00G)
    ])
    Map<String, Object> args = [company_id: (Object) company.id, fiscal_year_id: (Object) fiscalYearId]

    if (overrideAccount != null) {
      args.put('closing_account', overrideAccount)
    }
    Map<String, Object> preview = tools.callTool('preview_year_end', args)

    assertEquals(expectedAccount, preview.get('closing_account'))
    assertEquals(true, preview.get('ready_to_close'))
    args.put('preview_token', preview.get('preview_token'))
    Map<String, Object> result = tools.callTool('close_fiscal_year', args)
    assertEquals(true, result.get('ok'), result.toString())
    long voucherId = ((Number) result.get('closing_voucher_id')).longValue()
    assertTrue(voucherService.findVoucher(voucherId).lines.any { VoucherLine line ->
      line.accountNumber == expectedAccount && line.creditAmount == 100.00G
    })
  }

  @Test
  void changingLegalFormInvalidatesYearEndPreviewWithADifferentDefault() {
    Company company = companyService.findById(CompanyService.LEGACY_COMPANY_ID)
    company.legalForm = LegalForm.ENSKILD_FIRMA
    companyService.save(company)
    Map<String, Object> args = [company_id: (Object) company.id, fiscal_year_id: (Object) fiscalYearId]
    Map<String, Object> preview = tools.callTool('preview_year_end', args)
    assertEquals('2019', preview.get('closing_account'))
    company.legalForm = LegalForm.AKTIEBOLAG
    companyService.save(company)
    args.put('preview_token', preview.get('preview_token'))

    Map<String, Object> result = tools.callTool('close_fiscal_year', args)

    assertEquals(false, result.get('ok'))
    assertTrue(result.get('errors').toString().contains('preview_token'))
    assertFalse(fiscalYearService.findById(fiscalYearId).closed)
  }

  @Test
  void yearEndReportsActualResultAndRemainingTransferSeparately() {
    voucherService.createVoucher(fiscalYearId, 'A', LocalDate.of(2026, 12, 31), 'Delvis överfört årsresultat', [
        new VoucherLine(null, null, 0, null, '1930', null, null, 1000.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, null, 0.00G, 1000.00G),
        new VoucherLine(null, null, 0, null, '4010', null, null, 300.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '1930', null, null, 0.00G, 300.00G),
        new VoucherLine(null, null, 0, null, '8999', null, null, 400.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '2099', null, null, 0.00G, 400.00G)
    ])
    Map<String, Object> args = [company_id: (Object) CompanyService.LEGACY_COMPANY_ID, fiscal_year_id: (Object) fiscalYearId]

    Map<String, Object> preview = tools.callTool('preview_year_end', args)

    assertEquals(1000.00G, preview.get('income_total'))
    assertEquals(300.00G, preview.get('expense_total'))
    assertEquals(700.00G, preview.get('net_result'))
    assertEquals(400.00G, preview.get('transferred_result'))
    assertEquals(300.00G, preview.get('remaining_result'))
    assertEquals(3, preview.get('result_accounts_with_balance_count'))
    assertFalse(preview.containsKey('result_account_count'))
    args.put('preview_token', preview.get('preview_token'))
    Map<String, Object> result = tools.callTool('close_fiscal_year', args)
    assertEquals(true, result.get('ok'), result.toString())
    assertEquals(700.00G, result.get('net_result'))
    assertEquals(400.00G, result.get('transferred_result'))
    assertEquals(300.00G, result.get('remaining_result'))
    assertEquals(3, result.get('result_accounts_with_balance_count'))
    assertFalse(result.containsKey('result_account_count'))
  }

  private static void insertAccount(Sql sql, String number, String name, String accountClass, String side) {
    sql.executeInsert('''
        insert into account (company_id, account_number, account_name, account_class, normal_balance_side,
            active, manual_review_required, created_at, updated_at)
        values (?, ?, ?, ?, ?, true, false, current_timestamp, current_timestamp)
    ''', [CompanyService.LEGACY_COMPANY_ID, number, name, accountClass, side])
  }
}
