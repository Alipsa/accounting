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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException

import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.border.EtchedBorder

/** Status dashboard shown on the Overview tab: company, fiscal year, vouchers, periods, backup, integrity. */
final class OverviewPanel extends JPanel implements PropertyChangeListener {

  private static final String LOCALE_PROPERTY = 'locale'
  private static final Color GREY = new Color(108, 117, 125)
  private static final Color RED = new Color(220, 53, 69)
  private static final Color GREEN = new Color(25, 135, 84)
  private static final int BACKUP_WARN_DAYS = 60
  private static final DateTimeFormatter BACKUP_DATE_FORMATTER = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm')

  private final VoucherService voucherService
  private final AccountingPeriodService accountingPeriodService
  private final BackupService backupService
  private final StartupVerificationService startupVerificationService
  private final ActiveCompanyManager activeCompanyManager

  private boolean integrityChecked = false
  private boolean integrityCheckStarted = false
  private boolean integrityOk = false
  private boolean listenersRegistered = false
  private int reloadRequestId = 0
  private OverviewSnapshot currentSnapshot = OverviewSnapshot.empty()

  // Header strip labels
  private final JLabel companyTitleLabel = new JLabel()
  private final JLabel companyNameLabel = new JLabel()
  private final JLabel orgNumberLabel = new JLabel()
  private final JLabel fiscalYearTitleLabel = new JLabel()
  private final JLabel fiscalYearNameLabel = new JLabel()
  private final JLabel fiscalYearDetailLabel = new JLabel()

