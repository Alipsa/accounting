# Overview Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder Overview tab with a minimal status dashboard showing company, fiscal year, voucher count, accounting periods, last backup, and integrity status.

**Architecture:** A new `OverviewPanel` Swing component receives services via constructor injection, registers as a `PropertyChangeListener` on `ActiveCompanyManager`, and is refreshed on tab selection via a `ChangeListener` added in `MainFrame`. Two new query methods are added to `VoucherService` for counting vouchers and checking whether vouchers were created after a timestamp. No new service classes are needed.

**Tech Stack:** Groovy, Java Swing (`GridBagLayout`, `GridLayout`), JUnit 6, H2 embedded DB

---

## File Map

| Action | File |
|--------|------|
| Modify | `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy` |
| Create | `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceCountTest.groovy` |
| Modify | `app/src/main/resources/i18n/messages.properties` |
| Modify | `app/src/main/resources/i18n/messages_sv.properties` |
| Create | `app/src/main/groovy/se/alipsa/accounting/ui/OverviewPanel.groovy` |
| Modify | `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy` |

---

### Task 1: VoucherService — add `countVouchers` and `hasVouchersCreatedAfter`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy`
- Create: `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceCountTest.groovy`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceCountTest.groovy`:

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

class VoucherServiceCountTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
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
  void countVouchersReturnsZeroForEmptyYear() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))

    assertEquals(0, voucherService.countVouchers(CompanyService.LEGACY_COMPANY_ID, year.id))
  }

  @Test
  void countVouchersCountsActiveAndCorrectionVouchers() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))

    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'First', lines)
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 4, 1), 'Second', lines)

    assertEquals(2, voucherService.countVouchers(CompanyService.LEGACY_COMPANY_ID, year.id))
  }

  @Test
  void hasVouchersCreatedAfterReturnsTrueWhenVoucherExistsAfterTimestamp() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    LocalDateTime before = LocalDateTime.now().minusSeconds(1)

    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'Test', lines)

    assertTrue(voucherService.hasVouchersCreatedAfter(CompanyService.LEGACY_COMPANY_ID, before))
  }

  @Test
  void hasVouchersCreatedAfterReturnsFalseWhenNoVouchersAfterTimestamp() {
    FiscalYear year = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
    List<VoucherLine> lines = [
        new VoucherLine(null, null, 0, null, '1930', null, 'Bank', 100.00G, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Income', 0.00G, 100.00G)
    ]
    voucherService.createVoucher(year.id, 'A', LocalDate.of(2025, 3, 1), 'Test', lines)

    LocalDateTime future = LocalDateTime.now().plusSeconds(5)
    assertFalse(voucherService.hasVouchersCreatedAfter(CompanyService.LEGACY_COMPANY_ID, future))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "*.VoucherServiceCountTest" 2>&1 | tail -20
```

Expected: compilation error — `countVouchers` and `hasVouchersCreatedAfter` do not exist yet.

- [ ] **Step 3: Add `LocalDateTime` import and the two methods to `VoucherService`**

At the top of `VoucherService.groovy`, add `import java.time.LocalDateTime` after the existing `import java.time.LocalDate` line.

Add these two methods after `listVouchers` (around line 299):

