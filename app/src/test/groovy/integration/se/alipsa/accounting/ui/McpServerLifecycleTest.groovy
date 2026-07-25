package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class McpServerLifecycleTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private FiscalYearService fiscalYearService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
  }

  @AfterEach
  void tearDown() {
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void activeContextReflectsFiscalYearClosedEvenWhenCachedObjectIsStale() {
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    Company company = new Company(id: CompanyService.LEGACY_COMPANY_ID, companyName: 'Testbolaget AB')

    // Simulate closing performed by a process that does not go through the desktop UI, e.g.
    // the MCP close_fiscal_year tool, which never updates ActiveCompanyManager's cached object.
    fiscalYearService.closeFiscalYear(fiscalYear.id)
    FiscalYear staleCachedFiscalYear = fiscalYear

    Map<String, Object> context = McpServerLifecycle.buildActiveContext(fiscalYearService, company, staleCachedFiscalYear)

    assertEquals(true, context.fiscal_year_closed)
  }

  @Test
  void activeContextReportsUnavailableWhenNoCompanyOrFiscalYearSelected() {
    Map<String, Object> context = McpServerLifecycle.buildActiveContext(fiscalYearService, null, null)

    assertEquals(false, context.ok)
  }
}
