package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.mcp.AccountingMcpTools
import se.alipsa.accounting.mcp.LoopbackMcpServer
import se.alipsa.accounting.mcp.McpDispatcher
import se.alipsa.accounting.mcp.McpUiGuard
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.PurgeResult
import se.alipsa.accounting.service.UserPreferencesService

import java.util.logging.Logger

import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Starts and stops the local MCP server and coordinates its write-operation UI guard. */
final class McpServerLifecycle implements Closeable {

  private static final Logger log = Logger.getLogger(McpServerLifecycle.name)

  private final UserPreferencesService userPreferencesService
  private final ActiveCompanyManager activeCompanyManager
  private final VoucherPanel voucherPanel
  private final McpSettingsSection settingsSection
  private final JPanel glassPane
  private final AiWorkspaceService aiWorkspaceService
  private final AiAssistantLauncherSection aiLauncherSection
  private final FiscalYearService fiscalYearService
  private LoopbackMcpServer server

  McpServerLifecycle(
      UserPreferencesService userPreferencesService,
      ActiveCompanyManager activeCompanyManager,
      VoucherPanel voucherPanel,
      McpSettingsSection settingsSection,
      JPanel glassPane,
      AiWorkspaceService aiWorkspaceService,
      AiAssistantLauncherSection aiLauncherSection,
      FiscalYearService fiscalYearService
  ) {
    this.userPreferencesService = userPreferencesService
    this.activeCompanyManager = activeCompanyManager
    this.voucherPanel = voucherPanel
    this.settingsSection = settingsSection
    this.glassPane = glassPane
    this.aiWorkspaceService = aiWorkspaceService
    this.aiLauncherSection = aiLauncherSection
    this.fiscalYearService = fiscalYearService
  }

  void start() {
    try {
      PurgeResult startupPurge = aiWorkspaceService.purgeAllSecrets()
      if (!startupPurge.complete) {
        log.warning("Could not remove stale AI workspace secrets at startup: ${startupPurge.failed}")
      }
      AccountingMcpTools tools = new AccountingMcpTools()
      tools.setVoucherDraftAccess(voucherPanel.mcpVoucherDraftAccess)
      tools.setActiveContextProvider {
        buildActiveContext(fiscalYearService, activeCompanyManager.activeCompany, activeCompanyManager.fiscalYear)
      }
      server = new LoopbackMcpServer(userPreferencesService, new McpDispatcher(tools), uiGuard())
      server.start()
      settingsSection.setRunning()
      aiLauncherSection.setMcpAvailable(true)
      log.info("Local MCP server available at ${LoopbackMcpServer.ENDPOINT}")
    } catch (Exception exception) {
      log.warning("Could not start local MCP server: ${exception.message}")
      settingsSection.setUnavailable(exception.message)
      aiLauncherSection.setMcpAvailable(false)
    }
  }

  @Override
  void close() {
    server?.close()
    try {
      aiWorkspaceService.purgeAllSecrets()
    } catch (Exception exception) {
      log.warning("Could not purge AI workspace secrets on shutdown: ${exception.message}")
    }
  }

  // Re-reads the fiscal year fresh by id rather than trusting cachedFiscalYear's own fields:
  // MCP write tools (e.g. close_fiscal_year) mutate the database directly through backend
  // services and never touch ActiveCompanyManager's cached object, so cachedFiscalYear.closed
  // can be stale until the user reselects the year in the desktop UI.
  @PackageScope
  static Map<String, Object> buildActiveContext(
      FiscalYearService fiscalYearService, Company company, FiscalYear cachedFiscalYear
  ) {
    if (company == null || cachedFiscalYear == null) {
      return [ok: false, error: 'No active company or fiscal year is selected.']
    }
    FiscalYear fiscalYear = fiscalYearService.findById(cachedFiscalYear.id) ?: cachedFiscalYear
    [
        ok: true,
        company_id: company.id,
        company_name: company.companyName,
        fiscal_year_id: fiscalYear.id,
        fiscal_year_name: fiscalYear.name,
        fiscal_year_start: fiscalYear.startDate?.toString(),
        fiscal_year_end: fiscalYear.endDate?.toString(),
        fiscal_year_closed: fiscalYear.closed
    ]
  }

  private McpUiGuard uiGuard() {
    new McpUiGuard() {
      @Override
      void beginWrite() {
        SwingUtilities.invokeAndWait { glassPane.visible = true }
      }

      @Override
      void endWrite() {
        SwingUtilities.invokeAndWait { glassPane.visible = false }
      }
    }
  }
}
