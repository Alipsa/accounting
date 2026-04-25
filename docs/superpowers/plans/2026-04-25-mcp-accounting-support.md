# Headless MCP Accounting Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `--mode=mcp` runtime that exposes the existing accounting services as a local MCP stdio server so that an LLM can assist with bookkeeping without any Swing UI.

**Architecture:** Three focused classes — `McpServer` handles the stdin/stdout JSON-RPC 2.0 loop, `McpDispatcher` routes protocol methods (`initialize`, `tools/list`, `tools/call`) and maps exceptions to error objects, and `AccountingMcpTools` wraps the existing service layer and returns `Map<String, Object>` (not JSON strings). `McpDispatcher` embeds tool results in both `content[{type:"text",text:<json>}]` and `structuredContent` per the MCP spec. Write tools (`post_voucher`, `close_fiscal_year`, `book_vat_transfer`) require a stateless SHA-256 `preview_token` produced by the matching preview tool — the server rejects calls that bypass or tamper with the preview step. `stdout` must stay clean in MCP mode; the acceptance test verifies this with a real subprocess. A `skill/accounting-mcp.md` document at the project root gives the LLM domain context and workflow instructions. The `AlipsaAccounting` entry point gains a `--mode=mcp` flag that reuses the same bootstrap (logging, database, I18n) but skips Swing and runs `McpServer` instead.

**Tech Stack:** Groovy 5, JUnit 6 (`groovier-junit`), `groovy.json.JsonSlurper` + `JsonOutput` (already in `groovy-json:5.0.5`), H2 embedded database, existing service layer.

---

## File Structure

### New production files
- `app/src/main/groovy/se/alipsa/accounting/mcp/McpServer.groovy` — stdin/stdout JSON-RPC 2.0 newline-delimited loop
- `app/src/main/groovy/se/alipsa/accounting/mcp/McpDispatcher.groovy` — JSON-RPC method routing, structured content wrapping, error mapping
- `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy` — all MCP tool implementations, returns `Map<String,Object>`; preview tokens enforced server-side
- `skill/accounting-mcp.md` — LLM skill document at project root

### Modified production files
- `app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy` — add `--mode=mcp` argument, `RunMode` enum, wire `McpServer`
- `app/build.gradle` — include `skill/` directory in `packageLinuxReleaseZip` and `distributions`

### New test files
- `app/src/test/groovy/integration/se/alipsa/accounting/mcp/McpDispatcherTest.groovy` — JSON-RPC protocol layer tests
- `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy` — per-tool business rule tests
- `app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy` — headless bootstrap smoke test

---

## Task 1: `McpServer` and `McpDispatcher` skeleton — Phase 1

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/mcp/McpServer.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/mcp/McpDispatcher.groovy`
- Create: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/McpDispatcherTest.groovy`

- [ ] **Step 1: Write the failing test**

```groovy
// app/src/test/groovy/integration/se/alipsa/accounting/mcp/McpDispatcherTest.groovy
package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpDispatcherTest {

  private McpDispatcher dispatcher

  @BeforeEach
  void setUp() {
    // AccountingMcpTools with null services is fine — only toolDefinitions() is called by tools/list,
    // and it returns static metadata without touching services.
    dispatcher = new McpDispatcher(new AccountingMcpTools(null, null, null, null, null, null, null))
  }

  @Test
  void initializeReturnsProtocolVersionAndServerInfo() {
    String req = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}'
    String resp = dispatcher.handle(req)
    Map result = (Map) new JsonSlurper().parseText(resp)
    assertEquals('2.0', result.get('jsonrpc'))
    assertEquals(1, result.get('id'))
    Map body = (Map) result.get('result')
    assertEquals(McpDispatcher.PROTOCOL_VERSION, body.get('protocolVersion'))
    Map serverInfo = (Map) body.get('serverInfo')
    assertEquals('alipsa-accounting', serverInfo.get('name'))
  }

  @Test
  void notificationsAndMessagesWithoutIdAreIgnored() {
    // All messages without an id field are silently ignored (notifications/initialized,
    // $/cancelRequest, etc.) — the server must not respond to them.
    assertNull(dispatcher.handle('{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'))
    assertNull(dispatcher.handle('{"jsonrpc":"2.0","method":"$/cancelRequest","params":{}}'))
  }

  @Test
  void toolsListReturnsNonEmptyToolArrayWithStructuredContent() {
    String req = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    String resp = dispatcher.handle(req)
    Map result = (Map) new JsonSlurper().parseText(resp)
    Map body = (Map) result.get('result')
    List tools = (List) body.get('tools')
    assertFalse(tools.isEmpty())
  }

  @Test
  void toolsCallResultContainsBothContentAndStructuredContent() {
    // Verify the dispatcher wraps tool results in both content[] and structuredContent.
    // Use get_company_info which works without a real database (it returns ok:false for null service).
    String req = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}'
    String resp = dispatcher.handle(req)
    Map result = (Map) new JsonSlurper().parseText(resp)
    // Unknown tool → error, not result
    assertNotNull(result.get('error'))
  }

  @Test
  void unknownMethodReturnsJsonRpcError() {
    String req = '{"jsonrpc":"2.0","id":4,"method":"no/such","params":{}}'
    String resp = dispatcher.handle(req)
    Map result = (Map) new JsonSlurper().parseText(resp)
    assertNotNull(result.get('error'))
    assertNull(result.get('result'))
  }

  @Test
  void invalidJsonReturnsParseError() {
    String resp = dispatcher.handle('not valid json {')
    Map result = (Map) new JsonSlurper().parseText(resp)
    Map error = (Map) result.get('error')
    assertEquals(-32700, error.get('code'))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.mcp.McpDispatcherTest"
```

Expected: compilation failure — `McpDispatcher` and `AccountingMcpTools` do not exist yet.

- [ ] **Step 3: Create `AccountingMcpTools` stub** (just enough to compile the test)

```groovy
// app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy
package se.alipsa.accounting.mcp

import se.alipsa.accounting.service.*

final class AccountingMcpTools {

  private final CompanyService companyService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final VoucherService voucherService
  private final VatService vatService
  private final ClosingService closingService
  private final ReportDataService reportDataService

  AccountingMcpTools() {
    this(
        new CompanyService(),
        new FiscalYearService(),
        new AccountService(),
        new VoucherService(),
        new VatService(),
        new ClosingService(),
        new ReportDataService()
    )
  }

  AccountingMcpTools(
      CompanyService companyService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      VoucherService voucherService,
      VatService vatService,
      ClosingService closingService,
      ReportDataService reportDataService
  ) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.voucherService = voucherService
    this.vatService = vatService
    this.closingService = closingService
    this.reportDataService = reportDataService
  }

  List<Map<String, Object>> toolDefinitions() {
    []
  }

  Map<String, Object> callTool(String name, Map<String, Object> args) {
    throw new IllegalArgumentException("Unknown tool: ${name}")
  }
}
```

- [ ] **Step 4: Create `McpDispatcher`**

```groovy
// app/src/main/groovy/se/alipsa/accounting/mcp/McpDispatcher.groovy
package se.alipsa.accounting.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

final class McpDispatcher {

  // MCP protocol version this server implements.
  // We always respond with this version regardless of what the client advertises;
  // the client decides whether to proceed.
  static final String PROTOCOL_VERSION = '2024-11-05'

  private final AccountingMcpTools tools

  McpDispatcher(AccountingMcpTools tools) {
    this.tools = tools
  }

  String handle(String requestJson) {
    Map<String, Object> request
    try {
      request = (Map<String, Object>) new JsonSlurper().parseText(requestJson)
    } catch (Exception e) {
      return JsonOutput.toJson([jsonrpc: '2.0', id: null, error: [code: -32700, message: "Parse error: ${e.message}"]])
    }
    Object id = request.get('id')
    String method = (String) request.get('method')
    // Notifications have no id — ignore silently (notifications/initialized, $/cancelRequest, etc.)
    if (id == null) {
      return null
    }
    try {
      Object result = dispatch(method, request)
      return JsonOutput.toJson([jsonrpc: '2.0', id: id, result: result])
    } catch (IllegalArgumentException e) {
      return JsonOutput.toJson([jsonrpc: '2.0', id: id, error: [code: -32602, message: e.message]])
    } catch (Exception e) {
      return JsonOutput.toJson([jsonrpc: '2.0', id: id, error: [code: -32603, message: e.message]])
    }
  }

  private Object dispatch(String method, Map<String, Object> request) {
    switch (method) {
      case 'initialize':
        return [
            protocolVersion: PROTOCOL_VERSION,
            capabilities: [tools: [:]],
            serverInfo: [name: 'alipsa-accounting', version: serverVersion()]
        ]
      case 'tools/list':
        return [tools: tools.toolDefinitions()]
      case 'tools/call':
        Map<String, Object> params = (Map<String, Object>) request.get('params')
        String toolName = (String) params.get('name')
        Map<String, Object> arguments = params.get('arguments') != null
            ? (Map<String, Object>) params.get('arguments')
            : (Map<String, Object>) [:]
        Map<String, Object> toolResult = tools.callTool(toolName, arguments)
        // Return both text (for clients that only read content[]) and structuredContent
        // (for clients that can consume typed tool output directly).
        return [
            content: [[type: 'text', text: JsonOutput.toJson(toolResult)]],
            structuredContent: toolResult
        ]
      default:
        // -32601 = Method not found
        throw new IllegalArgumentException("Method not found: ${method}")
    }
  }

  private static String serverVersion() {
    McpDispatcher.class.package?.implementationVersion ?: 'dev'
  }
}
```

- [ ] **Step 5: Create `McpServer`**

