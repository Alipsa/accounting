# Användarmanual för Alipsa Accounting

Alipsa Accounting är ett bokföringsprogram för små svenska företag. Programmet hanterar löpande bokföring, moms, rapporter, SIE-utväxling och årsbokslut. Fakturering, lönehantering och bankintegration ingår inte.

## Kom igång

1. Starta programmet. Ett standardföretag skapas automatiskt vid första start.
2. Redigera företagsuppgifter via `Redigera företag` i verktygsfältet, eller skapa ett nytt företag via `Nytt företag`.
3. Skapa ett räkenskapsår i fliken `Räkenskapsår`.
4. Importera BAS-kontoplanen i fliken `Kontoplan`.
5. Kontrollera fliken `System` efter första start så att startup-verifieringen är grön.

## Flerföretagsstöd

Programmet kan hantera flera företag i samma installation. Varje företag har sin egen kontoplan, sina egna räkenskapsår, nummerserier, momsperioder och rapportarkiv.

- **Växla företag** — välj aktivt företag i rullgardinsmenyn högst upp i fönstret. Alla flikar och flöden arbetar mot det valda företaget.
- **Skapa nytt företag** — klicka `Nytt företag` i verktygsfältet och fyll i företagsuppgifter.
- **Redigera företag** — klicka `Redigera företag` för att ändra namn, organisationsnummer, valuta eller momsperiodicitet.
- **Arkivera företag** — använd `Arkiv -> Arkivera företag...` när ett företag inte längre ska synas i normalflödet men datan ska sparas.
- **Återställ arkiverat företag** — använd `Arkiv -> Återställ arkiverat företag...` för att göra ett arkiverat företag aktivt igen.
- **Radera företag** — använd `Arkiv -> Radera företag...` först när företagets räkenskapsår har raderats och datan inte längre ska sparas.
- **Isolering** — data är fullständigt isolerad mellan företag. Sökning, rapporter, SIE-export och bokslut för ett företag visar aldrig data från ett annat.

## Löpande bokföring

- Skapa verifikationer i fliken `Verifikationer`.
- Bokförda verifikationer kan inte ändras direkt utan korrigeras med korrigeringsverifikation.
- Bilagor kan kopplas till en verifikation och spåras i audit-loggen.
- Utkast kan uppdateras eller makuleras innan de bokförs.
- Om en period eller momsperiod är låst måste rätt flöde användas i stället för direkt ändring.

## Moms, rapporter och SIE

- Stäng och rapportera momsperioder i fliken `Moms`.
- Stöder månads-, kvartals- och årsmoms.
- Generera PDF- och CSV-rapporter i fliken `Rapporter`.
- Importera och exportera SIE4 under `Arkiv -> SIE import/export...`.
- SIE-importen förhandsgranskar filen och väljer importflöde automatiskt: vanlig import när inget krockar, ersättningsbekräftelse när ett befintligt räkenskapsår kan ersättas, upplåsning och ersättning när ett stängt år saknar bokslutsposter, eller ett tydligt fel när ersättning blockeras.
- CSV-export använder semikolon som avgränsare för att fungera bra med svensk Excel-standard.
- När en momsperiod har rapporterats ska ändringar göras via korrigering, inte genom att skriva över tidigare bokningar.

## Årsbokslut

- Lås alla perioder innan årsbokslut genomförs.
- Kör `Årsbokslut...` i fliken `Räkenskapsår`.
- Systemet stänger resultatkonton mot konto `2099` som standard och skapar nästa års ingående balanser.
- Förhandsgranskningen visar blockerande fel och varningar innan bokslutet genomförs.
- Räkenskapsår kan raderas från fliken `Räkenskapsår` när bevarandespärren har passerat och inga blockerande beroenden finns. Förhandsgranskningen visar hur många verifikationer, bilagor, rapporter, momsperioder och andra poster som tas bort.

## Backup, restore och systeminformation

- Öppna fliken `System` för diagnostik, backup/restore och export av systemdokumentation.
- Backup innehåller databasen, bilagearkivet och rapportarkivet med checksummor.
- Restore ersätter nuvarande databas och filarkiv efter verifiering av manifest och checksummor.
- Återställ endast backupfiler som kommer från en betrodd källa.
- Systemdiagnostiken visar schema-version, senaste backup och senaste SIE-export.
- Systemdokumentationen kan exporteras som Markdown för drift- eller revisionsunderlag.
- Bilagor skrivs med ett kraschsäkert copy-then-confirm-flöde. Om programmet avbryts mitt i en filoperation upptäcks det vid nästa start och visas som återställd eller som varning i systemdiagnostiken.

## Säkerhet och bevarande

- Databasen körs endast i lokal embedded H2-konfiguration.
- Integritetskontroller körs vid start och före känsliga operationer.
- Bokföringsdata, bilagor och rapportarkiv får inte rensas före sju års bevarandetid.
- Räkenskapsår eller företag med blockerande beroenden kan inte raderas. Programmet visar orsaken innan något tas bort.

## Uppdatering och avinstallation

- Programmet kan söka efter uppdateringar vid start. Automatisk uppdateringskontroll kan stängas av i `Inställningar`.
- Välj `Hjälp -> Sök efter uppdateringar...` för att kontrollera manuellt. Om en ny version finns kan programmet ladda ner och installera det generiska uppdateringsarkivet `app-<version>.zip`.
- Vid uppdatering behålls användardata i plattformens datakatalog.
- Linux-paketet innehåller `install.sh` och `uninstall.sh`. Installationsskriptet skapar en applikationsgenväg och markerar den som betrodd där skrivbordsmiljön stöder det.
- Linux- och macOS-avinstallationsskripten och Windows cleanup-skript frågar innan installationskatalogen tas bort och frågar separat innan användardata tas bort. Standardvalet är att behålla datan.

## Felsökning

- Om startvarning visas: öppna fliken `System` och kontrollera verifieringsfel eller varningar.
- Om en backup inte kan återställas: kontrollera att ZIP-filen är komplett och inte har ändrats manuellt.
- Om bokföring blockeras: kontrollera om bokföringsperiod eller momsperiod är rapporterad eller låst.
- Om en uppdatering inte kan installeras: kontrollera felmeddelandet i uppdateringsdialogen och prova vid behov att ladda ner plattformspaketet manuellt från GitHub Releases.

## Uppskjuten funktionalitet

Följande förbättringar är planerade till v1.3.0:
- Anläggningsregister för materiella och immateriella anläggningstillgångar.
