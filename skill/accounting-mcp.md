---
name: accounting-mcp
description: Bookkeeping workflows for the Alipsa Accounting MCP server.
---

# Accounting MCP Skill

## Overview

This skill governs how you assist users with bookkeeping through the Alipsa Accounting MCP server. The server exposes the application's existing domain and service layer. The same business rules that apply in the GUI apply here. You cannot bypass them.

Always read before you write. Gather context first, propose actions, then post only after the user confirms.

## Available Tools

### Read-only tools

| Tool | Purpose |
|------|---------|
| `get_company_info` | Active company name, currency, VAT periodicity |
| `list_fiscal_years` | All fiscal years with open/closed status |
| `list_accounts` | Chart of accounts; supports optional query string filter |
| `list_vouchers` | Recent vouchers for a fiscal year |
| `get_trial_balance` | Opening balance, period debit/credit, closing balance per account |
| `get_general_ledger` | Full posting history with running balance per account |
| `list_vat_periods` | VAT periods with status (`OPEN`, `REPORTED`, `LOCKED`) |
| `get_vat_report` | Calculated VAT report for a period; returns `report_hash` |
| `preview_voucher` | Validate a proposed voucher without posting it; returns `preview_token` only when valid |
| `preview_year_end` | Year-end pre-checks: blocking issues, warnings, net result; returns `preview_token` only when ready |
| `preview_sie_import` | Preview a SIE4 import; returns `import_token` only when importable |
| `list_import_jobs` | Recent SIE import history |

### Write tools

Only call write tools after the user explicitly confirms the proposed action.

| Tool | Purpose |
|------|---------|
| `post_voucher` | Post a balanced voucher using a token from `preview_voucher` |
| `create_correction_voucher` | Reverse a posted voucher; direct edit/delete is not exposed |
| `book_vat_transfer` | Book the VAT settlement voucher using `report_hash` from `get_vat_report` |
| `close_fiscal_year` | Close the fiscal year using a token from `preview_year_end` |
| `import_sie` | Import a SIE4 file using a token from `preview_sie_import` |
| `export_sie` | Export a fiscal year as SIE4; no token, but overwrite needs confirmation |

## Workflow: Voucher Entry

1. Gather context.
   Call `get_company_info` and `list_fiscal_years`. Identify the open fiscal year and its `fiscal_year_id`.

2. Look up accounts.
   Call `list_accounts` with a query string when needed. Verify each account is active and has the expected `account_class`.

3. Propose and validate.
   Summarize the voucher to the user: date, description, and lines with account numbers and amounts. Call `preview_voucher` and show resolved account names plus any errors or warnings. Do not proceed if `ok` is false or `preview_token` is missing.

4. Confirm and post.
   Ask the user explicitly whether to post the voucher. Only call `post_voucher` after confirmation, passing the unchanged payload and the returned `preview_token`.

5. Correct posted vouchers through corrections.
   Direct edits and deletes are not exposed. If a posted voucher is wrong, explain that the permitted path is `create_correction_voucher`.

## Workflow: Correction Voucher

1. Gather and verify the original voucher.
   Call `list_vouchers` and, if needed, `get_general_ledger` to identify the posted voucher that must be reversed. Confirm its date, description, voucher number, and lines with the user.

2. Explain the correction.
   State that posted vouchers are append-only and that `create_correction_voucher` creates a reversing voucher. Ask the user to confirm the original voucher ID and optional correction description.

3. Create after confirmation.
   Only call `create_correction_voucher` after explicit confirmation, passing `original_voucher_id` and the optional `description`. Show the created correction voucher number and lines.

## Workflow: VAT Reporting

1. Call `list_vat_periods` for the relevant fiscal year.
2. Identify the period the user wants to report.
3. Call `get_vat_report` and show output VAT, input VAT, net VAT to pay, and any unusual rows.
4. Ask whether to book the VAT transfer.
5. Only call `book_vat_transfer` after confirmation, passing the exact `report_hash` returned by `get_vat_report`.

## Workflow: Year-End Closing

1. Call `list_fiscal_years` and confirm the year is open.
2. Call `preview_year_end`.
3. Show `blocking_issues`, `warnings`, `net_result`, and whether the next fiscal year will be created automatically.
4. If `ready_to_close` is false or `preview_token` is missing, explain the blockers and do not call `close_fiscal_year`.
5. If `ready_to_close` is true, ask the user to confirm closing the year.
6. Only call `close_fiscal_year` after confirmation, passing the returned `preview_token`.

## Workflow: SIE Import

1. Preview the file.
   Call `preview_sie_import`. If `ok` is false, explain `blockingIssues` and do not call `import_sie`.

2. Handle duplicates and warnings.
   If `isDuplicate` is true, show `duplicateJobId` and stop ordinary import. Forward all `warnings` to the user before import.

3. Summarize the import.
   Show `companyNameInFile`, fiscal-year period, account count, voucher count, line count, and whether the fiscal year already exists.

4. Confirm replacements explicitly.
   If `replace_existing` is true and `fiscalYearExists` is true, show `purgeSummary` in plain language: vouchers, attachments, report archives, opening balances, VAT periods, and audit-log rows affected. Only proceed after explicit confirmation.

5. Import after confirmation.
   Call `import_sie` with unchanged `file_path`, `replace_existing`, and `import_token`. Show the resulting job id, fiscal year, counts, duplicate status, and warnings.

## Workflow: SIE Export

1. Identify the fiscal year.
   Call `list_fiscal_years` if the `fiscal_year_id` is unknown.

2. Export.
   Call `export_sie` without `output_path` for the default timestamped export path, or with a user-specified path.

3. Handle existing files.
   If `export_sie` returns `ok: false` and `file_exists: true`, show `existing_file_path` and ask whether to overwrite. Only call `export_sie` again with `overwrite: true` after explicit confirmation.

4. Show the result.
   Show output path, file size, checksum, and exported counts.

## Domain Reference

### Fiscal years

Each fiscal year has `start_date`, `end_date`, and `closed`. Closed years block new ordinary vouchers. Reopening a year with existing closing entries is blocked by the service layer.

### Vouchers

Vouchers are append-only. A voucher must balance: total debit equals total credit. The only exposed correction path is a reversing correction voucher.

### Account classes

| Class | Meaning | Normal balance |
|-------|---------|----------------|
| `ASSET` | Asset | Debit |
| `LIABILITY` | Liability | Credit |
| `EQUITY` | Equity | Credit |
| `INCOME` | Income | Credit |
| `EXPENSE` | Expense | Debit |

### VAT

VAT periods are derived from accounting periods and the company's VAT periodicity. A VAT period moves from `OPEN` to `REPORTED` to `LOCKED` after the VAT transfer voucher is booked.

## Constraints

- Never call write tools speculatively.
- Never proceed after `ok: false`; explain the returned `errors` in plain language.
- Never suggest direct modification or deletion of posted vouchers.
- Never make legal statements about tax liability. Describe bookkeeping consequences only.
- Always use the company's `default_currency` when quoting amounts.
- Never call `import_sie` with `replace_existing: true` before showing `purgeSummary` and receiving explicit confirmation.
- Never call `export_sie` with `overwrite: true` before asking the user to confirm overwriting the existing file.
