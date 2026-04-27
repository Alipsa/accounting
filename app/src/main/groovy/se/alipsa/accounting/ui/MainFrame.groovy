package se.alipsa.accounting.ui

import groovy.swing.SwingBuilder
import groovy.transform.CompileDynamic

import com.formdev.flatlaf.util.UIScale

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ThemeMode
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.CompanySettingsService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearDeletionService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.JournoReportService
import se.alipsa.accounting.service.MigrationService
import se.alipsa.accounting.service.OpeningBalanceService
import se.alipsa.accounting.service.ReportArchiveService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportExportService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.SystemDiagnosticsService
import se.alipsa.accounting.service.SystemDocumentationService
import se.alipsa.accounting.service.UpdateService
import se.alipsa.accounting.service.UpdateService.UpdateInfo
import se.alipsa.accounting.service.UserManualService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n
import se.alipsa.accounting.support.LoggingConfigurer

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Image
import java.awt.Taskbar
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.logging.Level
import java.util.logging.Logger

import javax.imageio.ImageIO
import javax.swing.*

/**
 * Main desktop window with phase two navigation and setup actions.
 */
@CompileDynamic
final class MainFrame implements PropertyChangeListener {

  private static final Logger log = Logger.getLogger(MainFrame.name)
  private static final List<String> ICON_PATHS = [
      '/icons/logo16.png',
      '/icons/logo32.png',
      '/icons/logo64.png',
      '/icons/logo128.png'
  ]
  private static final String TASKBAR_ICON_PATH = '/icons/logo512.png'

  private final SwingBuilder swing = new SwingBuilder()
  private final CompanyService companyService = new CompanyService()
  private final CompanySettingsService companySettingsService = new CompanySettingsService()
  private final UserPreferencesService userPreferencesService = new UserPreferencesService()
  private final AuditLogService auditLogService = new AuditLogService()
  private final AccountingPeriodService accountingPeriodService = new AccountingPeriodService()
  private final AccountService accountService = new AccountService()
  private final AttachmentService attachmentService = new AttachmentService(DatabaseService.instance, auditLogService)
  private final ChartOfAccountsImportService chartOfAccountsImportService = new ChartOfAccountsImportService()
  private final FiscalYearService fiscalYearService = new FiscalYearService(DatabaseService.instance, accountingPeriodService, auditLogService)
  private final OpeningBalanceService openingBalanceService = new OpeningBalanceService(DatabaseService.instance)
  private final VoucherService voucherService = new VoucherService(DatabaseService.instance, auditLogService)
  private final VatService vatService = new VatService(DatabaseService.instance, voucherService)
  private final MigrationService migrationService = new MigrationService(DatabaseService.instance)
  private final ReportDataService reportDataService = new ReportDataService(DatabaseService.instance, fiscalYearService, accountingPeriodService)
  private final ReportArchiveService reportArchiveService = new ReportArchiveService(DatabaseService.instance)
  private final ReportIntegrityService reportIntegrityService = new ReportIntegrityService(attachmentService, auditLogService)
  private final BackupService backupService = new BackupService(
      DatabaseService.instance,
      attachmentService,
      reportArchiveService,
      auditLogService,
      migrationService,
      reportIntegrityService
  )
  private final ClosingService closingService = new ClosingService(
      DatabaseService.instance,
      accountingPeriodService,
      fiscalYearService,
      voucherService,
      reportIntegrityService
  )
  private final StartupVerificationService startupVerificationService = new StartupVerificationService(
      DatabaseService.instance,
      reportIntegrityService,
      reportArchiveService
  )
  private final SystemDiagnosticsService diagnosticsService = new SystemDiagnosticsService(
      migrationService,
      startupVerificationService,
      backupService,
      auditLogService
  )
  private final UserManualService userManualService = new UserManualService()
  private final SystemDocumentationService systemDocumentationService = new SystemDocumentationService(
      migrationService,
      diagnosticsService,
      attachmentService,
      reportArchiveService,
      auditLogService
  )
  private final ReportExportService reportExportService = new ReportExportService(
      reportDataService,
      reportArchiveService,
      reportIntegrityService,
      auditLogService,
      companyService
  )
  private final SieImportExportService sieImportExportService = new SieImportExportService(
      DatabaseService.instance,
      accountingPeriodService,
      voucherService,
      companyService,
      reportIntegrityService,
      auditLogService
  )
  private final JournoReportService journoReportService = new JournoReportService(
      reportDataService,
      reportArchiveService,
      reportIntegrityService,
      companyService,
      auditLogService,
      DatabaseService.instance
  )
  private final FiscalYearDeletionService fiscalYearDeletionService = new FiscalYearDeletionService(DatabaseService.instance)
  private final ActiveCompanyManager activeCompanyManager = new ActiveCompanyManager(companyService, fiscalYearService)

