package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
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

class FiscalYearLockMcpToolsTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private AuditLogService auditLogService
  private FiscalYearService fiscalYearService
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
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, periodService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        new AttachmentService(databaseService, auditLogService),
        auditLogService
    )

    tools = new AccountingMcpTools(
        companyService,
        fiscalYearService,
        new AccountService(databaseService),
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
  void lockFiscalYearLocksWithoutCreatingClosingVouchers() {
    Map<String, Object> result = tools.callTool('lock_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId,
        reason: (Object) 'Redan stängt i Fortnox'
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    assertTrue((boolean) result.get('closed'))
    assertEquals('Redan stängt i Fortnox', result.get('reason'))
    assertTrue(fiscalYearService.findById(fiscalYearId).closed)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from closing_entry where fiscal_year_id = ?',
          [fiscalYearId]
      ) as GroovyRowResult
      assertEquals(0, ((Number) row.get('total')).intValue(), 'Locking must not create closing entries')
    }
    List<AuditLogEntry> auditEntries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)
    AuditLogEntry entry = auditEntries.find { AuditLogEntry auditEntry ->
      auditEntry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && auditEntry.fiscalYearId == fiscalYearId
    }
    assertNotNull(entry, 'Locking must write a CLOSE_FISCAL_YEAR audit entry')
    assertTrue(entry.details.contains('reason=Redan stängt i Fortnox'))
  }

  @Test
  void lockFiscalYearWithoutReasonIsAllowed() {
    Map<String, Object> result = tools.callTool('lock_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertTrue((boolean) result.get('ok'), "Expected ok but got: ${result.get('errors')}")
    assertNull(result.get('reason'))
    assertTrue(fiscalYearService.findById(fiscalYearId).closed)
    List<AuditLogEntry> auditEntries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)
    AuditLogEntry entry = auditEntries.find { AuditLogEntry auditEntry ->
      auditEntry.eventType == AuditLogService.CLOSE_FISCAL_YEAR && auditEntry.fiscalYearId == fiscalYearId
    }
    assertNotNull(entry)
    assertFalse(entry.details.contains('reason='))
  }

  @Test
  void lockFiscalYearRejectsAlreadyClosedYear() {
    tools.callTool('lock_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    Map<String, Object> result = tools.callTool('lock_fiscal_year', [
        company_id: (Object) 1L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('redan stängt') })
  }

  @Test
  void lockFiscalYearChecksOwnership() {
    Map<String, Object> result = tools.callTool('lock_fiscal_year', [
        company_id: (Object) 9999L,
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertFalse((boolean) result.get('ok'))
    List<String> errors = (List<String>) result.get('errors')
    assertTrue(errors.any { String error -> error.contains('does not belong to company') })
  }
}
