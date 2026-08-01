# Corrected-Voucher Marker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface "this voucher was corrected by <number(s)>" wherever a voucher can be viewed (transaction report, voucher editor), and warn — without hard-blocking — before creating another correction against an already-corrected voucher, in both the Swing GUI and the MCP tool.

**Architecture:** A single new narrow query (`VoucherService.findCorrectionVoucherNumbers`) is the source of truth everywhere, keyed off the existing `voucher.original_voucher_id` column with no status filter. The transaction report gets a bulk, fiscal-year-scoped variant of the same idea (`ReportSqlLoader.loadCorrectionVoucherNumbersByOriginal`) to avoid N+1 queries. No schema changes.

**Tech Stack:** Groovy, Gradle, JUnit 6 (`groovier-junit`), H2 (embedded), Swing, FreeMarker (report templates), a local MCP tool dispatcher.

## Global Constraints

- No schema migration — `voucher.original_voucher_id` is sufficient (spec: Non-goals).
- All correction-lookup queries filter on `original_voucher_id` only, never on `status` — the pairing of `original_voucher_id` and `status = CORRECTION` is an application-level invariant, not DB-enforced (spec: Design §1).
- Wording is "Corrected by <number>" / "Korrigerad av <nummer>" everywhere — never "Superseded"/"Ersatt" (spec: Semantics).
- The original voucher's `status` and appearance in reports/balances are unchanged — this is a display-only addition (spec: Semantics).
- No change to `VoucherStatus.CANCELLED` handling, and no new guard preventing multiple corrections against the same original — only a non-blocking warning (spec: Non-goals, Goals).
- Correction-of-correction chains are impossible today (`VoucherService.createCorrectionVoucher` requires `status == ACTIVE`) and this work does not change that.
- Follow `CLAUDE.md`: 2-space indentation, no unrelated formatting churn, `./gradlew spotlessApply` before each commit, `./gradlew codenarcMain` after modifying production Groovy classes.

---

## File Structure

- **Modify** `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy` — add `findCorrectionVoucherNumbers(long): List<String>`.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/service/ReportSqlLoader.groovy` — add `loadCorrectionVoucherNumbersByOriginal(Sql, long): Map<Long, List<String>>`.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy` — use the bulk lookup in `buildTransactionReport`, localize the status column.
- **Modify** `app/src/main/resources/i18n/messages.properties` / `messages_sv.properties` — new report-status, label, and confirmation keys.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy` — add `correctedByLabel`, wire it into `showVoucher()`/`refreshCaptionLabels()`/`showEmptyVoucher()`, add a shared `confirmRecorrectionIfNeeded` helper wired into both `correctionButton` and `deleteOrCancelVoucher()`.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/mcp/McpToolDefinitions.groovy` — add `force` parameter to the `create_correction_voucher` tool schema.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy` — add the force-check warning behavior.
- **Test** `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceTest.groovy` — `findCorrectionVoucherNumbers` coverage.
- **Test** `app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy` — transaction report status column coverage.
- **Test** `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy` — `correctedByLabel` and confirm-before-recorrecting coverage.
- **Test** `app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy` — `force` schema coverage.
- **Test** `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy` — `force` behavior coverage.

---

### Task 1: `VoucherService.findCorrectionVoucherNumbers`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy:150-172` (insert new method after the two `findVoucher` overloads, before `listVouchers`)
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceTest.groovy`

**Interfaces:**
- Produces: `VoucherService.findCorrectionVoucherNumbers(long originalVoucherId): List<String>` — voucher numbers of all vouchers whose `original_voucher_id` equals the argument, ordered by `running_number`, empty list if none. Consumed by Task 3 (editor), Task 4 (confirm helper), Task 6 (MCP).

- [ ] **Step 1: Write the failing tests**

Add to `VoucherServiceTest.groovy`, immediately after the existing `recordedVoucherCanBeCorrected` test (around line 243):

```groovy
  @Test
  void findCorrectionVoucherNumbersReturnsEmptyForAnUncorrectedVoucher() {
    Voucher active = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 2, 1), 'Ej korrigerad', balancedLines(100.00G))

    assertEquals([], voucherService.findCorrectionVoucherNumbers(active.id))
  }

  @Test
  void findCorrectionVoucherNumbersReturnsTheCorrectionsVoucherNumber() {
    Voucher active = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 2, 2), 'Korrigerad en gång', balancedLines(100.00G))
    Voucher correction = voucherService.createCorrectionVoucher(active.id)

    assertEquals([correction.voucherNumber], voucherService.findCorrectionVoucherNumbers(active.id))
  }

  @Test
  void findCorrectionVoucherNumbersReturnsMultipleCorrectionsInRunningNumberOrder() {
    Voucher active = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 2, 3), 'Korrigerad flera gånger', balancedLines(100.00G))
    Voucher firstCorrection = voucherService.createCorrectionVoucher(active.id)
    Voucher secondCorrection = voucherService.createCorrectionVoucher(active.id)

    assertEquals(
        [firstCorrection.voucherNumber, secondCorrection.voucherNumber],
        voucherService.findCorrectionVoucherNumbers(active.id))
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.service.VoucherServiceTest"`
Expected: compilation failure — `findCorrectionVoucherNumbers` does not exist on `VoucherService`.

- [ ] **Step 3: Implement the method**

In `VoucherService.groovy`, insert after the closing brace of the `findVoucher(long companyId, long fiscalYearId, String voucherNumber)` overload (line 172) and before `List<Voucher> listVouchers(` (line 174):

```groovy
  List<String> findCorrectionVoucherNumbers(long originalVoucherId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select voucher_number as voucherNumber
            from voucher
           where original_voucher_id = ?
           order by running_number
      ''', [originalVoucherId]).collect { GroovyRowResult row -> row.get('voucherNumber') as String }
    }
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.VoucherServiceTest"`
Expected: PASS (all tests in the class, including the three new ones).

- [ ] **Step 5: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherServiceTest.groovy
git commit -m "$(cat <<'EOF'
lägg till findCorrectionVoucherNumbers i VoucherService

