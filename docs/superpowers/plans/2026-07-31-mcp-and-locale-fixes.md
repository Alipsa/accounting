# MCP Path Confinement and Locale-Refresh Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three findings from a code review: (1) a `ClassCastException` in the MCP tool layer that gets misreported as an internal error instead of a clean invalid-params error, (2) `export_sie` accepting an unrestricted absolute output path (a file-write primitive), validated only once at the start rather than immediately before the write, and (3) `VoucherPanel` header/nav labels and the "corrects" label not refreshing on a live locale switch. (A fourth candidate fix, the same locale-refresh gap in `FiscalYearPanel`, was investigated and dropped — see "Dropped: FiscalYearPanel" below.)

**Architecture:** Each fix is narrow and localized to the file(s) that own the bug — no new abstractions. The SIE-export fix reuses the existing `AiWorkspacePermissions.verifyNoSymlinksInPath` containment check and `AppPaths.aiWorkspaceDirectory()` root (both already used elsewhere for the AI assistant's own workspace files), applied at two points (right after resolving the path, and again immediately before the write) rather than inventing new path-validation logic. The locale-refresh fix follows the exact pattern `ChartOfAccountsPanel` already uses correctly: store every static-caption `JLabel` as a field, refresh its `.text` in the panel's existing `updateLabels()` method.

**Dropped: FiscalYearPanel locale-refresh "fix".** A prior draft of this plan included a Task 4 making `FiscalYearPanel`'s Name/Start date/End date labels fields refreshed by `applyLocale()`, mirroring Task 3. Investigation before implementation showed this doesn't fix a reachable bug: those labels are built fresh inside `buildInputGrid()`, which is only ever invoked from `showCreateFiscalYearDialog()` (`FiscalYearPanel.groovy:272-284`) as the message component of a blocking `JOptionPane.showConfirmDialog`. Since that call is modal and Swing is single-threaded, a user cannot reach the "switch language" menu action while the dialog is open, and `buildInputGrid()` always constructs new `JLabel`s with the then-current locale text on every open. Unlike `VoucherPanel`'s always-visible header (Task 3), there is no code path where these three labels are observed showing stale text. Confirmed with the user and dropped rather than implemented for consistency's sake alone.

**Tech Stack:** Groovy, Swing, JUnit 6 (`groovier-junit`), H2 (embedded, via `DatabaseService.newForTesting()`).

## Global Constraints

- 2-space indentation in all Groovy files, no unrelated formatting churn (`CLAUDE.md`).
- `@CompileStatic` is enforced globally; do not add per-class annotations.
- Run `./gradlew spotlessApply` after all edits, then inspect the diff, then run `./gradlew codenarcMain`, then `./gradlew build` before considering the work done (`CLAUDE.md` Agent-Specific Notes).
- Do not edit generated build output (`app/build/`, root `build/`, `.gradle/`).
- Test classes live under `app/src/test/groovy/{unit|integration|acceptance}` and are named `*Test.groovy`. Most tests touched/added here are `integration` (they use a real H2-backed `DatabaseService`); the one exception is `McpToolDefinitionsTest` (Task 2, Step 11), which only inspects static tool-schema data and belongs under `unit`.

---

### Task 1: Fix `requiredLong` misclassifying non-numeric MCP arguments as internal errors

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy:854-860`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

**Interfaces:**
- Consumes: nothing new.
- Produces: `requiredLong(Map<String, Object> args, String key)` now throws `IllegalArgumentException` (not `ClassCastException`) for a non-`Number` argument value. Every existing caller (`exportSie`, `previewSieImport`, `importSie`, etc.) is unaffected in the success path; only the error path changes.

- [x] **Step 1: Write the failing tests**

Add to `AccountingMcpToolsTest.groovy`, after the existing `exportSieCreatesDefaultTimestampedFileAndProtectsExistingOutput` test (around line 1005):

```groovy
  @Test
  void requiredLongRejectsNonNumericArgumentWithACleanMessage() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      tools.callTool('export_sie', [fiscal_year_id: (Object) 'not-a-number'])
    }
    assertTrue(exception.message.contains('fiscal_year_id'))
  }

  @Test
  void nonNumericArgumentReturnsJsonRpcInvalidParamsNotInternalError() {
    McpDispatcher dispatcher = new McpDispatcher(tools)

    Map<String, Object> response = (Map<String, Object>) dispatcher.dispatch([
        jsonrpc: '2.0',
        id     : 1,
        method : 'tools/call',
        params : [
            name     : 'export_sie',
            arguments: [fiscal_year_id: (Object) 'not-a-number']
        ]
    ])

    Map<String, Object> error = (Map<String, Object>) response.error
    assertEquals(-32602, error.code)
  }
