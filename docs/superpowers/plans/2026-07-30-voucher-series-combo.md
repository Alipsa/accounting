# Voucher Series Combo Box Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the free-text voucher series field (`VoucherPanel.seriesField`, a `JTextField`) with a locked-dropdown combo box populated from `VoucherService.listSeries()`, plus a `+` button that opens a small dialog to explicitly create a new series — so every series that ever gets created is a deliberate, visible action instead of a silent side effect of typing an unrecognized code and saving.

**Architecture:** All changes live in the existing `se.alipsa.accounting.ui` package. One new file (`NewVoucherSeriesDialog.groovy`, a small modal `JDialog` matching the style of `PeriodLockDialog.groovy`). One existing file substantially modified (`VoucherPanel.groovy`) — the series field becomes a `JComboBox<VoucherSeries>`, and several existing private methods gain new logic to keep the combo, the lock state, and the "create series" write path consistent with each other. No service-layer or schema changes: `VoucherService.listSeries()` and `VoucherService.ensureSeries()` already exist and are reused as-is.

**Tech Stack:** Groovy, Swing, JUnit 6 (`groovier-junit`), H2 (test DB via `DatabaseService.newForTesting()`).

## Global Constraints

- 2-space indentation in Groovy files.
- `@CompileStatic` is enforced globally via `config/groovy/compileStatic.groovy` — do not add per-class `@CompileStatic` annotations.
- Every new user-facing string needs a key in **both** `app/src/main/resources/i18n/messages.properties` (English) and `messages_sv.properties` (Swedish, non-ASCII characters written as `\uXXXX` escapes, matching the existing file's convention).
- Tests go under `app/src/test/groovy/{unit|integration}/...` by directory, but **package declarations for `se.alipsa.accounting.ui` tests use `package se.alipsa.accounting.ui`** (not `unit.se.alipsa.accounting.ui`/`integration.se.alipsa.accounting.ui`) — this is required for package-private (`@PackageScope`) access to `VoucherPanel` internals, and is what the existing `VoucherPanelNavigationTest.groovy` and `SieExchangeDialogTest.groovy` already do. Follow the same convention for new test files in this area.
- Never test a modal `JDialog`'s `setVisible(true)` path directly (it blocks the calling thread until disposed). Extract validation logic into a static, pure method and unit-test that instead — this is the established pattern in `SieExchangeDialogTest.groovy` (`SieExchangeDialog.sieImportFileFilter()`, `SieExchangeDialog.initialFiscalYearId(...)`).
- After all production-code changes: run `./gradlew spotlessApply`, inspect the diff (it can touch `.properties`/Markdown too), then `./gradlew codenarcMain`, then the full `./gradlew build`.
- Full design rationale for every decision below lives in `docs/superpowers/specs/2026-07-30-voucher-series-combo-design.md` — this plan implements it; consult it if a "why" isn't obvious here.

---

## File Structure

- **Create** `app/src/main/groovy/se/alipsa/accounting/ui/NewVoucherSeriesDialog.groovy` — modal dialog collecting a series code (required) + name (optional), calls `VoucherService.ensureSeries(...)`.
- **Create** `app/src/test/groovy/unit/se/alipsa/accounting/ui/NewVoucherSeriesDialogTest.groovy` — unit tests for the dialog's static code-validation helper.
- **Modify** `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy` — swap `seriesField` for `seriesComboBox` + `newSeriesButton`; add lock-awareness helpers; update every read/write call site; add three package-scope test seams.
- **Modify** `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy` — extend with new test cases covering the combo's baseline behavior and all review findings from the design spec.
- **Modify** `app/src/main/resources/i18n/messages.properties` and `messages_sv.properties` — new keys for the `+` button, the dialog, and one new error message.

---

## Task 1: i18n keys

**Files:**
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

**Interfaces:**
- Produces: the message keys `voucherPanel.button.newSeries`, `voucherPanel.error.seriesCreationLocked`, `newVoucherSeriesDialog.title`, `newVoucherSeriesDialog.label.code`, `newVoucherSeriesDialog.label.name`, `newVoucherSeriesDialog.button.ok`, `newVoucherSeriesDialog.button.cancel`, `newVoucherSeriesDialog.error.invalidCode` — consumed by Task 2 and Task 3.

- [ ] **Step 1: Add the new keys to `messages.properties`**

Find the existing `voucherPanel.label.series=Series` line (around line 841) and add the new button key directly after it:

```properties
voucherPanel.label.series=Series
voucherPanel.button.newSeries=Create new series...
```

Find the existing `voucherPanel.error.periodLocked=...` line (around line 882) and add the new error key directly after it:

```properties
voucherPanel.error.periodLocked=The fiscal year is locked. Reopen it before registering corrections.
voucherPanel.error.seriesCreationLocked=Cannot create a voucher series while the accounting period is locked.
```

Add a new block at the end of the file for the dialog:

```properties
newVoucherSeriesDialog.title=New voucher series
newVoucherSeriesDialog.label.code=Code
newVoucherSeriesDialog.label.name=Name
newVoucherSeriesDialog.button.ok=OK
newVoucherSeriesDialog.button.cancel=Cancel
newVoucherSeriesDialog.error.invalidCode=Series code must be 1-8 characters, A-Z or 0-9.
```

- [ ] **Step 2: Add the matching keys to `messages_sv.properties`**

Same insertion points, Swedish text (unicode-escaped to match the file's existing convention):

```properties
voucherPanel.label.series=Serie
voucherPanel.button.newSeries=Skapa ny serie...
```

```properties
voucherPanel.error.periodLocked=Räkenskapsåret är låst. Lås upp det innan du registrerar rättelser.
voucherPanel.error.seriesCreationLocked=Det går inte att skapa en verifikationsserie när räkenskapsperioden är låst.
```

```properties
newVoucherSeriesDialog.title=Ny verifikationsserie
newVoucherSeriesDialog.label.code=Kod
newVoucherSeriesDialog.label.name=Namn
newVoucherSeriesDialog.button.ok=OK
newVoucherSeriesDialog.button.cancel=Avbryt
newVoucherSeriesDialog.error.invalidCode=Seriekoden måste vara 1-8 tecken, A-Z eller 0-9.
```

- [ ] **Step 3: Verify the properties files still parse**

Run: `./gradlew spotlessCheck`
Expected: PASS (or only reformats unrelated to your edits — if so, run `./gradlew spotlessApply` and re-check the diff touches only what you added).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/i18n/messages.properties app/src/main/resources/i18n/messages_sv.properties
git commit -m "i18n: add voucher series dialog and button strings"
```

---

## Task 2: `NewVoucherSeriesDialog`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/NewVoucherSeriesDialog.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/ui/NewVoucherSeriesDialogTest.groovy`

**Interfaces:**
- Consumes: `VoucherService.ensureSeries(long fiscalYearId, String seriesCode, String seriesName = null)` (existing, returns `VoucherSeries`), `VoucherSeries` domain class (existing: `id`, `fiscalYearId`, `seriesCode`, `seriesName`, `nextRunningNumber`), i18n keys from Task 1.
- Produces: `static VoucherSeries showDialog(Frame owner, VoucherService voucherService, long fiscalYearId)` — the only entry point Task 3 calls. Also `static String normalizeCode(String rawCode)` — pure validation helper, `null` if invalid, otherwise the trimmed/uppercased code.

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/groovy/unit/se/alipsa/accounting/ui/NewVoucherSeriesDialogTest.groovy`:

```groovy
package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

final class NewVoucherSeriesDialogTest {

  @Test
  void normalizesValidCodesToUppercase() {
    assertEquals('B', NewVoucherSeriesDialog.normalizeCode('b'))
    assertEquals('B2', NewVoucherSeriesDialog.normalizeCode(' b2 '))
    assertEquals('ABCDEFGH', NewVoucherSeriesDialog.normalizeCode('abcdefgh'))
  }

  @Test
  void rejectsBlankCode() {
    assertNull(NewVoucherSeriesDialog.normalizeCode(''))
    assertNull(NewVoucherSeriesDialog.normalizeCode(null))
    assertNull(NewVoucherSeriesDialog.normalizeCode('   '))
  }

  @Test
  void rejectsCodesLongerThanEightCharacters() {
    assertNull(NewVoucherSeriesDialog.normalizeCode('ABCDEFGHI'))
  }

  @Test
  void rejectsCodesWithCharactersOutsideAToZAndZeroToNine() {
    assertNull(NewVoucherSeriesDialog.normalizeCode('B-1'))
    assertNull(NewVoucherSeriesDialog.normalizeCode('B 1'))
    assertNull(NewVoucherSeriesDialog.normalizeCode('Ö'))
  }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.NewVoucherSeriesDialogTest"`
Expected: FAIL — `NewVoucherSeriesDialog` doesn't exist yet.

- [ ] **Step 3: Implement `NewVoucherSeriesDialog.groovy`**

Create `app/src/main/groovy/se/alipsa/accounting/ui/NewVoucherSeriesDialog.groovy`:

```groovy
package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridLayout

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Collects a series code (required) and name (optional), then resolves it via
 * {@link VoucherService#ensureSeries}. If the code already exists, ensureSeries returns the
 * existing row - this dialog treats that as success, not an error.
 */
final class NewVoucherSeriesDialog extends JDialog {

  private static final String CODE_PATTERN = /[A-Z0-9]{1,8}/

  private final VoucherService voucherService
  private final long fiscalYearId
  private final JTextField codeField = new JTextField(8)
  private final JTextField nameField = new JTextField(24)
  private final JLabel errorLabel = new JLabel(' ')
  private VoucherSeries result

  NewVoucherSeriesDialog(Frame owner, VoucherService voucherService, long fiscalYearId) {
    super(owner, I18n.instance.getString('newVoucherSeriesDialog.title'), true)
    this.voucherService = voucherService
    this.fiscalYearId = fiscalYearId
    buildUi()
  }

  /** Shows the dialog modally and returns the created/matched series, or null if cancelled. */
  static VoucherSeries showDialog(Frame owner, VoucherService voucherService, long fiscalYearId) {
    NewVoucherSeriesDialog dialog = new NewVoucherSeriesDialog(owner, voucherService, fiscalYearId)
    dialog.setVisible(true)
    dialog.result
  }

  /** Trims and uppercases rawCode; returns null if the result isn't 1-8 chars of A-Z/0-9. */
  static String normalizeCode(String rawCode) {
    String code = rawCode?.trim()?.toUpperCase(Locale.ROOT)
    (code && code ==~ CODE_PATTERN) ? code : null
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    JPanel formPanel = new JPanel(new GridLayout(2, 2, 8, 8))
    formPanel.add(new JLabel(I18n.instance.getString('newVoucherSeriesDialog.label.code')))
    formPanel.add(codeField)
    formPanel.add(new JLabel(I18n.instance.getString('newVoucherSeriesDialog.label.name')))
    formPanel.add(nameField)
    add(formPanel, BorderLayout.CENTER)

    errorLabel.foreground = new Color(153, 27, 27)
    add(errorLabel, BorderLayout.NORTH)

    add(buildButtonPanel(), BorderLayout.SOUTH)

    pack()
    setResizable(false)
    setLocationRelativeTo(owner)
  }

  private JPanel buildButtonPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    JButton cancelButton = new JButton(I18n.instance.getString('newVoucherSeriesDialog.button.cancel'))
    cancelButton.addActionListener { dispose() }
    panel.add(cancelButton)

    JButton okButton = new JButton(I18n.instance.getString('newVoucherSeriesDialog.button.ok'))
    okButton.addActionListener { okRequested() }
    panel.add(okButton)
    panel
  }

  private void okRequested() {
    String code = normalizeCode(codeField.text)
    if (code == null) {
      errorLabel.text = I18n.instance.getString('newVoucherSeriesDialog.error.invalidCode')
      pack()
      return
    }
    try {
      result = voucherService.ensureSeries(fiscalYearId, code, nameField.text?.trim() ?: null)
      dispose()
    } catch (IllegalArgumentException exception) {
      errorLabel.text = exception.message
      pack()
    }
  }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.NewVoucherSeriesDialogTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: CodeNarc the new file**

Run: `./gradlew codenarcMain`
Expected: PASS. Fix any violations (don't suppress) before moving on.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/NewVoucherSeriesDialog.groovy app/src/test/groovy/unit/se/alipsa/accounting/ui/NewVoucherSeriesDialogTest.groovy
git commit -m "feat: add NewVoucherSeriesDialog for creating voucher series"
```

---

## Task 3: Rewire `VoucherPanel.groovy`

This is one cohesive change: `VoucherPanel` is a single Swing class where the series field, the lock-check, the date picker, and several existing methods all need to agree with each other at every commit (the class must compile and its own existing tests must still pass). It is implemented as one step with the full set of edits, then verified.

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`

**Interfaces:**
- Consumes: `NewVoucherSeriesDialog.showDialog(Frame, VoucherService, long)` (Task 2), `VoucherService.listSeries(long fiscalYearId)` and `VoucherService.ensureSeries(long, String, String)` (existing), `AccountingPeriodService.isDateLocked(long companyId, LocalDate date)` (existing), `DatePicker.addListener(Consumer<LocalDate>)` (existing, in `se.alipsa.datepicker.DatePicker`), `VoucherDraftMapper.VoucherDraft` (existing, `se.alipsa.accounting.ui.VoucherDraftMapper.VoucherDraft`), `VoucherNavigation.rememberDraft(VoucherDraftMapper.VoucherDraft)` (existing).
- Produces (new members Task 4's tests rely on): field `JComboBox<VoucherSeries> seriesComboBox`, field `JButton newSeriesButton`, `private boolean isNewVoucherPeriodLocked()`, and three `@PackageScope` test seams added in Step 10: `void rememberDraftForTest(VoucherDraftMapper.VoucherDraft draft)`, `void reloadVoucherListForTest()`, `void createNewSeriesForTest()`. `applyDraft(VoucherDraftMapper.VoucherDraft)` now throws `IllegalArgumentException` for an unresolvable series code (was previously silent).

- [ ] **Step 1: Update imports**

At the top of `VoucherPanel.groovy`, add two imports (the file already imports `VoucherSeries`, `SwingUtilities`, `LocalDate`, `Consumer`, `Logger`, so those don't need re-adding):

```groovy
import java.awt.Frame
```

and

```groovy
import javax.swing.JComboBox
```

Add these alongside the existing `import java.awt.*` and `import javax.swing.*` groups respectively (`java.awt.Frame` next to `java.awt.FlowLayout`/`java.awt.Insets`; `javax.swing.JComboBox` next to `javax.swing.JButton`).

- [ ] **Step 2: Replace the `seriesField` declaration**

Find (around line 100):

```groovy
  private final JTextField seriesField = new JTextField(4)
```

Replace with:

```groovy
  private final JComboBox<VoucherSeries> seriesComboBox = new JComboBox<>()
  private boolean applyingSeriesProgrammatically = false
```

Find the `JButton` field declarations block (around lines 107-117, ending with `private JButton openAttachmentButton`) and add one more field:

```groovy
  private JButton newSeriesButton
```

- [ ] **Step 3: Update the `VoucherEditorActions` construction in the constructor**

Find (around line 175-177):

```groovy
    voucherEditorActions = new VoucherEditorActions(voucherOperations, { activeCompanyManager.fiscalYear }, { datePicker.date },
        { descriptionField.text?.trim() }, { lineTableModel.toVoucherLines() }, { currentVoucher },
        { seriesField.text?.trim() ?: 'A' }, this::showInfo, this::showError, this::handleSavedVoucher)
```

Replace the series-supplier lambda:

```groovy
    voucherEditorActions = new VoucherEditorActions(voucherOperations, { activeCompanyManager.fiscalYear }, { datePicker.date },
        { descriptionField.text?.trim() }, { lineTableModel.toVoucherLines() }, { currentVoucher },
        { (seriesComboBox.selectedItem as VoucherSeries)?.seriesCode ?: 'A' }, this::showInfo, this::showError, this::handleSavedVoucher)
```

- [ ] **Step 4: Wire the `datePicker` listener in `buildUi()`**

Find `buildUi()` (around line 229):

```groovy
  private void buildUi() {
    setLayout(new BorderLayout(8, 8))
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    add(buildHeaderBar(), BorderLayout.NORTH)
    JPanel center = new JPanel(new BorderLayout(0, 8))
    center.add(buildNavigationToolbar(), BorderLayout.NORTH)
    center.add(buildMainTabs(), BorderLayout.CENTER)
    add(center, BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)
  }
```

Add one line at the end:

```groovy
  private void buildUi() {
    setLayout(new BorderLayout(8, 8))
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    add(buildHeaderBar(), BorderLayout.NORTH)
    JPanel center = new JPanel(new BorderLayout(0, 8))
    center.add(buildNavigationToolbar(), BorderLayout.NORTH)
    center.add(buildMainTabs(), BorderLayout.CENTER)
    add(center, BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)
    datePicker.addListener { LocalDate date -> applyReadOnlyState() }
  }
```

- [ ] **Step 5: Replace the series controls in `buildHeaderBar()`**

Find (around line 240-258):

```groovy
  private JPanel buildHeaderBar() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4))
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.voucherNumber')))
    panel.add(voucherNumberLabel)
    unsavedLabel.foreground = new Color(180, 83, 9)
    unsavedLabel.text = I18n.instance.getString('voucherPanel.label.unsaved')
    unsavedLabel.toolTipText = I18n.instance.getString('voucherPanel.label.unsaved')
    panel.add(unsavedLabel)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.date')))
    panel.add(datePicker)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.description')))
    descriptionField.addActionListener { moveCursorToCell(0, 0) }
    panel.add(descriptionField)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.series')))
    panel.add(seriesField)
    correctsLabel.visible = false
    panel.add(correctsLabel)
    panel
  }