Grundfrågan som resten av "korrigerad av"-markeringen bygger på: hittar
alla verifikationer vars original_voucher_id pekar på en given
verifikation, ordnat efter running_number.
EOF
)"
```

---

### Task 2: Transaction report status column

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ReportSqlLoader.groovy` (add method after `loadPostingLines`, which ends at line 187)
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy:1067-1127` (`buildTransactionReport` and `transactionReportHeaders`)
- Modify: `app/src/main/resources/i18n/messages.properties` and `messages_sv.properties`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy`

**Interfaces:**
- Consumes: `VoucherService.findCorrectionVoucherNumbers` is NOT used here — the report uses its own bulk query for efficiency (see below), by design (spec: Design §1).
- Produces: `ReportSqlLoader.loadCorrectionVoucherNumbersByOriginal(Sql sql, long fiscalYearId): Map<Long, List<String>>` — map of original voucher id → correction voucher numbers, for all corrections in that fiscal year.

- [ ] **Step 1: Write the failing tests**

Add to `ReportServicesTest.groovy`, immediately after the `densePdfReportTemplatesUseFixedColumnTables` test (around line 240):

```groovy
  @Test
  void transactionReportMarksACorrectedVoucherAndLocalizesTheStatusColumn() {
    Voucher toBeCorrected = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 2, 5), 'Felbokad post',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 100.00G)
        ]
    )
    Voucher correction = voucherService.createCorrectionVoucher(toBeCorrected.id, 'Rättelse')

    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.TRANSACTION_REPORT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28)
    ))

    int statusColumn = report.tableHeaders.indexOf(I18n.instance.getString('transactionReport.column.status'))
    int originalRowIndex = report.rowVoucherIds.indexOf(toBeCorrected.id)
    int correctionRowIndex = report.rowVoucherIds.indexOf(correction.id)

    assertEquals(
        I18n.instance.format('transactionReport.status.correctedBy', correction.voucherNumber),
        report.tableRows[originalRowIndex][statusColumn]
    )
    assertEquals(
        I18n.instance.getString('transactionReport.status.correction'),
        report.tableRows[correctionRowIndex][statusColumn]
    )
  }

  @Test
  void transactionReportShowsPlainActiveStatusForUncorrectedVouchers() {
    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.TRANSACTION_REPORT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    int statusColumn = report.tableHeaders.indexOf(I18n.instance.getString('transactionReport.column.status'))
    assertTrue(report.tableRows.every { List<String> row ->
      row[statusColumn] == I18n.instance.getString('transactionReport.status.active')
    })
  }

  @Test
  void transactionReportJoinsMultipleCorrectionNumbers() {
    Voucher toBeCorrected = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2026, 2, 10), 'Felbokad flera gånger',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 100.00G)
        ]
    )
    Voucher firstCorrection = voucherService.createCorrectionVoucher(toBeCorrected.id)
    Voucher secondCorrection = voucherService.createCorrectionVoucher(toBeCorrected.id)

    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.TRANSACTION_REPORT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28)
    ))

    int statusColumn = report.tableHeaders.indexOf(I18n.instance.getString('transactionReport.column.status'))
    int originalRowIndex = report.rowVoucherIds.indexOf(toBeCorrected.id)

    assertEquals(
        I18n.instance.format('transactionReport.status.correctedBy',
            "${firstCorrection.voucherNumber}, ${secondCorrection.voucherNumber}"),
        report.tableRows[originalRowIndex][statusColumn]
    )
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.service.ReportServicesTest"`
Expected: FAIL — status column still contains raw `ACTIVE`/`CORRECTION` enum text, not the localized/corrected-by strings.

- [ ] **Step 3: Add the bulk query to `ReportSqlLoader.groovy`**

Insert immediately after `loadPostingLines` (its closing brace is at line 187). This deliberately filters on `fiscal_year_id`, unlike `VoucherService.findCorrectionVoucherNumbers` (Task 1), which has no fiscal-year filter at all — that's safe only because a correction is always created in its original's own fiscal year (`VoucherService.createCorrectionVoucher` passes `original.fiscalYearId` straight through to `insertVoucher`, and `ensureFiscalYearOpen` is checked against that same id). If a correction could ever land in a different fiscal year than its original, this bulk report query would miss it while the single-voucher lookup used by the editor and MCP would not — worth a one-line comment in the code itself, and worth re-checking this method if `createCorrectionVoucher`'s fiscal-year handling ever changes:

```groovy
  // Corrections always share the original's fiscal year (VoucherService.createCorrectionVoucher
  // never lets it differ), so scoping this bulk lookup to one fiscal year is safe.
  static Map<Long, List<String>> loadCorrectionVoucherNumbersByOriginal(Sql sql, long fiscalYearId) {
    Map<Long, List<String>> correctionsByOriginal = [:]
    sql.rows('''
        select original_voucher_id as originalVoucherId,
               voucher_number as voucherNumber
          from voucher
         where fiscal_year_id = ?
           and original_voucher_id is not null
         order by running_number
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      long originalVoucherId = ((Number) row.get('originalVoucherId')).longValue()
      correctionsByOriginal.computeIfAbsent(originalVoucherId) { [] as List<String> } << (row.get('voucherNumber') as String)
    }
    correctionsByOriginal
  }
```

- [ ] **Step 4: Update `buildTransactionReport` and `transactionReportHeaders` in `ReportDataService.groovy`**

Replace the existing `buildTransactionReport` and `transactionReportHeaders` methods (as of before this task's edits, lines 1067-1127 — locate by name, since Task 1 touched a different file and can't have shifted these, but treat the line numbers as a hint, not a guarantee) with:

