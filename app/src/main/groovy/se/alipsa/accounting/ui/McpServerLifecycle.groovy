package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.mcp.AccountingMcpTools
import se.alipsa.accounting.mcp.LoopbackMcpServer
import se.alipsa.accounting.mcp.McpDispatcher
import se.alipsa.accounting.mcp.McpUiGuard
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
  private LoopbackMcpServer server

  McpServerLifecycle(
      UserPreferencesService userPreferencesService,
      ActiveCompanyManager activeCompanyManager,
      VoucherPanel voucherPanel,
      McpSettingsSection settingsSection,
      JPanel glassPane
  ) {
    this.userPreferencesService = userPreferencesService
    this.activeCompanyManager = activeCompanyManager
    this.voucherPanel = voucherPanel
    this.settingsSection = settingsSection
    this.glassPane = glassPane
  }

  void start() {
    try {
      AccountingMcpTools tools = new AccountingMcpTools()
      tools.setVoucherDraftAccess(voucherPanel.mcpVoucherDraftAccess)
      tools.setActiveContextProvider {
        Map<String, Object> context = [:]
        Company company = activeCompanyManager.activeCompany
        FiscalYear fiscalYear = activeCompanyManager.fiscalYear
        if (company == null || fiscalYear == null) {
          context.ok = false
          context.error = 'No active company or fiscal year is selected.'
          return context
        }
        context.ok = true
        context.company_id = company.id
        context.company_name = company.companyName
        context.fiscal_year_id = fiscalYear.id
        context.fiscal_year_name = fiscalYear.name
        context.fiscal_year_start = fiscalYear.startDate?.toString()
        context.fiscal_year_end = fiscalYear.endDate?.toString()
        context.fiscal_year_closed = fiscalYear.closed
        context
      }
      server = new LoopbackMcpServer(userPreferencesService, new McpDispatcher(tools), uiGuard())
      server.start()
      settingsSection.setRunning()
      log.info("Local MCP server available at ${LoopbackMcpServer.ENDPOINT}")
    } catch (Exception exception) {
      log.warning("Could not start local MCP server: ${exception.message}")
      settingsSection.setUnavailable(exception.message)
    }
  }

  @Override
  void close() {
    server?.close()
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
