# <img src="app/src/main/resources/icons/logo128.png" alt="Alipsa Accounting" width="32" height="32"> Alipsa Bokföring / Alipsa Accounting

![Groovy 5.0](https://img.shields.io/badge/Groovy-5.0-blue?logo=apachegroovy)
![Java 21+](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)
![Gradle 9.6](https://img.shields.io/badge/Gradle-9.6-02303A?logo=gradle)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

Desktopbaserat bokföringsprogram för små svenska företag.
Byggt med Groovy, Swing och en inbäddad H2-databas — inga externa tjänster behövs.

Programmet hanterar löpande bokföring, moms, rapporter, SIE-utväxling och årsbokslut — och ger dig dessutom AI-assisterad bokföring med människan i kontroll.
Anslut valfri MCP-kompatibel AI-klient för att analysera ditt bokföringsunderlag, få förslag och arbeta direkt mot samma valideringar och affärsregler som i desktopappen. AI:n kan förbereda en verifikation, men bara du kan spara den.
Det är inte ett komplett affärssystem — fakturering, lönehantering, bankintegration och årsredovisningsflöden ingår inte.

## Funktioner

- **AI-assisterad bokföring — med dig i kontroll** — anslut Claude Code, Codex, Kimi eller Vibe till en lokal, token-skyddad MCP-server. AI:n får kontoplan, historik och rapporter som kontext, kan validera förslag och fylla den osparade verifikationsvyn, men kan aldrig bokföra eller spara åt dig. Allt går genom samma lokala H2-databas, valideringar och affärsregler som i appen.
- **Flerföretagsstöd** — skapa och växla mellan flera företag i samma installation. Varje företag har egen kontoplan, egna räkenskapsår, nummerserier, hashkedjor och rapportarkiv. Data isoleras fullständigt via `company_id` i datamodellen.
- **Kontoplan** — BAS-baserad kontoplan med import från Excel och automatisk klassificering. Kontoplanen är företagsspecifik — två företag kan ha samma BAS-kontonummer utan konflikt.
- **Räkenskapsår och perioder** — skapa år, dela in i perioder och lås perioder när de är klara.
- **Verifikationer** — registrera, bokför, korrigera, duplicera och navigera mellan verifikationer med bilagor. Efter sparning öppnas nästa utkast automatiskt, eller så kan du stanna kvar på den sparade verifikationen för utskrift eller duplicering.
- **Moms** — beräkna, rapportera och bokför momsöverföring per period. Stöder månads-, kvartals- och årsmoms.
- **Rapporter** — generera verifikationslista, huvudbok, provbalans, resultat- och balansrapport, transaktionsrapport och momsrapport som PDF eller CSV. Rapporter arkiveras med checksumma.
- **SIE4** — importera och exportera bokföringsdata via SIE4 med dubblettskydd, automatisk import/ersätt-logik och integritetskontroll.
- **Bokslut** — stäng räkenskapsår med bokslutsverifikation och automatisk generering av nästa års ingående balanser.
- **Arkivering och radering** — arkivera företag som inte längre ska visas i normalflödet, återställ arkiverade företag och radera företag eller räkenskapsår när bevarandekraven tillåter det.
- **Kraschsäkra filarkiv** — bilagor och rapportarkiv verifieras vid start, och avbrutna bilageoperationer återställs eller rapporteras tydligt.
- **Revisionskedja** — alla väsentliga händelser loggas i en hashkedja för spårbarhet.
- **Uppdateringar och avinstallation** — kontrollera uppdateringar från programmet, installera generiska uppdateringsarkiv och använd plattformsspecifika avinstallationsskript med separata bekräftelser för programfiler och användardata.

### Avgränsningar

Följande funktioner ingår inte ännu:

- Fakturering och lön
- Bankintegration
- Årsredovisningsflöden
- Anläggningsregister (under utredning, inget fastställt versionsmål)

## Installation

Ladda ner rätt paket för din plattform från [GitHub Releases](https://github.com/Alipsa/accounting/releases). Varje paket innehåller en egen inbäddad Java-runtime — ingen separat Java-installation behövs.

- **Linux**: packa upp `alipsa-accounting-<version>-linux.zip` och kör `./install.sh` i den uppackade katalogen.
- **Windows**: packa upp `alipsa-accounting-<version>-windows.zip` och kör exe-installeraren, t.ex. `AlipsaAccounting-<version>.exe`.
- **macOS**: packa upp `alipsa-accounting-<version>-macos.zip` och flytta `AlipsaAccounting.app` till Programmappen. Appen är inte signerad/notariserad (se [Release](#release) nedan), så Gatekeeper varnar första gången — högerklicka (Ctrl-klicka) på appen, välj **Öppna** och bekräfta i dialogrutan.

Se [Release](#release) för filverifiering (checksummor och GPG-signaturer) samt avinstallation.

## För utvecklare

Det här avsnittet är för dig som vill bygga, köra eller bidra till projektet från källkod. Vill du bara använda programmet, se [Installation](#installation) ovan istället.

- Java 21 eller senare krävs för att bygga och köra från källkod.

### Kom igång

```bash
git clone https://github.com/Alipsa/accounting.git
cd accounting
./gradlew run
```

Applikationen skapar sin H2-databas automatiskt vid första start.

### Bygg och kör

- `./gradlew build` kör full validering med kompilering, tester, Spotless och CodeNarc.
- `./gradlew test` kör testsviten.
- `./gradlew run` startar desktopapplikationen.
- `./gradlew :app:packageCurrentPlatformRelease` bygger releasepaket för aktuell plattform via `jpackage`.
- `./gradlew :app:verifyCurrentPlatformRelease` paketerar aktuell plattform och verifierar att launchern kan starta applikationen i ett isolerat hemkatalogsläge.

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

| Komponent     | Teknologi                               |
|---------------|-----------------------------------------|
| Språk         | Groovy (`@CompileStatic`)               |
| UI            | Swing med flatlaf look and feel         |
| Databas       | H2 (inbäddad)                           |
| PDF-rapporter | Alipsa Journo (FreeMarker + HTML → PDF) |
| SIE-parsning  | Alipsa SieParser                        |
| Bygg          | Gradle 9.6                              |
| Kodstil       | Spotless + CodeNarc                     |
| Tester        | JUnit 6 + groovier-junit                |

## Drift och säkerhet

- Applikationen använder endast embedded H2 i fil-läge.
- Databasen, bilagor, rapportarkiv, loggar, backups och exporterad dokumentation lagras under applikationskatalogen i användarens profil.
- Startup-verifiering kontrollerar driftkonfiguration och integritet för hashkedjor, bilagor och rapportarkiv.
- Känsliga operationer som rapportexport, SIE-export, backup och årsstängning blockerar på kritiska integritetsfel.
- Bokföringsdata, bilagor och rapportarkiv omfattas av sju års bevarandespärr.
- Borttagning av räkenskapsår och företag visar förhandsgranskning eller blockerande fel innan data tas bort.

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

## AI-assisterad bokföring

Alipsa Accounting kombinerar en vanlig desktopapp med en lokal AI-arbetsyta: AI-klienten får relevant bokföringskontext och kan hjälpa till med förslag, konton, moms, rättelser, SIE och bokslut, medan appens regler och ditt godkännande styr alla förändringar. Det är assistans i bokföringsflödet — inte automatisk bokföring.

När desktopappen körs startar den en lokal, token-skyddad MCP-server på `http://127.0.0.1:48652/mcp`. Konfigurera Claude Code, Codex, Kimi eller Vibe som en HTTP-MCP-klient med `Authorization: Bearer <token>`. Endpoint och token visas under Inställningar; token kan regenereras där.

Servern använder samma lokala H2-databas, valideringar och affärsregler som desktopappen. Den är endast åtkomlig från den egna datorn och slutar svara när appen stängs; ett anslutningsfel i AI-klienten då är förväntat och ofarligt. Äldre stdio-konfigurationer med `--mode=mcp` stöds inte längre och måste ersättas med HTTP-konfigurationen.

AI:n kan lägga ett förslag i den osparade verifikationsvyn, men kan aldrig spara det. Användaren granskar och trycker själv på Spara i desktopappen.

MCP-servern ger klienten verktygen. Skill-filen styr hur LLM:en bör använda verktygen. Skill-filen installeras inte automatiskt av Claude Code eller Codex; se releaseavsnittet nedan för hur den länkas eller kopieras till respektive klients skill-katalog.

## Release

### Nedladdningar

Varje release publiceras på [GitHub Releases](https://github.com/Alipsa/accounting/releases) med tre användardistributioner och ett generiskt uppdateringsarkiv:

| Fil                                       | Plattform                                                             |
|-------------------------------------------|-----------------------------------------------------------------------|
| `alipsa-accounting-<version>-linux.zip`   | Linux — app-image, install-/avinstallationsskript och `skill/accounting-mcp.md` |
| `alipsa-accounting-<version>-windows.zip` | Windows — exe-installerare, cleanup-skript och `skill/accounting-mcp.md`        |
| `alipsa-accounting-<version>-macos.zip`   | macOS — `AlipsaAccounting.app`, avinstallationsskript och `skill/accounting-mcp.md` |
| `app-<version>.zip`                       | Generiskt arkiv som används av den inbyggda automatiska uppdateraren  |

Varje distributionsfil åtföljs av två verifieringsfiler:

- **`.sha256`** — SHA-256-kontrollsumma. Verifiera att nedladdningen är intakt:
  ```
  sha256sum -c alipsa-accounting-<version>-linux.zip.sha256
  ```
- **`.asc`** — GPG-signatur. Verifiera att filen är publicerad av releaseansvarig:
  ```
  gpg --verify alipsa-accounting-<version>-linux.zip.asc alipsa-accounting-<version>-linux.zip
  ```

För vanliga användare räcker det att ladda ner distributionsfilen. Verifieringsfilerna riktar sig till den som vill kontrollera filens integritet och äkthet.

### Artefaktverifiering och signering

Publicerade artefakter åtföljs av SHA-256-kontrollsummor och GPG-signaturer så att nedladdningen kan verifieras efter publicering.

Projektet levererar för närvarande inte plattformsbetrodda kodsignaturer för Windows eller notariserade/signerade macOS-appar. Användare på dessa plattformar kan därför se operativsystemets vanliga varningar för osignerade applikationer.

- Committers och granskare: bidragsgivare till repot med skrivbehörighet till projektet. Offentlig bidragshistorik: [Contributors](https://github.com/Alipsa/accounting/graphs/contributors)
- Releaseansvariga: projektförvaltare med ansvar för publicering. Aktuell projektsida: [Alipsa/accounting](https://github.com/Alipsa/accounting)
- Integritetspolicy: [docs/privacy-policy.md](docs/privacy-policy.md)

Applikationen lagrar bokföringsdata lokalt på användarens dator.

Applikationen laddar inte upp bokföringsposter, bilagor, rapporter, backuper eller exporterade filer till projektdrivna servrar.

Applikationen kan göra en bakgrundskontroll av uppdateringar mot GitHub Releases vid start, och kan ladda ner releaseartefakter från GitHub när användaren uttryckligen väljer att installera en uppdatering. Automatisk uppdateringskontroll kan stängas av i Inställningar. Se integritetspolicyn för detaljer.

Uppdateraren i appen använder det generiska arkivet `app-<version>.zip` och uppdaterar den installerade jpackage-app-imagen på plats. Plattformsspecifika releasezip-filer används för nyinstallationer eller manuella uppgraderingar.

### Bygga en release

Releasebyggen använder `jpackage` och kräver Java 21 med tillhörande paketeringsverktyg på respektive plattform.

- Linux: `./gradlew :app:packageLinuxReleaseZip`
- Windows: `./gradlew :app:packageWindowsRelease`
- macOS: `./gradlew :app:packageMacosRelease`
- Aktuell plattform: `./gradlew :app:packageCurrentPlatformRelease`
- Smoke test av aktuell plattform: `./gradlew :app:verifyCurrentPlatformRelease`

Om Gradle hittar fel JDK för jpackage (t.ex. en inbäddad JDK utan jpackage) kan sökvägen sättas explicit:

```
./gradlew :app:verifyCurrentPlatformRelease -Pjpackage.executable=/path/to/jdk-21/bin/jpackage
```

Byggartefakter skrivs till `app/build/release/` och använder samma appnamn, versionsnummer och ikonuppsättning för alla tre plattformar.

Alla tre plattformsreleaserna producerar ett zip-arkiv som innehåller `skill/accounting-mcp.md`. Linux-arkivet innehåller app-imagen samt `install.sh` och `uninstall.sh`. Windows-arkivet innehåller exe-installeraren med meny- och skrivbordsgenväg samt `uninstall-cleanup.ps1` för valfri borttagning av kvarvarande installationskatalog och användardata. macOS-arkivet innehåller `AlipsaAccounting.app` och `uninstall.command`.

Claude Code och Codex läser inte automatiskt `skill/`-katalogen; extrahera arkivet och länka eller kopiera den till klientens skill-katalog:

```
# Claude Code
ln -s /path/to/release/skill ~/.claude/skills/accounting

# Codex
ln -s /path/to/release/skill ~/.agents/skills/accounting

# Windows PowerShell, Claude Code
New-Item -ItemType Junction -Path "$HOME\.claude\skills\accounting" -Target "C:\path\to\release\skill"

# Windows PowerShell, Codex
New-Item -ItemType Junction -Path "$HOME\.agents\skills\accounting" -Target "C:\path\to\release\skill"
```

Applikationen använder plattformsspecifika standardvägar för data och loggar:

- Linux: `~/.local/share/alipsa-accounting`
- Windows: `%APPDATA%\\Alipsa\\Accounting`
- macOS: `~/Library/Application Support/AlipsaAccounting`

Datakatalogen är skild från applikationsinstallationen. Det innebär att befintlig data är tillgänglig direkt när en ny version installeras — ingen manuell flytt behövs. På Windows tar avinstallationen av den gamla versionen inte bort `%APPDATA%\Alipsa\Accounting`.

Avinstallationsskripten frågar innan de tar bort installationskatalogen och frågar separat innan de tar bort användardata. Standardvalet är att behålla både installation och data om användaren bara trycker Enter.

Signering av Windows-installatör och notarisering/signering av macOS-app är avsiktligt lämnade som framtida release-steg. Nuvarande buildflöde producerar signerbara artefakter men utför inte signering automatiskt.