```groovy
  int countVouchers(long companyId, long fiscalYearId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as cnt
            from voucher
           where company_id = ?
             and fiscal_year_id = ?
             and status in ('ACTIVE', 'CORRECTION')
      ''', [companyId, fiscalYearId])
      ((Number) row.cnt).intValue()
    }
  }

  boolean hasVouchersCreatedAfter(long companyId, LocalDateTime since) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as cnt
            from voucher
           where company_id = ?
             and status in ('ACTIVE', 'CORRECTION')
             and created_at > ?
      ''', [companyId, since])
      ((Number) row.cnt).intValue() > 0
    }
  }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*.VoucherServiceCountTest" 2>&1 | tail -20
```

Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceCountTest.groovy
git commit -m "lägg till countVouchers och hasVouchersCreatedAfter i VoucherService"
```

---

### Task 2: i18n — add `overviewPanel.*` keys, remove obsolete description key

**Files:**
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

- [ ] **Step 1: Add keys to `messages.properties`**

Remove the line:
```
mainFrame.tab.overview.description=Overview and status for accounting will be shown here.
```

Add the following block after the `mainFrame.tab.*` section (after line 201):

```properties
# OverviewPanel
overviewPanel.header.company=Company
overviewPanel.header.fiscalYear=Current Fiscal Year
overviewPanel.status.open=Open
overviewPanel.status.closed=Closed
overviewPanel.card.vouchers=Vouchers
overviewPanel.card.vouchers.subtitle=active this year
overviewPanel.card.periods=Accounting Periods
overviewPanel.card.periods.subtitle=locked
overviewPanel.card.backup=Last Backup
overviewPanel.card.backup.today=today
overviewPanel.card.backup.yesterday=yesterday
overviewPanel.card.backup.daysAgo={0} days ago
overviewPanel.card.backup.never=Never
overviewPanel.card.integrity=Integrity
overviewPanel.card.integrity.ok=\u2713 OK
overviewPanel.card.integrity.failed=\u2717 Failed
overviewPanel.card.integrity.subtitle=verified at startup
overviewPanel.card.noData=\u2014
```

- [ ] **Step 2: Add keys to `messages_sv.properties`**

Remove the Swedish description line (equivalent of `mainFrame.tab.overview.description`).

Add the following block in the same location as step 1:

```properties
# OverviewPanel
overviewPanel.header.company=F\u00f6retag
overviewPanel.header.fiscalYear=Aktuellt r\u00e4kenskaps\u00e5r
overviewPanel.status.open=\u00d6ppen
overviewPanel.status.closed=St\u00e4ngd
overviewPanel.card.vouchers=Verifikationer
overviewPanel.card.vouchers.subtitle=aktiva detta \u00e5r
overviewPanel.card.periods=Bokf\u00f6ringsperioder
overviewPanel.card.periods.subtitle=l\u00e5sta
overviewPanel.card.backup=Senaste s\u00e4kerhetskopia
overviewPanel.card.backup.today=idag
overviewPanel.card.backup.yesterday=ig\u00e5r
overviewPanel.card.backup.daysAgo={0} dagar sedan
overviewPanel.card.backup.never=Aldrig
overviewPanel.card.integrity=Integritet
overviewPanel.card.integrity.ok=\u2713 OK
overviewPanel.card.integrity.failed=\u2717 Misslyckades
overviewPanel.card.integrity.subtitle=verifierad vid start
overviewPanel.card.noData=\u2014
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/i18n/messages.properties \
        app/src/main/resources/i18n/messages_sv.properties
git commit -m "lägg till i18n-nycklar för OverviewPanel, ta bort föråldrad beskrivningsnyckel"
```

---

### Task 3: Create `OverviewPanel`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/OverviewPanel.groovy`

- [ ] **Step 1: Create the file**

Create `app/src/main/groovy/se/alipsa/accounting/ui/OverviewPanel.groovy`:

```groovy
package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.BackupSummary
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import javax.swing.*
import javax.swing.border.EtchedBorder

final class OverviewPanel extends JPanel implements PropertyChangeListener {

  private static final Color GREY = new Color(108, 117, 125)
  private static final Color RED = new Color(220, 53, 69)
  private static final Color GREEN = new Color(25, 135, 84)
  private static final int BACKUP_WARN_DAYS = 60

  private final VoucherService voucherService
  private final AccountingPeriodService accountingPeriodService
  private final BackupService backupService
  private final StartupVerificationService startupVerificationService
  private final ActiveCompanyManager activeCompanyManager

  private boolean integrityChecked = false
  private boolean integrityOk = false

  // Header strip labels
  private final JLabel companyTitleLabel = new JLabel()
  private final JLabel companyNameLabel = new JLabel()
  private final JLabel orgNumberLabel = new JLabel()
  private final JLabel fiscalYearTitleLabel = new JLabel()
  private final JLabel fiscalYearNameLabel = new JLabel()
  private final JLabel fiscalYearDetailLabel = new JLabel()

  // Stat card title labels (need locale update)
  private final JLabel voucherCardTitle = new JLabel()
  private final JLabel periodsCardTitle = new JLabel()
  private final JLabel backupCardTitle = new JLabel()
  private final JLabel integrityCardTitle = new JLabel()

  // Stat card value labels
  private final JLabel voucherValueLabel = new JLabel()
  private final JLabel voucherSubLabel = new JLabel()
  private final JLabel periodsValueLabel = new JLabel()
  private final JLabel periodsSubLabel = new JLabel()
  private final JLabel backupValueLabel = new JLabel()
  private final JLabel backupSubLabel = new JLabel()
  private final JLabel integrityValueLabel = new JLabel()
  private final JLabel integritySubLabel = new JLabel()

  OverviewPanel(
      VoucherService voucherService,
      AccountingPeriodService accountingPeriodService,
      BackupService backupService,
      StartupVerificationService startupVerificationService,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.voucherService = voucherService
    this.accountingPeriodService = accountingPeriodService
    this.backupService = backupService
    this.startupVerificationService = startupVerificationService
    this.activeCompanyManager = activeCompanyManager
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reload()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { reload() }
    }
  }

  void reload() {
    applyLocale()
    Company company = activeCompanyManager.activeCompany
    FiscalYear fiscalYear = activeCompanyManager.fiscalYear

    if (company == null) {
      clearStatCards()
      companyNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      orgNumberLabel.text = ''
      fiscalYearNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      fiscalYearDetailLabel.text = ''
      return
    }

    companyNameLabel.text = company.companyName ?: ''
    orgNumberLabel.text = company.organizationNumber ?: ''

    if (fiscalYear == null) {
      fiscalYearNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      fiscalYearDetailLabel.text = ''
      clearStatCards()
      return
    }

    fiscalYearNameLabel.text = fiscalYear.name ?: ''
    String statusText
    if (fiscalYear.closed) {
      statusText = I18n.instance.getString('overviewPanel.status.closed')
      fiscalYearDetailLabel.foreground = GREY
    } else {
      statusText = I18n.instance.getString('overviewPanel.status.open')
      fiscalYearDetailLabel.foreground = GREEN
    }
    fiscalYearDetailLabel.text = "${fiscalYear.startDate} \u2013 ${fiscalYear.endDate}  \u00b7  ${statusText}"

    // Vouchers
    int count = voucherService.countVouchers(company.id, fiscalYear.id)
    voucherValueLabel.text = String.valueOf(count)
    voucherSubLabel.text = I18n.instance.getString('overviewPanel.card.vouchers.subtitle')

    // Accounting periods
    List<AccountingPeriod> periods = accountingPeriodService.listPeriods(fiscalYear.id)
    int locked = periods.count { AccountingPeriod p -> p.locked } as int
    int total = periods.size()
    periodsValueLabel.text = "${locked} / ${total}"
    periodsSubLabel.text = I18n.instance.getString('overviewPanel.card.periods.subtitle')

    // Backup
    updateBackupCard(company.id)

    // Integrity — run once per session
    if (!integrityChecked) {
      integrityOk = startupVerificationService.verify().ok
      integrityChecked = true
    }
    updateIntegrityCard()
  }

  private void updateBackupCard(long companyId) {
    List<BackupSummary> backups = backupService.listBackups(1)
    if (backups.isEmpty()) {
      backupValueLabel.text = I18n.instance.getString('overviewPanel.card.backup.never')
      backupValueLabel.foreground = GREY
      backupSubLabel.text = ''
      return
    }
    BackupSummary latest = backups.first()
    LocalDateTime createdAt = latest.createdAt
    LocalDateTime now = LocalDateTime.now()
    long daysAgo = ChronoUnit.DAYS.between(createdAt, now)

    String ageText
    if (daysAgo == 0) {
      ageText = I18n.instance.getString('overviewPanel.card.backup.today')
    } else if (daysAgo == 1) {
      ageText = I18n.instance.getString('overviewPanel.card.backup.yesterday')
    } else {
      ageText = I18n.instance.format('overviewPanel.card.backup.daysAgo', daysAgo)
    }

    boolean warn = daysAgo > BACKUP_WARN_DAYS && voucherService.hasVouchersCreatedAfter(companyId, createdAt)

    String dateText = createdAt.toString().replace('T', ' ').substring(0, 16)
    if (warn) {
      backupValueLabel.foreground = RED
      backupSubLabel.foreground = RED
      backupSubLabel.text = ageText
    } else {
      backupValueLabel.foreground = UIManager.getColor('Label.foreground') ?: Color.BLACK
      backupSubLabel.foreground = GREY
      backupSubLabel.text = ageText
    }
    backupValueLabel.text = dateText
  }

  private void updateIntegrityCard() {
    integritySubLabel.text = I18n.instance.getString('overviewPanel.card.integrity.subtitle')
    if (integrityOk) {
      integrityValueLabel.text = I18n.instance.getString('overviewPanel.card.integrity.ok')
      integrityValueLabel.foreground = GREEN
    } else {
      integrityValueLabel.text = I18n.instance.getString('overviewPanel.card.integrity.failed')
      integrityValueLabel.foreground = RED
    }
  }

  private void clearStatCards() {
    String dash = I18n.instance.getString('overviewPanel.card.noData')
    [voucherValueLabel, periodsValueLabel, backupValueLabel, integrityValueLabel].each { JLabel l ->
      l.text = dash
      l.foreground = UIManager.getColor('Label.foreground') ?: Color.BLACK
    }
    [voucherSubLabel, periodsSubLabel, backupSubLabel, integritySubLabel].each { JLabel l ->
      l.text = ''
    }
  }

  private void applyLocale() {
    companyTitleLabel.text = I18n.instance.getString('overviewPanel.header.company').toUpperCase(I18n.instance.locale)
    fiscalYearTitleLabel.text = I18n.instance.getString('overviewPanel.header.fiscalYear').toUpperCase(I18n.instance.locale)
    voucherCardTitle.text = I18n.instance.getString('overviewPanel.card.vouchers').toUpperCase(I18n.instance.locale)
    periodsCardTitle.text = I18n.instance.getString('overviewPanel.card.periods').toUpperCase(I18n.instance.locale)
    backupCardTitle.text = I18n.instance.getString('overviewPanel.card.backup').toUpperCase(I18n.instance.locale)
    integrityCardTitle.text = I18n.instance.getString('overviewPanel.card.integrity').toUpperCase(I18n.instance.locale)
  }

  private void buildUi() {
    setLayout(new BorderLayout(0, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    add(buildHeaderStrip(), BorderLayout.NORTH)
    add(buildStatGrid(), BorderLayout.CENTER)
  }

  private JPanel buildHeaderStrip() {
    JPanel strip = new JPanel(new GridBagLayout())
    strip.border = BorderFactory.createCompoundBorder(
        BorderFactory.createEtchedBorder(EtchedBorder.LOWERED),
        BorderFactory.createEmptyBorder(10, 14, 10, 14)
    )

    Font titleFont = companyNameLabel.font.deriveFont(Font.BOLD, 14.0f)
    Font subtitleFont = companyTitleLabel.font.deriveFont(9.0f)

    companyTitleLabel.foreground = GREY
    companyTitleLabel.font = subtitleFont
    companyNameLabel.font = titleFont
    orgNumberLabel.foreground = GREY
    orgNumberLabel.font = companyNameLabel.font.deriveFont(11.0f)

    fiscalYearTitleLabel.foreground = GREY
    fiscalYearTitleLabel.font = subtitleFont
    fiscalYearNameLabel.font = titleFont
    fiscalYearDetailLabel.font = companyNameLabel.font.deriveFont(11.0f)

    GridBagConstraints gbc = new GridBagConstraints()
    gbc.insets = new Insets(0, 0, 0, 0)
    gbc.anchor = GridBagConstraints.WEST
    gbc.fill = GridBagConstraints.BOTH

    // Company column
    JPanel companyPanel = new JPanel(new GridLayout(3, 1, 0, 2))
    companyPanel.opaque = false
    companyPanel.add(companyTitleLabel)
    companyPanel.add(companyNameLabel)
    companyPanel.add(orgNumberLabel)

    gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.weighty = 1.0
    strip.add(companyPanel, gbc)

    // Vertical separator
    JSeparator sep = new JSeparator(JSeparator.VERTICAL)
    gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.VERTICAL
    gbc.insets = new Insets(0, 16, 0, 16)
    strip.add(sep, gbc)

    // Fiscal year column
    JPanel fyPanel = new JPanel(new GridLayout(3, 1, 0, 2))
    fyPanel.opaque = false
    fyPanel.add(fiscalYearTitleLabel)
    fyPanel.add(fiscalYearNameLabel)
    fyPanel.add(fiscalYearDetailLabel)

    gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH
    gbc.insets = new Insets(0, 0, 0, 0)
    strip.add(fyPanel, gbc)

    strip
  }

  private JPanel buildStatGrid() {
    JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8))

    grid.add(buildStatCard(voucherCardTitle, voucherValueLabel, voucherSubLabel, 22.0f))
    grid.add(buildStatCard(periodsCardTitle, periodsValueLabel, periodsSubLabel, 22.0f))
    grid.add(buildStatCard(backupCardTitle, backupValueLabel, backupSubLabel, 13.0f))
    grid.add(buildStatCard(integrityCardTitle, integrityValueLabel, integritySubLabel, 13.0f))

    grid
  }

  private static JPanel buildStatCard(JLabel titleLabel, JLabel valueLabel, JLabel subLabel, float valueFontSize) {
    JPanel card = new JPanel(new GridLayout(3, 1, 0, 2))
    card.border = BorderFactory.createCompoundBorder(
        BorderFactory.createEtchedBorder(EtchedBorder.LOWERED),
        BorderFactory.createEmptyBorder(8, 12, 8, 12)
    )
    titleLabel.foreground = new Color(108, 117, 125)
    titleLabel.font = titleLabel.font.deriveFont(9.0f)
    valueLabel.font = valueLabel.font.deriveFont(Font.BOLD, valueFontSize)
    subLabel.font = subLabel.font.deriveFont(10.0f)
    subLabel.foreground = new Color(108, 117, 125)
    card.add(titleLabel)
    card.add(valueLabel)
    card.add(subLabel)
    card
  }
}
```

