# <img src="app/src/main/resources/icons/logo128.png" alt="Alipsa Accounting" width="32" height="32"> Alipsa Accounting

![Groovy 5.0](https://img.shields.io/badge/Groovy-5.0-blue?logo=apachegroovy)
![Java 21+](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)
![Gradle 9.6](https://img.shields.io/badge/Gradle-9.4-02303A?logo=gradle)
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
- **Verifikationer** — registrera, bokför och korrigera verifikationer med bilagor.
- **Moms** — beräkna, rapportera och bokför momsöverföring per period. Stöder månads-, kvartals- och årsmoms.
- **Rapporter** — generera verifikationslista, huvudbok, provbalans, resultat- och balansrapport, transaktionsrapport och momsrapport som PDF eller CSV. Rapporter arkiveras med checksumma.
- **SIE4** — importera och exportera bokföringsdata via SIE4 med dubblettskydd, automatisk import/ersätt-logik och integritetskontroll.
- **Bokslut** — stäng räkenskapsår med bokslutsverifikation och automatisk generering av nästa års ingående balanser.
- **Arkivering och radering** — arkivera företag som inte längre ska visas i normalflödet, återställ arkiverade företag och radera företag eller räkenskapsår när bevarandekraven tillåter det.
- **Kraschsäkra filarkiv** — bilagor och rapportarkiv verifieras vid start, och avbrutna bilageoperationer återställs eller rapporteras tydligt.
- **Revisionskedja** — alla väsentliga händelser loggas i en hashkedja för spårbarhet.
- **Uppdateringar och avinstallation** — kontrollera uppdateringar från programmet, installera generiska uppdateringsarkiv och använd plattformsspecifika avinstallationsskript med separata bekräftelser för programfiler och användardata.

### Avgränsningar

Följande funktioner ingår inte i v1.3.0:

- Fakturering och lön
- Bankintegration
- Årsredovisningsflöden
- Anläggningsregister (planerat till v1.5.0)

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

| Komponent     | Teknologi                               |
|---------------|-----------------------------------------|
| Språk         | Groovy (`@CompileStatic`)               |
| UI            | Swing med flatlaf look and feel         |
| Databas       | H2 (inbäddad)                           |
| PDF-rapporter | Alipsa Journo (FreeMarker + HTML → PDF) |
| SIE-parsning  | Alipsa SieParser                        |
| Bygg          | Gradle 9.4                              |
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

### Artifact verification and signing

Released artifacts are accompanied by SHA-256 checksum files and GPG signatures so the download can be verified after publication.

The project does not currently ship platform-trusted code signatures for Windows or notarized/signature-stamped macOS app bundles. Users on those platforms may therefore see the standard unsigned-application warnings from the operating system.

- Committers and reviewers: repository contributors with write access to the project. Public contribution history: [Contributors](https://github.com/Alipsa/accounting/graphs/contributors)
- Release approvers: project maintainers responsible for publication. Current project home page: [Alipsa/accounting](https://github.com/Alipsa/accounting)
- Privacy policy: [docs/privacy-policy.md](docs/privacy-policy.md)

The application stores accounting data locally on the user's system.

The application does not upload accounting records, attachments, reports, backups, or exported files to project-operated servers.

The application can perform a background update check against GitHub Releases on startup, and may download release artifacts from GitHub when the user explicitly chooses to install an update. Automatic update checks can be disabled in Settings. See the privacy policy for details.

The in-app updater uses the generic `app-<version>.zip` archive and updates the installed jpackage app image in place. Platform release zips are used for fresh installs or manual upgrades.

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