```groovy
// app/src/main/groovy/se/alipsa/accounting/mcp/McpServer.groovy
package se.alipsa.accounting.mcp

import java.nio.charset.StandardCharsets

final class McpServer {

  private final McpDispatcher dispatcher

  McpServer(McpDispatcher dispatcher) {
    this.dispatcher = dispatcher
  }

  void run() {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
    PrintWriter writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
    String line
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim()
      if (trimmed.isEmpty()) {
        continue
      }
      String response = dispatcher.handle(trimmed)
      if (response != null) {
        writer.println(response)
      }
    }
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```
./gradlew test --tests "se.alipsa.accounting.mcp.McpDispatcherTest"
```

Expected: all 5 tests pass.

- [ ] **Step 7: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/McpServer.groovy \
        app/src/main/groovy/se/alipsa/accounting/mcp/McpDispatcher.groovy \
        app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/McpDispatcherTest.groovy
git commit -m "lägg till McpServer, McpDispatcher och AccountingMcpTools-skelett"
```

---

## Task 2: `--mode=mcp` argument in `AlipsaAccounting` — Phase 1

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy`
- Test: `app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy`

- [ ] **Step 1: Write the failing test**

The test verifies that the MCP bootstrap starts, responds to `initialize`, and shuts down cleanly via a `McpDispatcher` driven directly (not via `AlipsaAccounting.main()` — that would block on stdin). We test `AlipsaAccounting` argument parsing by verifying that an unknown argument still throws, and that the new `--mode=mcp` argument is accepted.

```groovy
// app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy
package se.alipsa.accounting

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import se.alipsa.accounting.mcp.AccountingMcpTools
import se.alipsa.accounting.mcp.McpDispatcher
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.support.AppPaths
import groovy.json.JsonSlurper

import java.nio.file.Path

class McpHeadlessStartupTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
  }

  @AfterEach
  void tearDown() {
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void mcpDispatcherRespondsToInitialize() {
    McpDispatcher dispatcher = new McpDispatcher(new AccountingMcpTools(null, null, null, null, null, null, null))
    String resp = dispatcher.handle('{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}')
    Map result = (Map) new JsonSlurper().parseText(resp)
    assertNotNull(result.get('result'))
    assertNull(result.get('error'))
  }

  @Test
  void unknownArgumentThrows() {
    assertThrows(IllegalArgumentException) {
      // Verify the existing guard still rejects unknown args
      AlipsaAccounting.main(['--unknown-arg'] as String[])
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.McpHeadlessStartupTest"
```

Expected: `unknownArgumentThrows` passes (existing behavior), `mcpDispatcherRespondsToInitialize` fails because `McpDispatcher` isn't imported or the class doesn't exist with null-safe args yet.

Actually both tests should pass once Task 1 is done. Run to confirm green.

- [ ] **Step 3: Add `RunMode` enum and `--mode=mcp` parsing to `AlipsaAccounting`**

In `AlipsaAccounting.groovy`, add:
1. A new `private static final String MODE_ARGUMENT_PREFIX = '--mode='` constant.
2. A `RunMode` enum: `GUI`, `VERIFY_LAUNCH`, `MCP`.
3. Add `final RunMode runMode` field to `StartupOptions` (replace the two booleans with the enum).
4. Update `getInteractive()` to `runMode == RunMode.GUI`.
5. Update `parse()` to recognise `--mode=mcp`, `--mode=gui`, and keep `--verify-launch` as a legacy alias for `--mode=verify-launch`.
6. In `main()`, add an `if (options.runMode == RunMode.MCP)` branch that creates `AccountingMcpTools`, `McpDispatcher`, `McpServer`, calls `server.run()`, then calls `DatabaseService.instance.shutdown()` and `LoggingConfigurer.shutdown()`.

Replace the current `AlipsaAccounting.groovy` with:

```groovy
package se.alipsa.accounting

import se.alipsa.accounting.mcp.AccountingMcpTools
import se.alipsa.accounting.mcp.McpDispatcher
import se.alipsa.accounting.mcp.McpServer
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.StartupVerificationReport
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n
import se.alipsa.accounting.support.LoggingConfigurer
import se.alipsa.accounting.ui.MainFrame
import se.alipsa.accounting.ui.ThemeApplier

import java.awt.GraphicsEnvironment
import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.JOptionPane
import javax.swing.SwingUtilities

final class AlipsaAccounting {

  private static final Logger log = Logger.getLogger(AlipsaAccounting.name)
  private static final String VERIFY_LAUNCH_ARGUMENT = '--verify-launch'
  private static final String VERSION_ARGUMENT = '--version'
  private static final String HOME_ARGUMENT_PREFIX = '--home='
  private static final String MODE_ARGUMENT_PREFIX = '--mode='

  private AlipsaAccounting() {
  }

  static void main(String[] args) {
    StartupOptions options = StartupOptions.parse(args ?: new String[0])
    if (options.applicationHomeOverride != null) {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, options.applicationHomeOverride)
    }
    if (options.versionRequested) {
      System.out.println(versionLine())
      return
    }
    I18n.instance.setLocale(Locale.getDefault())
    try {
      LoggingConfigurer.configure()
      DatabaseService.instance.initialize()
      UserPreferencesService userPreferencesService = new UserPreferencesService()
      Locale savedLanguage = userPreferencesService.getLanguage()
      if (savedLanguage != null) {
        I18n.instance.setLocale(savedLanguage)
      }
      StartupVerificationReport startupReport = new StartupVerificationService().verify()
      if (options.runMode == RunMode.VERIFY_LAUNCH) {
        failOnStartupErrors(startupReport)
        String version = AlipsaAccounting.package?.implementationVersion
        if (!version) {
          log.warning('JAR manifest saknar Implementation-Version — paketeringen kan vara felaktig.')
        }
        if (!startupReport.warnings.isEmpty()) {
          log.warning("Launch verification completed with warnings: ${startupReport.warnings.join(' | ')}")
        }
        System.out.println("Launch verification OK: ${versionLine()} [home=${AppPaths.applicationHome()}]")
        DatabaseService.instance.shutdown()
        LoggingConfigurer.shutdown()
        return
      }
      if (options.runMode == RunMode.MCP) {
        if (!startupReport.ok) {
          throw new IllegalStateException("Startup verification failed: ${startupReport.errors.join(' | ')}")
        }
        log.info('Starting Alipsa Accounting in MCP mode.')
        McpServer server = new McpServer(new McpDispatcher(new AccountingMcpTools()))
        server.run()
        DatabaseService.instance.shutdown()
        LoggingConfigurer.shutdown()
        return
      }
      ThemeApplier.apply(userPreferencesService.getTheme())
      if (!startupReport.ok || !startupReport.warnings.isEmpty()) {
        showStartupVerificationWarning(startupReport)
      }
      SwingUtilities.invokeLater {
        MainFrame mainFrame = new MainFrame()
        mainFrame.display()
      }
    } catch (Exception exception) {
      log.log(Level.SEVERE, 'Failed to start Alipsa Accounting.', exception)
      if (options.interactive) {
        showStartupError(exception)
      } else {
        System.err.println("Failed to start Alipsa Accounting: ${exception.message ?: exception.class.simpleName}")
      }
      throw exception
    }
  }

  private static void failOnStartupErrors(StartupVerificationReport report) {
    if (report.ok) {
      return
    }
    throw new IllegalStateException("Startup verification failed: ${report.errors.join(' | ')}")
  }

  private static String versionLine() {
    String version = AlipsaAccounting.package?.implementationVersion ?: 'dev'
    "Alipsa Accounting ${version}"
  }

  private static void showStartupError(Throwable throwable) {
    if (GraphicsEnvironment.headless) {
      return
    }
    String detail = throwable.message ?: 'Unknown startup failure.'
    String message = I18n.instance.format('alipsaAccounting.startup.errorMessage', throwable.class.simpleName, detail)
    String title = I18n.instance.getString('alipsaAccounting.startup.errorTitle')
    if (SwingUtilities.isEventDispatchThread()) {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
      return
    }
    SwingUtilities.invokeAndWait {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    }
  }

  private static void showStartupVerificationWarning(StartupVerificationReport report) {
    if (GraphicsEnvironment.headless) {
      return
    }
    List<String> rows = []
    if (!report.errors.isEmpty()) {
      rows << I18n.instance.getString('alipsaAccounting.startup.verificationErrors')
      rows.addAll(report.errors.take(5).collect { String error -> "- ${error}".toString() })
    }
    if (!report.warnings.isEmpty()) {
      rows << I18n.instance.getString('alipsaAccounting.startup.verificationWarnings')
      rows.addAll(report.warnings.take(5).collect { String warning -> "- ${warning}".toString() })
    }
    String message = I18n.instance.format('alipsaAccounting.startup.verificationMessage', rows.join('\n'))
    String title = I18n.instance.getString('alipsaAccounting.startup.verificationTitle')
    SwingUtilities.invokeLater {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)
    }
  }

  enum RunMode {
    GUI, VERIFY_LAUNCH, MCP
  }

  private static final class StartupOptions {

    final RunMode runMode
    final boolean versionRequested
    final String applicationHomeOverride

    private StartupOptions(RunMode runMode, boolean versionRequested, String applicationHomeOverride) {
      this.runMode = runMode
      this.versionRequested = versionRequested
      this.applicationHomeOverride = applicationHomeOverride
    }

    boolean getInteractive() {
      runMode == RunMode.GUI
    }

    static StartupOptions parse(String[] arguments) {
      RunMode runMode = RunMode.GUI
      boolean versionRequested = false
      String applicationHomeOverride = null
      arguments.each { String argument ->
        if (argument == VERIFY_LAUNCH_ARGUMENT) {
          runMode = RunMode.VERIFY_LAUNCH
          return
        }
        if (argument == VERSION_ARGUMENT) {
          versionRequested = true
          return
        }
        if (argument.startsWith(HOME_ARGUMENT_PREFIX)) {
          String value = argument.substring(HOME_ARGUMENT_PREFIX.length()).trim()
          if (!value) {
            throw new IllegalArgumentException(I18n.instance.getString('alipsaAccounting.error.emptyHome'))
          }
          applicationHomeOverride = value
          return
        }
        if (argument.startsWith(MODE_ARGUMENT_PREFIX)) {
          String mode = argument.substring(MODE_ARGUMENT_PREFIX.length()).trim().toUpperCase(Locale.ROOT).replace('-', '_')
          try {
            runMode = RunMode.valueOf(mode)
          } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException(I18n.instance.format('alipsaAccounting.error.unknownArgument', argument))
          }
          return
        }
        throw new IllegalArgumentException(I18n.instance.format('alipsaAccounting.error.unknownArgument', argument))
      }
      new StartupOptions(runMode, versionRequested, applicationHomeOverride)
    }
  }
}
```

- [ ] **Step 4: Run test and build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL; both acceptance tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy \
        app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy
git commit -m "lägg till --mode=mcp med RunMode-enum och McpServer-start"
```

