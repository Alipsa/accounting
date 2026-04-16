# Grupperad resultatrapport — Design

## Bakgrund

Den nuvarande resultatrapporten (`buildIncomeStatementReport` i `ReportDataService`) grupperar konton i
bara två sektioner: "Intäkter" och "Kostnader". Enligt ÅRL och K2/K3 för mindre företag ska en
resultatrapport visa poster som Nettoomsättning, Övriga externa kostnader, Personalkostnader,
Avskrivningar, Rörelseresultat, Finansiella poster, Bokslutsdispositioner, Skatt och Årets resultat.

Syftet med denna ändring är att:

1. Införa en `AccountSubgroup` enum som representerar BAS-kontoplansgrupper (10–89).
2. Lagra undergruppen per konto i databasen via ny kolumn `account_subgroup`.
3. Införa en `IncomeStatementSection` enum som mappar undergrupper till rapportsektioner.
4. Skriva om resultatrapportens datalogik och mall för att producera en korrekt grupperad rapport.

Designen förbereder även för framtida gruppering i balansrapport och huvudbok.

---

## 1. `AccountSubgroup` enum

Paket: `se.alipsa.accounting.domain`

Varje entry definieras med ett intervall av tvåsiffriga BAS-gruppnummer. Metoden
`fromAccountNumber(String)` härleder grupp från ett 4-siffrigt kontonummer.

Display-namn hämtas via `I18n` med nyckeln `accountSubgroup.<NAME>`, samma mönster som `VatCode`
och `ReportType`.

### Enumvärden

#### Balanskonton (10–29)

| Namn | Intervall | Beskrivning |
|---|---|---|
| INTANGIBLE_ASSETS | 10 | Immateriella anläggningstillgångar |
| BUILDINGS_AND_LAND | 11 | Byggnader och mark |
| MACHINERY | 12 | Maskiner respektive inventarier |
| FINANCIAL_FIXED_ASSETS | 13 | Finansiella anläggningstillgångar |
| INVENTORY | 14 | Lager, produkter i arbete och pågående arbeten |
| RECEIVABLES | 15 | Kundfordringar |
| OTHER_CURRENT_RECEIVABLES | 16 | Övriga kortfristiga fordringar |
| PREPAID_EXPENSES | 17 | Förutbetalda kostnader och upplupna intäkter |
| SHORT_TERM_INVESTMENTS | 18 | Kortfristiga placeringar |
| CASH_AND_BANK | 19 | Kassa och bank |
| EQUITY | 20 | Eget kapital |
| UNTAXED_RESERVES | 21 | Obeskattade reserver |
| PROVISIONS | 22 | Avsättningar |
| LONG_TERM_LIABILITIES | 23 | Långfristiga skulder |
| SHORT_TERM_LIABILITIES_CREDIT | 24 | Kortfristiga skulder till kreditinstitut, kunder och leverantörer |
| TAX_LIABILITIES | 25 | Skatteskulder |
| VAT_AND_EXCISE | 26 | Moms och punktskatter |
| PAYROLL_TAXES | 27 | Personalens skatter, avgifter och löneavdrag |
| OTHER_CURRENT_LIABILITIES | 28 | Övriga kortfristiga skulder |
| ACCRUED_EXPENSES | 29 | Upplupna kostnader och förutbetalda intäkter |

#### Resultatkonton (30–89)

