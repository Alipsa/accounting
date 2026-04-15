# FlatLaf Theme Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the system Look & Feel with FlatLaf 3.7.1 so the app looks good on all platforms, handles HiDPI automatically, and lets users choose between system-default, light, and dark themes.

**Architecture:** New `ThemeMode` enum in `domain/` with an `apply()` method that calls the right FlatLaf setup. Theme preference persisted via `java.util.prefs.Preferences` in `UserPreferencesService`. FlatLaf initialized in `AlipsaAccounting.main()` before any Swing component. Settings tab gets radio buttons for theme selection with live switching.

**Tech Stack:** FlatLaf 3.7.1, Groovy 5.0.5, Swing, Java 21

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `gradle/libs.versions.toml` | Modify | Add flatlaf version |
| `app/build.gradle` | Modify | Add flatlaf dependency |
| `app/src/main/groovy/se/alipsa/accounting/domain/ThemeMode.groovy` | Create | Enum with SYSTEM/LIGHT/DARK and `apply()` |
| `app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy` | Modify | Add theme get/set |
| `app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy` | Modify | Init FlatLaf before UI |
| `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy` | Modify | Remove `lookAndFeel 'system'`, add theme selector |
| `app/src/main/resources/i18n/messages.properties` | Modify | Add theme i18n keys |
| `app/src/main/resources/i18n/messages_sv.properties` | Modify | Add Swedish theme i18n keys |
| `app/src/test/groovy/unit/ThemeModeTest.groovy` | Create | Unit test for ThemeMode enum |
| `app/src/test/groovy/unit/UserPreferencesThemeTest.groovy` | Create | Unit test for theme persistence |

---

### Task 1: Add FlatLaf dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`

- [ ] **Step 1: Add FlatLaf version to version catalog**

In `gradle/libs.versions.toml`, add `flatlaf` to `[versions]` and a library entry to `[libraries]`:

```toml
# Add to [versions] section, after the existing entries:
flatlaf = "3.7.1"

# Add to [libraries] section, after the existing entries:
flatlaf = { module = "com.formdev:flatlaf", version.ref = "flatlaf" }
```

- [ ] **Step 2: Add FlatLaf dependency to build.gradle**

In `app/build.gradle`, add to the `dependencies` block, after the existing `implementation` lines:

```groovy
implementation libs.flatlaf
```

- [ ] **Step 3: Verify the dependency resolves**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep flatlaf`

Expected: A line showing `com.formdev:flatlaf:3.7.1`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle
git commit -m "lägg till FlatLaf 3.7.1 som beroende"
```

---

### Task 2: Create ThemeMode enum

**Files:**
- Create: `app/src/test/groovy/unit/ThemeModeTest.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/ThemeMode.groovy`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/groovy/unit/ThemeModeTest.groovy`:

```groovy
package unit

import se.alipsa.accounting.domain.ThemeMode

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

class ThemeModeTest {

  @Test
  void allModesExist() {
    assertEquals(3, ThemeMode.values().length)
    assertNotNull(ThemeMode.SYSTEM)
    assertNotNull(ThemeMode.LIGHT)
    assertNotNull(ThemeMode.DARK)
  }

  @Test
  void fromNameReturnsCorrectMode() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName('SYSTEM'))
    assertEquals(ThemeMode.LIGHT, ThemeMode.fromName('LIGHT'))
    assertEquals(ThemeMode.DARK, ThemeMode.fromName('DARK'))
  }

  @Test
  void fromNameReturnsSystemForNull() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
  }

  @Test
  void fromNameReturnsSystemForUnknown() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName('UNKNOWN'))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'unit.ThemeModeTest' --info 2>&1 | tail -20`

Expected: Compilation failure — `ThemeMode` does not exist yet.

- [ ] **Step 3: Write ThemeMode enum**

Create `app/src/main/groovy/se/alipsa/accounting/domain/ThemeMode.groovy`:

```groovy
package se.alipsa.accounting.domain

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf

import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Available theme modes for the application UI.
 */
enum ThemeMode {

  SYSTEM,
  LIGHT,
  DARK