```groovy
  private ReportResult buildTransactionReport(EffectiveSelection effective) {
    List<TransactionReportRow> rows = databaseService.withSql { Sql sql ->
      ReportSqlLoader.loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
          .sort { PostingLine line ->
            [line.accountingDate, line.voucherNumber ?: '', line.voucherId, line.lineIndex]
          }.collect { PostingLine line ->
            new TransactionReportRow(
                line.voucherId,
                line.accountingDate,
                line.voucherNumber,
                line.accountNumber,
                line.accountName,
                line.voucherDescription,
                line.lineDescription,
                line.debitAmount,
                line.creditAmount,
                line.status
            )
          }
    }
    Map<Long, List<String>> correctionsByOriginal = databaseService.withSql { Sql sql ->
      ReportSqlLoader.loadCorrectionVoucherNumbersByOriginal(sql, effective.selection.fiscalYearId)
    }
    BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.debitAmount } as BigDecimal
    BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.creditAmount } as BigDecimal
    createResult(
        effective,
        [
            I18n.instance.format('transactionReport.summary.count', rows.size()),
            I18n.instance.format('transactionReport.summary.debitTotal', formatAmountLocale(scale(debitTotal), effective.locale)),
            I18n.instance.format('transactionReport.summary.creditTotal', formatAmountLocale(scale(creditTotal), effective.locale))
        ],
        transactionReportHeaders(),
        rows.collect { TransactionReportRow row ->
          stringRow(
              row.accountingDate.toString(),
              row.voucherNumber,
              row.accountNumber,
              row.accountName,
              row.voucherDescription,
              row.lineDescription ?: '',
              formatAmountLocale(row.debitAmount, effective.locale),
              formatAmountLocale(row.creditAmount, effective.locale),
              transactionStatusLabel(row, correctionsByOriginal)
          )
        },
        rows.collect { TransactionReportRow row -> row.voucherId },
        [typedRows: rows, lead: I18n.instance.getString('report.transactionReport.lead')]
    )
  }

  private static String transactionStatusLabel(TransactionReportRow row, Map<Long, List<String>> correctionsByOriginal) {
    List<String> correctionNumbers = correctionsByOriginal[row.voucherId]
    if (correctionNumbers) {
      return I18n.instance.format('transactionReport.status.correctedBy', correctionNumbers.join(', '))
    }
    row.status == 'CORRECTION' ?
        I18n.instance.getString('transactionReport.status.correction') :
        I18n.instance.getString('transactionReport.status.active')
  }

  private static List<String> transactionReportHeaders() {
    [
        I18n.instance.getString('transactionReport.column.date'),
        I18n.instance.getString('transactionReport.column.voucher'),
        I18n.instance.getString('transactionReport.column.account'),
        I18n.instance.getString('transactionReport.column.accountName'),
        I18n.instance.getString('transactionReport.column.voucherText'),
        I18n.instance.getString('transactionReport.column.lineText'),
        I18n.instance.getString('transactionReport.column.debit'),
        I18n.instance.getString('transactionReport.column.credit'),
        I18n.instance.getString('transactionReport.column.status')
    ]
  }
```

- [ ] **Step 5: Add the new i18n keys**

In `app/src/main/resources/i18n/messages.properties`, immediately after `transactionReport.column.status=Status` (line 175):

```properties
transactionReport.status.active=Active
transactionReport.status.correction=Correction
transactionReport.status.correctedBy=Corrected by {0}
```

In `app/src/main/resources/i18n/messages_sv.properties`, at the same position:

```properties
transactionReport.status.active=Aktiv
transactionReport.status.correction=Korrigering
transactionReport.status.correctedBy=Korrigerad av {0}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.ReportServicesTest"`
Expected: PASS (all tests, including the three new ones and `densePdfReportTemplatesUseFixedColumnTables`, which must still pass since row/column structure is unchanged).

- [ ] **Step 7: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/service/ReportSqlLoader.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy \
        app/src/main/resources/i18n/messages.properties \
        app/src/main/resources/i18n/messages_sv.properties \
        app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy
git commit -m "$(cat <<'EOF'
visa "korrigerad av" i transaktionsrapportens statuskolumn

Statuskolumnen visade tidigare den råa ACTIVE/CORRECTION-strängen
olokaliserat. Nu lokaliseras den, och en verifikation som sedan
korrigerats får texten "Korrigerad av <nummer>" istället för "Aktiv",
så man inte råkar använda en redan korrigerad verifikation som referens.
EOF
)"
```

---

### Task 3: Voucher editor "corrected by" label

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`
- Modify: `app/src/main/resources/i18n/messages.properties` and `messages_sv.properties`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy`

**Interfaces:**
- Consumes: `VoucherService.findCorrectionVoucherNumbers(long): List<String>` (Task 1).
- Produces: a `correctedByLabel` JLabel (found in tests via `label.name == 'correctedByLabel'`), visible with text "Corrected by <numbers>" whenever the currently displayed voucher has one or more corrections; hidden and cleared otherwise. Consumed by Task 4's tests only insofar as they share the same `showVoucher()` flow — no direct interface dependency.

- [ ] **Step 1: Write the failing tests**

Add to `VoucherPanelNavigationTest.groovy`, immediately after `correctsLabelUpdatesAfterALocaleSwitch` (around line 921):

```groovy
  @Test
  void correctedByLabelShowsAfterVoucherIsCorrected() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 1), 'Original',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    Voucher correction = voucherService.createCorrectionVoucher(original.id, 'Korrigering')
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    JLabel correctedByLabel = findComponent(panel, JLabel) { JLabel label -> label.name == 'correctedByLabel' }

    assertTrue(onEdt { correctedByLabel.visible })
    assertTrue(onEdt { correctedByLabel.text.endsWith(correction.voucherNumber) })
  }

  @Test
  void correctedByLabelUpdatesAfterALocaleSwitch() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 2), 'Original2',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id, 'Korrigering2')
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    JLabel correctedByLabel = findComponent(panel, JLabel) { JLabel label -> label.name == 'correctedByLabel' }
    Locale previousLocale = I18n.instance.locale

    try {
      onEdt { I18n.instance.setLocale(Locale.forLanguageTag('sv')) }

      assertTrue(onEdt { correctedByLabel.text.startsWith(I18n.instance.getString('voucherPanel.label.correctedBy')) })
    } finally {
      onEdt { I18n.instance.setLocale(previousLocale) }
    }
  }

  @Test
  void correctedByLabelIsClearedOnAFreshBlankVoucher() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 3), 'Original3',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id, 'Korrigering3')
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }
    JLabel correctedByLabel = findComponent(panel, JLabel) { JLabel label -> label.name == 'correctedByLabel' }
    assertTrue(onEdt { correctedByLabel.visible })

    panel?.dispose()
    panel = buildPanel()

    JLabel correctedByLabelOnFreshPanel = findComponent(panel, JLabel) { JLabel label -> label.name == 'correctedByLabel' }
    assertFalse(onEdt { correctedByLabelOnFreshPanel.visible })
  }

  @Test
  void correctionVoucherNeverShowsCorrectedByLabelAlongsideCorrectsLabel() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 4), 'Original4',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id, 'Korrigering4')
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.last')) }

    JLabel correctsLabel = findComponent(panel, JLabel) { JLabel label ->
      label.text.startsWith(I18n.instance.getString('voucherPanel.label.corrects'))
    }
    JLabel correctedByLabel = findComponent(panel, JLabel) { JLabel label -> label.name == 'correctedByLabel' }

    assertTrue(onEdt { correctsLabel.visible })
    assertFalse(onEdt { correctedByLabel.visible })
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`
Expected: FAIL — `findComponent` cannot find a `JLabel` with `name == 'correctedByLabel'` (it doesn't exist yet).

- [ ] **Step 3: Add the field**

In `VoucherPanel.groovy`, change the `correctsLabel`/`correctsOriginalVoucherNumber` field declarations (lines 112-113) from:

```groovy
  private final JLabel correctsLabel = new JLabel('')
  private String correctsOriginalVoucherNumber