```

(`assertThrows`, `assertTrue`, `assertEquals` are already available via the file's existing `import static org.junit.jupiter.api.Assertions.*`; `McpDispatcher` needs no import since the test class is already `package se.alipsa.accounting.mcp`.)

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest.requiredLongRejectsNonNumericArgumentWithACleanMessage" --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest.nonNumericArgumentReturnsJsonRpcInvalidParamsNotInternalError"`

Expected: FAIL — the first test fails with "Unexpected exception type thrown: expected IllegalArgumentException, but was ClassCastException" (or equivalent JUnit message); the second fails on `assertEquals(-32602, error.code)` because `error.code` is `-32603`.

- [x] **Step 3: Fix `requiredLong`**

In `AccountingMcpTools.groovy`, replace:

```groovy
  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    ((Number) value).longValue()
  }
```

with:

```groovy
  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("Argument ${key} must be a number.")
    }
    ((Number) value).longValue()
  }
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`

Expected: PASS (all tests in the class, including the two new ones).

- [x] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "fix: reject non-numeric MCP long arguments as invalid params, not internal errors"
```

---

### Task 2: Confine `export_sie` output paths to the AI assistant workspace root, and re-validate immediately before the write

**Context:** `AppPaths.aiWorkspaceDirectory()` (`app/src/main/groovy/se/alipsa/accounting/support/AppPaths.groovy:55-57`) is already the fixed per-user root the AI assistant's own files live under, independent of the user's configurable data-location setting. `AiWorkspacePermissions.verifyNoSymlinksInPath(Path root, Path candidate)` (`app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePermissions.groovy:91-107`) already implements a fail-closed "is `candidate` inside `root`, with no symlink escape at any segment" check and is used elsewhere for this exact workspace.

This task wires `export_sie` through that root and check at **two points**, not one:

1. Right after computing `outputPath` (whether default or explicit) — this catches a bad `output_path` argument *and* a pre-existing symlink planted at the default `sie-exports` location, before any DB work happens.
2. Immediately before `Files.write` inside `SieImportExportService.exportFiscalYear` — this closes the gap between step 1 and the actual write, which is separated by a DB read and document render. `exportFiscalYear` is also called by the human-driven manual SIE export feature (`SieExchangeDialog.groovy:630`) and several service-level tests, none of which should be confined to the AI workspace, so the re-check is threaded through as an optional callback that only the MCP caller supplies (default no-op for every other caller).

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy` (imports; new field; `defaultSieExportPath`; `exportSie`; new `validateWithinAiWorkspace` helper)
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy` (import; `exportFiscalYear` gains an optional `Consumer<Path> prewriteValidation` parameter)
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/McpToolDefinitions.groovy:213-221` (`export_sie`'s `output_path` schema description)
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/service/SieImportExportServiceTest.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy`

**Interfaces:**
- Consumes: `AppPaths.aiWorkspaceDirectory()`, `AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY`, `AiWorkspacePermissions.verifyNoSymlinksInPath(Path, Path)`, `McpToolDefinitions.listTools()` — all pre-existing.
- Produces: `AccountingMcpTools.validateWithinAiWorkspace(Path candidate)` — new private helper, throws `IllegalArgumentException` if `candidate` is outside `AppPaths.aiWorkspaceDirectory()` or escapes it via a symlink. `SieImportExportService.exportFiscalYear(long fiscalYearId, Path targetPath, Consumer<Path> prewriteValidation = null)` — existing method, new trailing optional parameter; `null` (the default) preserves current behavior for every existing caller.

- [x] **Step 1: Write the failing MCP-level tests**

First, extend the test fixture so it doesn't write outside the AI workspace root during the whole test class (every test in this file calls `setUp()`/`tearDown()`). In `AccountingMcpToolsTest.groovy`, replace:

```groovy
  private String previousHome
```

with:

```groovy
  private String previousHome
  private String previousAiWorkspaceHome
```

Replace:

```groovy
  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
```

with:

```groovy
  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    previousAiWorkspaceHome = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.toString())
```

Replace:

```groovy
  @AfterEach
  void tearDown() {
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }
```

with:

```groovy
  @AfterEach
  void tearDown() {
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
    if (previousAiWorkspaceHome == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousAiWorkspaceHome)
    }
  }
```

Now update the existing export test's explicit path to be inside the (about-to-be-enforced) workspace root, and add three new tests. Replace:

```groovy
  @Test
  void exportSieCreatesDefaultTimestampedFileAndProtectsExistingOutput() {
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))
    Path explicitPath = tempDir.resolve('export.sie')
```

with:

```groovy
  @Test
  void exportSieCreatesDefaultTimestampedFileAndProtectsExistingOutput() {
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))
    Path explicitPath = AppPaths.aiWorkspaceDirectory().resolve('export.sie')
