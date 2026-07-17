# Update Excel Export Design

## Goal

Align Excel exports with the redesigned PDF report layouts, especially for statement-style reports.

## Plan

1. Add a dedicated balance sheet Excel renderer
   - Add an explicit `ReportType.BALANCE_SHEET` branch in `ReportExportService.renderExcel`.
   - Mirror the income statement Excel path in `ReportExportService`.
   - Use `typedRows` from the report template model.
   - Add a balance-specific guard for missing `typedRows`, with an error message that names the balance sheet export.
   - Write title, company, and selected-period metadata rows.
   - Export the four balance sheet columns: `Post`, `Ingående balans`, `Denna period`, `Utgående saldo`.

2. Share statement-style Excel helpers
   - Extract reusable helpers for title/meta rows, header styles, numeric styles, row heights, and column widths.
   - Keep income-specific and balance-specific numeric mapping separate.
   - Do not rely on direct enum type sharing. `IncomeStatementRowType` and `BalanceSheetRowType` are not shape-compatible:
     income has `GROUP_HEADER`, `SUBTOTAL`, and `RESULT_LINE`; balance has `SUBGROUP_TOTAL`.
   - Prefer a shared name-keyed style/height lookup, with per-report mappings for semantic equivalents such as `SUBTOTAL` and `SUBGROUP_TOTAL`.
   - Keep report-specific overrides for income-only rows (`GROUP_HEADER`, `RESULT_LINE`) and balance-only rows (`SUBGROUP_TOTAL`).

3. Preserve balance sheet hierarchy
   - Use `BalanceSheetRowType` to style section headers, account detail rows, subgroup totals, section totals, and grand totals.
   - Keep the hierarchy visually aligned with the PDF report structure.
   - Match the PDF's closing-balance emphasis by applying a bold numeric style to every `Utgående saldo` cell, including detail rows, not only totals.
   - Decide explicitly whether `SECTION_HEADER` rows should merge across all four columns with `sheet.addMergedRegion`, matching the PDF `colspan="4"` behavior. If not merged, document that Excel uses label-only section rows with styled blank cells.

4. Export numeric balance values as numbers
   - Map balance columns directly from `BalanceSheetRow.openingBalance`, `periodMovement`, and `closingBalance`.
   - Avoid string-only amount cells so formulas, sorting, and downstream Excel work behave correctly.

5. Keep account labels practical
   - Keep the label column readable using the current combined display label.
   - Do not split account number and account name into separate Excel columns unless users explicitly need account-number filtering.

6. Add regression tests
   - Verify balance sheet Excel includes `Ingående balans`, `Denna period`, and `Utgående saldo`.
   - Verify balance amount cells are written as `NUMERIC`.
   - Verify section headers and totals are present.
   - Verify the missing-`typedRows` guard for the balance sheet renderer.
   - Verify the existing income statement Excel behavior remains unchanged.

7. Define balance sheet column widths
   - Use a wide label column and three equal numeric columns.
   - Start with approximately 38 characters for `Post` and 15 characters each for `Ingående balans`, `Denna period`, and `Utgående saldo`.
   - Adjust only if generated real-data workbooks show clipping or excessive whitespace.

8. Validate
   - Run focused `ReportServicesTest` coverage.
   - Run `./gradlew spotlessApply`.
   - Run `git diff --check`.
   - Run `./gradlew build`.

9. Update PR #88
   - Commit the Excel alignment work.
   - Push it to `agent/update-excel-export`.