```

to:

```groovy
  private final JLabel correctsLabel = new JLabel('')
  private String correctsOriginalVoucherNumber
  private final JLabel correctedByLabel = new JLabel('').tap { name = 'correctedByLabel' }
  private String correctedByVoucherNumbers
```

- [ ] **Step 4: Add the header label placement**

In `VoucherPanel.groovy`, change `buildHeaderBar()` (lines 261-270) from:

```groovy
  private JPanel buildHeaderBar() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints constraints = new GridBagConstraints(
        gridx: 0, gridy: 0, anchor: GridBagConstraints.WEST, insets: new Insets(4, 0, 4, 8))
    addVoucherHeaderFields(panel, constraints)
    addDescriptionHeaderField(panel, constraints)
    addSeriesHeaderControls(panel, constraints)
    addCorrectsHeaderLabel(panel, constraints)
    panel
  }
```

to:

```groovy
  private JPanel buildHeaderBar() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints constraints = new GridBagConstraints(
        gridx: 0, gridy: 0, anchor: GridBagConstraints.WEST, insets: new Insets(4, 0, 4, 8))
    addVoucherHeaderFields(panel, constraints)
    addDescriptionHeaderField(panel, constraints)
    addSeriesHeaderControls(panel, constraints)
    addCorrectsHeaderLabel(panel, constraints)
    addCorrectedByHeaderLabel(panel, constraints)
    panel
  }
```

And add a new method immediately after `addCorrectsHeaderLabel` (its closing brace is at line 325):

```groovy
  private void addCorrectedByHeaderLabel(JPanel panel, GridBagConstraints constraints) {
    correctedByLabel.visible = false
    constraints.gridx = 0
    constraints.gridy++
    constraints.weightx = 0.2G
    constraints.fill = GridBagConstraints.HORIZONTAL
    constraints.gridwidth = GridBagConstraints.REMAINDER
    constraints.insets = new Insets(0, 0, 4, 0)
    panel.add(correctedByLabel, constraints)
  }
```

(`addCorrectsHeaderLabel` already set `gridwidth = REMAINDER` for itself, so `correctedByLabel` needs its own row — starting a fresh `gridy` and resetting `gridx` to 0 avoids fighting over the same row.)

- [ ] **Step 5: Wire `showVoucher()`**

In `VoucherPanel.groovy`, immediately after the existing `if (v.originalVoucherId != null) { ... } else { ... }` block in `showVoucher()` (lines 782-791), add:

```groovy
    List<String> correctionNumbers = voucherService.findCorrectionVoucherNumbers(v.id)
    if (correctionNumbers) {
      correctedByVoucherNumbers = correctionNumbers.join(', ')
      correctedByLabel.text = I18n.instance.getString('voucherPanel.label.correctedBy') + ' ' + correctedByVoucherNumbers
      correctedByLabel.visible = true
    } else {
      correctedByVoucherNumbers = null
      correctedByLabel.text = ''
      correctedByLabel.visible = false
    }
```

- [ ] **Step 6: Wire `refreshCaptionLabels()`**

In `VoucherPanel.groovy`, immediately after the existing `if (correctsOriginalVoucherNumber != null) { ... }` block in `refreshCaptionLabels()` (lines 1468-1470), add:

```groovy
    if (correctedByVoucherNumbers != null) {
      correctedByLabel.text = I18n.instance.getString('voucherPanel.label.correctedBy') + ' ' + correctedByVoucherNumbers
    }
```

- [ ] **Step 7: Wire the reset in `showEmptyVoucher()`**

In `VoucherPanel.groovy`, immediately after the existing `correctsLabel.visible = false` line in `showEmptyVoucher()` (line 826), add:

```groovy
    correctedByVoucherNumbers = null
    correctedByLabel.text = ''
    correctedByLabel.visible = false
```

- [ ] **Step 8: Add the new i18n key**

In `app/src/main/resources/i18n/messages.properties`, immediately after `voucherPanel.label.corrects=Corrects`:

```properties
voucherPanel.label.correctedBy=Corrected by
```

In `app/src/main/resources/i18n/messages_sv.properties`, at the same position:

```properties
voucherPanel.label.correctedBy=Korrigerad av
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`
Expected: PASS (all tests in the class, including the four new ones and the existing `correctsLabelUpdatesAfterALocaleSwitch`).

- [ ] **Step 10: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy \
        app/src/main/resources/i18n/messages.properties \
        app/src/main/resources/i18n/messages_sv.properties \
        app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy
git commit -m "$(cat <<'EOF'
lägg till "korrigerad av"-etikett i verifikationsredigeraren

Speglar den befintliga "Korrigerar X"-etiketten men i andra riktningen:
visas när den öppna verifikationen själv har korrigerats, så man inte
råkar återanvända en redan korrigerad verifikation som mall.
EOF
)"
```

---

