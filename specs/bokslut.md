# Bokslut (year-end closing) – Swedish practice and how it maps to the app

Reference notes for how the year-end transfer of the result works under Swedish
bookkeeping practice (BAS chart of accounts), and where the application currently
deviates. Researched 2026-11-09 during the review of PR #128 (issue #119).

## BAS accounts involved

Names below are taken from the official BAS 2026 chart of accounts in this
directory (`BAS_kontoplan_2026.xlsx`).

| Account | BAS 2026 name | Used by |
|---|---|---|
| 8910 | Skatt som belastar årets resultat | All company forms (tax before the result line) |
| 8990 | Resultat | Group heading for the result transfer |
| 8999 | Årets resultat | Transfer line in the income statement section, all company forms |
| 2099 | Årets resultat | Aktiebolag (AB) – current year's result |
| 2098 | Vinst eller förlust från föregående år | AB – previous year's result, before the AGM decides |
| 2091 | Balanserad vinst eller förlust | AB – retained earnings after the AGM (årsstämma) |
| 2898 | Outtagen vinstutdelning | AB – dividend decided by the AGM but not yet paid |
| 2010 | Eget kapital | Enskild firma (sole proprietorship) |
| 2013 | Övriga egna uttag | Enskild firma – owner withdrawals |
| 2018 | Övriga egna insättningar | Enskild firma – owner deposits |
| 2019 | Årets resultat, delägare 1 | Enskild firma / handelsbolag – current year's result |

## The year-end transfer

Booked on the last day of the fiscal year (bokslutsdagen), after all other
bokslut entries (accruals, depreciation, bokslutsdispositioner, tax on 8910).

| Company form | Profit | Loss |
|---|---|---|
| Aktiebolag | D 8999 / K 2099 | D 2099 / K 8999 |
| Enskild firma | D 8999 / K 2019 | D 2019 / K 8999 |

Properties of the Swedish model:

- It is **one transfer verification**. The individual result accounts (classes
  3–8) are *not* zeroed one by one; they keep their balances in the closed year.
- After the transfer, the sum of all result accounts including 8999 is 0, and the
  balance sheet balances, with the year's result on 2099 or 2019.
- The income statement (resultaträkning) shows the result **before** the 8999
  line. 8999 is the transfer line, not part of the year's result.
- The next fiscal year has opening balances (IB) only for balance sheet accounts
  (classes 1–2). Result accounts start from zero because they get no IB, not
  because a verification zeroed them.

## When the new year starts

### Aktiebolag

1. At the start of the new year, move the previous result so that 2099 only ever
   contains the current year's result: D 2099 / K 2098 for a profit, reversed for
   a loss.
2. After the AGM (årsstämma) has decided how to use the result:
   - move 2098 → 2091 Balanserad vinst eller förlust;
   - book any dividend as D 2098 (or 2091) / K 2898 Outtagen vinstutdelning, then
     pay it out from 2898.

### Enskild firma

At the start of the new year, the owner's equity accounts are "nollställda"
(zeroed into 2010) so that the total equity is collected in one account:

- 2019 Årets resultat → 2010
- 2013 Övriga egna uttag → 2010
- 2018 Övriga egna insättningar → 2010

## Imported years that are already closed (issue #119)

SIE4 exports from Fortnox and similar systems include the year-end transfer
verification (D 8999 / K 2099). For such years:

- 8999 already has a balance, so the result is already on 2099.
- The year should be **locked** (`lock_fiscal_year`, or the GUI "Stäng" button),
  not closed again. Running a full year-end closing on it must not book the
  result a second time.
- If a closing is run anyway, counting 8999 in the closing's
  "remaining-to-transfer" amount makes it transfer about 0, which is correct.

## How the application handles this today (as of 09fe298 / PR #128)