| Namn | Intervall | Beskrivning |
|---|---|---|
| NET_REVENUE | 30–34 | Nettoomsättning / Huvudintäkter |
| INVOICED_COSTS | 35 | Fakturerade kostnader |
| SECONDARY_INCOME | 36 | Rörelsens sidointäkter |
| REVENUE_ADJUSTMENTS | 37 | Intäktskorrigeringar |
| CAPITALIZED_WORK | 38 | Aktiverat arbete för egen räkning |
| OTHER_OPERATING_INCOME | 39 | Övriga rörelseintäkter |
| RAW_MATERIALS | 40–49 | Varuinköp / Råvaror och förnödenheter |
| OTHER_EXTERNAL_COSTS | 50–69 | Övriga externa kostnader |
| PERSONNEL_COSTS | 70–76 | Personalkostnader |
| DEPRECIATION | 77–78 | Av- och nedskrivningar |
| OTHER_OPERATING_COSTS | 79 | Övriga rörelsekostnader |
| FINANCIAL_INCOME | 80–83 | Finansiella intäkter |
| FINANCIAL_COSTS | 84 | Finansiella kostnader |
| APPROPRIATIONS | 88 | Bokslutsdispositioner |
| TAX_AND_RESULT | 89 | Skatter och årets resultat |

### Konstruktor

```groovy
AccountSubgroup(int basGroupStart, int basGroupEnd)
```

### Nyckelmetoder

- `static AccountSubgroup fromAccountNumber(String accountNumber)` — returnerar enum-värde
  baserat på kontonumrets tvåsiffriga prefix, eller `null` om ingen matchning.
- `static AccountSubgroup fromDatabaseValue(String value)` — `valueOf(value?.trim())` eller
  `null`, samma mönster som `VatCode.fromDatabaseValue`.
- `String getDisplayName()` — via `I18n.instance.getString("accountSubgroup.${name()}")`.
- `boolean contains(int basGroup)` — `true` om den tvåsiffriga gruppen faller inom intervallet.

---

## 2. `IncomeStatementSection` enum

Paket: `se.alipsa.accounting.domain.report`

Definierar rapportens logiska sektioner med de `AccountSubgroup`-värden varje sektion innehåller
och huruvida sektionen är en beräknad resultatrad.

### Enumvärden

| Namn | Undergrupper | Beräknad |
|---|---|---|
| OPERATING_INCOME | NET_REVENUE, INVOICED_COSTS, SECONDARY_INCOME, REVENUE_ADJUSTMENTS, CAPITALIZED_WORK, OTHER_OPERATING_INCOME | nej |
| OPERATING_EXPENSES | RAW_MATERIALS, OTHER_EXTERNAL_COSTS, PERSONNEL_COSTS, DEPRECIATION, OTHER_OPERATING_COSTS | nej |
| OPERATING_RESULT | — | ja |
| FINANCIAL_ITEMS | FINANCIAL_INCOME, FINANCIAL_COSTS | nej |
| RESULT_AFTER_FINANCIAL | — | ja |
| APPROPRIATIONS | APPROPRIATIONS | nej |
| TAX | TAX_AND_RESULT | nej |
| NET_RESULT | — | ja |

### Konstruktor

```groovy
IncomeStatementSection(List<AccountSubgroup> subgroups, boolean computed)
```

### Nyckelmetoder

- `String getDisplayName()` — via `I18n.instance.getString("incomeStatementSection.${name()}")`.
- `boolean isComputed()` — `true` för OPERATING_RESULT, RESULT_AFTER_FINANCIAL, NET_RESULT.

---

## 3. DB-migration `V18__account_subgroup.sql`

```sql
alter table account
    add column account_subgroup varchar(32);
```

Följt av UPDATE-satser som populerar befintliga konton baserat på kontonumrets tvåsiffriga prefix.
Alla 30 grupper mappas. Kolumnen är nullable — konton utan matchning lämnas null.

---

## 4. Ändringar i `Account` domänmodell

Nytt fält i `Account.groovy`:

```groovy
String accountSubgroup   // AccountSubgroup enum name, nullable
```

---

## 5. Import-uppdateringar

### `ChartOfAccountsImportService`

- `Classification` (inre klass) får nytt fält `accountSubgroup`.
- `classifyAccount()` härleder `AccountSubgroup` via `AccountSubgroup.fromAccountNumber()`.
- `persistAccounts()` sparar `account_subgroup` vid insert och uppdaterar vid re-import.

### `SieImportExportService`

