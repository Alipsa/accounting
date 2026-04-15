# Date Picker for Fiscal Year Panel

## Problem

The Startdatum and Slutdatum fields in the Räkenskapsår (fiscal year) panel are plain text fields that require manual `yyyy-MM-dd` input. This is error-prone and requires tooltip guidance. A date picker is more intuitive.

## Solution

Replace the two `JTextField` date inputs with `DatePicker` components from LGoodDatePicker 11.2.0. The picker provides a calendar popup, uses `java.time.LocalDate` natively, and respects locale settings.

## Changes

### Dependency

Add LGoodDatePicker 11.2.0 to `gradle/libs.versions.toml` and `app/build.gradle`.

Maven coordinates: `com.github.lgooddatepicker:LGoodDatePicker:11.2.0`

### FiscalYearPanel modifications

- Replace `JTextField startDateField` and `JTextField endDateField` with `DatePicker` instances
- Configure each picker with:
  - Display format: `yyyy-MM-dd`
  - Locale from `I18n.instance.locale`
- Simplify `createFiscalYearRequested()`: replace `parseDate()` text parsing with `datePicker.getDate()` null checks
- Remove the `parseDate()` helper method (no longer needed)
- Update `clearInputs()`: call `setDate(null)` instead of setting text to empty string
- Remove tooltip setup from `buildInputGrid()` and `applyLocale()` (the picker is self-explanatory)
- Update `applyLocale()`: update the picker's `DatePickerSettings` locale when language changes

### Files changed

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add lgooddatepicker version 11.2.0 |
| `app/build.gradle` | Add lgooddatepicker dependency |
| `app/src/main/groovy/se/alipsa/accounting/ui/FiscalYearPanel.groovy` | Replace JTextField with DatePicker |

### What this does NOT change

- No i18n key changes (date format tooltip keys remain in bundles but are unused — removing them would churn diffs for no benefit)
- No changes to FiscalYearService or domain layer
- No changes to other panels or dialogs
