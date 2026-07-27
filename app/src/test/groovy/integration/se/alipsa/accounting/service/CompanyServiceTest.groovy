package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AccountingMethod
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.LegalForm
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate

class CompanyServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private FiscalYearService fiscalYearService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    companyService = new CompanyService(databaseService)
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
  void initializeSeedsDefaultCompanyAndAllowsAdditionalCompanies() {
    List<Company> initialCompanies = companyService.listCompanies()

    assertEquals(1, initialCompanies.size())
    assertEquals(CompanyService.LEGACY_COMPANY_ID, initialCompanies.first().id)
    assertEquals('Default company', initialCompanies.first().companyName)
    assertEquals(AccountingMethod.CASH, initialCompanies.first().accountingMethod)

    Company created = companyService.save(
        new Company(null, 'Second AB', '556123-4567', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    assertNotNull(created.id)
    assertEquals('Second AB', created.companyName)

    List<Company> companies = companyService.listCompanies()
    assertEquals(2, companies.size())
    assertEquals(['Default company', 'Second AB'], companies*.companyName)
  }

  @Test
  void savesAndReadsInvoiceAccountingMethod() {
    Company created = companyService.save(
        new Company(null, 'Invoice AB', '556321-4321', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY,
            true, null, null, false, AccountingMethod.INVOICE)
    )

    assertEquals(AccountingMethod.INVOICE, created.accountingMethod)
    assertEquals(AccountingMethod.INVOICE, companyService.findById(created.id).accountingMethod)
  }

  @Test
  void savesAndReadsLegalFormAndSimplifiedAnnualReport() {
    Company created = companyService.save(
        new Company(null, 'Enskild AB', '556999-1111', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY,
            true, null, null, false, AccountingMethod.CASH, LegalForm.ENSKILD_FIRMA, true)
    )

    assertEquals(LegalForm.ENSKILD_FIRMA, created.legalForm)
    assertTrue(created.simplifiedAnnualReport)

    Company reloaded = companyService.findById(created.id)
    assertEquals(LegalForm.ENSKILD_FIRMA, reloaded.legalForm)
    assertTrue(reloaded.simplifiedAnnualReport)

    Company found = companyService.listCompanies().find { Company c -> c.id == created.id }
    assertEquals(LegalForm.ENSKILD_FIRMA, found.legalForm)

    Company updated = companyService.save(
        new Company(created.id, 'Enskild AB', '556999-1111', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY,
            true, null, null, false, AccountingMethod.CASH, LegalForm.AKTIEBOLAG, false)
    )
    assertEquals(LegalForm.AKTIEBOLAG, updated.legalForm)
    assertFalse(updated.simplifiedAnnualReport)
  }

  @Test
  void legalFormIsNullByDefaultForExistingCompanies() {
    Company defaultCompany = companyService.findById(CompanyService.LEGACY_COMPANY_ID)
    assertNull(defaultCompany.legalForm)
  }

  @Test
  void archiveCompanyHidesFromActiveList() {
    Company created = companyService.save(
        new Company(null, 'Archive AB', '556444-5555', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    Company archived = companyService.archiveCompany(created.id)

    assertTrue(archived.archived)
    assertFalse(archived.active)

    List<Company> activeCompanies = companyService.listCompanies(true)
    assertFalse(activeCompanies.any { Company c -> c.id == created.id })

    List<Company> archivedCompanies = companyService.listArchivedCompanies()
    assertTrue(archivedCompanies.any { Company c -> c.id == created.id })
  }

  @Test
  void unarchiveCompanyRestoresVisibility() {
    Company created = companyService.save(
        new Company(null, 'Restore AB', '556555-6666', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )
    companyService.archiveCompany(created.id)

    Company restored = companyService.unarchiveCompany(created.id)

    assertFalse(restored.archived)
    assertTrue(restored.active)

    List<Company> activeCompanies = companyService.listCompanies(true)
    assertTrue(activeCompanies.any { Company c -> c.id == created.id })

    List<Company> archivedCompanies = companyService.listArchivedCompanies()
    assertFalse(archivedCompanies.any { Company c -> c.id == created.id })
  }

  @Test
  void deleteCompanySucceedsWhenNoFiscalYearsRemain() {
    Company created = companyService.save(
        new Company(null, 'Delete AB', '556666-7777', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    companyService.deleteCompany(created.id)

    assertNull(companyService.findById(created.id))
  }

  @Test
  void deleteCompanyFailsWhenFiscalYearsExist() {
    Company created = companyService.save(
        new Company(null, 'Keep AB', '556777-8888', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )
    fiscalYearService.createFiscalYear(created.id, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    assertThrows(IllegalStateException) {
      companyService.deleteCompany(created.id)
    }
  }
}