```

Replace the two `seriesField` lines:

```groovy
  private JPanel buildHeaderBar() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4))
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.voucherNumber')))
    panel.add(voucherNumberLabel)
    unsavedLabel.foreground = new Color(180, 83, 9)
    unsavedLabel.text = I18n.instance.getString('voucherPanel.label.unsaved')
    unsavedLabel.toolTipText = I18n.instance.getString('voucherPanel.label.unsaved')
    panel.add(unsavedLabel)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.date')))
    panel.add(datePicker)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.description')))
    descriptionField.addActionListener { moveCursorToCell(0, 0) }
    panel.add(descriptionField)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.series')))
    seriesComboBox.editable = false
    seriesComboBox.addActionListener { onSeriesSelectionChanged() }
    panel.add(seriesComboBox)
    newSeriesButton = navigationButton('+', 'voucherPanel.button.newSeries') { createNewSeries() }
    panel.add(newSeriesButton)
    correctsLabel.visible = false
    panel.add(correctsLabel)
    panel
  }
```

(`VoucherSeries.toString()` already renders `"CODE - Name"`, and `JComboBox`'s default cell renderer calls `toString()` on each item, so no custom renderer is needed. `navigationButton(String, String, Closure)` is the existing private helper used by the navigation toolbar buttons — reused here as-is.)

- [ ] **Step 6: Add the new helper methods**

Add these new private methods. A good location is directly after `previewNextVoucherNumber(String)` (around line 767, right before `defaultDate()`) — they're all part of the same "series state" cluster:

```groovy
  private boolean isNewVoucherPeriodLocked() {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null) {
      return true
    }
    LocalDate date = datePicker.date ?: defaultDate()
    try {
      accountingPeriodService.isDateLocked(activeCompanyManager.companyId, date)
    } catch (Exception ex) {
      log.warning("Kunde inte avgöra om räkenskapsåret är låst – skrivskyddar verifikatet: ${ex.message}")
      true
    }
  }

  private VoucherSeries findComboSeries(String code) {
    if (code == null) {
      return null
    }
    (0..<seriesComboBox.itemCount)
        .collect { int index -> seriesComboBox.getItemAt(index) }
        .find { VoucherSeries series -> series.seriesCode == code }
  }

  private void selectSeriesCode(String code) {
    VoucherSeries match = findComboSeries(code)
    if (match == null) {
      return
    }
    applyingSeriesProgrammatically = true
    try {
      seriesComboBox.selectedItem = match
    } finally {
      applyingSeriesProgrammatically = false
    }
  }

  private void refreshSeriesComboBox(String preferredCode = null) {
    FiscalYear fy = activeCompanyManager.fiscalYear
    VoucherSeries previousSelection = seriesComboBox.selectedItem as VoucherSeries
    applyingSeriesProgrammatically = true
    try {
      seriesComboBox.removeAllItems()
      if (fy == null) {
        return
      }
      List<VoucherSeries> series = voucherService.listSeries(fy.id)
      series.each { VoucherSeries entry -> seriesComboBox.addItem(entry) }
      if (series.isEmpty()) {
        return
      }
      VoucherSeries target = preferredCode == null ? null : series.find { VoucherSeries entry -> entry.seriesCode == preferredCode }
      if (target == null && previousSelection != null) {
        target = series.find { VoucherSeries entry -> entry.seriesCode == previousSelection.seriesCode }
      }
      seriesComboBox.selectedItem = target ?: series.first()
    } finally {
      applyingSeriesProgrammatically = false
    }
  }

  private void ensureDefaultSeriesForNewVoucher() {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null || seriesComboBox.itemCount > 0) {
      return
    }
    if (isNewVoucherPeriodLocked()) {
      return
    }
    voucherService.ensureSeries(fy.id, 'A')
    refreshSeriesComboBox('A')
  }

  private void createNewSeries() {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null || isNewVoucherPeriodLocked()) {
      showError(I18n.instance.getString('voucherPanel.error.seriesCreationLocked'))
      return
    }
    VoucherSeries created = NewVoucherSeriesDialog.showDialog(
        SwingUtilities.getWindowAncestor(this) as Frame, voucherService, fy.id)
    if (created != null) {
      refreshSeriesComboBox(created.seriesCode)
    }
  }

  private void onSeriesSelectionChanged() {
    if (applyingSeriesProgrammatically || currentVoucher != null) {
      return
    }
    VoucherSeries selected = seriesComboBox.selectedItem as VoucherSeries
    if (selected == null) {
      return
    }
    String nextNumber = previewNextVoucherNumber(selected.seriesCode)
    voucherNumberLabel.text = nextNumber
    jumpField.text = nextNumber
  }