| Aspect | Status | Notes |
|---|---|---|
| 8999 classification on import | ✅ | EXPENSE/DEBIT, so a profit is a debit (PR #128). |
| 8999 excluded from the income statement | ✅ | `ReportAccountSupport.shouldExcludeFromIncomeStatement`. |
| 8999 included in the closing net result | ✅ | The closing transfers only what is not yet on equity. Avoids booking the result twice for imported years. |
| Opening balances only for balance accounts | ✅ | `OpeningBalanceService.loadBalanceClosingBalances` filters `ASSET`, `LIABILITY`, `EQUITY`. |
| Closing method | ❌ | `ClosingService.buildClosingVoucherLines` zeroes **every** result account against the closing account in a `YE`-series voucher (the Anglo-American "closing entries" method), instead of a single 8999 transfer. |
| Income statement of a closed year | ❌ | `ReportSqlLoader.loadPeriodTotals` includes the `YE` voucher, so the closed year's income statement shows 0 after closing. Verified with a probe test: net result 1 000 before closing, 0 after. |
| Comparison year in the next year's income statement | ❌ (inferred) | Same loader, so the comparison column should also show 0. K2 annual reports require comparison figures. |
| SIE4 export of a closed year | ❌ (inferred) | The export includes the `YE` voucher and writes no `#RES` lines, so a receiving system sees zeroed result accounts. |
| 2099 → 2098 roll-over at the new year | ❌ | No handling of 2098 exists. 2099 accumulates the results of several years. |
| Closing account by company form | ⚠️ | `ClosingService.DEFAULT_CLOSING_ACCOUNT = '2099'` (AB). A sole proprietorship should use 2019; it can be entered manually, and the EQUITY check accepts it. |
| Enskild firma zeroing (2013/2018/2019 → 2010) | ❌ | Not implemented. |

### Suggested direction (separate issue, not part of PR #128)

1. Replace the per-account closing voucher with one transfer: D/K 8999 against
   the closing account, for the amount not yet transferred. Skip it when that
   amount is 0, for example in imported years that already contain the transfer.
   Leave all other result accounts untouched.
2. Keep opening balances limited to balance sheet accounts (already the case).
3. Optionally create the new-year roll-over: 2099 → 2098 for an AB, or 2019,
   2013 and 2018 → 2010 for an enskild firma.
4. Default the closing account from the company form (AB: 2099, EF/HB: 2019).
5. Plan the migration of years already closed with the old method using
   correction vouchers, following the append-only rules in `CLAUDE.md`. Never
   edit the existing `YE` vouchers in place.

## Sources

- BAS 2026 chart of accounts: `specs/BAS_kontoplan_2026.xlsx` (account names above)
- [BAS – Kontoplan BAS 2024 (PDF)](https://www.bas.se/wp-content/uploads/2024/01/Kontoplan-BAS-2024.pdf)
- [Fortnox – Bokföra resultat i aktiebolag](https://www.fortnox.se/fortnox-foretagsguide/bokforingstips/resultat-i-aktiebolag)
- [Fortnox – Bokföra resultat i enskild firma](https://www.fortnox.se/fortnox-foretagsguide/bokforingstips/arets-resultat-enskild-firma)
- [Björn Lundén – Bokföra årets resultat, konteringsexempel](https://bjornlunden.com/se/bjornkoll/blogg/bokfora-arets-resultat-konteringsexempel/)
- [Bokio – Bokföra föregående års resultat i aktiebolag](https://www.bokio.se/hjalp/bokslut/bokslut/bokfora-foregaende-ars-resultat-i-aktiebolag-vid-nytt-rakenskapsar/)
- [Bokio – Omföring/nollställning eget kapital i enskild firma](https://www.bokio.se/hjalp/bokslut/bokslut/omforing-nollstallning-eget-kapital-i-enskild-firma/)
- [FAR Online – Årets resultat i resultaträkningen](https://www.faronline.se/dokument/rattserien/redovisa-ratt/a2/rr_aretsresultat/)
