package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTable

class ChartOfAccountsPanelTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private ActiveCompanyManager activeCompanyManager
  private Locale previousLocale
  private String previousHome
  private ChartOfAccountsPanel panel

  @BeforeEach
  void setUp() {
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    CompanyService companyService = new CompanyService(databaseService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService)
    activeCompanyManager = new ActiveCompanyManager(companyService, fiscalYearService, CompanyService.LEGACY_COMPANY_ID)
    insertAccount('1510', 'Kundfordringar', 'ASSET', true, false)
    insertAccount('3010', 'Försäljning', 'INCOME', true, false)
    insertAccount('3020', 'Annan försäljning', 'INCOME', true, true)
    insertAccount('4010', 'Varuinköp', 'EXPENSE', true, true)
    insertAccount('4020', 'Inaktivt konto', 'EXPENSE', false, true)
  }

  @AfterEach
  void tearDown() {
    panel?.dispose()
    I18n.instance.setLocale(previousLocale)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void classFilterContainsOnlyAllaAndAccountClasses() {
    panel = buildPanel()

    JComboBox<String> classFilter = fieldValue(panel, 'classFilter') as JComboBox<String>
    List<String> expected = onEdt {
      [I18n.instance.getString('chartOfAccountsPanel.filter.all')] +
          ChartOfAccountsPanel.ACCOUNT_CLASSES.collect { ChartOfAccountsPanel.displayAccountClass(it) }
    }
    List<String> items = onEdt {
      (0..<classFilter.itemCount).collect { classFilter.getItemAt(it) }
    }
    assertEquals(expected, items)
  }

  @Test
  void reviewCheckBoxFiltersIndependentlyOfClassFilter() {
    panel = buildPanel()
    JCheckBox reviewCheckBox = fieldValue(panel, 'manualReviewCheckBox') as JCheckBox
    assertNotNull(reviewCheckBox)
    assertEquals(I18n.instance.getString('chartOfAccountsPanel.checkbox.reviewRequired'),
        onEdt { reviewCheckBox.text })

    onEdt { reviewCheckBox.selected = true }

    assertEquals(['3020', '4010'], visibleAccountNumbers())

    JComboBox<String> classFilter = fieldValue(panel, 'classFilter') as JComboBox<String>
    onEdt { classFilter.selectedIndex = 1 + ChartOfAccountsPanel.ACCOUNT_CLASSES.indexOf('INCOME') }

    assertEquals(['3020'], visibleAccountNumbers())
  }

  @Test
  void activeOnlyCheckBoxCombinesWithReviewCheckBox() {
    panel = buildPanel()
    JCheckBox activeOnlyCheckBox = fieldValue(panel, 'activeOnlyCheckBox') as JCheckBox
    JCheckBox reviewCheckBox = fieldValue(panel, 'manualReviewCheckBox') as JCheckBox
    onEdt { reviewCheckBox.selected = true }

    assertEquals(['3020', '4010'], visibleAccountNumbers())

    onEdt { activeOnlyCheckBox.selected = false }

    assertEquals(['3020', '4010', '4020'], visibleAccountNumbers())
  }

  @Test
  void resetFiltersClearsReviewCheckBoxAndRestoresActiveOnly() {
    panel = buildPanel()
    JCheckBox activeOnlyCheckBox = fieldValue(panel, 'activeOnlyCheckBox') as JCheckBox
    JCheckBox reviewCheckBox = fieldValue(panel, 'manualReviewCheckBox') as JCheckBox
    onEdt {
      activeOnlyCheckBox.selected = false
      reviewCheckBox.selected = true
    }
    assertTrue(onEdt { reviewCheckBox.selected })
    assertFalse(onEdt { activeOnlyCheckBox.selected })

    onEdt { invoke(panel, 'resetFilters') }

    assertFalse(onEdt { reviewCheckBox.selected })
    assertTrue(onEdt { activeOnlyCheckBox.selected })
    assertEquals(['1510', '3010', '3020', '4010'], visibleAccountNumbers())
  }

  @Test
  void localeChangeKeepsFilterSelectionAndUpdatesLabels() {
    panel = buildPanel()
    JCheckBox reviewCheckBox = fieldValue(panel, 'manualReviewCheckBox') as JCheckBox
    JComboBox<String> classFilter = fieldValue(panel, 'classFilter') as JComboBox<String>
    int incomeIndex = 1 + ChartOfAccountsPanel.ACCOUNT_CLASSES.indexOf('INCOME')
    onEdt {
      classFilter.selectedIndex = incomeIndex
      reviewCheckBox.selected = true
    }
    assertEquals(['3020'], visibleAccountNumbers())

    I18n.instance.setLocale(Locale.ENGLISH)
    flushEdt()

    assertEquals(incomeIndex, onEdt { classFilter.selectedIndex })
    assertTrue(onEdt { reviewCheckBox.selected })
    assertEquals('Requires review', onEdt { reviewCheckBox.text })
    assertEquals(['3020'], visibleAccountNumbers())
    List<String> expectedItems = onEdt {
      ['All'] + ChartOfAccountsPanel.ACCOUNT_CLASSES.collect { ChartOfAccountsPanel.displayAccountClass(it) }
    }
    List<String> items = onEdt {
      (0..<classFilter.itemCount).collect { classFilter.getItemAt(it) }
    }
    assertEquals(expectedItems, items)
  }

  private ChartOfAccountsPanel buildPanel() {
    onEdt {
      panel = new ChartOfAccountsPanel(
          new AccountService(databaseService),
          new ChartOfAccountsImportService(databaseService),
          activeCompanyManager
      )
      panel
    }
  }

  private List<String> visibleAccountNumbers() {
    JTable table = fieldValue(panel, 'accountTable') as JTable
    onEdt {
      (0..<table.rowCount).collect { table.getValueAt(it, 0).toString() }
    }
  }

  private void insertAccount(
      String accountNumber, String accountName, String accountClass, boolean active, boolean manualReview) {
    databaseService.withTransaction { Sql sql ->
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
          ) values (?, ?, ?, ?, ?, null, ?, ?, null, current_timestamp, current_timestamp)
      ''', [CompanyService.LEGACY_COMPANY_ID, accountNumber, accountName, accountClass,
            accountClass in ['ASSET', 'EXPENSE'] ? 'DEBIT' : 'CREDIT', active, manualReview])
    }
  }

  private static Object fieldValue(Object target, String name) {
    Field field = target.class.getDeclaredField(name)
    field.accessible = true
    field.get(target)
  }

  private static Object invoke(Object target, String name) {
    Method method = target.class.getDeclaredMethod(name)
    method.accessible = true
    method.invoke(target)
  }

  private static <T> T onEdt(Closure<T> work) {
    if (java.awt.EventQueue.isDispatchThread()) {
      return work.call()
    }
    AtomicReference<T> result = new AtomicReference<>()
    AtomicReference<Throwable> failure = new AtomicReference<>()
    javax.swing.SwingUtilities.invokeAndWait {
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

  private static void flushEdt() {
    onEdt { true }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
    } else {
      System.setProperty(name, value)
    }
  }
}
