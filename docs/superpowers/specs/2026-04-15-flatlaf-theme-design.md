# FlatLaf Theme Integration

## Problem

The app uses `lookAndFeel 'system'` which looks good on macOS (Aqua) but poor on Linux (GTK/Metal). HiDPI displays also cause the app to render at half size because there is no explicit scaling configuration.

## Solution

Replace the system L&F with FlatLaf 3.7.1, which provides a modern cross-platform appearance and automatic HiDPI scaling. Users can choose between three modes: System (auto-detect OS dark/light), Light, and Dark.

## Components

### 1. Dependency

Add FlatLaf 3.7.1 to `gradle/libs.versions.toml` and `app/build.gradle`.

### 2. ThemeMode enum (`domain/ThemeMode.groovy`)

Three values: `SYSTEM`, `LIGHT`, `DARK`.

Provides an `apply()` method that calls the appropriate FlatLaf setup:
- `SYSTEM` — `FlatLaf.setup(new FlatIntelliJLaf())` with `FlatLaf.setUseNativeWindowDecorations(true)` and system preference detection via `FlatLaf.supportsNativeWindowDecorations()`. More precisely: use `com.formdev.flatlaf.FlatSystemProperties` or the built-in OS preference detection to auto-switch between light/dark.
- `LIGHT` — `FlatLightLaf.setup()`
- `DARK` — `FlatDarkLaf.setup()`

### 3. UserPreferencesService

Add `getTheme()` / `setTheme(ThemeMode)` methods. Stored in `java.util.prefs.Preferences` under key `ui.theme` (same pattern as `ui.language`). Returns `ThemeMode.SYSTEM` when no preference is saved.

### 4. Startup initialization (`AlipsaAccounting.main`)

After DB initialization and language loading, but **before** any Swing component is created:
1. Load saved theme from `UserPreferencesService`
2. Call `ThemeMode.apply()`

Remove `lookAndFeel 'system'` from `MainFrame.buildFrame()`.

### 5. Settings UI (`MainFrame.buildSettingsPanel`)

Add a theme row below the language row, matching the same `FlowLayout` pattern:
- Label: localized "Theme" text
- Three `JRadioButton`s in a `ButtonGroup`: System / Light / Dark
- On selection: persist via `UserPreferencesService`, call `FlatLaf.updateUI()` on all windows to apply the change live without restart

### 6. I18n

Add resource bundle keys for theme labels:
- `settings.label.theme`
- `settings.theme.system`
- `settings.theme.light`
- `settings.theme.dark`

Both English and Swedish bundles.

## Files changed

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add flatlaf version 3.7.1 |
| `app/build.gradle` | Add flatlaf dependency |
| `app/src/main/groovy/.../domain/ThemeMode.groovy` | New enum |
| `app/src/main/groovy/.../service/UserPreferencesService.groovy` | Add theme get/set |
| `app/src/main/groovy/.../AlipsaAccounting.groovy` | Init FlatLaf before UI |
| `app/src/main/groovy/.../ui/MainFrame.groovy` | Remove `lookAndFeel 'system'`, add theme selector to settings panel |
| `app/src/main/resources/Messages*.properties` | Add theme i18n keys |

## What this does NOT change

- No database migration needed (uses `java.util.prefs.Preferences`)
- No changes to packaging/jpackage configuration
- No changes to existing color constants or custom painting — FlatLaf handles these via its own defaults