### Task 4: Warn before creating another correction (GUI)

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`
- Modify: `app/src/main/resources/i18n/messages.properties` and `messages_sv.properties`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy`

**Interfaces:**
- Consumes: `VoucherService.findCorrectionVoucherNumbers(long): List<String>` (Task 1).
- Produces: `VoucherPanel.confirmRecorrectionIfNeeded(long voucherId): boolean` (private); a `@PackageScope Closure<Boolean> recorrectionConfirmer` test seam and a `@PackageScope Closure<Boolean> cannotDeleteConfirmer` test seam (both mirror the existing `attachmentFileChooser` seam pattern at `VoucherPanel.groovy:104`, overridable in tests to avoid driving a real `JOptionPane`); `deleteOrCancelVoucher()` changes from `private` to `@PackageScope` so `VoucherPanelNavigationTest` can invoke it directly — it has no other way in, since `voidButton` is permanently disabled and a disabled `JButton.doClick()` never fires its listener.

- [ ] **Step 1: Write the failing tests**

Add to `VoucherPanelNavigationTest.groovy`, immediately after the tests added in Task 3:

```groovy
  @Test
  void correctionButtonCreatesACorrectionWithoutPromptingWhenNoneExistYet() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 5), 'Original5',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    boolean confirmerCalled = false
    panel?.dispose()
    panel = buildPanel()
    panel.recorrectionConfirmer = { List<String> existing -> confirmerCalled = true; true }
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.createCorrection')) }

    assertFalse(confirmerCalled)
    assertEquals(1, voucherService.findCorrectionVoucherNumbers(original.id).size())
  }

  @Test
  void correctionButtonPromptsBeforeASecondCorrectionAndRespectsCancellation() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 6), 'Original6',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    Voucher firstCorrection = voucherService.createCorrectionVoucher(original.id)
    List<String> confirmerArgument = null
    panel?.dispose()
    panel = buildPanel()
    panel.recorrectionConfirmer = { List<String> existing -> confirmerArgument = existing; false }
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.createCorrection')) }

    assertEquals([firstCorrection.voucherNumber], confirmerArgument)
    assertEquals([firstCorrection.voucherNumber], voucherService.findCorrectionVoucherNumbers(original.id))
  }

  @Test
  void correctionButtonCreatesASecondCorrectionWhenConfirmerApproves() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 7), 'Original7',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id)
    panel?.dispose()
    panel = buildPanel()
    panel.recorrectionConfirmer = { List<String> existing -> true }
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.createCorrection')) }

    assertEquals(2, voucherService.findCorrectionVoucherNumbers(original.id).size())
  }

  @Test
  void deleteOrCancelVoucherWarnsBeforeASecondCorrectionEvenThoughVoidButtonIsDisabled() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 8), 'Original8',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    Voucher firstCorrection = voucherService.createCorrectionVoucher(original.id)
    panel?.dispose()
    panel = buildPanel()
    panel.cannotDeleteConfirmer = { Voucher voucher -> true }
    List<String> confirmerArgument = null
    panel.recorrectionConfirmer = { List<String> existing -> confirmerArgument = existing; false }
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    onEdt { panel.deleteOrCancelVoucher() }

    assertEquals([firstCorrection.voucherNumber], confirmerArgument)
    assertEquals([firstCorrection.voucherNumber], voucherService.findCorrectionVoucherNumbers(original.id))
  }

  @Test
  void deleteOrCancelVoucherCreatesASecondCorrectionWhenBothConfirmationsApprove() {
    Voucher original = voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 4, 9), 'Original9',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    voucherService.createCorrectionVoucher(original.id)
    panel?.dispose()
    panel = buildPanel()
    panel.cannotDeleteConfirmer = { Voucher voucher -> true }
    panel.recorrectionConfirmer = { List<String> existing -> true }
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.first')) }

    onEdt { panel.deleteOrCancelVoucher() }

    assertEquals(2, voucherService.findCorrectionVoucherNumbers(original.id).size())
  }
```

Each test rebuilds `panel` via `panel?.dispose(); panel = buildPanel()` before setting `recorrectionConfirmer`/`cannotDeleteConfirmer`, since the fields live on the panel instance and the default `setUp()`-built panel isn't wired to the fixture voucher yet at that point. The last two tests call `panel.deleteOrCancelVoucher()` directly — this is the only way to exercise that method at all today, since `voidButton` is permanently disabled (`voidButton.enabled = false` in `applyReadOnlyState()`) and Swing's `AbstractButton.doClick()` is a no-op on a disabled button.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`
Expected: compilation failure — `recorrectionConfirmer` does not exist on `VoucherPanel`.

- [ ] **Step 3: Add the test seam and shared confirm helper**

In `VoucherPanel.groovy`, immediately after the existing `attachmentFileChooser` field (line 104):

```groovy
  @PackageScope
  Closure<Boolean> recorrectionConfirmer = { List<String> existingNumbers -> showRecorrectionConfirmDialog(existingNumbers) }
  @PackageScope
  Closure<Boolean> cannotDeleteConfirmer = { Voucher voucher -> showCannotDeleteConfirmDialog(voucher) }
```

Add three new private methods, inserted immediately before the `deleteOrCancelVoucher()` method (note: Task 3 already shifted this method's line number down from its pre-Task-3 position of 1121 by inserting the field, header-label method, and `showVoucher()`/`showEmptyVoucher()` blocks earlier in the file — locate `deleteOrCancelVoucher()` by name, not by line number):

```groovy
  private boolean confirmRecorrectionIfNeeded(long voucherId) {
    List<String> existingNumbers = voucherService.findCorrectionVoucherNumbers(voucherId)
    existingNumbers.isEmpty() || recorrectionConfirmer.call(existingNumbers)
  }

  private boolean showRecorrectionConfirmDialog(List<String> existingNumbers) {
    int choice = javax.swing.JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format('voucherPanel.confirm.alreadyCorrected', existingNumbers.join(', ')),
        I18n.instance.getString('voucherPanel.button.createCorrection'),
        javax.swing.JOptionPane.YES_NO_OPTION
    )
    choice == javax.swing.JOptionPane.YES_OPTION
  }

  private boolean showCannotDeleteConfirmDialog(Voucher voucher) {
    int choice = javax.swing.JOptionPane.showConfirmDialog(
        this,
        I18n.instance.getString('voucherPanel.confirm.cannotDelete')
            .replace('{0}', voucher.voucherNumber ?: ''),
        I18n.instance.getString('voucherPanel.button.void'),
        javax.swing.JOptionPane.YES_NO_OPTION
    )
    choice == javax.swing.JOptionPane.YES_OPTION
  }