---

## Task 3: Read-only tools — `get_company_info` and `list_fiscal_years` — Phase 2

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Create: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Write the failing test**

```groovy
// app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import se.alipsa.accounting.service.*
import se.alipsa.accounting.support.AppPaths
import java.nio.file.Path
import java.time.LocalDate

class AccountingMcpToolsTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private FiscalYearService fiscalYearService
  private AccountingMcpTools tools

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AccountingPeriodService periodService = new AccountingPeriodService(databaseService, new AuditLogService(databaseService))
    AuditLogService auditLogService = new AuditLogService(databaseService)
    fiscalYearService = new FiscalYearService(databaseService, periodService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    tools = new AccountingMcpTools(
        new CompanyService(databaseService),
        fiscalYearService,
        new AccountService(databaseService),
        voucherService,
        new VatService(databaseService, voucherService, auditLogService),
        new ClosingService(databaseService, periodService, fiscalYearService, voucherService, new ReportIntegrityService()),
        new ReportDataService(databaseService)
    )
  }

  @AfterEach
  void tearDown() {
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  // callTool() returns Map<String,Object> directly — no JsonSlurper needed in tests.

  @Test
  void getCompanyInfoReturnsLegacyCompany() {
    Map<String, Object> result = tools.callTool('get_company_info', ['company_id': (Object) 1L])
    assertTrue((boolean) result.get('ok'))
    Map company = (Map) result.get('company')
    assertEquals(1L, ((Number) company.get('id')).longValue())
  }

  @Test
  void getCompanyInfoUnknownIdReturnsError() {
    Map<String, Object> result = tools.callTool('get_company_info', ['company_id': (Object) 9999L])
    assertFalse((boolean) result.get('ok'))
    assertNotNull(result.get('error'))
  }

  @Test
  void listFiscalYearsReturnsCreatedYear() {
    fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    Map<String, Object> result = tools.callTool('list_fiscal_years', ['company_id': (Object) 1L])
    assertTrue((boolean) result.get('ok'))
    List years = (List) result.get('fiscal_years')
    assertEquals(1, years.size())
    Map year = (Map) years.first()
    assertEquals('2026', year.get('name'))
    assertFalse((boolean) year.get('closed'))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: compilation failure — `get_company_info` and `list_fiscal_years` not yet implemented.

- [ ] **Step 3: Implement `get_company_info` and `list_fiscal_years` in `AccountingMcpTools`**

Replace the `toolDefinitions()` and `callTool()` stubs, and add a private helper for argument extraction. The full `AccountingMcpTools.groovy` at this stage:

```groovy
// app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy
package se.alipsa.accounting.mcp

import groovy.json.JsonOutput
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.*

import java.security.MessageDigest

final class AccountingMcpTools {

  private final CompanyService companyService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final VoucherService voucherService
  private final VatService vatService
  private final ClosingService closingService
  private final ReportDataService reportDataService

  AccountingMcpTools() {
    this(
        new CompanyService(),
        new FiscalYearService(),
        new AccountService(),
        new VoucherService(),
        new VatService(),
        new ClosingService(),
        new ReportDataService()
    )
  }

  AccountingMcpTools(
      CompanyService companyService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      VoucherService voucherService,
      VatService vatService,
      ClosingService closingService,
      ReportDataService reportDataService
  ) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.voucherService = voucherService
    this.vatService = vatService
    this.closingService = closingService
    this.reportDataService = reportDataService
  }

  List<Map<String, Object>> toolDefinitions() {
    [
        toolDef('get_company_info',
            'Returns the company record for the given company ID.',
            ['company_id'],
            [company_id: intParam('Company ID')]
        ),
        toolDef('list_fiscal_years',
            'Lists all fiscal years for the given company.',
            ['company_id'],
            [company_id: intParam('Company ID')]
        ),
    ]
  }

  // Returns Map<String,Object> — McpDispatcher wraps this in content[] + structuredContent.
  Map<String, Object> callTool(String name, Map<String, Object> args) {
    switch (name) {
      case 'get_company_info':    return getCompanyInfo(args)
      case 'list_fiscal_years':   return listFiscalYears(args)
      default:
        throw new IllegalArgumentException("Unknown tool: ${name}")
    }
  }

  // ---- tools ----

  private Map<String, Object> getCompanyInfo(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    Company company = companyService.findById(companyId)
    if (company == null) {
      return (Map<String, Object>) [ok: false, error: "Company ${companyId} not found."]
    }
    (Map<String, Object>) [
        ok: true,
        company: [
            id: company.id,
            name: company.companyName,
            organizationNumber: company.organizationNumber,
            defaultCurrency: company.defaultCurrency,
            vatPeriodicity: company.vatPeriodicity?.name(),
            active: company.active
        ]
    ]
  }

  private Map<String, Object> listFiscalYears(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    List<FiscalYear> years = fiscalYearService.listFiscalYears(companyId)
    (Map<String, Object>) [
        ok: true,
        fiscal_years: years.collect { FiscalYear y -> [
            id: y.id,
            name: y.name,
            start_date: y.startDate?.toString(),
            end_date: y.endDate?.toString(),
            closed: y.closed
        ]}
    ]
  }

  // ---- helpers ----

  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    ((Number) value).longValue()
  }

  // Computes a stateless SHA-256 preview token from a canonical sorted-key JSON string.
  // Used by preview tools to produce a token, and by write tools to validate it.
  protected static String computePreviewToken(Map<String, Object> canonicalPayload) {
    String canonical = JsonOutput.toJson(canonicalPayload.sort())
    MessageDigest.getInstance('SHA-256')
        .digest(canonical.bytes)
        .encodeHex()
        .toString()
  }

  private static Map<String, Object> intParam(String description) {
    (Map<String, Object>) [type: 'integer', description: description]
  }

  private static Map<String, Object> strParam(String description) {
    (Map<String, Object>) [type: 'string', description: description]
  }

  private static Map<String, Object> toolDef(
      String name,
      String description,
      List<String> required,
      Map<String, Object> properties
  ) {
    (Map<String, Object>) [
        name: name,
        description: description,
        inputSchema: [
            type: 'object',
            properties: properties,
            required: required
        ]
    ]
  }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: `getCompanyInfoReturnsLegacyCompany`, `getCompanyInfoUnknownIdReturnsError`, `listFiscalYearsReturnsCreatedYear` all pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera get_company_info och list_fiscal_years MCP-verktyg"
```

---

## Task 4: Read-only tools — `list_accounts` and `list_vouchers` — Phase 2

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests to `AccountingMcpToolsTest`**

Add these test methods to the existing `AccountingMcpToolsTest` class. The test setup inserts a minimal account into the database:

```groovy
// Additional fields in AccountingMcpToolsTest
private long fiscalYearId
private long accountId

// In setUp(), after tools = ..., insert test data:
databaseService.withTransaction { groovy.sql.Sql sql ->
  List<List<Object>> accountKeys = sql.executeInsert("""
      insert into account (
          company_id, account_number, account_name,
          account_class, normal_balance_side, active,
          manual_review_required, created_at, updated_at
      ) values (?, '1930', 'Företagskonto', 'ASSET', 'DEBIT',
          true, false, current_timestamp, current_timestamp)
  """, [CompanyService.LEGACY_COMPANY_ID])
  accountId = ((Number) accountKeys.first().first()).longValue()
  List<List<Object>> accountKeys2 = sql.executeInsert("""
      insert into account (
          company_id, account_number, account_name,
          account_class, normal_balance_side, active,
          manual_review_required, created_at, updated_at
      ) values (?, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT',
          true, false, current_timestamp, current_timestamp)
  """, [CompanyService.LEGACY_COMPANY_ID])
}
se.alipsa.accounting.domain.FiscalYear year = fiscalYearService.createFiscalYear(
    CompanyService.LEGACY_COMPANY_ID, '2026',
    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
)
fiscalYearId = year.id
```

And the new test methods:

```groovy
@Test
void listAccountsReturnsActiveAccounts() {
  Map<String, Object> result = tools.callTool('list_accounts', ['company_id': (Object) 1L])
  assertTrue((boolean) result.get('ok'))
  List accounts = (List) result.get('accounts')
  assertEquals(2, accounts.size())
  assertTrue(accounts.any { Map a -> a.get('account_number') == '1930' })
}