```

(`findComboSeries` is the single lookup both `selectSeriesCode` (fail-soft, used for already-saved vouchers where the code is guaranteed to exist) and `applyDraft` (strict, Step 9) rely on. `applyingSeriesProgrammatically` stops `onSeriesSelectionChanged` from overwriting `voucherNumberLabel`/`jumpField` during programmatic selection from `showVoucher`/`duplicateVoucher`/`applyDraft`/`refreshSeriesComboBox`, which already set those labels correctly themselves.)

- [ ] **Step 7: Update `showVoucher(v)`**

Find (around line 705):

```groovy
    seriesField.text = v.seriesCode ?: 'A'
```

Replace with:

```groovy
    selectSeriesCode(v.seriesCode ?: 'A')
```

- [ ] **Step 8: Rewrite `showEmptyVoucher()`**

Find (around lines 729-750):

```groovy
  private void showEmptyVoucher() {
    currentVoucher = null
    pendingReceiptAttachmentPath = null
    readOnly = false
    balanceCache.clear()
    String nextNumber = previewNextVoucherNumber('A')
    voucherNumberLabel.text = nextNumber
    unsavedLabel.visible = true
    jumpField.text = nextNumber
    datePicker.date = defaultDate()
    descriptionField.text = ''
    seriesField.text = 'A'
    correctsLabel.text = ''
    correctsLabel.visible = false
    lineTableModel.clear()
    lineTableModel.addBlankRows(2)
    clearAttachmentAndHistory()
    refreshTotals()
    applyReadOnlyState()
    updateNavigationButtons()
    feedbackArea.text = ''
  }