```

`cannotDeleteConfirmer` extracts the dialog that already existed in `deleteOrCancelVoucher()` behind the same kind of seam as `recorrectionConfirmer`. This is what makes Step 5 below fully testable headlessly: without it, `deleteOrCancelVoucher()` would still pop a real, unstubbed `JOptionPane` even after `recorrectionConfirmer` is overridden in a test.

- [ ] **Step 4: Wire the `correctionButton` listener**

In `VoucherPanel.groovy`, change the `correctionButton` listener (its line numbers have also shifted from their pre-Task-3 position — locate by the exact code below, which is still unique in the file) from:

```groovy
    correctionButton.addActionListener {
      if (voucherEditorActions.createCorrection() != null) {
        reloadVoucherList()
      }
    }
```

to:

```groovy
    correctionButton.addActionListener {
      if (currentVoucher != null && !confirmRecorrectionIfNeeded(currentVoucher.id)) {
        return
      }
      if (voucherEditorActions.createCorrection() != null) {
        reloadVoucherList()
      }
    }
```

- [ ] **Step 5: Wire `deleteOrCancelVoucher()`**

In `VoucherPanel.groovy`, change `deleteOrCancelVoucher()` (locate by name — line numbers have shifted, see note in Step 3) from:

```groovy
  private void deleteOrCancelVoucher() {
    if (currentVoucher == null) {
      return
    }
    try {
      int choice = javax.swing.JOptionPane.showConfirmDialog(
          this,
          I18n.instance.getString('voucherPanel.confirm.cannotDelete')
              .replace('{0}', currentVoucher.voucherNumber ?: ''),
          I18n.instance.getString('voucherPanel.button.void'),
          javax.swing.JOptionPane.YES_NO_OPTION
      )
      if (choice == javax.swing.JOptionPane.YES_OPTION) {
        Voucher correction = voucherService.createCorrectionVoucher(currentVoucher.id, null)
        showInfo(I18n.instance.format('voucherPanel.message.correctionCreated',
            correction.voucherNumber ?: ''))
        reloadVoucherList()
      }
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.voidFailed'))
    }
  }
```

to:

```groovy
  @PackageScope
  void deleteOrCancelVoucher() {
    if (currentVoucher == null) {
      return
    }
    try {
      if (!cannotDeleteConfirmer.call(currentVoucher)) {
        return
      }
      if (!confirmRecorrectionIfNeeded(currentVoucher.id)) {
        return
      }
      Voucher correction = voucherService.createCorrectionVoucher(currentVoucher.id, null)
      showInfo(I18n.instance.format('voucherPanel.message.correctionCreated',
          correction.voucherNumber ?: ''))
      reloadVoucherList()
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.voidFailed'))
    }
  }
```

Two changes beyond the added `confirmRecorrectionIfNeeded` check: the inline `JOptionPane.showConfirmDialog` call is replaced by `cannotDeleteConfirmer.call(currentVoucher)` (extracting it behind the same kind of seam as `recorrectionConfirmer`, added in Step 3), and the method goes from `private` to `@PackageScope`. Both changes exist solely to make this method testable — `voidButton` is currently hard-disabled in `applyReadOnlyState()`, so this path isn't reachable through the running GUI today, but it calls `VoucherService.createCorrectionVoucher` directly and must not silently skip the warning if that button is ever re-enabled (see spec Design §4). Without both changes, this task's new warning logic would ship with zero test coverage on this call site, automated or manual.

- [ ] **Step 6: Add the new i18n key**

In `app/src/main/resources/i18n/messages.properties`, immediately after `voucherPanel.confirm.cannotDelete=...`:

```properties
voucherPanel.confirm.alreadyCorrected=This voucher was already corrected by {0}. Create another correction anyway?
```

In `app/src/main/resources/i18n/messages_sv.properties`, at the same position:

```properties
voucherPanel.confirm.alreadyCorrected=Den här verifikationen har redan korrigerats av {0}. Skapa ännu en korrigering ändå?
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`
Expected: PASS (all tests in the class).

- [ ] **Step 8: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy \
        app/src/main/resources/i18n/messages.properties \
        app/src/main/resources/i18n/messages_sv.properties \
        app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy
git commit -m "$(cat <<'EOF'
varna innan en andra korrigering skapas i GUI:t

Delad bekräftelsehjälpare (confirmRecorrectionIfNeeded) används av både
korrigeringsknappen och deleteOrCancelVoucher()/voidButton, så en
verifikation som redan korrigerats aldrig kan korrigeras igen utan
varning - även om voidButton skulle aktiveras i framtiden. Båda
dialogerna (bekräfta korrigering, bekräfta att man inte kan radera)
körs nu bakom testbara closure-seams (recorrectionConfirmer,
cannotDeleteConfirmer), enligt samma mönster som attachmentFileChooser,
och deleteOrCancelVoucher() är @PackageScope så testerna kan anropa den
direkt - annars hade den vägen saknat all testtäckning eftersom
voidButton är permanent inaktiverad.
EOF
)"
```

---

### Task 5: MCP schema — `force` parameter

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/McpToolDefinitions.groovy:141-148`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy`

**Interfaces:**
- Produces: the `create_correction_voucher` tool's published `inputSchema.properties` gains a `force` key (`optBoolParam`), not added to `required`. Consumed by Task 6.

- [ ] **Step 1: Write the failing test**

Add to `McpToolDefinitionsTest.groovy`:

```groovy
  @Test
  void createCorrectionVoucherSchemaExposesAnOptionalForceFlag() {
    Map<String, Object> correctionDef = McpToolDefinitions.listTools().find { Map<String, Object> tool ->
      tool.name == 'create_correction_voucher'
    } as Map<String, Object>
    assertNotNull(correctionDef, 'create_correction_voucher tool definition must be registered')
    Map<String, Object> inputSchema = correctionDef.inputSchema as Map<String, Object>
    Map<String, Object> properties = inputSchema.get('properties') as Map<String, Object>
    List<String> required = inputSchema.get('required') as List<String>

    assertTrue(properties.containsKey('force'), 'force must be declared as a tool parameter')
    assertFalse(required.contains('force'), 'force must be optional')
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.McpToolDefinitionsTest"`
Expected: FAIL — `properties.containsKey('force')` is false.

- [ ] **Step 3: Add the schema parameter**

In `McpToolDefinitions.groovy`, change the `create_correction_voucher` `toolDef` call (lines 141-148) from:

```groovy
        toolDef('create_correction_voucher',
            'Creates a reversing correction voucher for an existing posted voucher. Direct edits to posted vouchers are not permitted.',
            ['original_voucher_id'],
            [
                original_voucher_id: intParam('ID of the voucher to correct'),
                description: optStrParam('Optional description for the correction. Defaults to "Korrigering av <original>".')
            ]
        ),
```

to:

```groovy
        toolDef('create_correction_voucher',
            'Creates a reversing correction voucher for an existing posted voucher. Direct edits to posted vouchers are not permitted.',
            ['original_voucher_id'],
            [
                original_voucher_id: intParam('ID of the voucher to correct'),
                description: optStrParam('Optional description for the correction. Defaults to "Korrigering av <original>".'),
                force: optBoolParam('Set to true to create another correction even though this voucher already has one or more. Required when create_correction_voucher previously returned ok:false with warning:true.')
            ]
        ),
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.McpToolDefinitionsTest"`
Expected: PASS (both the new test and the existing `exportSieOutputPathSchemaDescribesTheAiWorkspaceConfinement`).

- [ ] **Step 5: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/mcp/McpToolDefinitions.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/mcp/McpToolDefinitionsTest.groovy
git commit -m "$(cat <<'EOF'
lägg till valfri force-parameter i create_correction_voucher-schemat

Schemat måste deklarera parametern separat från verktygslogiken -
annars avvisas ett force:true-anrop redan innan det når
AccountingMcpTools.
EOF
)"
```

---

### Task 6: MCP behavior — warn before creating another correction

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy:628-647`
- Test: `app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy`

**Interfaces:**
- Consumes: `VoucherService.findCorrectionVoucherNumbers(long): List<String>` (Task 1), `force` schema parameter (Task 5).
- Produces: `create_correction_voucher` returns `[ok: false, warning: true, existing_corrections: [...], errors: [...]]` when corrections already exist and `force` is not `true`; otherwise behaves exactly as before.

- [ ] **Step 1: Write the failing tests**

Add to `AccountingMcpToolsTest.groovy`, immediately after `createCorrectionVoucherCreatesReversingVoucher` (around line 870):

```groovy
  @Test
  void createCorrectionVoucherWarnsWithoutForceWhenAlreadyCorrected() {
    Map<String, Object> posted = previewAndPost(balancedVoucherArgs('Original för dubbel korrigering', 150.00G))
    long originalId = ((Number) posted.get('voucher_id')).longValue()
    Map<String, Object> firstCorrection = tools.callTool('create_correction_voucher', [
        original_voucher_id: (Object) originalId
    ])
    assertTrue((boolean) firstCorrection.get('ok'))
    String firstCorrectionNumber = firstCorrection.get('voucher_number') as String

    Map<String, Object> secondAttempt = tools.callTool('create_correction_voucher', [
        original_voucher_id: (Object) originalId
    ])

    assertFalse((boolean) secondAttempt.get('ok'))
    assertEquals(true, secondAttempt.get('warning'))
    assertEquals([firstCorrectionNumber], secondAttempt.get('existing_corrections'))
  }

  @Test
  void createCorrectionVoucherProceedsWhenForceIsTrue() {
    Map<String, Object> posted = previewAndPost(balancedVoucherArgs('Original för forcerad korrigering', 175.00G))
    long originalId = ((Number) posted.get('voucher_id')).longValue()
    tools.callTool('create_correction_voucher', [original_voucher_id: (Object) originalId])

    Map<String, Object> secondCorrection = tools.callTool('create_correction_voucher', [
        original_voucher_id: (Object) originalId,
        force: (Object) true
    ])

    assertTrue((boolean) secondCorrection.get('ok'), "Expected ok but got: ${secondCorrection.get('errors')}")
    assertNotNull(secondCorrection.get('voucher_id'))
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`
Expected: FAIL — `createCorrectionVoucherWarnsWithoutForceWhenAlreadyCorrected` fails because the second attempt currently succeeds (`ok: true`) instead of returning a warning.

- [ ] **Step 3: Implement the force check**

In `AccountingMcpTools.groovy`, change `createCorrectionVoucher` (lines 628-647) from:

```groovy
  private Map<String, Object> createCorrectionVoucher(Map<String, Object> args) {
    long originalVoucherId = requiredLong(args, 'original_voucher_id')
    String description = args.get('description') as String
    try {
      Voucher correction = voucherService.createCorrectionVoucher(originalVoucherId, description)
      [
          ok: true,
          voucher_id: correction.id,
          voucher_number: correction.voucherNumber,
          original_voucher_id: correction.originalVoucherId,
          fiscal_year_id: correction.fiscalYearId,
          accounting_date: correction.accountingDate?.toString(),
          description: correction.description,
          status: correction.status?.name(),
          line_count: correction.lines?.size() ?: 0
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }
```

to:

```groovy
  private Map<String, Object> createCorrectionVoucher(Map<String, Object> args) {
    long originalVoucherId = requiredLong(args, 'original_voucher_id')
    String description = args.get('description') as String
    boolean force = optionalBoolean(args, 'force', false)
    try {
      List<String> existingCorrections = voucherService.findCorrectionVoucherNumbers(originalVoucherId)
      if (existingCorrections && !force) {
        return [
            ok: false,
            warning: true,
            existing_corrections: existingCorrections,
            errors: ["This voucher was already corrected by ${existingCorrections.join(', ')}. Pass force: true to create another correction anyway.".toString()]
        ]
      }
      Voucher correction = voucherService.createCorrectionVoucher(originalVoucherId, description)
      [
          ok: true,
          voucher_id: correction.id,
          voucher_number: correction.voucherNumber,
          original_voucher_id: correction.originalVoucherId,
          fiscal_year_id: correction.fiscalYearId,
          accounting_date: correction.accountingDate?.toString(),
          description: correction.description,
          status: correction.status?.name(),
          line_count: correction.lines?.size() ?: 0
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.mcp.AccountingMcpToolsTest"`
Expected: PASS (all tests in the class, including `createCorrectionVoucherCreatesReversingVoucher`, which must still pass unchanged since it only creates one correction).

- [ ] **Step 5: Format, lint, commit**

```bash
./gradlew spotlessApply
git diff --stat
./gradlew codenarcMain
git add app/src/main/groovy/se/alipsa/accounting/mcp/AccountingMcpTools.groovy \
        app/src/test/groovy/integration/se/alipsa/accounting/mcp/AccountingMcpToolsTest.groovy
git commit -m "$(cat <<'EOF'
varna innan en andra korrigering skapas via MCP

create_correction_voucher returnerar nu ok:false med warning:true och
existing_corrections istället för att tyst skapa ännu en korrigering,
om inte force:true skickas med. Speglar GUI:ts bekräftelsedialog för
en AI-driven anropare som inte kan svara på en Swing-dialog.
EOF
)"
```

---

### Task 7: Full build verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — compilation, all tests (unit, integration, acceptance), Spotless, and CodeNarc all pass.

- [ ] **Step 2: Manually verify the GUI changes**

Run: `./gradlew run`. Create a voucher, create a correction against it via the pencil (✎) button, and confirm, in the default (English) locale:
- The original voucher shows "Corrected by <number>" when navigated to directly.
- The correction voucher shows "Corrects <number>" as before, and does *not* also show "Corrected by".
- Clicking the correction button again on the original voucher shows a confirmation dialog naming the existing correction; choosing "No" creates nothing, choosing "Yes" creates a second correction.
- Run a Transaction Report covering the period and confirm the original voucher's row shows "Corrected by <number>" in the status column instead of "Active".
- With a corrected voucher displayed, check that the new "Corrected by" row in the header doesn't awkwardly resize or crowd the voucher editor window — `addCorrectedByHeaderLabel` (Task 3) adds a whole new `GridBagLayout` row, which is a layout change this session hasn't visually verified.

Then switch the application's language to Swedish (via its language/locale setting) and repeat the same checks, confirming the label reads "Korrigerad av <nummer>", the report's status column reads "Korrigerad av <nummer>" instead of "Aktiv", and the confirmation dialog text is in Swedish too.

The `JOptionPane` confirmation dialogs still have no headless test coverage for their actual on-screen rendering, even though Task 4's automated tests now drive the full decision logic — including the `deleteOrCancelVoucher()`/`voidButton` path, via the `recorrectionConfirmer`/`cannotDeleteConfirmer` seams and calling the now-`@PackageScope` method directly — without showing a real dialog. This step is specifically about the dialogs' visual rendering and the header layout change, which is what still needs an eyeball check per `CLAUDE.md`'s Swing verification guidance.

- [ ] **Step 3: No commit for this task** — it's verification only, nothing to stage.

---

## Self-Review Notes

- **Spec coverage:** Goals (surface marker in report + editor: Tasks 2, 3; handle multiple corrections: Tasks 1-3 tests; warn without hard-blocking in GUI + MCP: Tasks 4, 6) all have tasks. Non-goals (no chains, no CANCELLED handling, no migration) require no tasks — verified nothing in the plan violates them. Semantics section (informational-only, "Corrected by" wording) is reflected in every i18n key added. Design §1's "no status filter" rule is followed by both new queries (Task 1, Task 2). Design §5's testing list is fully covered by Tasks 1-6's test steps plus Task 7's manual dialog check.
- **Placeholder scan:** no TBD/TODO; every step has concrete code or an exact command.
- **Type consistency:** `findCorrectionVoucherNumbers(long): List<String>` (Task 1) is the exact signature used in Task 3 (`voucherService.findCorrectionVoucherNumbers(v.id)`), Task 4 (`confirmRecorrectionIfNeeded`), and Task 6 (`existingCorrections`). `loadCorrectionVoucherNumbersByOriginal(Sql, long): Map<Long, List<String>>` (Task 2) is used only within `ReportDataService`, consistent with the spec's decision to keep the report's bulk lookup separate from the single-voucher lookup. `recorrectionConfirmer` and `cannotDeleteConfirmer` are both declared as `Closure<Boolean>` in Task 4 and every test override returns a `boolean`, matching.

## Review Round 2 Notes

A second review pass caught: (1) several `VoucherPanel.groovy` line-number citations in Task 4 that go stale because Task 3 edits the same file first — fixed by anchoring those steps to method/field names instead, with the exact before/after code blocks doing the real work of locating the edit; (2) `report.tableRows[...][8]` in Task 2's tests was a fragile magic index — fixed to look the column up via `report.tableHeaders.indexOf(...)`; (3) `deleteOrCancelVoucher()`'s new warning check had zero test coverage, automated or manual, since `voidButton` is permanently disabled — fixed by extracting its `JOptionPane` behind a `cannotDeleteConfirmer` seam (same pattern as `recorrectionConfirmer`) and making the method `@PackageScope`, then adding two tests that call it directly; (4) `loadCorrectionVoucherNumbersByOriginal`'s fiscal-year scoping assumption is now called out with an explicit code comment, not just prose in this plan; (5) Task 7's manual verification now explicitly includes a Swedish-locale pass and a check that the new header row doesn't break the editor window's layout. `TransactionReportRow.status`/`PostingLine.status` being plain `String` (so `row.status == 'CORRECTION'` in Task 2 is type-safe) and `AccountingMcpTools.optionalBoolean` already existing (used as-is in Task 6) were both re-verified against the current source and needed no change. The spec + plan files were renamed from `superseded-voucher-marker*` to `corrected-voucher-marker*` to match the "Corrected by" terminology used throughout instead of the discarded "Superseded" wording.