  // Stat card title labels
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
    registerListeners()
    buildUi()
    applyLocale()
    renderSnapshot()
    SwingUtilities.invokeLater { reload() }
  }

  @Override
  void addNotify() {
    super.addNotify()
    registerListeners()
  }

  @Override
  void removeNotify() {
    unregisterListeners()
    super.removeNotify()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if (LOCALE_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater {
        applyLocale()
        renderSnapshot()
      }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName
        || ActiveCompanyManager.FISCAL_YEAR_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { reload() }
    }
  }

  void reload() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater { reload() }
      return
    }

    applyLocale()
    final int requestId = ++reloadRequestId
    Company company = activeCompanyManager.activeCompany
    FiscalYear fiscalYear = activeCompanyManager.fiscalYear

    if (company == null) {
      currentSnapshot = OverviewSnapshot.withoutCompany(currentIntegrityStatus())
      renderSnapshot()
      return
    }

    if (fiscalYear == null) {
      currentSnapshot = OverviewSnapshot.withoutFiscalYear(company, currentIntegrityStatus())
      renderSnapshot()
      return
    }

    currentSnapshot = OverviewSnapshot.loading(company, fiscalYear, currentIntegrityStatus())
    renderSnapshot()

    final boolean runIntegrityCheck = !integrityChecked && !integrityCheckStarted
    if (runIntegrityCheck) {
      integrityCheckStarted = true
    }

    new SwingWorker<OverviewSnapshot, Void>() {
      @Override
      protected OverviewSnapshot doInBackground() {
        int count = voucherService.countVouchers(company.id, fiscalYear.id)
        List<AccountingPeriod> periods = accountingPeriodService.listPeriods(fiscalYear.id)
        int locked = periods.count { AccountingPeriod period -> period.locked } as int
        List<BackupSummary> backups = backupService.listBackups(1)
        BackupSummary latestBackup = backups.isEmpty() ? null : backups.first()
        boolean warn = latestBackup != null && hasStaleBackupWithNewVouchers(company.id, latestBackup.createdAt)
        Boolean loadedIntegrity = runIntegrityCheck ? startupVerificationService.verify().ok : currentIntegrityStatus()
        new OverviewSnapshot(company, fiscalYear, count, locked, periods.size(), latestBackup?.createdAt, warn, loadedIntegrity)
      }

      @Override
      protected void done() {
        try {
          OverviewSnapshot snapshot = get()
          if (requestId != reloadRequestId) {
            return
          }
          currentSnapshot = snapshot
          if (runIntegrityCheck && snapshot.integrityOk != null) {
            integrityOk = snapshot.integrityOk
            integrityChecked = true
          }
          renderSnapshot()
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
        } catch (ExecutionException ignored) {
          // Keep the last rendered state and allow a future reload to retry.
        } finally {
          if (runIntegrityCheck) {
            integrityCheckStarted = false
          }
        }
      }
    }.execute()
  }

  private void renderSnapshot() {
    Company company = currentSnapshot.company
    FiscalYear fiscalYear = currentSnapshot.fiscalYear

    if (company == null) {
      clearStatCards()
      companyNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      orgNumberLabel.text = ''
      fiscalYearNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      fiscalYearDetailLabel.text = ''
      updateIntegrityCard(currentSnapshot.integrityOk)
      return
    }

    companyNameLabel.text = company.companyName ?: ''
    orgNumberLabel.text = company.organizationNumber ?: ''

    if (fiscalYear == null) {
      fiscalYearNameLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      fiscalYearDetailLabel.text = ''
      clearStatCards()
      updateIntegrityCard(currentSnapshot.integrityOk)
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

    if (currentSnapshot.voucherCount == null) {
      clearStatCards()
      updateIntegrityCard(currentSnapshot.integrityOk)
      return
    }

    voucherValueLabel.text = String.valueOf(currentSnapshot.voucherCount)
    voucherSubLabel.text = I18n.instance.getString('overviewPanel.card.vouchers.subtitle')

    periodsValueLabel.text = "${currentSnapshot.lockedPeriods} / ${currentSnapshot.totalPeriods}"
    periodsSubLabel.text = I18n.instance.getString('overviewPanel.card.periods.subtitle')

    updateBackupCard(currentSnapshot.backupCreatedAt, currentSnapshot.backupWarn)
    updateIntegrityCard(currentSnapshot.integrityOk)
  }

  private void updateBackupCard(LocalDateTime createdAt, boolean warn) {
    if (createdAt == null) {
      backupValueLabel.text = I18n.instance.getString('overviewPanel.card.backup.never')
      backupValueLabel.foreground = GREY
      backupSubLabel.text = ''
      backupSubLabel.foreground = GREY
      return
    }

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

    String dateText = BACKUP_DATE_FORMATTER.format(createdAt)
    if (warn) {
      backupValueLabel.foreground = RED
      backupSubLabel.foreground = RED
    } else {
      backupValueLabel.foreground = UIManager.getColor('Label.foreground') ?: Color.BLACK
      backupSubLabel.foreground = GREY
    }
    backupValueLabel.text = dateText
    backupSubLabel.text = ageText
  }

  private void updateIntegrityCard(Boolean value) {
    integritySubLabel.text = I18n.instance.getString('overviewPanel.card.integrity.subtitle')
    if (value == null) {
      integrityValueLabel.text = I18n.instance.getString('overviewPanel.card.noData')
      integrityValueLabel.foreground = UIManager.getColor('Label.foreground') ?: Color.BLACK
    } else if (value) {
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
    Locale locale = I18n.instance.locale
    companyTitleLabel.text = I18n.instance.getString('overviewPanel.header.company').toUpperCase(locale)
    fiscalYearTitleLabel.text = I18n.instance.getString('overviewPanel.header.fiscalYear').toUpperCase(locale)
    voucherCardTitle.text = I18n.instance.getString('overviewPanel.card.vouchers').toUpperCase(locale)
    periodsCardTitle.text = I18n.instance.getString('overviewPanel.card.periods').toUpperCase(locale)
    backupCardTitle.text = I18n.instance.getString('overviewPanel.card.backup').toUpperCase(locale)
    integrityCardTitle.text = I18n.instance.getString('overviewPanel.card.integrity').toUpperCase(locale)
  }

  private Boolean currentIntegrityStatus() {
    integrityChecked ? integrityOk : null
  }

  private boolean hasStaleBackupWithNewVouchers(long companyId, LocalDateTime createdAt) {
    long daysAgo = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now())
    daysAgo > BACKUP_WARN_DAYS && voucherService.hasVouchersCreatedAfter(companyId, createdAt)
  }

  private void registerListeners() {
    if (listenersRegistered) {
      return
    }
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    listenersRegistered = true
  }

  private void unregisterListeners() {
    if (!listenersRegistered) {
      return
    }
    I18n.instance.removeLocaleChangeListener(this)
    activeCompanyManager.removePropertyChangeListener(this)
    listenersRegistered = false
  }

  private void buildUi() {
    setLayout(new BorderLayout(0, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    add(buildHeaderStrip(), BorderLayout.NORTH)
    add(buildStatGrid(), BorderLayout.CENTER)
  }

  private void styleHeaderLabels() {
    Font titleFont = companyNameLabel.font.deriveFont(Font.BOLD, 14.0f)
    Font subtitleFont = companyTitleLabel.font.deriveFont(9.0f)
    Font detailFont = titleFont.deriveFont(11.0f)
    companyTitleLabel.foreground = GREY
    companyTitleLabel.font = subtitleFont
    companyNameLabel.font = titleFont
    orgNumberLabel.foreground = GREY
    orgNumberLabel.font = detailFont
    fiscalYearTitleLabel.foreground = GREY
    fiscalYearTitleLabel.font = subtitleFont
    fiscalYearNameLabel.font = titleFont
    fiscalYearDetailLabel.font = detailFont
  }

  private JPanel buildHeaderStrip() {
    styleHeaderLabels()
    JPanel strip = new JPanel(new GridBagLayout())
    strip.border = BorderFactory.createCompoundBorder(
        BorderFactory.createEtchedBorder(EtchedBorder.LOWERED),
        BorderFactory.createEmptyBorder(10, 14, 10, 14)
    )

    GridBagConstraints gbc = new GridBagConstraints()
    gbc.anchor = GridBagConstraints.WEST
    gbc.fill = GridBagConstraints.BOTH

    // Company column
    JPanel companyPanel = new JPanel(new GridLayout(3, 1, 0, 2))
    companyPanel.opaque = false
    companyPanel.add(companyTitleLabel)
    companyPanel.add(companyNameLabel)
    companyPanel.add(orgNumberLabel)

    gbc.gridx = 0
    gbc.gridy = 0
    gbc.weightx = 1.0
    gbc.weighty = 1.0
    gbc.insets = new Insets(0, 0, 0, 0)
    strip.add(companyPanel, gbc)

    // Vertical separator
    JSeparator sep = new JSeparator(JSeparator.VERTICAL)
    gbc.gridx = 1
    gbc.weightx = 0.0
    gbc.fill = GridBagConstraints.VERTICAL
    gbc.insets = new Insets(0, 16, 0, 16)
    strip.add(sep, gbc)

    // Fiscal year column
    JPanel fyPanel = new JPanel(new GridLayout(3, 1, 0, 2))
    fyPanel.opaque = false
    fyPanel.add(fiscalYearTitleLabel)
    fyPanel.add(fiscalYearNameLabel)
    fyPanel.add(fiscalYearDetailLabel)

    gbc.gridx = 2
    gbc.weightx = 1.0
    gbc.fill = GridBagConstraints.BOTH
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
    titleLabel.foreground = GREY
    titleLabel.font = titleLabel.font.deriveFont(9.0f)
    valueLabel.font = valueLabel.font.deriveFont(Font.BOLD, valueFontSize)
    subLabel.font = subLabel.font.deriveFont(10.0f)
    subLabel.foreground = GREY
    card.add(titleLabel)
    card.add(valueLabel)
    card.add(subLabel)
    card
  }

  private static final class OverviewSnapshot {

    final Company company
    final FiscalYear fiscalYear
    final Integer voucherCount
    final int lockedPeriods
    final int totalPeriods
    final LocalDateTime backupCreatedAt
    final boolean backupWarn
    final Boolean integrityOk

    OverviewSnapshot(
        Company company,
        FiscalYear fiscalYear,
        Integer voucherCount,
        int lockedPeriods,
        int totalPeriods,
        LocalDateTime backupCreatedAt,
        boolean backupWarn,
        Boolean integrityOk
    ) {
      this.company = company
      this.fiscalYear = fiscalYear
      this.voucherCount = voucherCount
      this.lockedPeriods = lockedPeriods
      this.totalPeriods = totalPeriods
      this.backupCreatedAt = backupCreatedAt
      this.backupWarn = backupWarn
      this.integrityOk = integrityOk
    }

    static OverviewSnapshot empty() {
      new OverviewSnapshot(null, null, null, 0, 0, null, false, null)
    }

    static OverviewSnapshot withoutCompany(Boolean integrityOk) {
      new OverviewSnapshot(null, null, null, 0, 0, null, false, integrityOk)
    }

    static OverviewSnapshot withoutFiscalYear(Company company, Boolean integrityOk) {
      new OverviewSnapshot(company, null, null, 0, 0, null, false, integrityOk)
    }

    static OverviewSnapshot loading(Company company, FiscalYear fiscalYear, Boolean integrityOk) {
      new OverviewSnapshot(company, fiscalYear, null, 0, 0, null, false, integrityOk)
    }
  }
}
