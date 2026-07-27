# BAS SRU kopplingstabell sources

Source: https://www.bas.se/kontoplaner/sru/

Bas.se confirms there is currently only one kopplingstabell per legal form/tax form
(no fiscal-year-period variants: "I dagsläget finns inte olika kopplingsscheman
baserat på granskningsperioder"). The files below are the complete, current set.

| File | Legal form / form | SHA-256 |
|---|---|---|
| `INK2_P1_intervall-241119.xlsx` | Aktiebolag (INK2) | `59bc463c3e5f75457cd16917c9e9b3fcc25418ea232d9c0c5955d0a72a8638ee` |
| `INK4_P1_Intervall-241119.xlsx` | Handelsbolag/KB (INK4) | `5130ddaf7751be690236498a1119242bb1942fc105c19bf0cac650bb184a13aa` |
| `NE_K1-201002.xlsx` | Enskild firma, förenklat årsbokslut (K1) | `15234aa24f1f127d8791fc8ce1d1a30b80c2d6503c44c19a488a50213562dc34` |
| `NE_EJ_K1-Intervall-231002.xlsx` | Enskild firma, ej K1 | `25fe5f804299699bf3c03a4145b8a76bbe386cc08b71e572858afde1d6ec9fb5` |

`INK2_P1-231002_241119.xlsx` and `INK4_P1-240828.xlsx` (SHA-256
`87f6be573b2e3709c659e3b63c68497a289f6189f26b0ec46460f0aebb28c79b` and
`a550bc4ccc89768b38c61b066e87190e878be0970d29cdc25a8788e952fe73c3`) are the same
data as the two "_intervall"/"_Intervall" files above, using `xx`-wildcard notation
instead of expanded numeric ranges - not used as a conversion source, kept for reference.

## Regenerating

This is a manual, one-off task (like the existing BAS chart-of-accounts import), not build
infrastructure. If bas.se publishes updated tables:

1. Download the new files into `docs/SRU/`, compute their SHA-256, update the table above.
2. Run `BasSruTableConverter.main` for each file (see its class doc) to regenerate the CSV in
   `app/src/main/resources/sru/`.
3. Diff the regenerated CSV against the previous version and review the change before committing.
4. Re-run `SruSuggestionServiceTest` - if the real-export fixture check (§ Task 8) starts failing,
   investigate before committing.