- [ ] **Step 2: Compile to check for errors**

```bash
./gradlew compileGroovy 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/OverviewPanel.groovy
git commit -m "lägg till OverviewPanel med företagsinfo, statistikkort och backupvarning"
```

---

### Task 4: Wire `OverviewPanel` into `MainFrame`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy`

Four changes are needed:
1. Remove `overviewDescriptionLabel` field declaration (line 151)
2. Replace `buildOverviewPanel()` to return an `OverviewPanel` and store it in a field
3. Remove the `overviewDescriptionLabel` update lines from `applyTabLocale()` (lines 278-280)
4. Add a `ChangeListener` on `tabbedPane` to call `overviewPanel.reload()` on tab 0 selection

- [ ] **Step 1: Remove `overviewDescriptionLabel` field**

In `MainFrame.groovy`, remove the line:
```groovy
  private JLabel overviewDescriptionLabel
```

- [ ] **Step 2: Add `overviewPanel` field**

After the line `private JLabel companySummaryLabel` (line 150), add:
```groovy
  private OverviewPanel overviewPanel
```

- [ ] **Step 3: Replace `buildOverviewPanel()`**

Replace the entire method:
```groovy
  private JPanel buildOverviewPanel() {
    String safeTitle = escapeHtml(I18n.instance.getString('mainFrame.tab.overview'))
    String safeDescription = escapeHtml(I18n.instance.getString('mainFrame.tab.overview.description'))
    swing.panel(border: swing.emptyBorder(24, 24, 24, 24)) {
      borderLayout()
      overviewDescriptionLabel = label(
          text: "<html><h2>${safeTitle}</h2><p>${safeDescription}</p></html>",
          horizontalAlignment: SwingConstants.CENTER,
          constraints: BorderLayout.CENTER
      )
    } as JPanel
  }
```

