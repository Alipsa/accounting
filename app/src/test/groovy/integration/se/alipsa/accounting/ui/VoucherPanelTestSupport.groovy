package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertNotNull

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

import javax.swing.SwingUtilities

/**
 * Shared fixture for integration tests that exercise a VoucherPanel against a real database.
 */
abstract class VoucherPanelTestSupport {

  @TempDir
  protected Path tempDir

  protected String previousHome
  protected DatabaseService databaseService
  protected AuditLogService auditLogService
  protected AccountingPeriodService accountingPeriodService
  protected FiscalYearService fiscalYearService
  protected VoucherService voucherService
  protected AttachmentService attachmentService
  protected ActiveCompanyManager activeCompanyManager
  protected FiscalYear fiscalYear
  protected VoucherPanel panel
  protected List<List<Integer>> moves
  protected int dateFocusRequests
  protected Preferences userPreferencesNode
  protected UserPreferencesService userPreferencesService

  @BeforeEach
  final void setUpVoucherPanelFixture() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    attachmentService = new AttachmentService(databaseService, auditLogService)
    fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2030',
        LocalDate.of(2030, 1, 1),
        LocalDate.of(2030, 12, 31)
    )
    insertTestAccounts()
    activeCompanyManager = new ActiveCompanyManager(new CompanyService(databaseService), fiscalYearService)
    userPreferencesNode = Preferences.userRoot().node("accounting-test-${UUID.randomUUID()}")
    userPreferencesService = new UserPreferencesService(userPreferencesNode)
    moves = []
    dateFocusRequests = 0
    panel = buildPanel()
    installPanelHooks()
  }

  @AfterEach
  final void tearDownVoucherPanelFixture() {
    if (panel != null) {
      panel.dispose()
      onEdt { null }
    }
    databaseService?.shutdown()
    userPreferencesNode?.removeNode()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  VoucherPanel buildPanel() {
    new VoucherPanel(
        voucherService,
        new AccountService(databaseService),
        accountingPeriodService,
        attachmentService,
        auditLogService,
        activeCompanyManager,
        userPreferencesService
    )
  }

  void installPanelHooks() {
    panel.cursorMover = { int row, int col ->
      moves << [row, col]
      null
    } as Closure
    panel.dateFocusRequester = {
      dateFocusRequests++
      null
    } as Closure
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            active,
            manual_review_required,
            classification_note,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [CompanyService.LEGACY_COMPANY_ID, accountNumber, accountName, accountClass, normalBalanceSide])
  }

  static <T extends Component> T findComponent(Container root, Class<T> type, Closure<Boolean> predicate) {
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

  static <T> T onEdt(Closure<T> work) {
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
}