```

Replace with:

```groovy
  private void showEmptyVoucher() {
    currentVoucher = null
    pendingReceiptAttachmentPath = null
    readOnly = false
    balanceCache.clear()
    // datePicker.date must be set before ensureDefaultSeriesForNewVoucher() runs, so its
    // internal isNewVoucherPeriodLocked() check reads this new blank voucher's own date rather
    // than whatever was left over from the previously displayed voucher/draft.
    datePicker.date = defaultDate()
    ensureDefaultSeriesForNewVoucher()
    VoucherSeries selectedSeries = seriesComboBox.selectedItem as VoucherSeries
    String nextNumber = selectedSeries != null ? previewNextVoucherNumber(selectedSeries.seriesCode) : ''
    voucherNumberLabel.text = nextNumber
    unsavedLabel.visible = true
    jumpField.text = nextNumber
    descriptionField.text = ''
    correctsLabel.text = ''
    correctsLabel.visible = false
    lineTableModel.clear()
    lineTableModel.addBlankRows(2)
    clearAttachmentAndHistory()
    refreshTotals()
    applyReadOnlyState()
    updateNavigationButtons()
    feedbackArea.text = ''
  }
```

- [ ] **Step 9: Update `snapshotDraft()` and rewrite `applyDraft(...)`**

Find `snapshotDraft()` (around line 819-822):

```groovy
  private Map<String, Object> snapshotDraft() {
    VoucherDraftMapper.toDraft(datePicker.date, descriptionField.text, seriesField.text, lineTableModel.toVoucherLines(),
        pendingReceiptAttachmentPath)
  }
```

Replace with:

```groovy
  private Map<String, Object> snapshotDraft() {
    String seriesCode = (seriesComboBox.selectedItem as VoucherSeries)?.seriesCode
    VoucherDraftMapper.toDraft(datePicker.date, descriptionField.text, seriesCode, lineTableModel.toVoucherLines(),
        pendingReceiptAttachmentPath)
  }
```

Find `applyDraft(...)` (around line 824-835):

```groovy
  private void applyDraft(VoucherDraftMapper.VoucherDraft voucherDraft) {
    showBlankVoucher()
    datePicker.date = voucherDraft.accountingDate
    descriptionField.text = voucherDraft.description
    seriesField.text = voucherDraft.seriesCode
    lineTableModel.setRows(voucherDraft.lines)
    pendingReceiptAttachmentPath = voucherDraft.attachmentPath
    ensureAutoRow()
    recalculateAllBalances()
    refreshTotals()
    dateFocusRequester.call()
  }