@Test
void listVouchersReturnsEmptyWhenNonePosted() {
  Map<String, Object> result = tools.callTool('list_vouchers', [
      'company_id': (Object) 1L,
      'fiscal_year_id': (Object) fiscalYearId
  ])
  assertTrue((boolean) result.get('ok'))
  List vouchers = (List) result.get('vouchers')
  assertTrue(vouchers.isEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: `listAccountsReturnsActiveAccounts` and `listVouchersReturnsEmptyWhenNonePosted` fail — tools not implemented.

- [ ] **Step 3: Add `list_accounts` and `list_vouchers` to `AccountingMcpTools`**

Add to `toolDefinitions()`:
```groovy
toolDef('list_accounts',
    'Returns active accounts in the chart of accounts for the given company. Accepts an optional query string.',
    ['company_id'],
    [
        company_id: intParam('Company ID'),
        query: (Map<String, Object>) [type: 'string', description: 'Optional search string (account number or name)']
    ]
),
toolDef('list_vouchers',
    'Returns posted vouchers for the given fiscal year. Returns at most 200 rows.',
    ['company_id', 'fiscal_year_id'],
    [
        company_id: intParam('Company ID'),
        fiscal_year_id: intParam('Fiscal year ID')
    ]
),
```

Add to `callTool()`:
```groovy
case 'list_accounts':   return listAccounts(args)
case 'list_vouchers':   return listVouchers(args)
```

Add private methods:

```groovy
private Map<String, Object> listAccounts(Map<String, Object> args) {
  long companyId = requiredLong(args, 'company_id')
  String query = args.get('query') as String
  List<se.alipsa.accounting.domain.Account> accounts =
      accountService.searchAccounts(companyId, query, null, true, false)
  (Map<String, Object>) [
      ok: true,
      accounts: accounts.collect { se.alipsa.accounting.domain.Account a -> [
          id: a.id,
          account_number: a.accountNumber,
          account_name: a.accountName,
          account_class: a.accountClass,
          normal_balance_side: a.normalBalanceSide,
          vat_code: a.vatCode,
          active: a.active
      ]}
  ]
}

private Map<String, Object> listVouchers(Map<String, Object> args) {
  long companyId = requiredLong(args, 'company_id')
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  List<se.alipsa.accounting.domain.Voucher> vouchers =
      voucherService.listVouchers(companyId, fiscalYearId, null, null)
  (Map<String, Object>) [
      ok: true,
      vouchers: vouchers.take(200).collect { se.alipsa.accounting.domain.Voucher v -> [
          id: v.id,
          voucher_number: v.voucherNumber,
          series_code: v.seriesCode,
          accounting_date: v.accountingDate?.toString(),
          description: v.description,
          status: v.status?.name(),
          line_count: v.lines?.size() ?: 0
      ]}
  ]
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera list_accounts och list_vouchers MCP-verktyg"
```

---

## Task 5: Read-only tools — `get_trial_balance`, `get_general_ledger`, `list_vat_periods` — Phase 2

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests**

Add these methods to `AccountingMcpToolsTest` (requires `fiscalYearId` from Task 4's test data setup):

```groovy
@Test
void getTrialBalanceReturnsStructuredRows() {
  Map<String, Object> result = tools.callTool('get_trial_balance', [
      'company_id': (Object) 1L,
      'fiscal_year_id': (Object) fiscalYearId
  ])
  assertTrue((boolean) result.get('ok'))
  assertNotNull(result.get('rows'))
}

@Test
void getGeneralLedgerReturnsStructuredRows() {
  Map<String, Object> result = tools.callTool('get_general_ledger', [
      'company_id': (Object) 1L,
      'fiscal_year_id': (Object) fiscalYearId
  ])
  assertTrue((boolean) result.get('ok'))
  assertNotNull(result.get('rows'))
}

@Test
void listVatPeriodsReturnsPeriodsForFiscalYear() {
  Map<String, Object> result = tools.callTool('list_vat_periods', ['fiscal_year_id': (Object) fiscalYearId])
  assertTrue((boolean) result.get('ok'))
  List periods = (List) result.get('vat_periods')
  assertFalse(periods.isEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: three new tests fail — tools not implemented.

- [ ] **Step 3: Implement the three tools in `AccountingMcpTools`**

Add imports at the top of `AccountingMcpTools`:
```groovy
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.report.GeneralLedgerRow
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.domain.report.TrialBalanceRow
```

Add to `toolDefinitions()`:
```groovy
toolDef('get_trial_balance',
    'Returns trial balance (råbalans) for the given fiscal year with opening balance, period movements and closing balance per account.',
    ['company_id', 'fiscal_year_id'],
    [
        company_id: intParam('Company ID'),
        fiscal_year_id: intParam('Fiscal year ID'),
        accounting_period_id: (Map<String, Object>) [type: 'integer', description: 'Optional: restrict to a specific accounting period.'],
        start_date: (Map<String, Object>) [type: 'string', description: 'Optional: restrict start date (ISO YYYY-MM-DD).'],
        end_date: (Map<String, Object>) [type: 'string', description: 'Optional: restrict end date (ISO YYYY-MM-DD).']
    ]
),
toolDef('get_general_ledger',
    'Returns the general ledger (huvudbok). One row per posting with running balance. Use limit to manage large years.',
    ['company_id', 'fiscal_year_id'],
    [
        company_id: intParam('Company ID'),
        fiscal_year_id: intParam('Fiscal year ID'),
        accounting_period_id: (Map<String, Object>) [type: 'integer', description: 'Optional: restrict to a specific accounting period.'],
        start_date: (Map<String, Object>) [type: 'string', description: 'Optional: restrict start date (ISO YYYY-MM-DD).'],
        end_date: (Map<String, Object>) [type: 'string', description: 'Optional: restrict end date (ISO YYYY-MM-DD).'],
        limit: (Map<String, Object>) [type: 'integer', description: 'Max rows returned. Default 1000, max 5000.']
    ]
),
toolDef('list_vat_periods',
    'Lists VAT periods for the given fiscal year with status (OPEN, REPORTED, LOCKED).',
    ['fiscal_year_id'],
    [fiscal_year_id: intParam('Fiscal year ID')]
),
```

Add to `callTool()`:
```groovy
case 'get_trial_balance':    return getTrialBalance(args)
case 'get_general_ledger':   return getGeneralLedger(args)
case 'list_vat_periods':     return listVatPeriods(args)
```

Add private methods:

```groovy
private Map<String, Object> getTrialBalance(Map<String, Object> args) {
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  Long periodId = args.get('accounting_period_id') != null ? ((Number) args.get('accounting_period_id')).longValue() : null
  LocalDate startDate = args.get('start_date') ? LocalDate.parse((String) args.get('start_date')) : null
  LocalDate endDate = args.get('end_date') ? LocalDate.parse((String) args.get('end_date')) : null
  se.alipsa.accounting.domain.report.ReportResult result = reportDataService.generate(
      new ReportSelection(ReportType.TRIAL_BALANCE, fiscalYearId, periodId, startDate, endDate)
  )
  List<TrialBalanceRow> rows = (List<TrialBalanceRow>) result.templateModel.get('typedRows')
  (Map<String, Object>) [
      ok: true,
      fiscal_year_id: fiscalYearId,
      rows: rows.collect { TrialBalanceRow r -> [
          account_number: r.accountNumber,
          account_name: r.accountName,
          opening_balance: r.openingBalance,
          debit: r.debitAmount,
          credit: r.creditAmount,
          closing_balance: r.closingBalance
      ]}
  ]
}

private Map<String, Object> getGeneralLedger(Map<String, Object> args) {
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  Long periodId = args.get('accounting_period_id') != null ? ((Number) args.get('accounting_period_id')).longValue() : null
  LocalDate startDate = args.get('start_date') ? LocalDate.parse((String) args.get('start_date')) : null
  LocalDate endDate = args.get('end_date') ? LocalDate.parse((String) args.get('end_date')) : null
  int limit = args.get('limit') != null ? Math.min(((Number) args.get('limit')).intValue(), 5000) : 1000
  se.alipsa.accounting.domain.report.ReportResult result = reportDataService.generate(
      new ReportSelection(ReportType.GENERAL_LEDGER, fiscalYearId, periodId, startDate, endDate)
  )
  List<GeneralLedgerRow> rows = (List<GeneralLedgerRow>) result.templateModel.get('typedRows')
  boolean truncated = rows.size() > limit
  (Map<String, Object>) [
      ok: true,
      fiscal_year_id: fiscalYearId,
      truncated: truncated,
      total_rows: rows.size(),
      rows: rows.take(limit).collect { GeneralLedgerRow r -> [
          account_number: r.accountNumber,
          account_name: r.accountName,
          accounting_date: r.accountingDate?.toString(),
          voucher_number: r.voucherNumber,
          description: r.description,
          debit: r.debitAmount,
          credit: r.creditAmount,
          balance: r.balance,
          voucher_id: r.voucherId
      ]}
  ]
}

private Map<String, Object> listVatPeriods(Map<String, Object> args) {
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  List<VatPeriod> periods = vatService.listPeriods(fiscalYearId)
  (Map<String, Object>) [
      ok: true,
      vat_periods: periods.collect { VatPeriod p -> [
          id: p.id,
          period_name: p.periodName,
          start_date: p.startDate?.toString(),
          end_date: p.endDate?.toString(),
          status: p.status,
          reported: p.reported,
          locked: p.locked
      ]}
  ]
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera get_trial_balance, get_general_ledger, list_vat_periods MCP-verktyg"
```

---

## Task 6: Voucher preview tool — Phase 3

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests**

Add these methods to `AccountingMcpToolsTest`:

```groovy
@Test
void previewVoucherValidatesBalance() {
  Map<String, Object> result = tools.callTool('preview_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Test',
      lines: (Object) [
          [account_number: '1930', debit: 1000.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 500.00]
      ]
  ])
  assertFalse((boolean) result.get('ok'))
  List errors = (List) result.get('errors')
  assertTrue(errors.any { String e -> e.contains('obalanserad') })
  assertNull(result.get('preview_token'), 'Ogiltigt förslag skall inte ha en preview_token')
}

@Test
void previewVoucherResolvesAccountsAndReturnsTokenWhenValid() {
  Map<String, Object> result = tools.callTool('preview_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Balanserad verifikation',
      lines: (Object) [
          [account_number: '1930', debit: 1000.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 1000.00]
      ]
  ])
  assertTrue((boolean) result.get('ok'))
  assertTrue(((List) result.get('errors')).isEmpty())
  assertEquals(2, ((List) result.get('lines')).size())
  assertEquals('1930', ((Map) ((List) result.get('lines')).first()).get('account_number'))
  assertNotNull(result.get('preview_token'), 'Giltigt förslag skall ha en preview_token')
}

@Test
void previewVoucherRejectsDateOutsideFiscalYear() {
  Map<String, Object> result = tools.callTool('preview_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2025-12-31',
      description: (Object) 'Fel datum',
      lines: (Object) [
          [account_number: '1930', debit: 100.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 100.00]
      ]
  ])
  assertFalse((boolean) result.get('ok'))
  List errors = (List) result.get('errors')
  assertTrue(errors.any { String e -> e.contains('utanför') })
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: three new tests fail — `preview_voucher` not implemented.

- [ ] **Step 3: Implement `preview_voucher` in `AccountingMcpTools`**

Add imports:
```groovy
import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.FiscalYear
import java.time.LocalDate
import java.time.format.DateTimeParseException
```

Add to `toolDefinitions()`:
```groovy
toolDef('preview_voucher',
    'Validates a voucher proposal without posting it. Returns resolved accounts, balance check, and any errors or warnings.',
    ['company_id', 'fiscal_year_id', 'series_code', 'accounting_date', 'description', 'lines'],
    [
        company_id: intParam('Company ID'),
        fiscal_year_id: intParam('Fiscal year ID'),
        series_code: strParam('Voucher series code, e.g. "A"'),
        accounting_date: strParam('Accounting date in ISO format YYYY-MM-DD'),
        description: strParam('Voucher description'),
        lines: (Map<String, Object>) [
            type: 'array',
            description: 'Voucher lines. Each line: { account_number, debit, credit }',
            items: [
                type: 'object',
                properties: [
                    account_number: [type: 'string'],
                    debit: [type: 'number'],
                    credit: [type: 'number']
                ],
                required: ['account_number', 'debit', 'credit']
            ]
        ]
    ]
),
```

Add to `callTool()`:
```groovy
case 'preview_voucher':  return previewVoucher(args)
```

Add private method:

```groovy
private String previewVoucher(Map<String, Object> args) {
  long companyId = requiredLong(args, 'company_id')
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  String seriesCode = (String) args.get('series_code')
  String dateStr = (String) args.get('accounting_date')
  String description = (String) args.get('description')
  List<Map<String, Object>> rawLines = (List<Map<String, Object>>) args.get('lines')

  List<String> errors = []
  List<String> warnings = []

  FiscalYear year = fiscalYearService.findById(fiscalYearId)
  if (year == null) {
    return JsonOutput.toJson([ok: false, errors: ["Räkenskapsår ${fiscalYearId} hittades inte."]])
  }
  if (year.closed) {
    errors << "Räkenskapsåret '${year.name}' är stängt. Rättelse kräver upplåsning."
  }

  LocalDate accountingDate = null
  try {
    accountingDate = LocalDate.parse(dateStr)
    if (accountingDate.isBefore(year.startDate) || accountingDate.isAfter(year.endDate)) {
      errors << "Datum ${accountingDate} är utanför räkenskapsåret (${year.startDate} – ${year.endDate})."
    }
  } catch (DateTimeParseException ignored) {
    errors << "Ogiltigt datumformat: '${dateStr}'. Använd YYYY-MM-DD."
  }

  List<Map<String, Object>> resolvedLines = []
  BigDecimal totalDebit = BigDecimal.ZERO
  BigDecimal totalCredit = BigDecimal.ZERO

  rawLines?.eachWithIndex { Map<String, Object> line, int idx ->
    String accountNumber = (String) line.get('account_number')
    BigDecimal debit = line.get('debit') != null ? new BigDecimal(line.get('debit').toString()) : BigDecimal.ZERO
    BigDecimal credit = line.get('credit') != null ? new BigDecimal(line.get('credit').toString()) : BigDecimal.ZERO
    totalDebit = totalDebit.add(debit)
    totalCredit = totalCredit.add(credit)
    Account account = accountService.findAccount(companyId, accountNumber)
    if (account == null) {
      errors << "Konto ${accountNumber} hittades inte."
      resolvedLines << [account_number: accountNumber, error: 'Konto saknas', debit: debit, credit: credit]
    } else if (!account.active) {
      errors << "Konto ${accountNumber} är inaktivt."
      resolvedLines << [account_number: accountNumber, account_id: account.id, account_name: account.accountName, active: false, debit: debit, credit: credit]
    } else {
      resolvedLines << [account_number: accountNumber, account_id: account.id, account_name: account.accountName, debit: debit, credit: credit]
    }
  }

  if (!rawLines || rawLines.size() < 2) {
    errors << 'En verifikation kräver minst två rader.'
  }

  if (totalDebit.compareTo(totalCredit) != 0) {
    errors << "Verifikationen är obalanserad: debet ${totalDebit} ≠ kredit ${totalCredit}."
  }

  boolean valid = errors.isEmpty()
  // Only include preview_token when the proposal is valid, so callers cannot
  // extract a token from a failing validation and pass it to post_voucher.
  String previewToken = valid ? computePreviewToken((Map<String, Object>) [
      fiscal_year_id: fiscalYearId,
      series_code: seriesCode,
      accounting_date: accountingDate?.toString() ?: dateStr,
      description: description,
      lines: rawLines
  ]) : null
  (Map<String, Object>) [
      ok: valid,
      errors: errors,
      warnings: warnings,
      preview_token: previewToken,
      fiscal_year: [id: year.id, name: year.name, closed: year.closed],
      accounting_date: accountingDate?.toString() ?: dateStr,
      series_code: seriesCode,
      description: description,
      lines: resolvedLines,
      total_debit: totalDebit,
      total_credit: totalCredit
  ]
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera preview_voucher MCP-verktyg"
```

---

## Task 7: Voucher post tool — Phase 3

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests**

```groovy
// Helper: run preview to get a valid token, then call post_voucher with that token.
private Map<String, Object> previewAndPost(Map<String, Object> postArgs) {
  Map<String, Object> previewResult = tools.callTool('preview_voucher', postArgs)
  assertTrue((boolean) previewResult.get('ok'), "Preview failed: ${previewResult.get('errors')}")
  String token = (String) previewResult.get('preview_token')
  Map<String, Object> argsWithToken = new LinkedHashMap<>(postArgs)
  argsWithToken.put('preview_token', (Object) token)
  tools.callTool('post_voucher', argsWithToken)
}

@Test
void postVoucherCreatesVoucherAndReturnsId() {
  Map<String, Object> result = previewAndPost([
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Kontantinsättning',
      lines: (Object) [
          [account_number: '1930', debit: 500.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 500.00]
      ]
  ])
  assertTrue((boolean) result.get('ok'), "Expected ok but got errors: ${result.get('errors')}")
  assertNotNull(result.get('voucher_id'))
  assertEquals('2026-03-01', result.get('accounting_date'))
}

@Test
void postVoucherWithoutPreviewTokenIsRejected() {
  Map<String, Object> result = tools.callTool('post_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Ingen token',
      lines: (Object) [
          [account_number: '1930', debit: 500.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 500.00]
      ]
      // no preview_token
  ])
  assertFalse((boolean) result.get('ok'))
  List errors = (List) result.get('errors')
  assertTrue(errors.any { String e -> e.contains('preview_token') })
}

@Test
void postVoucherWithTamperedPayloadIsRejected() {
  // Get a token for one payload, then change the amount — token must not match.
  Map<String, Object> validArgs = [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Ska postas',
      lines: (Object) [
          [account_number: '1930', debit: 500.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 500.00]
      ]
  ]
  Map<String, Object> preview = tools.callTool('preview_voucher', validArgs)
  String token = (String) preview.get('preview_token')
  Map<String, Object> tampered = new LinkedHashMap<>(validArgs)
  tampered.put('description', (Object) 'Ändrad beskrivning')
  tampered.put('preview_token', (Object) token)
  Map<String, Object> result = tools.callTool('post_voucher', tampered)
  assertFalse((boolean) result.get('ok'))
}

@Test
void postVoucherRejectsUnbalancedLinesWithoutToken() {
  Map<String, Object> result = tools.callTool('post_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Obalanserad',
      lines: (Object) [
          [account_number: '1930', debit: 500.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 200.00]
      ]
  ])
  assertFalse((boolean) result.get('ok'))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: both new tests fail.

- [ ] **Step 3: Implement `post_voucher` in `AccountingMcpTools`**

Add import:
```groovy
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
```

Add to `toolDefinitions()`:
```groovy
toolDef('post_voucher',
    'Posts a voucher. Use preview_voucher first to validate. The lines must be balanced (debit total = credit total).',
    ['company_id', 'fiscal_year_id', 'series_code', 'accounting_date', 'description', 'lines'],
    [
        company_id: intParam('Company ID'),
        fiscal_year_id: intParam('Fiscal year ID'),
        series_code: strParam('Voucher series code, e.g. "A"'),
        accounting_date: strParam('Accounting date in ISO format YYYY-MM-DD'),
        description: strParam('Voucher description'),
        lines: (Map<String, Object>) [
            type: 'array',
            description: 'Voucher lines. Each line: { account_number, debit, credit }',
            items: [
                type: 'object',
                properties: [
                    account_number: [type: 'string'],
                    debit: [type: 'number'],
                    credit: [type: 'number']
                ],
                required: ['account_number', 'debit', 'credit']
            ]
        ]
    ]
),
```

Add to `callTool()`:
```groovy
case 'post_voucher':     return postVoucher(args)
```

Add private method:

```groovy
private Map<String, Object> postVoucher(Map<String, Object> args) {
  long companyId = requiredLong(args, 'company_id')
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  String seriesCode = (String) args.get('series_code')
  String dateStr = (String) args.get('accounting_date')
  String description = (String) args.get('description')
  List<Map<String, Object>> rawLines = (List<Map<String, Object>>) args.get('lines')
  String providedToken = args.get('preview_token') as String

  // Token is required — reject immediately if absent.
  if (!providedToken) {
    return (Map<String, Object>) [ok: false, errors: ['preview_token krävs — kör preview_voucher med exakt samma argument först.']]
  }

  // Recompute the token from the incoming payload and compare.
  String expectedToken = computePreviewToken((Map<String, Object>) [
      fiscal_year_id: fiscalYearId,
      series_code: seriesCode,
      accounting_date: dateStr,
      description: description,
      lines: rawLines
  ])
  if (providedToken != expectedToken) {
    return (Map<String, Object>) [ok: false, errors: ['preview_token stämmer inte med aktuell nyttolast — kör preview_voucher igen med exakt samma argument.']]
  }

  List<String> errors = []

  FiscalYear year = fiscalYearService.findById(fiscalYearId)
  if (year == null) {
    return (Map<String, Object>) [ok: false, errors: ["Räkenskapsår ${fiscalYearId} hittades inte."]]
  }

  LocalDate accountingDate
  try {
    accountingDate = LocalDate.parse(dateStr)
  } catch (DateTimeParseException ignored) {
    return (Map<String, Object>) [ok: false, errors: ["Ogiltigt datumformat: '${dateStr}'."]]
  }

  if (!rawLines || rawLines.size() < 2) {
    return (Map<String, Object>) [ok: false, errors: ['En verifikation kräver minst två rader.']]
  }

  BigDecimal totalDebit = BigDecimal.ZERO
  BigDecimal totalCredit = BigDecimal.ZERO
  List<VoucherLine> lines = []
  rawLines.eachWithIndex { Map<String, Object> raw, int idx ->
    String accountNumber = (String) raw.get('account_number')
    BigDecimal debit = raw.get('debit') != null ? new BigDecimal(raw.get('debit').toString()) : BigDecimal.ZERO
    BigDecimal credit = raw.get('credit') != null ? new BigDecimal(raw.get('credit').toString()) : BigDecimal.ZERO
    totalDebit = totalDebit.add(debit)
    totalCredit = totalCredit.add(credit)
    Account account = accountService.findAccount(companyId, accountNumber)
    if (account == null) {
      errors << "Konto ${accountNumber} hittades inte."
    } else if (!account.active) {
      errors << "Konto ${accountNumber} är inaktivt."
    } else {
      lines << new VoucherLine(null, null, idx, account.id, account.accountNumber, account.accountName, null, debit, credit)
    }
  }

  if (totalDebit.compareTo(totalCredit) != 0) {
    errors << "Verifikationen är obalanserad: debet ${totalDebit} ≠ kredit ${totalCredit}."
  }

  if (!errors.isEmpty()) {
    return (Map<String, Object>) [ok: false, errors: errors]
  }

  try {
    Voucher voucher = voucherService.createVoucher(fiscalYearId, seriesCode, accountingDate, description, lines)
    (Map<String, Object>) [
        ok: true,
        voucher_id: voucher.id,
        voucher_number: voucher.voucherNumber,
        fiscal_year_id: voucher.fiscalYearId,
        accounting_date: voucher.accountingDate?.toString(),
        description: voucher.description,
        status: voucher.status?.name(),
        line_count: voucher.lines?.size() ?: 0
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera post_voucher MCP-verktyg"
```

---

## Task 8: Correction voucher tool — Phase 3

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing test**

```groovy
@Test
void createCorrectionVoucherCreatesReversingVoucher() {
  Map<String, Object> posted = previewAndPost([
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-03-01',
      description: (Object) 'Original att korrigera',
      lines: (Object) [
          [account_number: '1930', debit: 300.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 300.00]
      ]
  ])
  assertTrue((boolean) posted.get('ok'))
  long originalId = ((Number) posted.get('voucher_id')).longValue()

  Map<String, Object> correction = tools.callTool('create_correction_voucher', [
      original_voucher_id: (Object) originalId,
      description: (Object) 'Korrigering av felaktig post'
  ])
  assertTrue((boolean) correction.get('ok'), "Expected ok but got: ${correction.get('errors')}")
  assertNotNull(correction.get('voucher_id'))
  assertNotEquals(originalId, ((Number) correction.get('voucher_id')).longValue())
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: `createCorrectionVoucherCreatesReversingVoucher` fails.

- [ ] **Step 3: Implement `create_correction_voucher` in `AccountingMcpTools`**

Add to `toolDefinitions()`:
```groovy
toolDef('create_correction_voucher',
    'Creates a reversing correction voucher for an existing posted voucher. This is the only way to correct a posted voucher — direct edits are not permitted.',
    ['original_voucher_id'],
    [
        original_voucher_id: intParam('ID of the voucher to correct'),
        description: strParam('Optional description for the correction. Defaults to "Korrigering av <original>".')
    ]
),
```

Add to `callTool()`:
```groovy
case 'create_correction_voucher': return createCorrectionVoucher(args)
```

Add private method:

```groovy
private Map<String, Object> createCorrectionVoucher(Map<String, Object> args) {
  long originalVoucherId = requiredLong(args, 'original_voucher_id')
  String description = args.get('description') as String
  try {
    Voucher correction = voucherService.createCorrectionVoucher(originalVoucherId, description)
    (Map<String, Object>) [
        ok: true,
        voucher_id: correction.id,
        voucher_number: correction.voucherNumber,
        original_voucher_id: correction.originalVoucherId,
        fiscal_year_id: correction.fiscalYearId,
        accounting_date: correction.accountingDate?.toString(),
        description: correction.description,
        status: correction.status?.name()
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera create_correction_voucher MCP-verktyg"
```

---

## Task 9: VAT tools — `get_vat_report` and `book_vat_transfer` — Phase 4

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests**

Add these methods to `AccountingMcpToolsTest`. The test needs a VAT period ID — fetch it from `list_vat_periods` after creating the fiscal year.

Add a helper to get the first OPEN VAT period ID:
```groovy
// Add to test class
private long getFirstVatPeriodId() {
  String resp = tools.callTool('list_vat_periods', ['fiscal_year_id': (Object) fiscalYearId])
  Map result = (Map) new JsonSlurper().parseText(resp)
  List periods = (List) result.get('vat_periods')
  Map first = (Map) periods.first()
  ((Number) first.get('id')).longValue()
}
```

```groovy
@Test
void getVatReportReturnsReportStructureWithHash() {
  long vatPeriodId = getFirstVatPeriodId()
  Map<String, Object> result = tools.callTool('get_vat_report', ['vat_period_id': (Object) vatPeriodId])
  assertTrue((boolean) result.get('ok'))
  assertNotNull(result.get('output_vat_total'))
  assertNotNull(result.get('net_vat_to_pay'))
  // report_hash is required by book_vat_transfer
  assertNotNull(result.get('report_hash'), 'get_vat_report skall returnera report_hash')
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: `getVatReportReturnsReportStructure` fails.

- [ ] **Step 3: Implement `get_vat_report` and `book_vat_transfer` in `AccountingMcpTools`**

Add import:
```groovy
import se.alipsa.accounting.service.VatService.VatReport
import se.alipsa.accounting.service.VatService.VatReportRow
```

Add to `toolDefinitions()`:
```groovy
toolDef('get_vat_report',
    'Calculates the VAT report for the given VAT period. Returns output VAT, input VAT, net payable, and per-code breakdown.',
    ['vat_period_id'],
    [vat_period_id: intParam('VAT period ID')]
),
toolDef('book_vat_transfer',
    'Books the VAT transfer voucher for a reported VAT period. Run get_vat_report first and verify the amounts before calling this tool.',
    ['vat_period_id'],
    [
        vat_period_id: intParam('VAT period ID'),
        series_code: strParam('Voucher series code. Defaults to "M".'),
        settlement_account: strParam('Settlement account number. Defaults to "2650".')
    ]
),
```

Add to `callTool()`:
```groovy
case 'get_vat_report':    return getVatReport(args)
case 'book_vat_transfer': return bookVatTransfer(args)
```

Add private methods:

```groovy
private Map<String, Object> getVatReport(Map<String, Object> args) {
  long vatPeriodId = requiredLong(args, 'vat_period_id')
  try {
    VatReport report = vatService.calculateReport(vatPeriodId)
    // report_hash is returned so book_vat_transfer can require it as confirmation.
    String reportHash = report.period?.reportHash ?: computePreviewToken((Map<String, Object>) [vat_period_id: vatPeriodId])
    (Map<String, Object>) [
        ok: true,
        vat_period_id: report.period?.id,
        period_name: report.period?.periodName,
        status: report.period?.status,
        output_vat_total: report.outputVatTotal,
        input_vat_total: report.inputVatTotal,
        net_vat_to_pay: report.netVatToPay,
        report_hash: reportHash,
        rows: report.rows.collect { VatReportRow r -> [
            vat_code: r.vatCode?.name(),
            label: r.label,
            base_amount: r.baseAmount,
            output_vat: r.outputVatAmount,
            input_vat: r.inputVatAmount
        ]}
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}

private Map<String, Object> bookVatTransfer(Map<String, Object> args) {
  long vatPeriodId = requiredLong(args, 'vat_period_id')
  String providedHash = args.get('report_hash') as String
  if (!providedHash) {
    return (Map<String, Object>) [ok: false, errors: ['report_hash krävs — kör get_vat_report först och skicka med det returnerade report_hash.']]
  }
  String seriesCode = args.get('series_code') as String ?: VatService.DEFAULT_TRANSFER_SERIES
  String settlementAccount = args.get('settlement_account') as String ?: VatService.DEFAULT_SETTLEMENT_ACCOUNT
  try {
    Voucher voucher = vatService.bookTransfer(vatPeriodId, seriesCode, settlementAccount)
    (Map<String, Object>) [
        ok: true,
        voucher_id: voucher.id,
        voucher_number: voucher.voucherNumber,
        accounting_date: voucher.accountingDate?.toString(),
        description: voucher.description
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera get_vat_report och book_vat_transfer MCP-verktyg"
```

---

## Task 10: Year-end tools — `preview_year_end` and `close_fiscal_year` — Phase 4

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy`
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

- [ ] **Step 1: Add failing tests**

```groovy
@Test
void previewYearEndReturnsFiscalYearInfoAndToken() {
  Map<String, Object> result = tools.callTool('preview_year_end', ['fiscal_year_id': (Object) fiscalYearId])
  assertTrue((boolean) result.get('ok'))
  assertNotNull(result.get('net_result'))
  assertNotNull(result.get('blocking_issues'))
  assertNotNull(result.get('warnings'))
  assertNotNull(result.get('preview_token'), 'preview_year_end skall returnera preview_token')
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: fails.

- [ ] **Step 3: Implement `preview_year_end` and `close_fiscal_year` in `AccountingMcpTools`**

Add import:
```groovy
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.YearEndClosingPreview
import se.alipsa.accounting.service.YearEndClosingResult
```

Add to `toolDefinitions()`:
```groovy
toolDef('preview_year_end',
    'Runs year-end closing pre-checks. Returns blocking issues, warnings, income/expense totals and net result. Always run this before close_fiscal_year.',
    ['fiscal_year_id'],
    [
        fiscal_year_id: intParam('Fiscal year ID to preview'),
        closing_account: strParam('Closing account number. Defaults to "2099".')
    ]
),
toolDef('close_fiscal_year',
    'Closes the fiscal year: posts the closing voucher, creates next year opening balances and marks the year closed. Only call this after preview_year_end shows no blocking issues.',
    ['fiscal_year_id'],
    [
        fiscal_year_id: intParam('Fiscal year ID to close'),
        closing_account: strParam('Closing account number. Defaults to "2099".')
    ]
),
```

Add to `callTool()`:
```groovy
case 'preview_year_end':    return previewYearEnd(args)
case 'close_fiscal_year':   return closeFiscalYear(args)
```

Add private methods:

```groovy
private Map<String, Object> previewYearEnd(Map<String, Object> args) {
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  String closingAccount = args.get('closing_account') as String ?: ClosingService.DEFAULT_CLOSING_ACCOUNT
  try {
    YearEndClosingPreview preview = closingService.previewClosing(fiscalYearId, closingAccount)
    String previewToken = computePreviewToken((Map<String, Object>) [
        fiscal_year_id: fiscalYearId,
        closing_account: closingAccount
    ])
    (Map<String, Object>) [
        ok: true,
        fiscal_year_id: preview.fiscalYear?.id,
        fiscal_year_name: preview.fiscalYear?.name,
        closing_account: preview.closingAccountNumber,
        result_account_count: preview.resultAccountCount,
        income_total: preview.incomeTotal,
        expense_total: preview.expenseTotal,
        net_result: preview.netResult,
        blocking_issues: preview.blockingIssues,
        warnings: preview.warnings,
        ready_to_close: preview.blockingIssues.isEmpty(),
        preview_token: previewToken
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}

private Map<String, Object> closeFiscalYear(Map<String, Object> args) {
  long fiscalYearId = requiredLong(args, 'fiscal_year_id')
  String closingAccount = args.get('closing_account') as String ?: ClosingService.DEFAULT_CLOSING_ACCOUNT
  String providedToken = args.get('preview_token') as String
  if (!providedToken) {
    return (Map<String, Object>) [ok: false, errors: ['preview_token krävs — kör preview_year_end först.']]
  }
  String expectedToken = computePreviewToken((Map<String, Object>) [
      fiscal_year_id: fiscalYearId,
      closing_account: closingAccount
  ])
  if (providedToken != expectedToken) {
    return (Map<String, Object>) [ok: false, errors: ['preview_token stämmer inte — kör preview_year_end igen.']]
  }
  try {
    YearEndClosingResult result = closingService.closeFiscalYear(fiscalYearId, closingAccount)
    (Map<String, Object>) [
        ok: true,
        closed_fiscal_year_id: result.closedFiscalYear?.id,
        closed_fiscal_year_name: result.closedFiscalYear?.name,
        next_fiscal_year_id: result.nextFiscalYear?.id,
        closing_voucher_id: result.closingVoucher?.id,
        result_account_count: result.resultAccountCount,
        opening_balance_count: result.openingBalanceCount,
        net_result: result.netResult,
        warnings: result.warnings
    ]
  } catch (Exception e) {
    (Map<String, Object>) [ok: false, errors: [e.message ?: e.class.simpleName]]
  }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "implementera preview_year_end och close_fiscal_year MCP-verktyg"
```

---

## Task 11: Enforcement tests + process-level stdout cleanliness test — Phase 6

Verify business-rule enforcement and that stdout stays clean in MCP mode.

**Files:**
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`
- Modify: `app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy`

- [ ] **Step 1: Add enforcement + bypass tests to `AccountingMcpToolsTest`**

```groovy
@Test
void postVoucherInClosedYearReturnsError() {
  // Get a valid token before closing the year
  Map<String, Object> args = [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-06-01',
      description: (Object) 'Ska misslyckas',
      lines: (Object) [
          [account_number: '1930', debit: 100.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 100.00]
      ]
  ]
  Map<String, Object> preview = tools.callTool('preview_voucher', args)
  assertTrue((boolean) preview.get('ok'))
  String token = (String) preview.get('preview_token')

  // Now close the year — posting must be rejected even with a valid token
  fiscalYearService.closeFiscalYear(fiscalYearId)
  Map<String, Object> argsWithToken = new LinkedHashMap<>(args)
  argsWithToken.put('preview_token', (Object) token)
  Map<String, Object> result = tools.callTool('post_voucher', argsWithToken)
  assertFalse((boolean) result.get('ok'))
  assertFalse(((List) result.get('errors')).isEmpty())
}

@Test
void postVoucherWithoutTokenIsRejected() {
  Map<String, Object> result = tools.callTool('post_voucher', [
      company_id: (Object) 1L,
      fiscal_year_id: (Object) fiscalYearId,
      series_code: (Object) 'A',
      accounting_date: (Object) '2026-06-01',
      description: (Object) 'Ingen token',
      lines: (Object) [
          [account_number: '1930', debit: 100.00, credit: 0.00],
          [account_number: '2440', debit: 0.00, credit: 100.00]
      ]
  ])
  assertFalse((boolean) result.get('ok'))
  assertTrue(((List) result.get('errors')).any { String e -> e.contains('preview_token') })
}

@Test
void closeFiscalYearWithoutTokenIsRejected() {
  Map<String, Object> result = tools.callTool('close_fiscal_year', [
      fiscal_year_id: (Object) fiscalYearId
      // no preview_token
  ])
  assertFalse((boolean) result.get('ok'))
  assertTrue(((List) result.get('errors')).any { String e -> e.contains('preview_token') })
}

@Test
void bookVatTransferWithoutHashIsRejected() {
  long vatPeriodId = getFirstVatPeriodId()
  Map<String, Object> result = tools.callTool('book_vat_transfer', [
      vat_period_id: (Object) vatPeriodId
      // no report_hash
  ])
  assertFalse((boolean) result.get('ok'))
  assertTrue(((List) result.get('errors')).any { String e -> e.contains('report_hash') })
}

@Test
void unknownToolThrowsFromDispatcher() {
  McpDispatcher dispatcher = new McpDispatcher(tools)
  String req = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}'
  String resp = dispatcher.handle(req)
  Map result = (Map) new JsonSlurper().parseText(resp)
  assertNotNull(result.get('error'))
  assertNull(result.get('result'))
}
```

- [ ] **Step 2: Add process-level stdout cleanliness test to `McpHeadlessStartupTest`**

This test starts the application as a real subprocess. It requires that the application JAR is built before the test runs. Add to `McpHeadlessStartupTest`:

```groovy
@Test
void mcpModeStdoutContainsExactlyOneValidJsonRpcLine() {
  // Build the startScripts distribution so we have a runnable launcher.
  // The test assumes ./gradlew installDist has already been run (or is a test dependency).
  File launcher = findInstallDistLauncher()
  assumeTrue(launcher != null && launcher.exists(), 'installDist launcher not found — run ./gradlew installDist first')

  File testHome = tempDir.resolve('mcp-stdout-test').toFile()
  testHome.mkdirs()

  ProcessBuilder pb = new ProcessBuilder(
      launcher.absolutePath,
      '--mode=mcp',
      "--home=${testHome.absolutePath}"
  )
  pb.redirectErrorStream(false)
  Process process = pb.start()

  // Send initialize and close stdin
  PrintWriter stdin = new PrintWriter(new OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8), true)
  stdin.println('{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}')
  stdin.close()

  // Read all stdout lines with a 10-second timeout
  List<String> stdoutLines = []
  BufferedReader reader = new BufferedReader(new InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
  process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
  String line
  while ((line = reader.readLine()) != null) {
    String trimmed = line.trim()
    if (!trimmed.isEmpty()) {
      stdoutLines << trimmed
    }
  }

  assertEquals(1, stdoutLines.size(),
      "MCP stdout skall innehålla exakt en rad, fick ${stdoutLines.size()}: ${stdoutLines}")
  Map response = (Map) new JsonSlurper().parseText(stdoutLines.first())
  assertNotNull(response.get('result'), 'Svaret skall ha result, fick: ' + stdoutLines.first())
  assertNull(response.get('error'), 'Svaret skall inte ha error')
}

private File findInstallDistLauncher() {
  // Look for the launcher produced by ./gradlew installDist
  // Adjust path if the project layout differs
  File base = new File(System.getProperty('user.dir')).parentFile ?: new File('.')
  File launcher = new File(base, 'app/build/install/app/bin/app')
  launcher.exists() ? launcher : null
}
```

Also add to imports at the top of the test class:
```groovy
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assumptions
import static org.junit.jupiter.api.Assumptions.assumeTrue
```

- [ ] **Step 3: Run tests**

```
./gradlew installDist && ./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest" --tests "se.alipsa.accounting.McpHeadlessStartupTest"
```

Expected: all enforcement tests pass; the stdout test passes if the launcher is present.

- [ ] **Step 4: Run full build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy \
        app/src/test/groovy/acceptance/se/alipsa/accounting/McpHeadlessStartupTest.groovy
git commit -m "lägg till bypass-tester och process-nivå stdout-renhetstest för MCP"
```

---

## Task 12: Create `skill/accounting-mcp.md` — Phase 5

**Files:**
- Create: `skill/accounting-mcp.md`

- [ ] **Step 1: Create the skill document**

```markdown
# skill/accounting-mcp.md
---
name: accounting-mcp
description: Guides an LLM assistant through bookkeeping workflows using the Alipsa Accounting MCP server. Covers context gathering, voucher entry, VAT reporting and year-end closing.
---

# Accounting MCP Skill

## Overview

This skill governs how you assist users with bookkeeping through the Alipsa Accounting MCP server. The server exposes the application's existing domain and service layer — the same business rules that apply in the GUI apply here. You cannot bypass them.

Always read before you write. Gather context first, propose actions, then post only after the user confirms.

---

## Available Tools

### Read-only (safe to call freely)
| Tool | Purpose |
|------|---------|
| `get_company_info` | Active company name, currency, VAT periodicity |
| `list_fiscal_years` | All fiscal years with open/closed status |
| `list_accounts` | Chart of accounts; supports optional query string filter |
| `list_vouchers` | Recent vouchers for a fiscal year |
| `get_trial_balance` | Opening balance, period debit/credit, closing balance per account |
| `get_general_ledger` | Full posting history with running balance per account |
| `list_vat_periods` | VAT periods with status (OPEN / REPORTED / LOCKED) |
| `get_vat_report` | Calculated VAT report for a period |
| `preview_voucher` | Validate a proposed voucher without posting it |
| `preview_year_end` | Year-end pre-checks: blocking issues, warnings, net result |

### Write operations (require user confirmation before calling)
| Tool | Purpose |
|------|---------|
| `post_voucher` | Post a balanced voucher |
| `create_correction_voucher` | Reverse a posted voucher (only permitted mutation) |
| `book_vat_transfer` | Book the VAT settlement voucher |
| `close_fiscal_year` | Close the fiscal year and create next year's opening balances |

---

## Workflow: Voucher Entry

1. **Gather context**
   - Call `get_company_info` to confirm company name and default currency.
   - Call `list_fiscal_years` to identify the active (open) fiscal year and its ID.
   - Note the `fiscal_year_id` — you need it for all write operations.

2. **Look up accounts**
   - Call `list_accounts` (with a query string if needed) to find account numbers for the proposed debit and credit sides.
   - Verify each account is active and has the expected `account_class`.

3. **Propose and validate**
   - Summarise the proposed voucher to the user: date, description, lines with account numbers and amounts.
   - Call `preview_voucher` with the proposal. Show the user:
     - Any errors (unbalanced lines, unknown accounts, closed year).
     - Any warnings (date outside typical range, inactive account, etc.).
     - Resolved account names from the preview response.
   - Do not proceed if `ok` is false.

4. **Confirm and post**
   - Ask the user explicitly: "Shall I post this voucher?"
   - Only call `post_voucher` after the user confirms.
   - Report back: voucher ID, voucher number, accounting date.

5. **Corrections**
   - If a posted voucher must be corrected, explain that direct edits are not permitted.
   - Propose a correction voucher: call `preview_voucher` with the reversing lines, show the user, then call `create_correction_voucher` after confirmation.

---

## Workflow: VAT Reporting

1. Call `list_vat_periods` for the relevant fiscal year.
2. Identify the OPEN period the user wants to report.
3. Call `get_vat_report` and show the user the output VAT total, input VAT total and net payable amount.
4. Point out any zero-balance rows that are unusual or any deviations from prior periods if available.
5. Ask: "Shall I book the VAT transfer for this period?"
6. Only call `book_vat_transfer` after the user confirms.

---

## Workflow: Year-End Closing

1. Call `list_fiscal_years` — confirm the year the user wants to close is open and not already closed.
2. Call `preview_year_end` and show the user:
   - `blocking_issues` — must all be empty before proceeding.
   - `warnings` — explain each one.
   - `net_result` — the result carried forward to retained earnings.
   - Whether a new fiscal year will be created automatically.
3. If there are blocking issues, explain each one and what the user needs to do to resolve it. Do not proceed.
4. If `ready_to_close` is true, ask: "All pre-checks pass. Shall I close fiscal year [name]?"
5. Only call `close_fiscal_year` after the user confirms.
6. Report: closed year name, next year ID, closing voucher ID, opening balance count.

---

## Domain Model Reference

### Fiscal year
- Each year has a `start_date`, `end_date`, and `closed` flag.
- A closed year blocks all new vouchers except via `create_correction_voucher` after reopening.
- Reopening a year with existing closing entries (bokslutsposter) is blocked — this is intentional.

### Voucher
- Vouchers are append-only. Once posted, they cannot be edited or deleted.
- The only way to correct a mistake is `create_correction_voucher`, which creates a reversing voucher.
- A voucher must balance: sum of debit lines == sum of credit lines.

### Account classes
- `ASSET` (Tillgång) — normally debit balance
- `LIABILITY` (Skuld) — normally credit balance
- `EQUITY` (Eget kapital) — normally credit balance
- `INCOME` (Intäkt) — normally credit balance
- `EXPENSE` (Kostnad) — normally debit balance

### VAT
- VAT periods are derived from accounting periods and the company's VAT periodicity (monthly, quarterly, yearly).
- A VAT period moves: OPEN → REPORTED → LOCKED (after transfer voucher is booked).
- Once LOCKED, no further VAT activity is expected for that period.

---

## Important Constraints

- **Never suggest reopening a closed year** unless the user has explicitly asked and understands the consequences. Reopening is blocked if closing entries exist.
- **Never make legal statements** about tax liability. Describe bookkeeping consequences, not legal obligations.
- **Describe what the tool returns** before asking the user to confirm a write operation. Do not call write tools speculatively.
- **If a tool returns `ok: false`**, explain the errors to the user in plain language and suggest how to resolve them before retrying.
- **Currency**: always use the company's `defaultCurrency` when quoting amounts.
```

- [ ] **Step 2: Verify the file exists**

```bash
ls skill/accounting-mcp.md
```

Expected: file is present.

- [ ] **Step 3: Commit**

```bash
git add skill/accounting-mcp.md
git commit -m "lägg till skill/accounting-mcp.md: LLM-guide för bokföringsassistenten"
```

---

## Task 13: Gradle — include `skill/` in distributions — Phase 5

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Add `skill/` to `packageLinuxReleaseZip` and Gradle `distributions`**

In `app/build.gradle`, locate `tasks.register('packageLinuxReleaseZip', Zip)` (around line 336). After the existing `from(linuxUninstallScript)` block and before the closing `}`, add:

```groovy
from(rootProject.file('skill')) {
  into('skill')
}
```

Also add the skill directory to the standard Gradle distribution (used by `distZip` / `distTar` / `installDist`). At the end of the existing `application { ... }` block, or at the root of the `build.gradle` file after the `application` block, add:

```groovy
distributions {
  main {
    contents {
      from(rootProject.file('skill')) {
        into('skill')
      }
    }
  }
}
```

The full modified `packageLinuxReleaseZip` task should look like:

```groovy
tasks.register('packageLinuxReleaseZip', Zip) {
  onlyIf { isLinux }
  dependsOn 'packageLinuxAppImage', 'generateLinuxInstallScripts'
  archiveBaseName = releaseConfig.packageName
  archiveVersion = releaseConfig.version
  archiveClassifier = 'linux'
  destinationDirectory = releaseConfig.releaseRoot.map { it.dir('linux') }
  from(releaseConfig.releaseRoot.map { it.dir("linux/${releaseConfig.appName}") }) {
    into(releaseConfig.appName)
    filesMatching("bin/${releaseConfig.appName}") {
      it.permissions { unix(0755) }
    }
  }
  from(linuxInstallScript) {
    filePermissions { unix(0755) }
  }
  from(linuxUninstallScript) {
    filePermissions { unix(0755) }
  }
  from(rootProject.file('skill')) {
    into('skill')
  }
}
```

- [ ] **Step 2: Verify the build still passes**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL (the `skill/` directory will be created when needed; the `from` in `distributions` handles the case where the directory may not yet exist by producing an empty contents if the dir is absent).

- [ ] **Step 3: Verify skill directory is included in the distribution zip (when run on Linux)**

```bash
./gradlew distZip
unzip -l app/build/distributions/app-*.zip | grep skill
```

Expected: entries starting with `app-.../skill/` appear in the listing.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle
git commit -m "inkludera skill/-katalogen i distributionspaket"
```

---

## Self-Review

### Spec coverage

| Spec section | Covered by task |
|---|---|
| `--mode=mcp` bootstrap | Task 2 |
| Shared startup (logging, database, I18n) | Task 2 — reuses existing bootstrap path |
| Clean shutdown in MCP mode (H2 + log handles) | Task 2 — `DatabaseService.instance.shutdown()` + `LoggingConfigurer.shutdown()` |
| Headless startup test | Task 2 + Task 11 |
| MCP stdio server | Task 1 |
| `initialize` / `tools/list` / `tools/call` | Task 1 |
| Read-only tools (company, fiscal years, accounts, vouchers) | Tasks 3–4 |
| Read-only tools (trial balance, general ledger, VAT periods) | Task 5 |
| Voucher preview + post two-step flow | Tasks 6–7 |
| Correction voucher | Task 8 |
| VAT read + write tools | Task 9 |
| Year-end preview + close | Task 10 |
| Business rule enforcement in MCP (same as GUI) | Task 11 |
| Skill document | Task 12 |
| Distribution packaging of `skill/` | Task 13 |
| No direct update/delete of posted vouchers exposed | All voucher write tools — only `post_voucher` and `create_correction_voucher` |
| Preview before write (two-step) | `preview_voucher`→`post_voucher`, `preview_year_end`→`close_fiscal_year`, `get_vat_report`→`book_vat_transfer` |

### Placeholder scan

No TBD, TODO, "similar to", or "add appropriate" phrases found.

### Type consistency

- `requiredLong(args, key)` used consistently throughout `AccountingMcpTools`.
- `VatReport`, `VatReportRow` imported as `VatService.VatReport` / `VatService.VatReportRow` (inner classes).
- `ClosingService.DEFAULT_CLOSING_ACCOUNT` used as default in both `preview_year_end` and `close_fiscal_year`.
- `toolDef()` helper signature is `(String name, String description, List<String> required, Map<String, Object> properties)` — used identically in every call.
- Test field `fiscalYearId` set in Task 4's setUp extension and reused in Tasks 5–11.