- `AccountClassification` (inre klass) får nytt fält `accountSubgroup`.
- `classifyAccount()` härleder `AccountSubgroup` via `AccountSubgroup.fromAccountNumber()`.
- `upsertAccounts()` sparar `account_subgroup` vid insert och uppdaterar vid re-import.

---

## 6. Utökad `IncomeStatementRow`

Nuvarande fält: `section`, `accountNumber`, `accountName`, `amount`.

Nya fält:

```groovy
String subgroupDisplayName   // T.ex. "Personalkostnader", null för resultatrader
boolean summaryRow           // true för delsumma- och resultatrader
```

Fältet `section` ändras till att innehålla `IncomeStatementSection`-enumvärde (som sträng)
i stället för hårdkodat "Intäkter"/"Kostnader".

---

## 7. Omskriven `buildIncomeStatementReport`

Ny logik i `ReportDataService.buildIncomeStatementReport()`:

1. Hämta alla konton med `accountClass` INCOME eller EXPENSE, inklusive `account_subgroup`.
2. Beräkna signerat belopp per konto (samma som idag).
3. Gruppera per `AccountSubgroup` och summera.
4. Iterera `IncomeStatementSection.values()` i ordning:
   - För ej beräknade sektioner: lägg till en rad per undergrupp som har belopp != 0,
     plus en summeringsrad för sektionen.
   - För beräknade sektioner (OPERATING_RESULT, RESULT_AFTER_FINANCIAL, NET_RESULT):
     lägg till en enda resultatrad med korrekt ackumulerad summa.
5. Summarylines och template-model uppdateras med den nya strukturen.

### Beräkningslogik för resultatrader

- **Rörelseresultat** = Summa rörelseintäkter + Summa rörelsekostnader
- **Resultat efter finansiella poster** = Rörelseresultat + Finansiella intäkter + Finansiella kostnader
- **Årets resultat** = Resultat efter finansiella poster + Bokslutsdispositioner + Skatt

Intäkter representeras som positiva belopp, kostnader som negativa (via befintlig `signedAmount`).

---

## 8. FreeMarker-mall `income-statement.ftl`

Omskriven för att rendera den grupperade strukturen:

- Sektionsrubriker som egna rader.
- Kontogrupp-rader med undergruppnamn och belopp.
- Summeringsrader markerade med `summaryRow`-flagga (fetstil i HTML/PDF).
- Resultatrader (Rörelseresultat, Resultat efter finansiella poster, Årets resultat)
  renderade som framträdande summeringsrader.

Tabellkolumner: **Post** och **Belopp (SEK)** — enklare än nuvarande fyra kolumner.

---

## 9. I18n-nycklar

### `messages_sv.properties`

