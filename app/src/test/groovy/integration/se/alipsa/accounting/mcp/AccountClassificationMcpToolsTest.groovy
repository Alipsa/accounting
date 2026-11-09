package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
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

class AccountClassificationMcpToolsTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountService accountService
  private VoucherService voucherService
  private AccountingMcpTools tools
  private long fiscalYearId

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()

    auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService periodService = new AccountingPeriodService(databaseService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    voucherService = new VoucherService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, periodService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        new AttachmentService(databaseService, auditLogService),
        auditLogService
    )
    accountService = new AccountService(databaseService)

    tools = new AccountingMcpTools(
        companyService,
        fiscalYearService,
        accountService,
        voucherService,
        new VatService(databaseService, voucherService, auditLogService),
        new ClosingService(
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
            companyService,
            reportIntegrityService,
            auditLogService,
            fiscalYearService
        ),
        new AccountingInstructionService(databaseService)
    )

    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (?, '1930', 'Företagskonto', 'ASSET', 'DEBIT',
              true, false, current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID])
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, classification_note, created_at, updated_at
          ) values (?, '8314', 'Skattefria ränteintäkter', null, null,
              true, true, 'Kontot kunde inte klassificeras automatiskt från SIE-importen.',
              current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID])
    }

    fiscalYearId = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026',
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
    ).id
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
  void previewAccountUpdateReturnsCurrentProposedUsageAndToken() {
    postVoucherTouching8314()

    Map<String, Object> result = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT'
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    Map<String, Object> account = (Map<String, Object>) result.get('account')
    assertEquals('8314', account.get('account_number'))
    assertNull(account.get('account_class'))
    assertTrue((boolean) account.get('manual_review_required'))
    assertNotNull(account.get('classification_note'))
    Map<String, Object> proposed = (Map<String, Object>) result.get('proposed')
    assertEquals('INCOME', proposed.get('account_class'))
    assertEquals('CREDIT', proposed.get('normal_balance_side'))
    Map<String, Object> usage = (Map<String, Object>) result.get('usage')
    assertEquals(1, usage.get('voucher_count'))
    assertEquals(1, usage.get('voucher_line_count'))
    assertEquals(1, usage.get('fiscal_year_count'))
    assertNotNull(result.get('preview_token'))
  }

  @Test
  void updateAccountAppliesPreviewedChangeClearsManualReviewAndWritesAudit() {
    Map<String, Object> preview = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT'
    ])
    assertTrue((boolean) preview.get('ok'), "Expected ok but got: ${preview.get('errors')}")

    Map<String, Object> result = tools.callTool('update_account', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT',
        preview_token: (Object) preview.get('preview_token')
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    Map<String, Object> account = (Map<String, Object>) result.get('account')
    assertEquals('INCOME', account.get('account_class'))
    assertEquals('CREDIT', account.get('normal_balance_side'))
    assertFalse((boolean) account.get('manual_review_required'))
    assertNull(account.get('classification_note'))
    assertEquals('Skattefria ränteintäkter', account.get('account_name'))

    List<AuditLogEntry> entries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)
    AuditLogEntry entry = entries.find { AuditLogEntry auditEntry ->
      auditEntry.eventType == AuditLogService.UPDATE_ACCOUNT
    }
    assertNotNull(entry, 'Reclassification must write an UPDATE_ACCOUNT audit entry')
    assertTrue(entry.details.contains('accountNumber=8314'))
    assertTrue(entry.details.contains('accountClass=INCOME'))
    assertTrue(entry.details.contains('normalBalanceSide=CREDIT'))
  }

  @Test
  void updateAccountRejectsReusedPreviewToken() {
    Map<String, Object> preview = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT'
    ])
    Map<String, Object> first = tools.callTool('update_account', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT',
        preview_token: (Object) preview.get('preview_token')
    ])
    assertTrue((boolean) first.get('ok'))

    Map<String, Object> second = tools.callTool('update_account', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT',
        preview_token: (Object) preview.get('preview_token')
    ])

    assertFalse((boolean) second.get('ok'))
    List<String> errors = (List<String>) second.get('errors')
    assertTrue(errors.any { String error -> error.contains('redan använts') })
  }

  @Test
  void updateAccountRejectsMismatchedPreviewToken() {
    Map<String, Object> result = tools.callTool('update_account', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT',
        preview_token: (Object) 'not-the-right-token'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('stämmer inte') })
  }

  @Test
  void updateAccountRequiresPreviewToken() {
    Map<String, Object> result = tools.callTool('update_account', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('preview_token krävs') })
  }

  @Test
  void previewAccountUpdateRejectsUnknownAccount() {
    Map<String, Object> result = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '9999',
        account_class: (Object) 'INCOME',
        normal_balance_side: (Object) 'CREDIT'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('Okänt kontonummer') })
    assertNull(result.get('preview_token'))
  }

  @Test
  void previewAccountUpdateRejectsUnknownAccountClass() {
    Map<String, Object> result = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '8314',
        account_class: (Object) 'NOT_A_CLASS',
        normal_balance_side: (Object) 'CREDIT'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('NOT_A_CLASS') })
  }

  @Test
  void previewAccountUpdateFlagsVatCodeIncompatibleWithProposedClass() {
    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, vat_code, created_at, updated_at
          ) values (?, '2610', 'Utgående moms', 'LIABILITY', 'CREDIT',
              true, false, 'OUTPUT_25', current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID])
    }

    Map<String, Object> result = tools.callTool('preview_account_update', [
        company_id: (Object) 1L,
        account_number: (Object) '2610',
        account_class: (Object) 'ASSET',
        normal_balance_side: (Object) 'DEBIT'
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('momskod OUTPUT_25') })
    assertNull(result.get('preview_token'))
  }

  @Test
  void listAccountsManualReviewFilterReturnsOnlyUnclassifiedAccounts() {
    Map<String, Object> result = tools.callTool('list_accounts', [
        company_id: (Object) 1L,
        manual_review_required: (Object) true
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get('accounts')
    assertEquals(1, accounts.size())
    Map<String, Object> flagged = accounts.first()
    assertEquals('8314', flagged.get('account_number'))
    assertTrue((boolean) flagged.get('manual_review_required'))
    assertNotNull(flagged.get('classification_note'))
    assertNull(flagged.get('account_class'))
  }

  private void postVoucherTouching8314() {
    long accountId1930 = accountId('1930')
    long accountId8314 = accountId('8314')
    voucherService.createVoucher(
        fiscalYearId,
        'A',
        LocalDate.of(2026, 3, 1),
        'Skattefri ränta',
        [
            new VoucherLine(null, null, 0, accountId1930, '1930', 'Företagskonto', null, 100.00G, 0.00G),
            new VoucherLine(null, null, 1, accountId8314, '8314', 'Skattefria ränteintäkter', null, 0.00G, 100.00G)
        ]
    )
  }

  private long accountId(String accountNumber) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select id from account where company_id = ? and account_number = ?',
          [CompanyService.LEGACY_COMPANY_ID, accountNumber]
      ) as GroovyRowResult
      ((Number) row.get('id')).longValue()
    }
  }
}
