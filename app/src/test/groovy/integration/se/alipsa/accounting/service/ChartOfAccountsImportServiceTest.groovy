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

    Account assetAccount = accountService.findAccount('1010')
    Account equityAccount = accountService.findAccount('2010')
    Account incomeAccount = accountService.findAccount('3000')
    Account manualReviewAccount = accountService.findAccount('8999')

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
  void searchToggleAndOpeningBalanceRulesWork() {
    importService.importFromExcel(workbookPath())
    long fiscalYearId = fiscalYearService.createFiscalYear(
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    ).id

    List<Account> assets = accountService.searchAccounts('fordr', 'ASSET', true, false)
    assertFalse(assets.isEmpty())

    accountService.setAccountActive('1010', false)
    Account updated = accountService.findAccount('1010')
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
}
