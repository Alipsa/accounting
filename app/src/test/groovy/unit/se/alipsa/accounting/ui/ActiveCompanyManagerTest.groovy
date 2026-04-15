package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.support.AppPaths

import java.beans.PropertyChangeEvent
import java.nio.file.Path

class ActiveCompanyManagerTest {

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
  void initializesToFirstCompany() {
    ActiveCompanyManager manager = new ActiveCompanyManager(companyService, fiscalYearService)

    assertTrue(manager.hasActiveCompany())
    assertEquals(CompanyService.LEGACY_COMPANY_ID, manager.companyId)
  }

  @Test
  void firesPropertyChangeEventOnCompanySwitch() {
    Company second = companyService.save(new Company(
        null, 'Zetterberg AB', '556000-0002', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null
    ))

    ActiveCompanyManager manager = new ActiveCompanyManager(companyService, fiscalYearService)
    long initialId = manager.companyId
    List<PropertyChangeEvent> events = []
    manager.addPropertyChangeListener { PropertyChangeEvent evt -> events << evt }

    manager.companyId = second.id

    assertEquals(2, events.size())
    assertEquals(ActiveCompanyManager.COMPANY_ID_PROPERTY, events[0].propertyName)
    assertEquals(initialId, events[0].oldValue)
    assertEquals(second.id, events[0].newValue)
    assertEquals(ActiveCompanyManager.FISCAL_YEAR_PROPERTY, events[1].propertyName)
    assertEquals(second.id, manager.companyId)
  }

  @Test
  void doesNotFireEventWhenSettingSameCompanyId() {
    ActiveCompanyManager manager = new ActiveCompanyManager(companyService, fiscalYearService)
    long initialId = manager.companyId
    List<PropertyChangeEvent> events = []
    manager.addPropertyChangeListener { PropertyChangeEvent evt -> events << evt }

    manager.companyId = initialId

    assertTrue(events.isEmpty())
  }

  @Test
  void removedListenerDoesNotReceiveEvents() {
    Company second = companyService.save(new Company(
        null, 'Gamma AB', '556000-0003', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY, true, null, null
    ))

    ActiveCompanyManager manager = new ActiveCompanyManager(companyService, fiscalYearService)
    List<PropertyChangeEvent> events = []
    java.beans.PropertyChangeListener listener = { PropertyChangeEvent evt -> events << evt }
    manager.addPropertyChangeListener(listener)
    manager.removePropertyChangeListener(listener)

    manager.companyId = second.id

    assertTrue(events.isEmpty())
  }
}
