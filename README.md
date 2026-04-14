# <img src="app/src/main/resources/icons/logo128.png" alt="Alipsa Accounting" width="32" height="32"> Alipsa Accounting

![Groovy 5.0](https://img.shields.io/badge/Groovy-5.0-blue?logo=apachegroovy)
![Java 21+](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)
![Gradle 9.4](https://img.shields.io/badge/Gradle-9.4-02303A?logo=gradle)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

Desktopbaserat bokföringsprogram för små svenska företag.
Byggt med Groovy, Swing och en inbäddad H2-databas — inga externa tjänster behövs.

Programmet hanterar löpande bokföring, moms, rapporter, SIE-utväxling och årsbokslut.
Det är inte ett komplett affärssystem — fakturering, lönehantering, bankintegration och årsredovisningsflöden ingår inte.

## Funktioner

- **Flerföretagsstöd** — skapa och växla mellan flera företag i samma installation. Varje företag har egen kontoplan, egna räkenskapsår, nummerserier, hashkedjor och rapportarkiv. Data isoleras fullständigt via `company_id` i datamodellen.
- **Kontoplan** — BAS-baserad kontoplan med import från Excel och automatisk klassificering. Kontoplanen är företagsspecifik — två företag kan ha samma BAS-kontonummer utan konflikt.
- **Räkenskapsår och perioder** — skapa år, dela in i perioder och lås perioder när de är klara.
- **Verifikationer** — registrera, bokför och korrigera verifikationer med bilagor.
- **Moms** — beräkna, rapportera och bokför momsöverföring per period. Stöder månads-, kvartals- och årsmoms.
- **Rapporter** — generera verifikationslista, huvudbok, provbalans, resultat- och balansrapport, transaktionsrapport och momsrapport som PDF eller CSV. Rapporter arkiveras med checksumma.
- **SIE4** — importera och exportera bokföringsdata via SIE4 med dubblettskydd och integritetskontroll.
- **Bokslut** — stäng räkenskapsår med bokslutsverifikation och automatisk generering av nästa års ingående balanser.
- **Revisionskedja** — alla väsentliga händelser loggas i en hashkedja för spårbarhet.

### Avgränsningar

Följande funktioner ingår inte i v1.0.0:

- Fakturering och lön
- Bankintegration
- Årsredovisningsflöden
- Anläggningsregister (planerat till v1.1.0)
- Förbättrad kraschsäker bilagehantering (planerat till v1.1.0)

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
- `./gradlew :app:packageCurrentPlatformRelease` bygger releasepaket för aktuell plattform via `jpackage`.
- `./gradlew :app:verifyCurrentPlatformRelease` paketerar aktuell plattform och verifierar att launchern kan starta applikationen i ett isolerat hemkatalogsläge.

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
| Tester        | JUnit 6 + groovier-junit         |

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

Releasebyggen använder `jpackage` och kräver Java 21 med tillhörande paketeringsverktyg på respektive plattform.

- Linux: `./gradlew :app:packageLinuxReleaseZip`
- Windows: `./gradlew :app:packageWindowsInstaller`
- macOS: `./gradlew :app:packageMacosAppImage`
- Aktuell plattform: `./gradlew :app:packageCurrentPlatformRelease`
- Smoke test av aktuell plattform: `./gradlew :app:verifyCurrentPlatformRelease`

Om Gradle hittar fel JDK för jpackage (t.ex. en inbäddad JDK utan jpackage) kan sökvägen sättas explicit:

```
./gradlew :app:verifyCurrentPlatformRelease -Pjpackage.executable=/path/to/jdk-21/bin/jpackage
```

Byggartefakter skrivs till `app/build/release/` och använder samma appnamn, versionsnummer och ikonuppsättning för alla tre plattformar.

Linux-releasen producerar en `app-image` plus ett zip-arkiv som även innehåller `.desktop`-filen. Windows-releasen producerar en `exe`-installerare med meny- och skrivbordsgenväg. macOS-releasen producerar `AlipsaAccounting.app`.

Applikationen använder plattformsspecifika standardvägar för data och loggar:

- Linux: `~/.local/share/alipsa-accounting`
- Windows: `%APPDATA%\\Alipsa\\Accounting`
- macOS: `~/Library/Application Support/AlipsaAccounting`

Signering av Windows-installatör och notarisering/signering av macOS-app är avsiktligt lämnade som framtida release-steg. Nuvarande buildflöde producerar signerbara artefakter men utför inte signering automatiskt.
