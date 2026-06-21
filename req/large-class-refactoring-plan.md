# Plan: dela upp stora klasser

## Bakgrund

Följande klasser har höga råa filradantal (`wc -l`) och blandar flera ansvar i samma fil:

- `VoucherPanel.groovy`: 1337 rader.
- `SieImportExportService.groovy`: 1275 rader.
- `ReportDataService.groovy`: 1206 rader.
- `AccountingMcpTools.groovy`: 1203 rader.

Detta är inte ett akut buildblockerande problem. `./gradlew codenarcMain` på aktuell `main` är grön med 0 violations i main-källorna trots `ClassSize maxLines = 1250`, eftersom CodeNarc mäter klassens kodstorlek snarare än råa filrader. Refaktoreringen är därför en frivillig kodhälsoinsats som ska minska ansvarsmix och hålla marginal till framtida CodeNarc-träffar.

Målet är inte att ändra funktionalitet, databasschema eller användarflöden. Målet är att sänka varje ursprunglig klass tydligt under riskzonen med tydliga ansvar, bibehållen publik API där andra delar av applikationen redan använder klasserna, och bättre testbarhet för de delar som idag ligger som privata helpers.

## Principer

- Behåll befintliga publika entrypoints: `VoucherPanel`, `SieImportExportService`, `ReportDataService` och `AccountingMcpTools` ska fortsätta vara de klasser som omgivande kod instansierar.
- Flytta sammanhängande ansvar till package-private eller `final` helperklasser i samma paket innan ny publik API införs.
- Gör en klass i taget och håll varje PR liten nog att kunna granskas med vanlig diffgranskning.
- Lägg inte till generiska abstraktioner förrän två konkreta extraktioner behöver samma kontrakt.
- Kör minst berörda integrationstester efter varje steg och full `./gradlew build` efter varje klass.

## Föreslagen ordning

Innan första refaktoreringen startar i en ny arbetsgren: kör `./gradlew codenarcMain` på aktuell `main` och dokumentera om `ClassSize` flaggar någon av de fyra filerna vid den tidpunkten. Om resultatet skiljer sig från förväntan ska `config/codenarc/ruleset.groovy` och eventuella exkluderingar kontrolleras innan planen genomförs.

1. `AccountingMcpTools.groovy`: lägst UI-risk och tydligast domängränser.
2. `ReportDataService.groovy`: stora rena serviceblock med befintlig integrationstesttäckning.
3. `SieImportExportService.groovy`: hög affärsrisk, men tydlig import/export-delning.
4. `VoucherPanel.groovy`: högst UI-risk; görs sist när övrig kodhälsoskuld är minskad.

## AccountingMcpTools.groovy

### Nuläge

Klassen innehåller tre separata ansvar: verktygsdefinitioner, dispatch från MCP-namn till implementation, samt implementationer för bolag/konton/rapporter/moms/verifikationer/bokslut/SIE.

### Målbild

- `AccountingMcpTools` blir en tunn registry/dispatcher som behåller konstruktorerna och `listTools()`/`callTool()`.
- Nya package-private handlers i `se.alipsa.accounting.mcp`, till exempel:
  - `McpToolDefinitions` för schema- och parameterdefinitioner.
  - `ReadOnlyMcpTools` för bolag, räkenskapsår, konton och rapportläsning.
  - `VoucherMcpTools` för `preview_voucher`, `post_voucher` och `create_correction_voucher`.
  - `VatMcpTools` för momsverktyg.
  - `YearEndMcpTools` för bokslutsverktyg.
  - `SieMcpTools` för SIE-import/export och importjobb.
  - `McpArgumentSupport` för `requiredLong`, `optionalString`, beloppstolkning och datumtillstånd.

### Steg

1. Extrahera statiska tool definitions och parameterhelpers utan beteendeändring.
2. Flytta argumentparsning och token/canonical payload helpers till `McpArgumentSupport`.
3. Flytta domänmetoderna gruppvis till handlers, en handler per commit.
4. Låt `AccountingMcpTools.callTool()` dispatcha till handlers med samma tool names.

### Tester

- `./gradlew test --tests 'se.alipsa.accounting.mcp.*'`
- `./gradlew codenarcMain`
- `./gradlew build`

## ReportDataService.groovy

### Nuläge

Klassen har en publik `generate()`-facade men bygger alla rapporttyper själv: verifikationslista, huvudbok, resultatrapport, balansrapport, transaktionsrapport och momsrapport. Den innehåller också gemensamma SQL-loaders, urvalslogik och formatering.

### Målbild

- `ReportDataService` behåller `generate(ReportSelection)` och gemensam dependency wiring.
- Rapportbyggare flyttas till små package-private klasser i `se.alipsa.accounting.service`, till exempel:
  - `VoucherListReportBuilder`
  - `GeneralLedgerReportBuilder`
  - `TrialBalanceReportBuilder`
  - `IncomeStatementReportBuilder`
  - `BalanceSheetReportBuilder`
  - `TransactionReportBuilder`
  - `VatReportBuilder`
- Gemensamt stöd samlas i:
  - `ReportSelectionResolver` för `EffectiveSelection`.
  - `ReportSqlLoader` för `loadAccountInfos`, `loadOpeningBalances`, `loadSignedMovements`, `loadPostingLines` och periodtotals.
  - `ReportResultFactory` eller en enkel package-private helper för `createResult`, rubriker och beloppsformat.

### Steg

