# Year-end result transfer — issue 130

Related: [GitHub issue 130](https://github.com/Alipsa/accounting/issues/130).

Closing now posts only the remaining result between 8999 and the selected equity
account. A profit debits 8999 and credits the equity account; a loss reverses those
sides. Existing transfers through 8999 reduce the amount still to transfer. A zero
remaining result produces no closing voucher. The result-closing metadata describes
the actual 8999 transfer rather than individual operating accounts.

Individual income and expense accounts retain their balances. The income statement
already excludes 8999, including in comparison figures. SIE export now includes
natural-sign `#RES` records for income and expense accounts, including 8999; these
reconcile to the exported vouchers. Only balance-sheet accounts carry forward.
There is no schema migration.

## Existing closed years: correction plan

This change applies to future closings. It does not rewrite historical vouchers,
closing entries, opening balances, or audit hashes. Historical corrections require
a separate, reviewed application workflow:

1. Identify the original closing voucher from closing metadata and inspect its
   operating-account reversals. Do not infer a faulty closing from series YE alone.
2. Back up the company and validate audit integrity. Preview the proposed correction,
   the restored account balances, and the effects on later years and archived reports.
3. Through domain operations, append a correction voucher referencing and reversing
   the faulty closing, then post the proper remaining-result transfer through 8999.
   An explicitly authorized reopening workflow is needed for a closed year; direct
   SQL edits and hash-chain rebuilding are not acceptable substitutes.
4. Verify the income statement, comparisons, balance sheet, SIE export, and audit
   chain. Preserve the original voucher and correction references. Reconcile later
   opening balances through audited domain operations only if the equity total changes.
5. Prevent duplicate remediation and retain evidence of the user's approval and reason.

## Separate equity workflows

The selectable equity account still defaults to 2099. Users can select 2019 for
sole proprietorships or partnerships. Legal-form defaults and new-year transfers
(2099 to 2098 for AB, or 2019/2013/2018 to 2010 for a sole proprietorship) should be
implemented in a separate previewable, audited workflow. Opening balances must
continue to match the preceding year's closing balances; reallocations belong in
new-year vouchers. Later allocations to retained earnings or dividends require
the applicable profit-allocation decision and must not be inferred by year closing.
