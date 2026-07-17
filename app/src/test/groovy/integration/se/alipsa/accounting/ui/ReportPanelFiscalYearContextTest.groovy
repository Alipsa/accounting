package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.JournoReportService
import se.alipsa.accounting.service.ReportArchiveService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportExportService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

import javax.swing.JComboBox
import javax.swing.SwingUtilities

class ReportPanelFiscalYearContextTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private ActiveCompanyManager activeCompanyManager
  private ReportDataService reportDataService
  private ReportArchiveService reportArchiveService
  private ReportExportService reportExportService
  private JournoReportService journoReportService
  private VoucherService voucherService
  private FiscalYear previousYear
  private FiscalYear globalYear
  private FiscalYear nextYear
  private String previousHome
  private Locale previousLocale

  @BeforeEach
  void setUp() {
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    ReportIntegrityService integrityService = new ReportIntegrityService(attachmentService, auditLogService)
    reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    reportArchiveService = new ReportArchiveService(databaseService)
    reportExportService = new ReportExportService(
        reportDataService,
        reportArchiveService,
        integrityService,
        auditLogService,
        companyService
    )
    journoReportService = new JournoReportService(
        reportDataService,
        reportArchiveService,
        integrityService,
        companyService,
        auditLogService,
        databaseService
    )
    voucherService = new VoucherService(databaseService, auditLogService)
    previousYear = createFiscalYear('2025', 2025)
    globalYear = createFiscalYear('2026', 2026)
    nextYear = createFiscalYear('2027', 2027)
    activeCompanyManager = new ActiveCompanyManager(companyService, fiscalYearService)
    activeCompanyManager.fiscalYear = globalYear
  }

  @AfterEach
  void tearDown() {
    I18n.instance.setLocale(previousLocale)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void reportYearDefaultsToGlobalKeepsLocalOverrideAndResetsOnActivation() {
    ReportPanel panel = onEdt {
      new ReportPanel(
          reportDataService,
          journoReportService,
          reportExportService,
          reportArchiveService,
          fiscalYearService,
          accountingPeriodService,
          voucherService,
          activeCompanyManager
      )
    }
    JComboBox<FiscalYear> comboBox = findFiscalYearComboBox(panel)

    assertSelectedFiscalYear(comboBox, globalYear)

    activeCompanyManager.fiscalYear = nextYear
    flushEdt()
    assertSelectedFiscalYear(comboBox, nextYear)

    onEdt {
      comboBox.selectedItem = previousYear
    }
    assertSelectedFiscalYear(comboBox, previousYear)

    activeCompanyManager.fiscalYear = globalYear
    flushEdt()
    assertSelectedFiscalYear(comboBox, previousYear)

    onEdt {
      panel.activateFiscalYearContext()
    }
    assertSelectedFiscalYear(comboBox, globalYear)
  }

  private FiscalYear createFiscalYear(String name, int year) {
    fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        name,
        LocalDate.of(year, 1, 1),
        LocalDate.of(year, 12, 31)
    )
  }

  private static void assertSelectedFiscalYear(JComboBox<FiscalYear> comboBox, FiscalYear fiscalYear) {
    assertEquals(fiscalYear.id, onEdt { ((FiscalYear) comboBox.selectedItem).id })
  }

  private static JComboBox<FiscalYear> findFiscalYearComboBox(Container root) {
    findComponent(root, JComboBox) { JComboBox comboBox ->
      comboBox.itemCount > 0 && comboBox.getItemAt(0) instanceof FiscalYear
    } as JComboBox<FiscalYear>
  }

  private static <T extends Component> T findComponent(Container root, Class<T> type, Closure<Boolean> predicate) {
    List<Component> components = allComponents(root)
    T component = components.find { Component child ->
      type.isInstance(child) && predicate.call(type.cast(child))
    } as T
    assertNotNull(component, "Hittade ingen komponent av typen ${type.simpleName}.")
    component
  }

  private static List<Component> allComponents(Container root) {
    List<Component> components = []
    root.components.each { Component component ->
      components << component
      if (component instanceof Container) {
        components.addAll(allComponents(component as Container))
      }
    }
    components
  }

  private static void flushEdt() {
    onEdt { null }
  }

  private static <T> T onEdt(Closure<T> work) {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call()
    }
    AtomicReference<T> result = new AtomicReference<>()
    AtomicReference<Throwable> failure = new AtomicReference<>()
    SwingUtilities.invokeAndWait {
      try {
        result.set(work.call())
      } catch (Throwable throwable) {
        failure.set(throwable)
      }
    }
    if (failure.get() != null) {
      throw failure.get()
    }
    result.get()
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
