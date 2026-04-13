package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

class CompanyServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private CompanyService companyService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    companyService = new CompanyService(databaseService)
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

    Company created = companyService.save(
        new Company(null, 'Second AB', '556123-4567', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null)
    )

    assertNotNull(created.id)
    assertEquals('Second AB', created.companyName)

    List<Company> companies = companyService.listCompanies()
    assertEquals(2, companies.size())
    assertEquals(['Default company', 'Second AB'], companies*.companyName)
  }
}
