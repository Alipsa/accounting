package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.OpeningBalance
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class ChartOfAccountsImportServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private FiscalYearService fiscalYearService
  private AccountService accountService
  private ChartOfAccountsImportService importService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService)
    accountService = new AccountService(databaseService)
    importService = new ChartOfAccountsImportService(databaseService)
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void basWorkbookCanBeImportedAndClassified() {
    ChartOfAccountsImportService.ImportSummary summary = importService.importFromExcel(
        workbookPath()
    )

    assertTrue(summary.importedCount > 1000)
    assertTrue(summary.createdCount > 1000)
    assertTrue(summary.manualReviewCount > 0)

    Account assetAccount = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, '1010')
    Account equityAccount = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, '2010')
    Account incomeAccount = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, '3000')
    Account manualReviewAccount = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, '8999')

    assertNotNull(assetAccount)
    assertEquals('ASSET', assetAccount.accountClass)
    assertEquals('DEBIT', assetAccount.normalBalanceSide)

    assertNotNull(equityAccount)
    assertEquals('EQUITY', equityAccount.accountClass)
    assertEquals('CREDIT', equityAccount.normalBalanceSide)

    assertNotNull(incomeAccount)
    assertEquals('INCOME', incomeAccount.accountClass)
    assertFalse(incomeAccount.manualReviewRequired)

    assertNotNull(manualReviewAccount)
    assertTrue(manualReviewAccount.manualReviewRequired)
    assertTrue(manualReviewAccount.accountClass == null)
  }

  @Test
  void importedVatAccountsHaveCorrectClassAndCode() {
    importService.importFromExcel(workbookPath())

    assertImportedVatAccount('2610', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25)
    assertImportedVatAccount('2611', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25)
    assertImportedVatAccount('2614', 'LIABILITY', 'CREDIT', VatCode.REVERSE_CHARGE_EU_25)
    assertImportedVatAccount('2620', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_12)
    assertImportedVatAccount('2621', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_12)
    assertImportedVatAccount('2630', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_6)
    assertImportedVatAccount('2631', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_6)
    assertImportedVatAccount('2640', 'ASSET', 'DEBIT', VatCode.INPUT_25)
    assertImportedVatAccount('2641', 'ASSET', 'DEBIT', VatCode.INPUT_25)
    assertImportedVatAccount('2642', 'ASSET', 'DEBIT', VatCode.INPUT_12)
    assertImportedVatAccount('2645', 'ASSET', 'DEBIT', VatCode.EU_ACQUISITION_GOODS)
  }

  @Test
  void resolvesVatCodeForMappedAccountsNotPresentInBasWorkbook() {
    assertEquals(VatCode.INPUT_6, ChartOfAccountsImportService.resolveVatCode('2643'))
  }

  @Test
  void standardVatCodeMappingsAreCompatibleWithImportedAccountClasses() {
    ChartOfAccountsImportService.STANDARD_VAT_CODES.each { String accountNumber, VatCode vatCode ->
      Account account = new Account(
          null,
          CompanyService.LEGACY_COMPANY_ID,
          accountNumber,
          'Testkonto',
          accountNumber.startsWith('264') ? 'ASSET' : 'LIABILITY',
          accountNumber.startsWith('264') ? 'DEBIT' : 'CREDIT',
          vatCode.name(),
          true,
          false,
          null,
          null
      )

      assertTrue(
          AccountService.compatibleVatCodes(account).contains(vatCode),
          "${accountNumber} should resolve to an import-compatible VAT code"
      )
    }
  }

  @Test
  void searchToggleAndOpeningBalanceRulesWork() {
    importService.importFromExcel(workbookPath())
    long fiscalYearId = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    ).id

    List<Account> assets = accountService.searchAccounts(CompanyService.LEGACY_COMPANY_ID, 'fordr', 'ASSET', true, false)
    assertFalse(assets.isEmpty())

    accountService.setAccountActive(CompanyService.LEGACY_COMPANY_ID, '1010', false)
    Account updated = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, '1010')
    assertFalse(updated.active)

    OpeningBalance openingBalance = accountService.saveOpeningBalance(fiscalYearId, '1010', 1250.50G)
    assertEquals(1250.50G, openingBalance.amount)

    Executable action = {
      accountService.saveOpeningBalance(fiscalYearId, '3000', 100.00G)
    } as Executable

    assertThrows(IllegalArgumentException, action)
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static Path workbookPath() {
    List<Path> candidates = [
        Path.of('specs', 'BAS_kontoplan_2026.xlsx'),
        Path.of('..', 'specs', 'BAS_kontoplan_2026.xlsx')
    ]
    Path workbook = candidates.find { Path candidate -> candidate.toFile().isFile() }
    if (workbook == null) {
      throw new IllegalStateException('Could not locate BAS_kontoplan_2026.xlsx for integration test.')
    }
    workbook.normalize()
  }

  private void assertImportedVatAccount(String accountNumber, String accountClass, String side, VatCode vatCode) {
    Account account = accountService.findAccount(CompanyService.LEGACY_COMPANY_ID, accountNumber)
    assertNotNull(account)
    assertEquals(accountClass, account.accountClass)
    assertEquals(side, account.normalBalanceSide)
    assertEquals(vatCode.name(), account.vatCode)
    assertTrue(AccountService.compatibleVatCodes(account).contains(vatCode))
  }
}