  void apply() {
    switch (this) {
      case LIGHT:
        FlatLightLaf.setup()
        break
      case DARK:
        FlatDarkLaf.setup()
        break
      default:
        FlatLaf.setup(new FlatLightLaf())
        FlatLaf.setUseNativeWindowDecorations(true)
        break
    }
  }

  void applyAndUpdateUI() {
    apply()
    FlatLaf.updateUI()
  }

  static ThemeMode fromName(String name) {
    if (name == null) {
      return SYSTEM
    }
    try {
      return valueOf(name)
    } catch (IllegalArgumentException ignored) {
      return SYSTEM
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'unit.ThemeModeTest' --info 2>&1 | tail -20`

Expected: All 4 tests PASS.

- [ ] **Step 5: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/ThemeMode.groovy app/src/test/groovy/unit/ThemeModeTest.groovy
git commit -m "lägg till ThemeMode-enum med SYSTEM/LIGHT/DARK"
```

---

### Task 3: Add theme persistence to UserPreferencesService

**Files:**
- Create: `app/src/test/groovy/unit/UserPreferencesThemeTest.groovy`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/groovy/unit/UserPreferencesThemeTest.groovy`:

```groovy
package unit

import se.alipsa.accounting.domain.ThemeMode
import se.alipsa.accounting.service.UserPreferencesService

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class UserPreferencesThemeTest {

  private final UserPreferencesService service = new UserPreferencesService()

  @AfterEach
  void cleanup() {
    service.setTheme(null)
  }

  @Test
  void defaultThemeIsSystem() {
    service.setTheme(null)
    assertEquals(ThemeMode.SYSTEM, service.getTheme())
  }

  @Test
  void roundTripsTheme() {
    service.setTheme(ThemeMode.DARK)
    assertEquals(ThemeMode.DARK, service.getTheme())

    service.setTheme(ThemeMode.LIGHT)
    assertEquals(ThemeMode.LIGHT, service.getTheme())
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'unit.UserPreferencesThemeTest' --info 2>&1 | tail -20`

Expected: Compilation failure — `getTheme()` and `setTheme()` do not exist.

- [ ] **Step 3: Add theme methods to UserPreferencesService**

In `app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy`, add a new constant and two methods. The file should become:

```groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.ThemeMode

import java.util.prefs.Preferences

/**
 * Manages user-level preferences (as opposed to company settings).
 */
final class UserPreferencesService {

  private static final String LANGUAGE_KEY = 'ui.language'
  private static final String THEME_KEY = 'ui.theme'

  private final Preferences preferences = Preferences.userNodeForPackage(UserPreferencesService)

  Locale getLanguage() {
    String tag = preferences.get(LANGUAGE_KEY, null)
    tag != null ? Locale.forLanguageTag(tag) : null
  }

  void setLanguage(Locale locale) {
    preferences.put(LANGUAGE_KEY, locale.toLanguageTag())
  }

  ThemeMode getTheme() {
    String name = preferences.get(THEME_KEY, null)
    ThemeMode.fromName(name)
  }

  void setTheme(ThemeMode mode) {
    if (mode == null || mode == ThemeMode.SYSTEM) {
      preferences.remove(THEME_KEY)
    } else {
      preferences.put(THEME_KEY, mode.name())
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'unit.UserPreferencesThemeTest' --info 2>&1 | tail -20`

Expected: Both tests PASS.

- [ ] **Step 5: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy app/src/test/groovy/unit/UserPreferencesThemeTest.groovy
git commit -m "lägg till tema-val i UserPreferencesService"
```

---

### Task 4: Initialize FlatLaf at startup

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy`

- [ ] **Step 1: Add FlatLaf initialization before UI creation**

In `AlipsaAccounting.groovy`, add an import for `ThemeMode` at the top:

```groovy
import se.alipsa.accounting.domain.ThemeMode
```

Then, in the `main` method, add theme initialization **after** the language loading block (after `I18n.instance.setLocale(savedLanguage)` closing brace, around line 48) and **before** the startup verification (before `StartupVerificationReport startupReport = ...`):

```groovy
      ThemeMode theme = new UserPreferencesService().getTheme()
      theme.apply()
```

- [ ] **Step 2: Verify the app compiles**

Run: `./gradlew compileGroovy`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/AlipsaAccounting.groovy
git commit -m "initiera FlatLaf-tema vid uppstart"
```

---

### Task 5: Remove old lookAndFeel and add theme selector to Settings

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy`
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

- [ ] **Step 1: Add i18n keys for theme**

Append to `app/src/main/resources/i18n/messages.properties`, at the end of the file:

```properties

# Theme
settings.label.theme=Theme
settings.theme.system=System default
settings.theme.light=Light
settings.theme.dark=Dark
settings.status.themeChanged=Theme changed.
```

Append to `app/src/main/resources/i18n/messages_sv.properties`, at the end of the file:

```properties

# Theme
settings.label.theme=Tema
settings.theme.system=Systemstandard
settings.theme.light=Ljust
settings.theme.dark=Mörkt
settings.status.themeChanged=Temat ändrades.
```

- [ ] **Step 2: Remove `lookAndFeel 'system'` from MainFrame**

In `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy`, in the `buildFrame()` method (around line 269), remove the line:

```groovy
      lookAndFeel 'system'
```

- [ ] **Step 3: Add theme imports to MainFrame**

Add these imports to `MainFrame.groovy`:

```groovy
import se.alipsa.accounting.domain.ThemeMode
```

- [ ] **Step 4: Add theme UI fields**

Add these fields to the `MainFrame` class, alongside the existing UI field declarations (around line 160, after `private JLabel languageLabel`):

```groovy
  private JLabel themeLabel
  private JRadioButton themeSystemButton
  private JRadioButton themeLightButton
  private JRadioButton themeDarkButton
```

- [ ] **Step 5: Add theme selector to buildSettingsPanel**

In the `buildSettingsPanel()` method, after the `languageRow` block (after `topPanel.add(languageRow, BorderLayout.NORTH)` at line 314), insert a theme row. Replace the line `topPanel.add(languageRow, BorderLayout.NORTH)` and everything down to `topPanel.add(companyRow, BorderLayout.SOUTH)` with:

```groovy
    JPanel settingsRows = new JPanel()
    settingsRows.layout = new BoxLayout(settingsRows, BoxLayout.Y_AXIS)
    settingsRows.add(languageRow)

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
    settingsRows.add(themeRow)

    topPanel.add(settingsRows, BorderLayout.NORTH)
    topPanel.add(companyRow, BorderLayout.SOUTH)
```

Also add the `javax.swing.BoxLayout` import — it is already covered by the wildcard `javax.swing.*` import.

- [ ] **Step 6: Add switchTheme and selectThemeButton methods**

Add these private methods to `MainFrame`, after the `switchLanguage` method:

```groovy
  private void switchTheme(ThemeMode mode) {
    userPreferencesService.setTheme(mode)
    mode.applyAndUpdateUI()
    setStatus(I18n.instance.getString('settings.status.themeChanged'))
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
```

- [ ] **Step 7: Update applyLocale to refresh theme labels**

In the `applyLocale()` method, after the `languageLabel.text = ...` line (around line 225), add:

```groovy
    themeLabel.text = I18n.instance.getString('settings.label.theme')
    themeSystemButton.text = I18n.instance.getString('settings.theme.system')
    themeLightButton.text = I18n.instance.getString('settings.theme.light')
    themeDarkButton.text = I18n.instance.getString('settings.theme.dark')
```

- [ ] **Step 8: Run spotlessApply and build**

Run: `./gradlew spotlessApply && ./gradlew build`

Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy app/src/main/resources/i18n/messages.properties app/src/main/resources/i18n/messages_sv.properties
git commit -m "ersätt system-L&F med FlatLaf, lägg till temaväljare i inställningar"
```

---

### Task 6: Full build verification

**Files:** None — verification only.

- [ ] **Step 1: Run full build**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL (compilation, tests, Spotless, CodeNarc all pass)

- [ ] **Step 2: Verify no CodeNarc warnings**

Run: `./gradlew codenarcMain`

Expected: BUILD SUCCESSFUL with zero violations.