With:
```groovy
  private JPanel buildOverviewPanel() {
    overviewPanel = new OverviewPanel(
        voucherService,
        accountingPeriodService,
        backupService,
        startupVerificationService,
        activeCompanyManager
    )
    overviewPanel
  }
```

- [ ] **Step 4: Clean up `applyTabLocale()`**

Remove these three lines from `applyTabLocale()`:
```groovy
    String overviewTitle = escapeHtml(I18n.instance.getString('mainFrame.tab.overview'))
    String overviewDesc = escapeHtml(I18n.instance.getString('mainFrame.tab.overview.description'))
    overviewDescriptionLabel.text = "<html><h2>${overviewTitle}</h2><p>${overviewDesc}</p></html>"
```

- [ ] **Step 5: Add tab ChangeListener after tabs are added in `buildFrame()`**

In `buildFrame()`, after the loop `buildMainTabs().each { ... }` (around line 313-315), add:

```groovy
    tabbedPane.addChangeListener { javax.swing.event.ChangeEvent ignored ->
      if (tabbedPane.selectedIndex == 0) {
        overviewPanel.reload()
      }
    }
```

- [ ] **Step 6: Check that `startupVerificationService` and `backupService` are fields in MainFrame**

Confirm in `MainFrame.groovy` that `startupVerificationService` and `backupService` are declared as fields and initialized. Search for:
```bash
grep -n "startupVerificationService\|backupService" app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy | head -10
```

Both should already exist (they are used by `SystemDocumentationPanel`).

- [ ] **Step 7: Build to verify**

```bash
./gradlew build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run spotless**

```bash
./gradlew spotlessApply
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy
git commit -m "ersätt platshållarvy med OverviewPanel i MainFrame"
```

---

### Task 5: Final build and verify

- [ ] **Step 1: Full build**

```bash
./gradlew build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Manual smoke test**

Run the application:
```bash
./gradlew run
```

Check:
- Overview tab shows company name and org number in header strip
- Fiscal year name, date range, and open/closed status shown
- Voucher count matches what you see in the Vouchers tab
- Accounting periods locked/total is correct
- Backup card shows date or "Never"
- Integrity card shows ✓ OK or ✗ Failed
- Switching company updates all cards
- Switching language (EN ↔ SV) updates all labels