```properties
# AccountSubgroup
accountSubgroup.INTANGIBLE_ASSETS=Immateriella anläggningstillgångar
accountSubgroup.BUILDINGS_AND_LAND=Byggnader och mark
accountSubgroup.MACHINERY=Maskiner respektive inventarier
accountSubgroup.FINANCIAL_FIXED_ASSETS=Finansiella anläggningstillgångar
accountSubgroup.INVENTORY=Lager, produkter i arbete och pågående arbeten
accountSubgroup.RECEIVABLES=Kundfordringar
accountSubgroup.OTHER_CURRENT_RECEIVABLES=Övriga kortfristiga fordringar
accountSubgroup.PREPAID_EXPENSES=Förutbetalda kostnader och upplupna intäkter
accountSubgroup.SHORT_TERM_INVESTMENTS=Kortfristiga placeringar
accountSubgroup.CASH_AND_BANK=Kassa och bank
accountSubgroup.EQUITY=Eget kapital
accountSubgroup.UNTAXED_RESERVES=Obeskattade reserver
accountSubgroup.PROVISIONS=Avsättningar
accountSubgroup.LONG_TERM_LIABILITIES=Långfristiga skulder
accountSubgroup.SHORT_TERM_LIABILITIES_CREDIT=Kortfristiga skulder
accountSubgroup.TAX_LIABILITIES=Skatteskulder
accountSubgroup.VAT_AND_EXCISE=Moms och punktskatter
accountSubgroup.PAYROLL_TAXES=Personalens skatter, avgifter och löneavdrag
accountSubgroup.OTHER_CURRENT_LIABILITIES=Övriga kortfristiga skulder
accountSubgroup.ACCRUED_EXPENSES=Upplupna kostnader och förutbetalda intäkter
accountSubgroup.NET_REVENUE=Nettoomsättning
accountSubgroup.INVOICED_COSTS=Fakturerade kostnader
accountSubgroup.SECONDARY_INCOME=Rörelsens sidointäkter
accountSubgroup.REVENUE_ADJUSTMENTS=Intäktskorrigeringar
accountSubgroup.CAPITALIZED_WORK=Aktiverat arbete för egen räkning
accountSubgroup.OTHER_OPERATING_INCOME=Övriga rörelseintäkter
accountSubgroup.RAW_MATERIALS=Råvaror och förnödenheter
accountSubgroup.OTHER_EXTERNAL_COSTS=Övriga externa kostnader
accountSubgroup.PERSONNEL_COSTS=Personalkostnader
accountSubgroup.DEPRECIATION=Av- och nedskrivningar
accountSubgroup.OTHER_OPERATING_COSTS=Övriga rörelsekostnader
accountSubgroup.FINANCIAL_INCOME=Finansiella intäkter
accountSubgroup.FINANCIAL_COSTS=Finansiella kostnader
accountSubgroup.APPROPRIATIONS=Bokslutsdispositioner
accountSubgroup.TAX_AND_RESULT=Skatt på årets resultat

# IncomeStatementSection
incomeStatementSection.OPERATING_INCOME=Rörelseintäkter
incomeStatementSection.OPERATING_EXPENSES=Rörelsekostnader
incomeStatementSection.OPERATING_RESULT=Rörelseresultat
incomeStatementSection.FINANCIAL_ITEMS=Finansiella poster
incomeStatementSection.RESULT_AFTER_FINANCIAL=Resultat efter finansiella poster
incomeStatementSection.APPROPRIATIONS=Bokslutsdispositioner
incomeStatementSection.TAX=Skatt på årets resultat
incomeStatementSection.NET_RESULT=Årets resultat
```

Engelska nycklar läggs till i `messages.properties` med motsvarande översättningar.

---

## 10. Testplan

- Enhetstest för `AccountSubgroup.fromAccountNumber()` — verifiera att alla BAS-intervall mappas
  korrekt, inklusive gränsfall (t.ex. 3000, 3499, 3500, 7699, 7700).
- Integrationstest i `ReportServicesTest`:
  - Verifiera att resultatrapporten producerar korrekta sektioner och delsummor.
  - Verifiera att rörelseresultat, resultat efter finansiella poster och årets resultat beräknas
    korrekt.
  - Uppdatera befintligt PDF-test med ny sha256-hash.
- Migreringstest: verifiera att `V18__account_subgroup.sql` populerar konton korrekt.

---

## Filer som ändras

| Fil | Typ |
|---|---|
| `domain/AccountSubgroup.groovy` | Ny |
| `domain/report/IncomeStatementSection.groovy` | Ny |
| `domain/Account.groovy` | Ändrad — nytt fält |
| `domain/report/IncomeStatementRow.groovy` | Ändrad — nya fält |
| `db/migrations/V18__account_subgroup.sql` | Ny |
| `service/ChartOfAccountsImportService.groovy` | Ändrad |
| `service/SieImportExportService.groovy` | Ändrad |
| `service/ReportDataService.groovy` | Ändrad |
| `resources/reports/income-statement.ftl` | Ändrad |
| `resources/i18n/messages_sv.properties` | Ändrad |
| `resources/i18n/messages.properties` | Ändrad |
| `test/.../ReportServicesTest.groovy` | Ändrad |