  private JLabel statusLabel
  private OverviewPanel overviewPanel
  private JLabel companyLabel
  private JComboBox<Company> companyComboBox
  private JComboBox<FiscalYear> fiscalYearComboBox
  private JLabel fiscalYearLabel
  private JMenu fileMenu
  private JMenuItem companySettingsMenuItem
  private JMenuItem newCompanyMenuItem
  private JMenuItem editCompanyMenuItem
  private JMenuItem sieExchangeMenuItem
  private JMenuItem archiveCompanyMenuItem
  private JMenuItem unarchiveCompanyMenuItem
  private JMenuItem deleteCompanyMenuItem
  private JMenuItem exitMenuItem
  private JMenu helpMenu
  private JMenuItem manualMenuItem
  private JMenuItem updateMenuItem
  private JMenuItem reportIssueMenuItem
  private JMenuItem aboutMenuItem
  private JButton updateNotificationButton
  private JButton englishButton
  private JButton swedishButton
  private JLabel languageLabel
  private JLabel themeLabel
  private JCheckBox automaticUpdateCheckBox
  private JRadioButton themeSystemButton
  private JRadioButton themeLightButton
  private JRadioButton themeDarkButton
  private JTabbedPane tabbedPane
  private final UpdateService updateService = new UpdateService()
  private UpdateInfo pendingUpdate
  private final JFrame frame

  MainFrame() {
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    frame = buildFrame()
    applyIcons()
    refreshTitle()
    setStatus(I18n.instance.getString('mainFrame.status.started'))
  }

  void display() {
    frame.visible = true
    if (!activeCompanyManager.hasActiveCompany()) {
      SwingUtilities.invokeLater {
        showNewCompanyDialog()
      }
    }
    if (userPreferencesService.isAutomaticUpdateCheckEnabled()) {
      checkForUpdateInBackground()
    }
  }