```

Replace with:

```groovy
  private void applyDraft(VoucherDraftMapper.VoucherDraft voucherDraft) {
    // voucherDraft.seriesCode can come from an external, unvalidated source (the MCP tool
    // set_active_voucher_draft, via VoucherDraftEditorAccess.setVoucherDraft -> this method as
    // draftConsumer). Validate before mutating any panel state: falling back to whatever the
    // combo already had selected would let a later Save post into a different series than
    // requested, with no error.
    VoucherSeries requestedSeries = findComboSeries(voucherDraft.seriesCode)
    if (requestedSeries == null) {
      throw new IllegalArgumentException(
          "Unknown voucher series '${voucherDraft.seriesCode}' for the current fiscal year. Create it first or choose an existing series.")
    }
    showBlankVoucher()
    datePicker.date = voucherDraft.accountingDate
    descriptionField.text = voucherDraft.description
    selectSeriesCode(requestedSeries.seriesCode)
    lineTableModel.setRows(voucherDraft.lines)
    pendingReceiptAttachmentPath = voucherDraft.attachmentPath
    ensureAutoRow()
    recalculateAllBalances()
    refreshTotals()
    dateFocusRequester.call()
  }
```

- [ ] **Step 10: Update `restoreNavigationDraft()` and add the Task 4 test seams**

Find (around lines 847-854):

```groovy
  private void restoreNavigationDraft() {
    VoucherDraftMapper.VoucherDraft draft = navigation.draft()
    if (draft == null) {
      showEmptyVoucher()
      return
    }
    applyDraft(draft)
  }
```

Replace with:

```groovy
  private void restoreNavigationDraft() {
    VoucherDraftMapper.VoucherDraft draft = navigation.draft()
    if (draft == null) {
      showEmptyVoucher()
      return
    }
    try {
      applyDraft(draft)
    } catch (IllegalArgumentException exception) {
      // This draft was populated by snapshotDraft() moments earlier in the same fiscal year, so
      // its series should always resolve - this is a should-never-happen defensive fallback, not
      // a path with untrusted input. An uncaught exception out of a Swing navigation callback is
      // a worse failure mode than silently starting a blank voucher.
      log.warning("Discarding a remembered draft with an unresolvable series: ${exception.message}")
      showEmptyVoucher()
    }
  }

  // --- Test seams below: same-package integration tests (VoucherPanelNavigationTest) need to
  // drive a few private methods directly, since there's no other public trigger for them without
  // going through a real fiscal-year/company switch or a blocking modal dialog. ---

  /** Lets tests seed navigation's remembered draft directly, to exercise
   * restoreNavigationDraft()'s fallback above for a deliberately-invalid draft - a state that can
   * never arise through real user action, since this design has no way to remove or rename an
   * already-created series.
   */
  @PackageScope
  void rememberDraftForTest(VoucherDraftMapper.VoucherDraft draft) {
    navigation.rememberDraft(draft)
  }

  /** Lets tests force a series-combo/voucher-list refresh after creating a series directly via
   * VoucherService (bypassing the modal NewVoucherSeriesDialog), without switching fiscal year.
   */
  @PackageScope
  void reloadVoucherListForTest() {
    reloadVoucherList()
  }

  /** Lets tests exercise createNewSeries()'s own defensive lock re-check without going through
   * the real, blocking NewVoucherSeriesDialog.showDialog() call.
   */
  @PackageScope
  void createNewSeriesForTest() {
    createNewSeries()
  }
```

- [ ] **Step 11: Update `duplicateVoucher()`**

Find (around lines 856-874), the single line:

```groovy
    seriesField.text = seriesCode
```

Replace with:

```groovy
    selectSeriesCode(seriesCode)
```

(`seriesCode` here is `source.seriesCode ?: 'A'` from an existing, already-persisted voucher in the current fiscal year — always resolvable, matching `showVoucher`'s fail-soft `selectSeriesCode` usage.)

- [ ] **Step 12: Rewrite `applyReadOnlyState()`**

Find (around lines 910-949):

```groovy
  private void applyReadOnlyState() {
    boolean fiscalYearClosed = false
    if (currentVoucher != null) {
      readOnly = true
      if (currentVoucher.accountingDate != null) {
        try {
          fiscalYearClosed = accountingPeriodService.isDateLocked(
              activeCompanyManager.companyId, currentVoucher.accountingDate)
        } catch (Exception ex) {
          log.warning("Kunde inte avgöra om räkenskapsåret är låst för korrigeringsknappen: ${ex.message}")
          fiscalYearClosed = true
        }
      } else {
        fiscalYearClosed = true
      }
    } else if (activeCompanyManager.fiscalYear != null) {
      try {
        readOnly = accountingPeriodService.isDateLocked(
            activeCompanyManager.companyId, defaultDate())
      } catch (Exception ex) {
        log.warning("Kunde inte avgöra om räkenskapsåret är låst – skrivskyddar verifikatet: ${ex.message}")
        readOnly = true
      }
    } else {
      readOnly = false
    }
    lineTableModel.editable = !readOnly
    datePicker.enabled = !readOnly
    descriptionField.enabled = !readOnly
    seriesField.enabled = currentVoucher == null
    saveButton.enabled = !readOnly
    printButton.enabled = true
    duplicateButton.enabled = currentVoucher != null
    voidButton.enabled = false
    correctionButton.enabled = currentVoucher != null
        && currentVoucher.accountingDate != null
        && currentVoucher.status == VoucherStatus.ACTIVE
        && !fiscalYearClosed
    addAttachmentButton.enabled = currentVoucher != null
  }
```

Replace with:

```groovy
  private void applyReadOnlyState() {
    boolean fiscalYearClosed = false
    if (currentVoucher != null) {
      readOnly = true
      if (currentVoucher.accountingDate != null) {
        try {
          fiscalYearClosed = accountingPeriodService.isDateLocked(
              activeCompanyManager.companyId, currentVoucher.accountingDate)
        } catch (Exception ex) {
          log.warning("Kunde inte avgöra om räkenskapsåret är låst för korrigeringsknappen: ${ex.message}")
          fiscalYearClosed = true
        }
      } else {
        fiscalYearClosed = true
      }
    } else if (activeCompanyManager.fiscalYear != null) {
      readOnly = isNewVoucherPeriodLocked()
    } else {
      readOnly = false
    }
    lineTableModel.editable = !readOnly
    // Unlike the other controls below, the date picker must stay usable for the whole duration
    // of composing a new voucher regardless of the currently-entered date's lock status - this
    // method is now reentrant (the datePicker listener added in Step 4 calls it on every date
    // change), and disabling the date picker itself here would trap the user in a locked date
    // with no way back (DatePicker.setEnabled(false) also closes its popup).
    datePicker.enabled = currentVoucher == null || !readOnly
    descriptionField.enabled = !readOnly
    seriesComboBox.enabled = currentVoucher == null
    newSeriesButton.enabled = currentVoucher == null && !readOnly
    saveButton.enabled = !readOnly
    printButton.enabled = true
    duplicateButton.enabled = currentVoucher != null
    voidButton.enabled = false
    correctionButton.enabled = currentVoucher != null
        && currentVoucher.accountingDate != null
        && currentVoucher.status == VoucherStatus.ACTIVE
        && !fiscalYearClosed
    addAttachmentButton.enabled = currentVoucher != null
  }