```

Then, immediately after the closing brace of that test (after the line `assertTrue((boolean) overwrite.get('ok'), "Overwrite failed: ${overwrite.get('errors')}")` and its following `  }`), add three new tests:

```groovy
  @Test
  void exportSieRejectsOutputPathOutsideAiWorkspace() {
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))
    Path outsidePath = tempDir.resolve('outside-workspace.sie')

    Map<String, Object> result = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId,
        output_path: (Object) outsidePath.toString()
    ])

    assertFalse((boolean) result.get('ok'))
    assertTrue(((List<String>) result.get('errors')).any { String error -> error.contains('workspace') })
    assertFalse(Files.exists(outsidePath))
  }

  @Test
  void exportSieDefaultPathStaysInsideAiWorkspace() {
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))

    Map<String, Object> result = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertTrue((boolean) result.get('ok'), "Default export failed: ${result.get('errors')}")
    Path filePath = Path.of((String) result.get('file_path'))
    assertTrue(filePath.startsWith(AppPaths.aiWorkspaceDirectory()))
  }

  @Test
  void exportSieRejectsDefaultPathWhenSieExportsIsASymlinkEscapingTheWorkspace() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        !System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    previewAndPost(balancedVoucherArgs('Exportunderlag', 100.00G))
    Path outsideDirectory = tempDir.resolve('outside-sie-exports')
    Files.createDirectories(outsideDirectory)
    Files.createDirectories(AppPaths.aiWorkspaceDirectory())
    Files.createSymbolicLink(AppPaths.aiWorkspaceDirectory().resolve('sie-exports'), outsideDirectory)

    Map<String, Object> result = tools.callTool('export_sie', [
        fiscal_year_id: (Object) fiscalYearId
    ])

    assertFalse((boolean) result.get('ok'))
    assertTrue(((List<String>) result.get('errors')).any { String error -> error.contains('symlink') })
    assertEquals(0, outsideDirectory.toFile().list().length)
  }
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`

Expected: FAIL — `exportSieRejectsOutputPathOutsideAiWorkspace` fails because today `output_path` is accepted unconditionally; `exportSieDefaultPathStaysInsideAiWorkspace` fails because the default path is currently under `AppPaths.sieExportsDirectory()` (the configurable data home), not `AppPaths.aiWorkspaceDirectory()`; `exportSieRejectsDefaultPathWhenSieExportsIsASymlinkEscapingTheWorkspace` fails because the default path is never validated at all today, so the export silently writes through the symlink into `outsideDirectory`.

- [x] **Step 3: Add the `AiWorkspacePermissions` import and field**

In `AccountingMcpTools.groovy`, replace:

```groovy
import se.alipsa.accounting.service.AccountingInstructionService
import se.alipsa.accounting.service.ClosingService
```

with:

```groovy
import se.alipsa.accounting.service.AccountingInstructionService
import se.alipsa.accounting.service.AiWorkspacePermissions
import se.alipsa.accounting.service.ClosingService
```

Replace:

```groovy
  private VoucherDraftAccess voucherDraftAccess
  private Closure<Map<String, Object>> activeContextProvider
  private final PreviewTokenLedger previewTokenLedger = new PreviewTokenLedger()
```

with:

```groovy
  private VoucherDraftAccess voucherDraftAccess
  private Closure<Map<String, Object>> activeContextProvider
  private final PreviewTokenLedger previewTokenLedger = new PreviewTokenLedger()
  private final AiWorkspacePermissions aiWorkspacePermissions = new AiWorkspacePermissions()
