# Alipsa Accounting

Desktopbaserat bokföringsprogram för små svenska företag.
Byggt med Groovy, Swing och en inbäddad H2-databas — inga externa tjänster behövs.

## Funktioner

- **Kontoplan** — BAS-baserad kontoplan med import från Excel och automatisk klassificering.
- **Räkenskapsår och perioder** — skapa år, dela in i perioder och lås perioder när de är klara.
- **Verifikationer** — registrera, bokför och korrigera verifikationer med bilagor.
- **Moms** — beräkna, rapportera och bokför momsöverföring per period.
- **Rapporter** — generera verifikationslista, huvudbok, provbalans, resultat- och balansrapport, transaktionsrapport och momsrapport som PDF eller CSV. Rapporter arkiveras med checksumma.
- **SIE4** — importera och exportera bokföringsdata via SIE4 med dubblettskydd och integritetskontroll.
- **Bokslut** — stäng räkenskapsår med bokslutsverifikation och automatisk generering av nästa års ingående balanser.
- **Revisionskedja** — alla väsentliga händelser loggas i en hashkedja för spårbarhet.

## Förutsättningar

- Java 21 eller senare

## Kom igång

```bash
git clone https://github.com/Alipsa/accounting.git
cd accounting
./gradlew run
```

Applikationen skapar sin H2-databas automatiskt vid första start.

## Bygg och kör

- `./gradlew build` kör full validering med kompilering, tester, Spotless och CodeNarc.
- `./gradlew test` kör testsviten.
- `./gradlew run` startar desktopapplikationen.
- `./gradlew distZip` och `./gradlew distTar` bygger generella distributionsarkiv från `application`-pluginet.

## Utveckling

### Projektstruktur

```
app/src/main/groovy/se/alipsa/accounting/
├── domain/          # domänmodeller
├── service/         # databas- och affärslogik
├── ui/              # Swing-paneler och dialoger
└── support/         # hjälpklasser

app/src/main/resources/
├── db/migrations/   # SQL-migrationer
└── reports/         # FreeMarker-mallar för PDF-rapporter

app/src/test/groovy/
├── unit/            # enhetstester
├── integration/     # integrationstester
└── acceptance/      # acceptanstester

req/                 # kravdokumentation och färdplan
```

### Kommandon för utvecklare

```bash
./gradlew build          # kompilering, tester, Spotless och CodeNarc
./gradlew test           # kör enbart tester
./gradlew spotlessApply  # auto-formatera kod
./gradlew spotlessCheck  # kontrollera formatering utan att ändra
./gradlew codenarcMain   # statisk analys på produktionskod
```

Kör alla kommandon från rotmappen.

### Teknikstack

| Komponent     | Teknologi                        |
|---------------|----------------------------------|
| Språk         | Groovy (`@CompileStatic`)        |
| UI            | Swing                            |
| Databas       | H2 (inbäddad)                   |
| PDF-rapporter | Journo (FreeMarker + HTML → PDF) |
| SIE-parsning  | sie-reader                       |
| Bygg          | Gradle 9.4                       |
| Kodstil       | Spotless + CodeNarc              |
| Tester        | JUnit 5 + groovier-junit         |

## Drift och säkerhet

- Applikationen använder endast embedded H2 i fil-läge.
- Databasen, bilagor, rapportarkiv, loggar, backups och exporterad dokumentation lagras under applikationskatalogen i användarens profil.
- Startup-verifiering kontrollerar driftkonfiguration och integritet för hashkedjor, bilagor och rapportarkiv.
- Känsliga operationer som rapportexport, SIE-export, backup och årsstängning blockerar på kritiska integritetsfel.
- Bokföringsdata, bilagor och rapportarkiv omfattas av sju års bevarandespärr.

## Backup och restore

- Backupformatet är ZIP med:
  - H2 `SCRIPT`-dump
  - `manifest.txt` med schema-version och checksummor
  - bilagearkiv
  - rapportarkiv
- Restore verifierar manifest och checksummor innan data och filarkiv återskapas.
- Backup och restore kan köras från fliken `System`.

## Dokumentation

- Fliken `System` visar diagnostik, backup/restore och inbyggd systemdokumentation.
- Hjälpmenyn öppnar användarmanualen från `app/src/main/resources/docs/user-manual.md`.

## Release

- Plattformsspecifik paketering för Windows, Linux och macOS planeras i fas 11.
- I nuläget produceras generella distributionsarkiv via Gradles `application`-plugin som grund för vidare releasearbete.