```

- [ ] **Step 13: Update `reloadVoucherList(...)`**

Find (around line 627):

```groovy
  private void reloadVoucherList(Voucher selectedVoucher = null) {
    cancelBalancePreload()
    voucherBalanceCache.clear()
    voucherBalanceCacheGeneration++
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null || activeCompanyManager.companyId <= 0) {
```

Add `refreshSeriesComboBox()` as the first line of the method body:

```groovy
  private void reloadVoucherList(Voucher selectedVoucher = null) {
    // Must run before any showBlankVoucher()/showVoucher()/navigation.select(...) call below -
    // those are the last action of every branch in this method, not after some later "end", so
    // refreshing anywhere but first would let them run against the previous fiscal year's still-
    // loaded combo items when switching fiscal years.
    refreshSeriesComboBox()
    cancelBalancePreload()
    voucherBalanceCache.clear()
    voucherBalanceCacheGeneration++
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null || activeCompanyManager.companyId <= 0) {
```

(The rest of the method is unchanged.)

- [ ] **Step 14: Compile and run the existing test suite**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`

Expected: PASS on all existing tests. If `duplicateCreatesUnsavedDraftWithCopiedVoucherFields` or any other existing test fails, check it's not asserting against `seriesField` directly (none currently do — confirmed by search — but re-verify) and check the combo's default selection logic in `refreshSeriesComboBox`/`ensureDefaultSeriesForNewVoucher` matches what the test expects for series `'A'`/`'B'`.

- [ ] **Step 15: CodeNarc**

Run: `./gradlew codenarcMain`
Expected: PASS. Fix any violations before moving on (unused imports are the most likely one here, e.g. if `Frame` or `JComboBox` end up unused due to a typo).

- [ ] **Step 16: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy
git commit -m "feat: replace voucher series text field with a locked combo box + create-series button"
```

---

## Task 4: Extend `VoucherPanelNavigationTest` with the new behavior and all review-finding regressions

**Files:**
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy`

**Interfaces:**
- Consumes everything Task 3 produced (`seriesComboBox`, `newSeriesButton`, and the three test seams `rememberDraftForTest`, `reloadVoucherListForTest`, `createNewSeriesForTest`; `isNewVoucherPeriodLocked` indirectly via `newSeriesButton.enabled`), plus `FiscalYearService.closeFiscalYear(long)` (existing), `ActiveCompanyManager.setFiscalYear(FiscalYear)` (existing).

Add imports needed by the new tests, before writing any of the steps below:
- `import se.alipsa.accounting.domain.VoucherSeries` — alongside the existing `se.alipsa.accounting.domain.*` imports.
- `import javax.swing.JComboBox` — alongside the existing `import javax.swing.JButton` etc. block.
- `import static org.junit.jupiter.api.Assertions.assertFalse` — alongside the existing `assertEquals`/`assertNotNull`/`assertThrows`/`assertTrue` static imports.

(`java.time.LocalDate` and `se.alipsa.datepicker.DatePicker`, both used by the new tests below, are already imported in this file — no change needed for those.)

- [ ] **Step 1: Baseline test — fresh fiscal year seeds `A` and previews `A-1`**

Add to `VoucherPanelNavigationTest.groovy`:

```groovy
  @Test
  void freshFiscalYearSeedsDefaultSeriesAAndPreviewsItsFirstNumber() {
    JComboBox<VoucherSeries> seriesComboBox = findComponent(panel, JComboBox) { true } as JComboBox<VoucherSeries>
    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }

    VoucherSeries selected = onEdt { seriesComboBox.selectedItem } as VoucherSeries

    assertEquals(1, onEdt { seriesComboBox.itemCount })
    assertEquals('A', selected.seriesCode)
    assertEquals('A-1', onEdt { voucherJumpField.text })
    assertEquals(1, voucherService.listSeries(fiscalYear.id).size())
  }
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.freshFiscalYearSeedsDefaultSeriesAAndPreviewsItsFirstNumber"`
Expected: PASS.

- [ ] **Step 3: Test — creating a series via the dialog appears in the combo and gets selected**

`createNewSeries()` opens a real, blocking modal dialog, so this test doesn't call it. Instead it calls `voucherService.ensureSeries` directly (exactly what the dialog does internally) and then forces the same combo refresh `createNewSeries()` triggers after the dialog returns, via the `reloadVoucherListForTest()` seam.

```groovy
  @Test
  void creatingASecondSeriesMakesItAppearInTheComboAndBecomeSelectable() {
    voucherService.ensureSeries(fiscalYear.id, 'B', 'Kassaverifikat')

    onEdt { panel.reloadVoucherListForTest() }

    JComboBox<VoucherSeries> seriesComboBox = findComponent(panel, JComboBox) { true } as JComboBox<VoucherSeries>
    List<String> codes = onEdt {
      (0..<seriesComboBox.itemCount).collect { int i -> (seriesComboBox.getItemAt(i) as VoucherSeries).seriesCode }
    }
    assertTrue(codes.containsAll(['A', 'B']))
  }
```

(This uses `reloadVoucherListForTest()`, the seam added in Task 3 Step 10.)

- [ ] **Step 4: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.creatingASecondSeriesMakesItAppearInTheComboAndBecomeSelectable"`
Expected: PASS.

- [ ] **Step 5: Test — switching fiscal year repopulates the combo (finding 3)**

```groovy
  @Test
  void switchingFiscalYearRepopulatesComboWithoutStaleSelection() {
    voucherService.ensureSeries(fiscalYear.id, 'B', null)
    // No 'A' in this fiscal year - only 'B'.
    onEdt { panel.reloadVoucherListForTest() }
    JComboBox<VoucherSeries> seriesComboBox = findComponent(panel, JComboBox) { true } as JComboBox<VoucherSeries>
    onEdt { seriesComboBox.selectedItem = seriesComboBox.getItemAt(
        (0..<seriesComboBox.itemCount).find { int i -> (seriesComboBox.getItemAt(i) as VoucherSeries).seriesCode == 'B' }) }

    FiscalYear secondYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2031', LocalDate.of(2031, 1, 1), LocalDate.of(2031, 12, 31))
    onEdt { activeCompanyManager.fiscalYear = secondYear }

    VoucherSeries selected = onEdt { seriesComboBox.selectedItem } as VoucherSeries
    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }

    assertEquals('A', selected.seriesCode)
    assertEquals('A-1', onEdt { voucherJumpField.text })
    assertEquals(0, voucherService.listSeries(fiscalYear.id).findAll { VoucherSeries s -> s.seriesCode == 'A' }.size())
  }
```

(Last assertion: year 1 must still have no `A` series - confirming the seed happened for year 2, not accidentally for year 1.)

- [ ] **Step 6: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.switchingFiscalYearRepopulatesComboWithoutStaleSelection"`
Expected: PASS.

- [ ] **Step 7: Test — non-`A` default series shows the correct preview and saves correctly (finding 2)**

```groovy
  @Test
  void fiscalYearWithOnlyNonADefaultSeriesPreviewsAndSavesUnderThatSeries() {
    voucherService.ensureSeries(fiscalYear.id, 'B', null)
    onEdt { panel.reloadVoucherListForTest() }

    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }
    assertEquals('B-1', onEdt { voucherJumpField.text })

    JTextField description = findComponent(panel, JTextField) { JTextField field -> field.columns == 30 }
    onEdt {
      description.text = 'Only B series exists'
      panel.lineTableModel.rows[0].accountNumber = '1510'
      panel.lineTableModel.rows[0].accountName = 'Kundfordringar'
      panel.lineTableModel.rows[0].debit = '100'
      panel.lineTableModel.rows[1].accountNumber = '3010'
      panel.lineTableModel.rows[1].accountName = 'Försäljning'
      panel.lineTableModel.rows[1].credit = '100'
      clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.save'))
    }

    List<Voucher> vouchers = voucherService.listVouchers(CompanyService.LEGACY_COMPANY_ID, fiscalYear.id)
    assertEquals(1, vouchers.size())
    assertEquals('B-1', vouchers.first().voucherNumber)
  }
```

- [ ] **Step 8: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.fiscalYearWithOnlyNonADefaultSeriesPreviewsAndSavesUnderThatSeries"`
Expected: PASS.

- [ ] **Step 9: Test — combo and `+` disabled once an existing voucher is loaded**

```groovy
  @Test
  void seriesComboAndNewSeriesButtonAreDisabledOnceAnExistingVoucherIsLoaded() {
    voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Saved voucher',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev')) }

    JComboBox<VoucherSeries> seriesComboBox = findComponent(panel, JComboBox) { true } as JComboBox<VoucherSeries>
    JButton newSeriesButton = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.newSeries')
    }

    assertFalse(onEdt { seriesComboBox.enabled })
    assertFalse(onEdt { newSeriesButton.enabled })
  }
```

- [ ] **Step 10: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.seriesComboAndNewSeriesButtonAreDisabledOnceAnExistingVoucherIsLoaded"`
Expected: PASS.

- [ ] **Step 11: Test — locked fiscal year does not write, `+` is disabled (finding 1)**

```groovy
  @Test
  void closedFiscalYearNeverSeedsASeriesAndDisablesNewSeriesButton() {
    FiscalYear closedYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2029', LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31))
    fiscalYearService.closeFiscalYear(closedYear.id)

    onEdt { activeCompanyManager.fiscalYear = closedYear }

    JComboBox<VoucherSeries> seriesComboBox = findComponent(panel, JComboBox) { true } as JComboBox<VoucherSeries>
    JButton newSeriesButton = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.newSeries')
    }

    assertEquals(0, onEdt { seriesComboBox.itemCount })
    assertFalse(onEdt { newSeriesButton.enabled })
    assertEquals(0, voucherService.listSeries(closedYear.id).size())
  }
```

- [ ] **Step 12: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.closedFiscalYearNeverSeedsASeriesAndDisablesNewSeriesButton"`
Expected: PASS.

- [ ] **Step 13: Test — date changed after init to a locked date disables `+` but not the date picker; recovers on an open date (findings 4 & 5)**

```groovy
  @Test
  void changingToALockedDateDisablesNewSeriesAndSaveButNotTheDatePicker() {
    FiscalYear closedYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2029', LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31))
    fiscalYearService.closeFiscalYear(closedYear.id)
    LocalDate lockedDate = LocalDate.of(2029, 6, 1)
    LocalDate openDate = LocalDate.of(2030, 6, 1)

    DatePicker datePicker = findComponent(panel, DatePicker) { true }
    JButton newSeriesButton = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.newSeries')
    }
    JButton saveButton = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.save')
    }

    assertTrue(onEdt { newSeriesButton.enabled })

    onEdt { datePicker.date = lockedDate }

    assertTrue(onEdt { datePicker.enabled }, 'Date picker must stay usable so the user can pick their way out of a locked date')
    assertFalse(onEdt { newSeriesButton.enabled })
    assertFalse(onEdt { saveButton.enabled })
    assertFalse(onEdt { panel.lineTableModel.editable })

    onEdt { datePicker.date = openDate }

    assertTrue(onEdt { newSeriesButton.enabled })
    assertTrue(onEdt { saveButton.enabled })
  }

  @Test
  void directlyCallingCreateSeriesWhileLockedShowsAnErrorAndCreatesNothing() {
    FiscalYear closedYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2029', LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31))
    fiscalYearService.closeFiscalYear(closedYear.id)
    DatePicker datePicker = findComponent(panel, DatePicker) { true }
    onEdt { datePicker.date = LocalDate.of(2029, 6, 1) }

    onEdt { panel.createNewSeriesForTest() }

    assertTrue(onEdt { findFeedbackArea(panel).text }.contains(
        I18n.instance.getString('voucherPanel.error.seriesCreationLocked')))
    assertEquals(0, voucherService.listSeries(fiscalYear.id).size())
  }
```

(The second test uses `createNewSeriesForTest()`, the seam added in Task 3 Step 10.)

- [ ] **Step 14: Run both**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.changingToALockedDateDisablesNewSeriesAndSaveButNotTheDatePicker" --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.directlyCallingCreateSeriesWhileLockedShowsAnErrorAndCreatesNothing"`
Expected: PASS.

- [ ] **Step 15: Test — unknown series code in an applied draft throws through the MCP path (finding 6, part 1)**

```groovy
  @Test
  void applyingAnMcpDraftWithAnUnknownSeriesCodeThrowsAndLeavesThePanelUnchanged() {
    JTextField voucherJumpField = findComponent(panel, JTextField) { JTextField field -> field.columns == 8 }
    String numberBefore = onEdt { voucherJumpField.text }

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      panel.mcpVoucherDraftAccess.setVoucherDraft([
          accounting_date: '2030-05-01',
          description: 'Draft with bad series',
          series_code: 'ZZZZZZZZ',
          lines: [[account_number: '1510', debit: 50G, credit: 0G]]
      ])
    }

    assertTrue(exception.message.contains('ZZZZZZZZ'))
    assertEquals(numberBefore, onEdt { voucherJumpField.text })
  }
```

- [ ] **Step 16: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.applyingAnMcpDraftWithAnUnknownSeriesCodeThrowsAndLeavesThePanelUnchanged"`
Expected: PASS.

- [ ] **Step 17: Test — the purely-internal `restoreNavigationDraft()` fallback (finding 6, part 2)**

This needs the panel to be `isOnDraft()` (fresh blank voucher, no navigation yet — true right after `buildPanel()`) so `VoucherNavigation.rememberDraft()` actually stores the deliberately-invalid draft, then needs at least one saved voucher to navigate through so falling off the end reaches `restoreNavigationDraft()`. `VoucherDraftMapper` needs no new import — it's already in package `se.alipsa.accounting.ui`, same as the test.

```groovy
  @Test
  void restoreNavigationDraftFallsBackToBlankVoucherForAnUnresolvableRememberedSeries() {
    voucherService.createVoucher(
        fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Only saved voucher',
        [voucherLine('1510', 'Kundfordringar', '', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', '', 0.00G, 100.00G)]
    )
    panel?.dispose()
    panel = buildPanel()
    installPanelHooks()

    VoucherDraftMapper.VoucherDraft invalidDraft = new VoucherDraftMapper.VoucherDraft(
        LocalDate.of(2030, 6, 1), 'Deliberately invalid', 'ZZZZZZZZ', [])
    onEdt { panel.rememberDraftForTest(invalidDraft) }

    JButton previous = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.prev')
    }
    JButton next = findComponent(panel, JButton) { JButton button ->
      button.toolTipText == I18n.instance.getString('voucherPanel.button.next')
    }
    // isOnDraft() is true here (no navigation yet), so rememberDraftForTest's value actually
    // stuck. "prev" moves onto the one saved voucher; "next" falls off the end back to the
    // (invalid) remembered draft, reaching restoreNavigationDraft()'s fallback.
    onEdt { previous.doClick() }
    onEdt { next.doClick() }

    JTextField description = findComponent(panel, JTextField) { JTextField field -> field.columns == 30 }
    assertEquals('', onEdt { description.text })
  }
```

- [ ] **Step 18: Run it**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest.restoreNavigationDraftFallsBackToBlankVoucherForAnUnresolvableRememberedSeries"`
Expected: PASS.

- [ ] **Step 19: Run the full test file**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.VoucherPanelNavigationTest"`
Expected: PASS on every test (existing + all new ones added in this task).

- [ ] **Step 20: CodeNarc**

Run: `./gradlew codenarcMain`
(This only lints `main`, not `test` — check the project's CodeNarc test config; if there's a `codenarcTest` or similar task, run it too and fix any violations.)

- [ ] **Step 21: Commit**

```bash
git add app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest.groovy app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy
git commit -m "test: cover voucher series combo behavior and all design-review findings"
```

---

## Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Spotless**

Run: `./gradlew spotlessApply`
Then: `git diff` — inspect the diff touches only files from this plan (Spotless can also reflow unrelated Markdown; if it touches something outside this feature, `git checkout` that file and investigate separately rather than bundling it into this change).

- [ ] **Step 2: Full build**

Run: `./gradlew build`
Expected: PASS — compilation, all tests, Spotless, CodeNarc.

If it fails with `BUG! UNCAUGHT EXCEPTION` from `org.codenarc.rule.unused.UnusedPrivateMethodRule` processing an unrelated file (a known flaky JVM/module-reflection issue in this environment, unrelated to this feature), just re-run `./gradlew build` once.

- [ ] **Step 3: Manual smoke test**

Run: `./gradlew run`

In the running app:
1. Open the Vouchers tab for a fiscal year with no vouchers yet. Confirm the series control shows a combo box (not a text field) with `A - Serie A` selected, and a `+` button next to it.
2. Click `+`, enter code `B`, name `Kassa`, click OK. Confirm the combo now offers both `A` and `B`, with `B` selected, and the "next number" preview updates to `B-1`.
3. Save a voucher under series `B`. Confirm its voucher number is `B-1`.
4. Load that saved voucher (navigate to it). Confirm the combo shows `B - Kassa`, disabled, and the `+` button is disabled.
5. Start a new voucher, pick a date in a closed/locked fiscal year (if one exists in your test data) or otherwise force `readOnly`. Confirm the date picker itself stays clickable/editable, while Save and `+` grey out; picking an open date again re-enables them.
6. Confirm the whole vouchers panel still behaves normally otherwise (navigation, printing, attachments, corrections) — this is a targeted change but the panel is large, so a quick pass over its other features is worth the two minutes.

- [ ] **Step 4: Final commit (only if smoke testing uncovered fixes)**

If Step 3 required any code changes, commit them separately with a clear message describing what the smoke test caught.

---

## Self-Review Notes

- **Spec coverage:** Context/Scope (Task 3), Empty-state default + locked-periods-must-not-write (Task 3 Steps 6, 8, 12; Task 4 Steps 5, 11-14), non-`A` default series preview fix (Task 3 Step 8; Task 4 Step 7), stale-combo-on-fiscal-year-switch fix (Task 3 Step 13; Task 4 Step 5), date-picker-stays-usable fix (Task 3 Step 12; Task 4 Step 13), unknown-series-in-draft rejection (Task 3 Steps 9-10; Task 4 Steps 15, 17), `NewVoucherSeriesDialog` (Task 2), i18n (Task 1) — all covered.
- **Placeholder scan:** every step has literal, complete code; no "similar to Task N" references.
- **Type consistency:** `seriesComboBox` is `JComboBox<VoucherSeries>` everywhere it's referenced; `findComboSeries`/`selectSeriesCode`/`refreshSeriesComboBox`/`isNewVoucherPeriodLocked`/`ensureDefaultSeriesForNewVoucher`/`createNewSeries`/`onSeriesSelectionChanged` names and signatures match between their Task 3 definitions and every call site added in later steps of the same task. `NewVoucherSeriesDialog.showDialog`/`normalizeCode` signatures match between Task 2's implementation and Task 3 Step 6's call site.