```

- [x] **Step 4: Confine the default export path to the AI workspace, and add the shared validation helper**

Replace:

```groovy
  private Path defaultSieExportPath(long fiscalYearId) {
    FiscalYear fiscalYear = fiscalYearService.findById(fiscalYearId)
    if (fiscalYear == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    String safeName = fiscalYear.name
        .replaceAll(/[^A-Za-z0-9._-]+/, '-')
        .replaceAll(/^-+|-+$/, '')
    if (!safeName) {
      safeName = fiscalYearId.toString()
    }
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMddHHmm'))
    AppPaths.sieExportsDirectory().resolve("AlipsaAccounting-${safeName}-${timestamp}.sie").toAbsolutePath().normalize()
  }
```

with:

```groovy
  private Path defaultSieExportPath(long fiscalYearId) {
    FiscalYear fiscalYear = fiscalYearService.findById(fiscalYearId)
    if (fiscalYear == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    String safeName = fiscalYear.name
        .replaceAll(/[^A-Za-z0-9._-]+/, '-')
        .replaceAll(/^-+|-+$/, '')
    if (!safeName) {
      safeName = fiscalYearId.toString()
    }
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMddHHmm'))
    AppPaths.aiWorkspaceDirectory().resolve('sie-exports')
        .resolve("AlipsaAccounting-${safeName}-${timestamp}.sie").toAbsolutePath().normalize()
  }

  private void validateWithinAiWorkspace(Path candidate) {
    try {
      aiWorkspacePermissions.verifyNoSymlinksInPath(AppPaths.aiWorkspaceDirectory(), candidate)
    } catch (IllegalStateException exception) {
      throw new IllegalArgumentException(exception.message)
    }
  }
```

- [x] **Step 5: Validate `exportSie`'s resolved path up front, and pass a re-check into `exportFiscalYear`**

Replace:

```groovy
  private Map<String, Object> exportSie(Map<String, Object> args) {
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    boolean overwrite = optionalBoolean(args, 'overwrite', false)
    try {
      Path outputPath = args.get('output_path') == null
          ? defaultSieExportPath(fiscalYearId)
          : Path.of(args.get('output_path') as String).toAbsolutePath().normalize()
      if (Files.exists(outputPath) && !overwrite) {
        return [
            ok: false,
            file_exists: true,
            existing_file_path: outputPath.toString(),
            errors: ['Målfilen finns redan. Bekräfta överskrivning och anropa export_sie med overwrite: true.']
        ]
      }
      SieExportResult result = sieImportExportService.exportFiscalYear(fiscalYearId, outputPath)
```

with:

```groovy
  private Map<String, Object> exportSie(Map<String, Object> args) {
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    boolean overwrite = optionalBoolean(args, 'overwrite', false)
    try {
      Path outputPath = args.get('output_path') == null
          ? defaultSieExportPath(fiscalYearId)
          : Path.of(args.get('output_path') as String).toAbsolutePath().normalize()
      validateWithinAiWorkspace(outputPath)
      if (Files.exists(outputPath) && !overwrite) {
        return [
            ok: false,
            file_exists: true,
            existing_file_path: outputPath.toString(),
            errors: ['Målfilen finns redan. Bekräfta överskrivning och anropa export_sie med overwrite: true.']
        ]
      }
      SieExportResult result = sieImportExportService.exportFiscalYear(
          fiscalYearId, outputPath, this::validateWithinAiWorkspace)
```

(`validateWithinAiWorkspace` matches `java.util.function.Consumer<Path>`'s `accept(Path)` shape, so `this::validateWithinAiWorkspace` binds directly to the new parameter added in Step 8 below — no functional-interface wrapper needed, following the same `this::method` style already used for `Consumer`/`Supplier` parameters elsewhere in this codebase, e.g. `VoucherNavigation.select(Voucher, Consumer<Voucher>)` called as `navigation.select(listedVoucher, this::showVoucher)` in `VoucherPanel.groovy:686`.)

- [x] **Step 6: Run the MCP-level tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`

Expected: PASS (all tests, including the three new ones). Note this requires Step 8 (the `exportFiscalYear` signature change) to be done first, since `exportSie` now calls the 3-argument overload — do Steps 1-8 as a block before running this.

- [x] **Step 7: Write the failing service-level tests for both prewrite re-check call sites**

Add to `SieImportExportServiceTest.groovy`, after the existing `exportFiscalYear`-related tests (a good anchor is right before the `createExportFixture` helper method around line 620, i.e. inside the test class, not the helper section). Two tests are needed: the callback is invoked twice per export (once before `Files.createDirectories`, once before `Files.write`), so one test must fail it on the first call and one on the second — a callback that always throws would only ever exercise the first call site, since the exception aborts the method before the second call is reached.

```groovy
  @Test
  void exportFiscalYearAbortsBeforeCreatingDirectoriesWhenPrewriteValidationFails() {
    switchHome(tempDir.resolve('prewrite-validation-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    Path exportPath = tempDir.resolve('guarded-export-dir').resolve('guarded-export.sie')
    List<Path> validatedPaths = []

    IllegalStateException exception = assertThrows(IllegalStateException) {
      services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath) { Path target ->
        validatedPaths << target
        throw new IllegalStateException('simulated symlink swap detected before mkdir')
      }
    }

    assertEquals('simulated symlink swap detected before mkdir', exception.message)
    assertEquals([exportPath.toAbsolutePath().normalize()], validatedPaths)
    assertFalse(Files.exists(exportPath.parent), 'Export directory must not be created before pre-write validation passes')
    assertFalse(Files.exists(exportPath))
  }

  @Test
  void exportFiscalYearAbortsBeforeWritingWhenTheSecondPrewriteValidationFails() {
    switchHome(tempDir.resolve('prewrite-validation-second-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    Path exportPath = tempDir.resolve('second-check-dir').resolve('guarded-export.sie')
    List<Path> validatedPaths = []

    IllegalStateException exception = assertThrows(IllegalStateException) {
      services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath) { Path target ->
        validatedPaths << target
        if (validatedPaths.size() == 2) {
          throw new IllegalStateException('simulated symlink swap detected before write')
        }
      }
    }

    assertEquals('simulated symlink swap detected before write', exception.message)
    assertEquals(2, validatedPaths.size())
    assertTrue(Files.exists(exportPath.parent), 'Export directory should already exist once the second check runs')
    assertFalse(Files.exists(exportPath))
  }
```

The first test proves the hook fires *before* `Files.createDirectories` (the export directory, `guarded-export-dir`, is never created when it fails there). The second test lets the first call through, so `createDirectories` actually runs (`second-check-dir` exists by the time the exception is asserted), then fails the second call and proves `Files.write` never ran (`exportPath` doesn't exist) — this is the one that specifically exercises the call site immediately before the write, which the first test's always-throwing callback never reaches.

- [x] **Step 8: Add the `prewriteValidation` parameter to `exportFiscalYear`**

In `SieImportExportService.groovy`, replace:

```groovy
import java.time.LocalDate
```

with:

```groovy
import java.time.LocalDate
import java.util.function.Consumer
```

Replace:

```groovy
  SieExportResult exportFiscalYear(long fiscalYearId, Path targetPath) {
    Path safeTarget = normalizeExportPath(targetPath)
    ExportPayload payload = databaseService.withSql { Sql sql ->
      long companyId = resolveCompanyId(sql, fiscalYearId)
      Company company = companyService.findById(companyId)
      if (company == null) {
        throw new IllegalStateException('Företagsuppgifter måste sparas innan SIE-export kan göras.')
      }
      reportIntegrityService.ensureReportingAllowed(companyId)
      buildExportPayload(sql, fiscalYearId, company)
    }
    byte[] content = renderDocument(payload.document)
    Files.createDirectories(safeTarget.parent)
    Files.write(safeTarget, content)
```

with:

```groovy
  SieExportResult exportFiscalYear(long fiscalYearId, Path targetPath, Consumer<Path> prewriteValidation = null) {
    Path safeTarget = normalizeExportPath(targetPath)
    ExportPayload payload = databaseService.withSql { Sql sql ->
      long companyId = resolveCompanyId(sql, fiscalYearId)
      Company company = companyService.findById(companyId)
      if (company == null) {
        throw new IllegalStateException('Företagsuppgifter måste sparas innan SIE-export kan göras.')
      }
      reportIntegrityService.ensureReportingAllowed(companyId)
      buildExportPayload(sql, fiscalYearId, company)
    }
    byte[] content = renderDocument(payload.document)
    prewriteValidation?.accept(safeTarget)
    Files.createDirectories(safeTarget.parent)
    prewriteValidation?.accept(safeTarget)
    Files.write(safeTarget, content)
```

The validation callback runs twice: once right after the DB read and document render (closing the window between `AccountingMcpTools`'s first check and here, which is the widest gap since it spans a database round-trip), and again immediately before `Files.write` (closing the much smaller window opened by `Files.createDirectories`, which itself must not run against a since-swapped symlinked parent before being checked). This still leaves a residual gap between the second check and the write call itself — closing that fully would need a no-follow write primitive, which this codebase doesn't otherwise use anywhere (`AtomicSecretFileWriter`, the closest existing precedent, narrows the same kind of gap with revalidate-before-and-after-move rather than a no-follow open); adding one here would be new infrastructure disproportionate to this finding, so it's called out as accepted residual risk rather than built.

(The default `null` keeps every other caller — `SieExchangeDialog.groovy:630`, `AcceptanceCriteriaTest.groovy`, `MultiCompanyIsolationTest.groovy`, `SieImportExportServiceSruTest.groovy`, and the pre-existing tests in this file — unaffected: `prewriteValidation?.accept(...)` is a no-op when `null`.)

- [x] **Step 9: Run the service-level tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.SieImportExportServiceTest"`

Expected: PASS (all tests in the class, including the two new ones and every pre-existing `exportFiscalYear` caller in this file, unaffected by the new optional parameter).

- [x] **Step 10: Run the MCP-level tests from Step 6 now that both files are in place**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`

Expected: PASS.

- [x] **Step 11: Write the failing MCP tool-schema test**

The `export_sie` tool's schema (returned by `tools/list` and read by MCP clients, including the AI assistant itself) still describes the pre-fix behavior: `McpToolDefinitions.groovy:218` currently reads `'Optional absolute output path. Defaults to the application SIE export directory.'` — an MCP client following that description would reasonably try an arbitrary absolute path and now get rejected, or expect the wrong default location.

Create `app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy`:

```groovy
package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

class McpToolDefinitionsTest {

  @Test
  void exportSieOutputPathSchemaDescribesTheAiWorkspaceConfinement() {
    Map<String, Object> exportSieDef = McpToolDefinitions.listTools().find { Map<String, Object> tool ->
      tool.name == 'export_sie'
    } as Map<String, Object>
    assertNotNull(exportSieDef, 'export_sie tool definition must be registered')
    Map<String, Object> inputSchema = exportSieDef.inputSchema as Map<String, Object>
    Map<String, Object> properties = inputSchema.get('properties') as Map<String, Object>
    Map<String, Object> outputPathSchema = properties.output_path as Map<String, Object>
    String description = outputPathSchema.description as String

    assertTrue(description.contains('workspace'),
        "output_path description should mention the AI workspace confinement, was: ${description}")
    assertFalse(description.contains('application SIE export directory'),
        'output_path description must not claim the stale unrestricted default location')
  }
}
```

- [x] **Step 12: Run the schema test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.McpToolDefinitionsTest"`

Expected: FAIL — the current description doesn't contain `'workspace'` and does contain `'application SIE export directory'`.

- [x] **Step 13: Update the `export_sie` schema description**

In `McpToolDefinitions.groovy`, replace:

```groovy
                output_path: optStrParam('Optional absolute output path. Defaults to the application SIE export directory.'),
```

with:

```groovy
                output_path: optStrParam('Optional absolute output path. Must resolve inside the AI assistant workspace directory; paths outside it, or reached via a symlink, are rejected. Defaults to a timestamped file under sie-exports inside that workspace.'),
```

- [x] **Step 14: Run the schema test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.McpToolDefinitionsTest"`

Expected: PASS.

- [x] **Step 15: Run the full MCP and SIE service test packages to check for regressions**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.*" --tests "se.alipsa.accounting.service.SieImportExportService*" --tests "se.alipsa.accounting.service.MultiCompanyIsolationTest" --tests "se.alipsa.accounting.ui.SieExchangeDialog*"`

Expected: PASS.

- [x] **Step 16: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy app/src/main/groovy/se/alipsa/accounting/mcp/McpToolDefinitions.groovy app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy app/src/test/groovy/integration/se/alipsa/accounting/service/SieImportExportServiceTest.groovy app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy
git commit -m "fix: confine export_sie output paths to the AI assistant workspace root, re-validate before writing, and update its tool schema"
```

---

### Task 3: Fix `VoucherPanel` locale-refresh gap (header/nav caption labels and the "corrects" label)

**Context:** `updateLabels()` (`VoucherPanel.groovy:1361-1386`) is invoked on every live locale switch (wired via `doRegisterListeners()`'s `I18n.instance.addLocaleChangeListener(this)` and `propertyChange()`'s `'locale' == event.propertyName` branch), but the five static caption labels built as anonymous `new JLabel(...)` in `buildHeaderBar()`/`buildNavigationToolbar()`, and the "corrects" label's already-substituted text, are never touched by it.

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy`

**Interfaces:**
- Consumes: nothing new.
- Produces: five new private fields on `VoucherPanel` — `voucherNumberCaptionLabel`, `dateCaptionLabel`, `descriptionCaptionLabel`, `seriesCaptionLabel`, `jumpCaptionLabel` (all `JLabel`) — plus `correctsOriginalVoucherNumber` (`String`, nullable). None of these are consumed outside this file.

- [x] **Step 1: Write the failing tests**

Add to `VoucherPanelNavigationTest.groovy`, directly after the existing `newSeriesButtonTooltipUpdatesAfterALocaleSwitch` test (after its closing `}` around line 675):

```groovy
  @Test
  void headerCaptionLabelsUpdateAfterALocaleSwitch() {
    Locale previousLocale = I18n.instance.locale
    JLabel voucherNumberCaption = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.voucherNumber')
    }
    JLabel dateCaption = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.date')
    }
    JLabel descriptionCaption = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.description')
    }
    JLabel seriesCaption = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.series')
    }
    JLabel jumpCaption = findComponent(panel, JLabel) { JLabel label ->
      label.text == I18n.instance.getString('voucherPanel.label.jump')
    }

    try {
      onEdt { I18n.instance.setLocale(Locale.forLanguageTag('sv')) }

      assertEquals(I18n.instance.getString('voucherPanel.label.voucherNumber'), onEdt { voucherNumberCaption.text })
      assertEquals(I18n.instance.getString('voucherPanel.label.date'), onEdt { dateCaption.text })
      assertEquals(I18n.instance.getString('voucherPanel.label.description'), onEdt { descriptionCaption.text })
      assertEquals(I18n.instance.getString('voucherPanel.label.series'), onEdt { seriesCaption.text })
      assertEquals(I18n.instance.getString('voucherPanel.label.jump'), onEdt { jumpCaption.text })
    } finally {
      onEdt { I18n.instance.setLocale(previousLocale) }
    }
  }

  @Test
  void correctsLabelUpdatesAfterALocaleSwitch() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 1), 'Original',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id, 'Korrigering')
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.last')) }
    JLabel correctsLabel = findComponent(panel, JLabel) { JLabel label ->
      label.text.startsWith(I18n.instance.getString('voucherPanel.label.corrects'))
    }
    assertTrue(onEdt { correctsLabel.visible })
    Locale previousLocale = I18n.instance.locale

    try {
      onEdt { I18n.instance.setLocale(Locale.forLanguageTag('sv')) }

      assertTrue(onEdt { correctsLabel.text.startsWith(I18n.instance.getString('voucherPanel.label.corrects')) })
      assertTrue(onEdt { correctsLabel.text.endsWith(original.voucherNumber) })
    } finally {
      onEdt { I18n.instance.setLocale(previousLocale) }
    }
  }
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.headerCaptionLabelsUpdateAfterALocaleSwitch" --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.correctsLabelUpdatesAfterALocaleSwitch"`

Expected: FAIL — both `assertEquals` calls in the first test fail (labels still show the English text after switching to `sv`); the second test fails on the `startsWith` assertion after the locale switch.

- [x] **Step 3: Add the new fields**

In `VoucherPanel.groovy`, replace:

```groovy
  private final JLabel correctsLabel = new JLabel('')
  private final JLabel totalsLabel = new JLabel('')
  private final JTextArea feedbackArea = new JTextArea(2, 40)
  private final JTextField jumpField = new JTextField(8)
  private final JCheckBox advanceAfterSaveCheckBox = new JCheckBox()
```

with:

```groovy
  private final JLabel correctsLabel = new JLabel('')
  private String correctsOriginalVoucherNumber
  private final JLabel totalsLabel = new JLabel('')
  private final JTextArea feedbackArea = new JTextArea(2, 40)
  private final JTextField jumpField = new JTextField(8)
  private final JCheckBox advanceAfterSaveCheckBox = new JCheckBox()
  private final JLabel voucherNumberCaptionLabel = new JLabel(I18n.instance.getString('voucherPanel.label.voucherNumber'))
  private final JLabel dateCaptionLabel = new JLabel(I18n.instance.getString('voucherPanel.label.date'))
  private final JLabel descriptionCaptionLabel = new JLabel(I18n.instance.getString('voucherPanel.label.description'))
  private final JLabel seriesCaptionLabel = new JLabel(I18n.instance.getString('voucherPanel.label.series'))
  private final JLabel jumpCaptionLabel = new JLabel(I18n.instance.getString('voucherPanel.label.jump'))
```

- [x] **Step 4: Use the new fields in `buildHeaderBar()`**

Replace:

```groovy
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.voucherNumber')), constraints)
    constraints.gridx++
    panel.add(voucherNumberLabel, constraints)
    unsavedLabel.foreground = new Color(180, 83, 9)
    unsavedLabel.text = I18n.instance.getString('voucherPanel.label.unsaved')
    unsavedLabel.toolTipText = I18n.instance.getString('voucherPanel.label.unsaved')
    constraints.gridx++
    panel.add(unsavedLabel, constraints)
    constraints.gridx++
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.date')), constraints)
    constraints.gridx++
    panel.add(datePicker, constraints)
    constraints.gridx++
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.description')), constraints)
    descriptionField.addActionListener { moveCursorToCell(0, 0) }
    constraints.gridx++
    constraints.weightx = 1.0G
    constraints.fill = GridBagConstraints.HORIZONTAL
    panel.add(descriptionField, constraints)
    constraints.gridx++
    constraints.weightx = 0.0G
    constraints.fill = GridBagConstraints.NONE
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.series')), constraints)
```

with:

```groovy
    panel.add(voucherNumberCaptionLabel, constraints)
    constraints.gridx++
    panel.add(voucherNumberLabel, constraints)
    unsavedLabel.foreground = new Color(180, 83, 9)
    unsavedLabel.text = I18n.instance.getString('voucherPanel.label.unsaved')
    unsavedLabel.toolTipText = I18n.instance.getString('voucherPanel.label.unsaved')
    constraints.gridx++
    panel.add(unsavedLabel, constraints)
    constraints.gridx++
    panel.add(dateCaptionLabel, constraints)
    constraints.gridx++
    panel.add(datePicker, constraints)
    constraints.gridx++
    panel.add(descriptionCaptionLabel, constraints)
    descriptionField.addActionListener { moveCursorToCell(0, 0) }
    constraints.gridx++
    constraints.weightx = 1.0G
    constraints.fill = GridBagConstraints.HORIZONTAL
    panel.add(descriptionField, constraints)
    constraints.gridx++
    constraints.weightx = 0.0G
    constraints.fill = GridBagConstraints.NONE
    panel.add(seriesCaptionLabel, constraints)
```

- [x] **Step 5: Use the new field in `buildNavigationToolbar()`**

Replace:

```groovy
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.jump')))
    jumpField.addActionListener { jumpToVoucher(jumpField.text) }
```

with:

```groovy
    panel.add(jumpCaptionLabel)
    jumpField.addActionListener { jumpToVoucher(jumpField.text) }
```

- [x] **Step 6: Refresh the new labels (and the "corrects" label) in `updateLabels()`**

Replace:

```groovy
  private void updateLabels() {
    datePicker.locale = I18n.instance.locale
    prevButton.toolTipText = I18n.instance.getString('voucherPanel.button.prev')
```

with:

```groovy
  private void updateLabels() {
    datePicker.locale = I18n.instance.locale
    voucherNumberCaptionLabel.text = I18n.instance.getString('voucherPanel.label.voucherNumber')
    dateCaptionLabel.text = I18n.instance.getString('voucherPanel.label.date')
    descriptionCaptionLabel.text = I18n.instance.getString('voucherPanel.label.description')
    seriesCaptionLabel.text = I18n.instance.getString('voucherPanel.label.series')
    jumpCaptionLabel.text = I18n.instance.getString('voucherPanel.label.jump')
    if (correctsOriginalVoucherNumber != null) {
      correctsLabel.text = I18n.instance.getString('voucherPanel.label.corrects') + ' ' + correctsOriginalVoucherNumber
    }
    prevButton.toolTipText = I18n.instance.getString('voucherPanel.button.prev')
```

- [x] **Step 7: Track the original voucher number so it survives a locale switch**

In `showVoucher()`, replace:

```groovy
    if (v.originalVoucherId != null) {
      Voucher original = voucherService.findVoucher(v.originalVoucherId)
      String originalNumber = original?.voucherNumber ?: String.valueOf(v.originalVoucherId)
      correctsLabel.text = I18n.instance.getString('voucherPanel.label.corrects') + ' ' + originalNumber
      correctsLabel.visible = true
    } else {
      correctsLabel.text = ''
      correctsLabel.visible = false
    }
```

with:

```groovy
    if (v.originalVoucherId != null) {
      Voucher original = voucherService.findVoucher(v.originalVoucherId)
      correctsOriginalVoucherNumber = original?.voucherNumber ?: String.valueOf(v.originalVoucherId)
      correctsLabel.text = I18n.instance.getString('voucherPanel.label.corrects') + ' ' + correctsOriginalVoucherNumber
      correctsLabel.visible = true
    } else {
      correctsOriginalVoucherNumber = null
      correctsLabel.text = ''
      correctsLabel.visible = false
    }
```

In `showEmptyVoucher()`, replace:

```groovy
    correctsLabel.text = ''
    correctsLabel.visible = false
    lineTableModel.clear()
```

with:

```groovy
    correctsOriginalVoucherNumber = null
    correctsLabel.text = ''
    correctsLabel.visible = false
    lineTableModel.clear()
```

- [x] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`

Expected: PASS (all tests in the class).

- [x] **Step 9: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy
git commit -m "fix: refresh VoucherPanel caption and corrects labels on locale switch"
```

---

### Task 4: Final verification

- [x] **Step 1: Format**

Run: `./gradlew spotlessApply`

Then inspect `git diff` — Spotless can reformat unrelated lines or touch Markdown; revert anything outside the files this plan intentionally changed.

- [x] **Step 2: Static analysis on the modified production classes**

Run: `./gradlew codenarcMain`

Expected: no new violations in `AccountingMcpTools.groovy`, `McpToolDefinitions.groovy`, `SieImportExportService.groovy`, or `VoucherPanel.groovy`.

- [x] **Step 3: Full build**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL (compilation, all tests, Spotless, CodeNarc).

- [x] **Step 4: Manual smoke check (Swing changes)**

Run: `./gradlew run`, open the Vouchers tab, switch the app language from the menu, and confirm every header/nav label (and the "Corrects ..." label, if a correction voucher is open) updates immediately. Take a screenshot before/after the switch per `CLAUDE.md`'s "include screenshots for Swing UI changes" guidance if this work is going into a PR.