1. Extrahera `ReportSelectionResolver` utan att flytta rapportlogik.
2. Extrahera SQL-loaders till `ReportSqlLoader` och håll metodsignaturerna nära dagens privata helpers.
3. Flytta en rapporttyp i taget till egen builder, börja med `VoucherListReportBuilder` eller `TransactionReportBuilder` eftersom de är smalast.
4. Flytta resultat- och balansrapporter sist eftersom de har mest grupperingslogik.
5. Efter varje builder: jämför `ReportResult` i befintliga integrationstester innan nästa flytt.

### Tester

- `./gradlew test --tests 'se.alipsa.accounting.service.ReportServicesTest'`
- `./gradlew test --tests 'se.alipsa.accounting.service.MultiCompanyIsolationTest'`
- `./gradlew test --tests 'se.alipsa.accounting.service.MultiCompanyChainHeadTest'`
- `./gradlew codenarcMain`
- `./gradlew build`

## SieImportExportService.groovy

### Nuläge

Klassen är facade, importmotor, exportmotor, importjobbslogg, SIE-normalisering, kontoklassificering och ersättningsflöde i samma fil.

### Målbild

- `SieImportExportService` behåller publik API: `peekSieCompany`, `previewSieImport`, `importFile`, `replaceFiscalYear`, `reopenAndReplaceFiscalYear`, `exportFiscalYear` och `listImportJobs`.
- Extrahera till package-private servicehelpers:
  - `SieDocumentParser` för filvalidering, storlekskontroll, checksumma och `SieDocumentReader`.
  - `SieImportPlanner` för bokföringsår, befintliga år, blockerande issues och `SieImportPreview`.
  - `SieImportPersister` för konton, IB, verifikationer och saldokontroller.
  - `SieExportBuilder` för `buildBookingYear`, konton, IB/UB och exportverifikationer.
  - `SieImportJobRepository` för `createImportJob`, duplicate lookup, statusuppdatering och mapping.
  - `SieAccountClassifier` för keywordbaserad kontoklassificering och diakritikhantering.

### Steg

1. Extrahera importjobbsrepository först; det är avgränsat och har många rena SQL-metoder.
2. Extrahera dokumentparsning och path/checksum-validering.
3. Extrahera exportbyggaren; den använder huvudsakligen läsande SQL och SIE-modeller.
4. Extrahera importpersisteringen sist eftersom den har störst transaktions- och auditlogg-risk.
5. Behåll transaktionsgränsen i facade-metoden tills alla helpers är flyttade, så att rollback-beteendet inte förändras.

### Tester

- `./gradlew test --tests 'se.alipsa.accounting.service.SieImportExportServiceTest'`
- `./gradlew test --tests 'se.alipsa.accounting.service.MultiCompanyIsolationTest'`
- `./gradlew test --tests 'se.alipsa.accounting.AcceptanceCriteriaTest'`
- `./gradlew codenarcMain`
- `./gradlew build`

## VoucherPanel.groovy

### Nuläge

Klassen blandar panelkonstruktion, tangentbordsnavigation, verifikationskommandon, bilagehantering, historikvisning, beloppsformattering och tre table models. Den har dessutom `@PackageScope` hooks som används av navigationstester.

### Målbild

- `VoucherPanel` fortsätter vara Swing-panelen som andra vyer använder.
- Flytta rena Swing/table delar först:
  - `VoucherLineCellEditor`
  - `VoucherLineEntry`
  - `VoucherLineTableModel`
  - `AttachmentTableModel`
  - `AuditLogTableModel`
- Flytta därefter samlade operationer till package-private collaborators:
  - `VoucherPanelNavigation` för tab/enter-flöde och `advanceFromCell`.
  - `VoucherPanelAttachments` för lägga till/öppna bilagor och knappstatus.
  - `VoucherPanelActions` eller små kommandohelpers för spara, korrigera, duplicera och makulera.
- Behåll UI-layouten i `VoucherPanel` tills table models och helpers är stabila; bred layoutrefaktor är inte målet.

### Steg

1. Flytta `AttachmentTableModel` och `AuditLogTableModel` till egna filer. De är statiska, små och har låg risk.
2. Flytta `VoucherLineCellEditor` till egen fil.
3. Flytta `LineEntry` och `LineTableModel` tillsammans till `VoucherLineTableModel`, med explicita callbacks för `parseAmount`, account lookup, balance recalculation och auto-row. Behåll testernas åtkomst via package-private API.
4. Extrahera bilageflödet efter att table models är fristående.
5. Extrahera navigation sist, eftersom `VoucherPanelNavigationTest` redan låser beteendet runt `advanceFromCell`.

### Tester

- `./gradlew test --tests 'se.alipsa.accounting.ui.VoucherPanelNavigationTest'`
- `./gradlew test --tests 'se.alipsa.accounting.AcceptanceCriteriaTest'`
- Manuell start med `./gradlew run` vid större UI-flytt.
- `./gradlew codenarcMain`
- `./gradlew build`

## Definition of done

- Varje ursprunglig klass ligger tydligt under CodeNarc:s `ClassSize`-gräns med marginal.
- `./gradlew codenarcMain` har körts och outputen har granskats så att `ClassSize`-regeln är aktiv och inte längre flaggar de refaktorerade filerna.
- Inga nya schema- eller migrationsfiler.
- Befintliga publika konstruktorer och metoder finns kvar eller har en uttrycklig migreringsmotivering.
- Alla berörda integrationstester går grönt efter respektive steg.
- Full `./gradlew build` går grönt innan varje PR markeras redo för review.
