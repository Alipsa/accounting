package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

class CompanySettingsServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanySettingsService companySettingsService
  private CompanyService companyService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    companySettingsService = new CompanySettingsService(databaseService)
    companyService = new CompanyService(databaseService)
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void saveCreatesAndUpdatesSingletonSettings() {
    CompanySettings created = companySettingsService.save(
        new CompanySettings(null, 'Demo AB', '556677-8899', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY)
    )

    assertNotNull(created.id)
    assertEquals('Demo AB', created.companyName)
    assertEquals('556677-8899', created.organizationNumber)

    CompanySettings updated = companySettingsService.save(
        new CompanySettings(null, 'Demo Holding AB', '556677-8899', 'EUR', 'en-GB', VatPeriodicity.ANNUAL)
    )

    assertEquals(created.id, updated.id)
    assertEquals('Demo Holding AB', updated.companyName)
    assertEquals('EUR', updated.defaultCurrency)
    assertEquals('en-GB', updated.localeTag)
    assertEquals(VatPeriodicity.ANNUAL, updated.vatPeriodicity)
  }

  @Test
  void saveKeepsLegacyCompanyRowInSync() {
    companySettingsService.save(
        new CompanySettings(null, 'Demo AB', '556677-8899', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY)
    )

    List<Company> companies = companyService.listCompanies()

    assertEquals(1, companies.size())
    assertEquals(CompanyService.LEGACY_COMPANY_ID, companies.first().id)
    assertEquals('Demo AB', companies.first().companyName)
    assertEquals('556677-8899', companies.first().organizationNumber)
    assertEquals(VatPeriodicity.MONTHLY, companies.first().vatPeriodicity)

    companySettingsService.save(
        new CompanySettings(null, 'Demo Holding AB', '556677-8899', 'EUR', 'en-GB', VatPeriodicity.ANNUAL)
    )

    Company company = companyService.findById(CompanyService.LEGACY_COMPANY_ID)
    assertNotNull(company)
    assertEquals('Demo Holding AB', company.companyName)
    assertEquals('EUR', company.defaultCurrency)
    assertEquals('en-GB', company.localeTag)
    assertEquals(VatPeriodicity.ANNUAL, company.vatPeriodicity)
    assertTrue(company.active)
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