  void setStatus(String text) {
    statusLabel.text = text
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { onCompanyChanged() }
    } else if (ActiveCompanyManager.FISCAL_YEAR_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { reloadFiscalYearComboBox() }
    }
  }

  private void onCompanyChanged() {
    refreshTitle()
    reloadFiscalYearComboBox()
    Company active = activeCompanyManager.activeCompany
    if (active != null) {
      setStatus(I18n.instance.format('mainFrame.status.companySwitched', active.companyName))
    }
  }

  private void refreshTitle() {
    Company active = activeCompanyManager.activeCompany
    if (active != null) {
      frame.title = I18n.instance.format('mainFrame.title.company', active.companyName)
    } else {
      frame.title = I18n.instance.getString('mainFrame.title')
    }
  }

  private void applyLocale() {
    refreshTitle()
    statusLabel.text = I18n.instance.getString('mainFrame.status.ready')
    applyMenuLocale()
    applyTabLocale()
    companyLabel.text = I18n.instance.getString('mainFrame.label.activeCompany')
    fiscalYearLabel.text = I18n.instance.getString('mainFrame.label.fiscalYear')
    languageLabel.text = I18n.instance.getString('companySettingsDialog.label.language')
    themeLabel.text = I18n.instance.getString('settings.label.theme')
    automaticUpdateCheckBox.text = I18n.instance.getString('settings.label.automaticUpdateCheck')
    themeSystemButton.text = I18n.instance.getString('settings.theme.system')
    themeLightButton.text = I18n.instance.getString('settings.theme.light')
    themeDarkButton.text = I18n.instance.getString('settings.theme.dark')
    updateLanguageButtonBorders()
    if (pendingUpdate != null) {
      updateNotificationButton.text = I18n.instance.format('mainFrame.button.updateVersion', pendingUpdate.availableVersion)
    }
  }

  private void applyMenuLocale() {
    fileMenu.text = I18n.instance.getString('mainFrame.menu.file')
    companySettingsMenuItem.text = I18n.instance.getString('mainFrame.menu.file.companySettings')
    newCompanyMenuItem.text = I18n.instance.getString('mainFrame.menu.file.newCompany')
    editCompanyMenuItem.text = I18n.instance.getString('mainFrame.menu.file.editCompany')
    sieExchangeMenuItem.text = I18n.instance.getString('mainFrame.menu.file.sieExchange')
    archiveCompanyMenuItem.text = I18n.instance.getString('mainFrame.menu.file.archiveCompany')
    unarchiveCompanyMenuItem.text = I18n.instance.getString('mainFrame.menu.file.unarchiveCompany')
    deleteCompanyMenuItem.text = I18n.instance.getString('mainFrame.menu.file.deleteCompany')
    exitMenuItem.text = I18n.instance.getString('mainFrame.menu.file.exit')
    helpMenu.text = I18n.instance.getString('mainFrame.menu.help')
    manualMenuItem.text = I18n.instance.getString('mainFrame.menu.help.manual')
    updateMenuItem.text = I18n.instance.getString('mainFrame.menu.help.checkForUpdates')
    reportIssueMenuItem.text = I18n.instance.getString('mainFrame.menu.help.reportIssue')
    aboutMenuItem.text = I18n.instance.getString('mainFrame.menu.help.about')
  }

  private void applyTabLocale() {
    tabbedPane.setTitleAt(0, I18n.instance.getString('mainFrame.tab.overview'))
    tabbedPane.setTitleAt(1, I18n.instance.getString('mainFrame.tab.vouchers'))
    tabbedPane.setTitleAt(2, I18n.instance.getString('mainFrame.tab.vat'))
    tabbedPane.setTitleAt(3, I18n.instance.getString('mainFrame.tab.reports'))
    tabbedPane.setTitleAt(4, I18n.instance.getString('mainFrame.tab.chartOfAccounts'))
    tabbedPane.setTitleAt(5, I18n.instance.getString('mainFrame.tab.fiscalYears'))
    tabbedPane.setTitleAt(6, I18n.instance.getString('mainFrame.tab.system'))
    tabbedPane.setTitleAt(7, I18n.instance.getString('mainFrame.tab.settings'))
  }

  private JFrame buildFrame() {
    List<Integer> size = scaledWindowSize(1100, 720)
    JFrame f = swing.frame(
        title: I18n.instance.getString('mainFrame.title'),
        size: size,
        defaultCloseOperation: JFrame.EXIT_ON_CLOSE,
        locationByPlatform: true,
        show: false
    ) {
      menuBar {
        buildFileMenu(delegate)
        buildHelpMenu(delegate)
      }
      borderLayout()
      tabbedPane = tabbedPane(constraints: BorderLayout.CENTER)
    } as JFrame
    buildMainTabs().each { Map<String, Object> tab ->
      tabbedPane.addTab(tab.title as String, tab.component as JComponent)
    }
    tabbedPane.addChangeListener { javax.swing.event.ChangeEvent ignored ->
      if (tabbedPane.selectedComponent == overviewPanel) {
        overviewPanel.reload()
      }
    }
    f.contentPane.add(buildStatusBar(), BorderLayout.SOUTH)
    f
  }

  private void buildFileMenu(Object builder) {
    builder.with {
      fileMenu = menu(text: I18n.instance.getString('mainFrame.menu.file')) {
        newCompanyMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.newCompany'), actionPerformed: { showNewCompanyDialog() })
        editCompanyMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.editCompany'), actionPerformed: { showEditCompanyDialog() })
        separator()
        companySettingsMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.companySettings'), actionPerformed: { showCompanySettingsDialog() })
        sieExchangeMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.sieExchange'), actionPerformed: { showSieExchangeDialog() })
        separator()
        archiveCompanyMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.archiveCompany'), actionPerformed: { archiveCompanyRequested() })
        unarchiveCompanyMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.unarchiveCompany'), actionPerformed: { showUnarchiveCompanyDialog() })
        deleteCompanyMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.deleteCompany'), actionPerformed: { deleteCompanyRequested() })
        separator()
        exitMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.exit'), actionPerformed: { exitRequested() })
      }
    }
  }

  private void buildHelpMenu(Object builder) {
    builder.with {
      helpMenu = menu(text: I18n.instance.getString('mainFrame.menu.help')) {
        manualMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.manual'), actionPerformed: { showUserManualDialog() })
        updateMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.checkForUpdates'), actionPerformed: { showUpdateDialog() })
        reportIssueMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.reportIssue'), actionPerformed: { openIssueTracker() })
        separator()
        aboutMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.about'), actionPerformed: { showAboutDialog() })
      }
    }
  }

  private JPanel buildSettingsPanel() {
    JPanel panel = new JPanel(new BorderLayout(0, 12))
    panel.border = BorderFactory.createEmptyBorder(24, 24, 24, 24)

    JPanel topPanel = new JPanel(new BorderLayout(0, 12))

    JPanel languageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    languageLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.language'))
    englishButton = new JButton(loadFlagIcon('/icons/UK.png'))
    swedishButton = new JButton(loadFlagIcon('/icons/sweden.png'))
    englishButton.addActionListener { switchLanguage(Locale.ENGLISH) }
    swedishButton.addActionListener { switchLanguage(Locale.forLanguageTag('sv')) }
    updateLanguageButtonBorders()
    languageRow.add(languageLabel)
    languageRow.add(englishButton)
    languageRow.add(swedishButton)
    JPanel settingsRows = new JPanel()
    settingsRows.layout = new BoxLayout(settingsRows, BoxLayout.Y_AXIS)
    settingsRows.add(languageRow)
    settingsRows.add(buildThemeRow())
    settingsRows.add(buildUpdateRow())

    topPanel.add(settingsRows, BorderLayout.NORTH)

    panel.add(topPanel, BorderLayout.NORTH)

    panel
  }

  private void switchLanguage(Locale locale) {
    I18n.instance.setLocale(locale)
    userPreferencesService.setLanguage(locale)
    updateLanguageButtonBorders()
  }

  private JPanel buildThemeRow() {
    JPanel themeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    themeLabel = new JLabel(I18n.instance.getString('settings.label.theme'))
    themeSystemButton = new JRadioButton(I18n.instance.getString('settings.theme.system'))
    themeLightButton = new JRadioButton(I18n.instance.getString('settings.theme.light'))
    themeDarkButton = new JRadioButton(I18n.instance.getString('settings.theme.dark'))
    ButtonGroup themeGroup = new ButtonGroup()
    themeGroup.add(themeSystemButton)
    themeGroup.add(themeLightButton)
    themeGroup.add(themeDarkButton)
    selectThemeButton(userPreferencesService.getTheme())
    themeSystemButton.addActionListener { switchTheme(ThemeMode.SYSTEM) }
    themeLightButton.addActionListener { switchTheme(ThemeMode.LIGHT) }
    themeDarkButton.addActionListener { switchTheme(ThemeMode.DARK) }
    themeRow.add(themeLabel)
    themeRow.add(themeSystemButton)
    themeRow.add(themeLightButton)
    themeRow.add(themeDarkButton)
    themeRow
  }

  private JPanel buildUpdateRow() {
    JPanel updateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    automaticUpdateCheckBox = new JCheckBox(I18n.instance.getString('settings.label.automaticUpdateCheck'))
    automaticUpdateCheckBox.selected = userPreferencesService.isAutomaticUpdateCheckEnabled()
    automaticUpdateCheckBox.addActionListener { toggleAutomaticUpdateCheck() }
    updateRow.add(automaticUpdateCheckBox)
    updateRow
  }

  private void switchTheme(ThemeMode mode) {
    userPreferencesService.setTheme(mode)
    ThemeApplier.applyAndUpdateUI(mode)
    setStatus(I18n.instance.getString('settings.status.themeChanged'))
  }

  private void toggleAutomaticUpdateCheck() {
    boolean enabled = automaticUpdateCheckBox.selected
    userPreferencesService.setAutomaticUpdateCheckEnabled(enabled)
    if (!enabled) {
      pendingUpdate = null
      updateNotificationButton.visible = false
      setStatus(I18n.instance.getString('settings.status.automaticUpdateCheckDisabled'))
      return
    }
    setStatus(I18n.instance.getString('settings.status.automaticUpdateCheckEnabled'))
    checkForUpdateInBackground()
  }

  private void selectThemeButton(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.LIGHT:
        themeLightButton.selected = true
        break
      case ThemeMode.DARK:
        themeDarkButton.selected = true
        break
      default:
        themeSystemButton.selected = true
        break
    }
  }

  private void updateLanguageButtonBorders() {
    boolean isSwedish = I18n.instance.locale.language == 'sv'
    swedishButton.border = isSwedish ?
        BorderFactory.createLoweredBevelBorder() : BorderFactory.createRaisedBevelBorder()
    englishButton.border = isSwedish ?
        BorderFactory.createRaisedBevelBorder() : BorderFactory.createLoweredBevelBorder()
  }

  private static ImageIcon loadFlagIcon(String path) {
    URL resource = MainFrame.getResource(path)
    resource != null ? new ImageIcon(resource) : null
  }

  private JPanel buildStatusBar() {
    JPanel statusBar = new JPanel(new BorderLayout())
    statusBar.border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
        BorderFactory.createEmptyBorder(4, 8, 4, 8)
    )

    JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    companyLabel = new JLabel(I18n.instance.getString('mainFrame.label.activeCompany'))
    companyComboBox = new JComboBox<>()
    reloadCompanyComboBox()
    companyComboBox.addActionListener { onCompanyComboBoxChanged() }
    leftPanel.add(companyLabel)
    leftPanel.add(companyComboBox)

    fiscalYearLabel = new JLabel(I18n.instance.getString('mainFrame.label.fiscalYear'))
    fiscalYearComboBox = new JComboBox<>()
    reloadFiscalYearComboBox()
    fiscalYearComboBox.addActionListener { onFiscalYearComboBoxChanged() }
    leftPanel.add(fiscalYearLabel)
    leftPanel.add(fiscalYearComboBox)

    statusLabel = new JLabel(I18n.instance.getString('mainFrame.status.ready'))
    statusLabel.border = BorderFactory.createEmptyBorder(0, 12, 0, 0)

    updateNotificationButton = new JButton(I18n.instance.getString('mainFrame.button.updateAvailable'))
    updateNotificationButton.visible = false
    updateNotificationButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    updateNotificationButton.borderPainted = false
    updateNotificationButton.contentAreaFilled = false
    updateNotificationButton.foreground = new Color(22, 101, 52)
    updateNotificationButton.addActionListener { showUpdateDialog() }

    statusBar.add(leftPanel, BorderLayout.WEST)
    statusBar.add(statusLabel, BorderLayout.CENTER)
    statusBar.add(updateNotificationButton, BorderLayout.EAST)
    statusBar
  }

  private void reloadCompanyComboBox() {
    List<Company> companies = companyService.listCompanies(true)
    Company selected = companyComboBox.selectedItem as Company
    java.awt.event.ActionListener[] listeners = companyComboBox.actionListeners
    listeners.each { companyComboBox.removeActionListener(it) }
    try {
      companyComboBox.removeAllItems()
      companies.each { Company c -> companyComboBox.addItem(c) }
      if (selected != null) {
        Company match = companies.find { Company c -> c.id == selected.id }
        if (match != null) {
          companyComboBox.selectedItem = match
        }
      } else if (!companies.isEmpty()) {
        companyComboBox.selectedIndex = 0
      }
    } finally {
      listeners.each { companyComboBox.addActionListener(it) }
    }
  }

  private void selectCompanyInComboBox(Long companyId) {
    for (int index = 0; index < companyComboBox.itemCount; index++) {
      Company c = companyComboBox.getItemAt(index) as Company
      if (c?.id == companyId) {
        companyComboBox.selectedItem = c
        return
      }
    }
  }

  private void onCompanyComboBoxChanged() {
    Company selected = companyComboBox.selectedItem as Company
    if (selected != null && selected.id != activeCompanyManager.companyId) {
      activeCompanyManager.companyId = selected.id
    }
  }

  private void reloadFiscalYearComboBox() {
    if (fiscalYearComboBox == null) {
      return
    }
    FiscalYear selected = fiscalYearComboBox.selectedItem as FiscalYear
    java.awt.event.ActionListener[] listeners = fiscalYearComboBox.actionListeners
    listeners.each { fiscalYearComboBox.removeActionListener(it) }
    try {
      fiscalYearComboBox.removeAllItems()
      activeCompanyManager.listFiscalYears().each { FiscalYear fy ->
        fiscalYearComboBox.addItem(fy)
      }
      if (selected != null) {
        for (int i = 0; i < fiscalYearComboBox.itemCount; i++) {
          FiscalYear item = fiscalYearComboBox.getItemAt(i)
          if (item.id == selected.id) {
            fiscalYearComboBox.selectedItem = item
            break
          }
        }
      } else if (fiscalYearComboBox.itemCount > 0) {
        fiscalYearComboBox.selectedIndex = 0
      }
    } finally {
      listeners.each { fiscalYearComboBox.addActionListener(it) }
    }
  }

  private void onFiscalYearComboBoxChanged() {
    FiscalYear selected = fiscalYearComboBox.selectedItem as FiscalYear
    if (selected != null) {
      activeCompanyManager.fiscalYear = selected
      maybePromptForOpeningBalanceRefresh(selected)
    }
  }

  private void maybePromptForOpeningBalanceRefresh(FiscalYear fiscalYear) {
    if (fiscalYear == null) {
      return
    }
    List<OpeningBalanceService.OpeningBalanceDrift> drift = openingBalanceService.detectDrift(fiscalYear.id)
    if (drift.isEmpty()) {
      activeCompanyManager.clearOpeningBalanceRefreshPrompt(fiscalYear.id)
      return
    }
    if (!activeCompanyManager.markOpeningBalanceRefreshPrompted(fiscalYear.id)) {
      return
    }
    FiscalYear sourceFiscalYear = openingBalanceService.findAutoManagedSourceFiscalYear(fiscalYear.id)
    String sourceName = sourceFiscalYear?.name ?: I18n.instance.getString('fiscalYearPanel.label.previousFiscalYearFallback')
    String promptKey = openingBalanceService.hasVoucherActivity(fiscalYear.id)
        ? 'fiscalYearPanel.confirm.refreshOpeningBalancesWithVouchers'
        : 'fiscalYearPanel.confirm.refreshOpeningBalances'
    int choice = JOptionPane.showConfirmDialog(
        frame,
        I18n.instance.format(promptKey, drift.size() as Object, sourceName, fiscalYear.name),
        I18n.instance.getString('fiscalYearPanel.confirm.refreshTitle'),
        JOptionPane.YES_NO_OPTION
    )
    if (choice != JOptionPane.YES_OPTION) {
      setStatus(I18n.instance.format('fiscalYearPanel.message.openingBalanceDriftDetected', drift.size() as Object, fiscalYear.name))
      return
    }
    int refreshed = openingBalanceService.refreshTransferredBalances(fiscalYear.id)
    setStatus(I18n.instance.format('fiscalYearPanel.message.openingBalancesRefreshed', refreshed as Object, fiscalYear.name))
  }

  private JPanel buildOverviewPanel() {
    overviewPanel = new OverviewPanel(
        voucherService,
        accountingPeriodService,
        backupService,
        startupVerificationService,
        activeCompanyManager,
        { showEditCompanyDialog() } as Runnable
    )
    overviewPanel
  }

  private List<Map<String, Object>> buildMainTabs() {
    [
        [title: I18n.instance.getString('mainFrame.tab.overview'), component: buildOverviewPanel()],
        [title: I18n.instance.getString('mainFrame.tab.vouchers'), component: new VoucherPanel(voucherService, accountService, accountingPeriodService, attachmentService, auditLogService, activeCompanyManager)],
        [title: I18n.instance.getString('mainFrame.tab.vat'), component: new VatPeriodPanel(vatService, fiscalYearService, activeCompanyManager)],
        [title: I18n.instance.getString('mainFrame.tab.reports'), component: new ReportPanel(
            reportDataService,
            journoReportService,
            reportExportService,
            reportArchiveService,
            fiscalYearService,
            accountingPeriodService,
            voucherService,
            activeCompanyManager
        )],
        [title: I18n.instance.getString('mainFrame.tab.chartOfAccounts'), component: new ChartOfAccountsPanel(accountService, chartOfAccountsImportService, activeCompanyManager)],
        [title: I18n.instance.getString('mainFrame.tab.fiscalYears'), component: new FiscalYearPanel(fiscalYearService, accountingPeriodService, closingService, openingBalanceService, fiscalYearDeletionService, activeCompanyManager)],
        [title: I18n.instance.getString('mainFrame.tab.system'), component: new SystemDocumentationPanel(
            systemDocumentationService,
            diagnosticsService,
            backupService,
            userManualService
        )],
        [title: I18n.instance.getString('mainFrame.tab.settings'), component: buildSettingsPanel()]
    ]
  }

  private void exitRequested() {
    LoggingConfigurer.shutdown()
    frame.dispose()
  }

  private void showAboutDialog() {
    ImageIcon icon = loadIcon('/icons/logo64.png')
    String version = MainFrame.package?.implementationVersion ?: I18n.instance.getString('mainFrame.about.versionDefault')
    JOptionPane.showMessageDialog(
        frame,
        I18n.instance.format('mainFrame.about.message', version),
        I18n.instance.getString('mainFrame.about.title'),
        JOptionPane.INFORMATION_MESSAGE,
        icon
    )
  }

  private void showCompanySettingsDialog() {
    CompanySettingsDialog.showDialog(frame, companySettingsService, {
      reloadCompanyComboBox()
      setStatus(I18n.instance.getString('mainFrame.status.companySaved'))
    } as Runnable)
  }

  private void showNewCompanyDialog() {
    CompanyDialog.showDialog(frame, companyService, null, { Company saved ->
      reloadCompanyComboBox()
      selectCompanyInComboBox(saved.id)
    } as java.util.function.Consumer<Company>)
  }

  private void showEditCompanyDialog() {
    Company active = activeCompanyManager.activeCompany
    if (active == null) {
      setStatus(I18n.instance.getString('mainFrame.companySettings.noProfile'))
      return
    }
    CompanyDialog.showDialog(frame, companyService, active, { Company saved ->
      reloadCompanyComboBox()
      refreshTitle()
      overviewPanel.reload()
    } as java.util.function.Consumer<Company>)
  }

  private void showSieExchangeDialog() {
    long openedForCompanyId = activeCompanyManager.companyId
    SieExchangeDialog.showDialog(frame, sieImportExportService, fiscalYearService, companyService, openedForCompanyId, { Long targetCompanyId ->
      if (targetCompanyId != openedForCompanyId) {
        reloadCompanyComboBox()
        selectCompanyInComboBox(targetCompanyId)
      } else {
        reloadFiscalYearComboBox()
        activeCompanyManager.refreshFiscalYear()
      }
    } as java.util.function.Consumer<Long>)
    setStatus(I18n.instance.getString('mainFrame.status.sieExchangeClosed'))
  }

  private void archiveCompanyRequested() {
    if (!activeCompanyManager.hasActiveCompany()) {
      setStatus(I18n.instance.getString('mainFrame.error.noActiveCompany'))
      return
    }
    Company active = activeCompanyManager.activeCompany
    int choice = JOptionPane.showConfirmDialog(
        frame,
        I18n.instance.format('mainFrame.confirm.archiveCompany', active.companyName),
        I18n.instance.getString('mainFrame.confirm.archiveTitle'),
        JOptionPane.YES_NO_OPTION
    )
    if (choice != JOptionPane.YES_OPTION) {
      return
    }
    try {
      companyService.archiveCompany(active.id)
      reloadCompanyComboBox()
      setStatus(I18n.instance.format('mainFrame.status.companyArchived', active.companyName))
    } catch (Exception exception) {
      setStatus(exception.message)
    }
  }

  private void showUnarchiveCompanyDialog() {
    List<Company> archived = companyService.listArchivedCompanies()
    if (archived.isEmpty()) {
      JOptionPane.showMessageDialog(
          frame,
          I18n.instance.getString('unarchiveDialog.noArchivedCompanies'),
          I18n.instance.getString('unarchiveDialog.title'),
          JOptionPane.INFORMATION_MESSAGE
      )
      return
    }
    Company selected = JOptionPane.showInputDialog(
        frame,
        I18n.instance.getString('unarchiveDialog.label.selectCompany'),
        I18n.instance.getString('unarchiveDialog.title'),
        JOptionPane.PLAIN_MESSAGE,
        null,
        archived.toArray(),
        archived.first()
    ) as Company
    if (selected == null) {
      return
    }
    try {
      companyService.unarchiveCompany(selected.id)
      reloadCompanyComboBox()
      selectCompanyInComboBox(selected.id)
      setStatus(I18n.instance.format('mainFrame.status.companyUnarchived', selected.companyName))
    } catch (Exception exception) {
      setStatus(exception.message)
    }
  }

  private void deleteCompanyRequested() {
    if (!activeCompanyManager.hasActiveCompany()) {
      setStatus(I18n.instance.getString('mainFrame.error.noActiveCompany'))
      return
    }
    Company active = activeCompanyManager.activeCompany
    List<FiscalYear> fiscalYears = fiscalYearService.listFiscalYears(active.id)
    if (!fiscalYears.isEmpty()) {
      JOptionPane.showMessageDialog(
          frame,
          I18n.instance.format('mainFrame.error.companyHasFiscalYears', active.companyName, fiscalYears.size() as Object),
          I18n.instance.getString('mainFrame.confirm.deleteTitle'),
          JOptionPane.WARNING_MESSAGE
      )
      return
    }
    int choice = JOptionPane.showConfirmDialog(
        frame,
        I18n.instance.format('mainFrame.confirm.deleteCompany', active.companyName),
        I18n.instance.getString('mainFrame.confirm.deleteTitle'),
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    if (choice != JOptionPane.YES_OPTION) {
      return
    }
    try {
      companyService.deleteCompany(active.id)
      reloadCompanyComboBox()
      setStatus(I18n.instance.format('mainFrame.status.companyDeleted', active.companyName))
    } catch (Exception exception) {
      setStatus(exception.message)
    }
  }

  private void showUserManualDialog() {
    UserManualDialog.showDialog(frame, userManualService)
    setStatus(I18n.instance.getString('mainFrame.status.manualShown'))
  }

  private void showUpdateDialog() {
    UpdateDialog.showDialog(frame)
  }

  private void openIssueTracker() {
    try {
      Desktop.desktop.browse(URI.create('https://github.com/Alipsa/accounting/issues'))
    } catch (Exception exception) {
      log.log(Level.WARNING, 'Could not open issue tracker.', exception)
      JOptionPane.showMessageDialog(
          frame,
          I18n.instance.getString('mainFrame.issueTracker.error'),
          I18n.instance.getString('mainFrame.menu.help.reportIssue'),
          JOptionPane.ERROR_MESSAGE
      )
    }
  }

  private void checkForUpdateInBackground() {
    Thread updateThread = new Thread({
      try {
        UpdateInfo info = updateService.checkForUpdate()
        if (info.updateAvailable) {
          pendingUpdate = info
          SwingUtilities.invokeLater {
            updateNotificationButton.text = I18n.instance.format(
                'mainFrame.button.updateVersion', info.availableVersion)
            updateNotificationButton.visible = true
          }
        }
      } catch (Exception exception) {
        log.log(Level.FINE, 'Background update check failed (offline or unreachable).', exception)
      }
    } as Runnable, 'update-check')
    updateThread.daemon = true
    updateThread.priority = Thread.MIN_PRIORITY
    updateThread.start()
  }

  private void applyIcons() {
    List<Image> icons = []
    ICON_PATHS.each { String path ->
      ImageIcon icon = loadIcon(path)
      if (icon != null) {
        icons << icon.image
      }
    }
    if (icons) {
      frame.iconImages = icons
      applyTaskbarIcon(icons)
    } else {
      log.fine('No application icons were available on the classpath.')
    }
  }

  private void applyTaskbarIcon(List<Image> icons) {
    try {
      if (!Taskbar.isTaskbarSupported()) {
        return
      }
      Taskbar taskbar = Taskbar.getTaskbar()
      if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        return
      }
      ImageIcon taskbarIcon = loadIcon(TASKBAR_ICON_PATH)
      taskbar.setIconImage(taskbarIcon?.image ?: icons.last())
    } catch (UnsupportedOperationException | SecurityException exception) {
      log.fine("Taskbar icon not supported on this platform: ${exception.message}")
    }
  }

  private static ImageIcon loadIcon(String path) {
    InputStream stream = MainFrame.getResourceAsStream(path)
    if (stream == null) {
      return null
    }
    stream.withCloseable { InputStream input ->
      new ImageIcon(ImageIO.read(input))
    }
  }

  private static List<Integer> scaledWindowSize(int baseWidth, int baseHeight) {
    float scale = 1.0f
    try {
      scale = UIScale.getUserScaleFactor()
    } catch (Exception ex) {
      log.warning("FlatLaf UIScale unavailable, using unscaled size: ${ex.message}")
    }
    [
        (int) (baseWidth * scale),
        (int) (baseHeight * scale)
    ]
  }

}
