# Användarmanual för Alipsa Accounting

## Kom igång

1. Starta programmet.
2. Registrera företagsuppgifter under `Arkiv -> Företagsuppgifter...`.
3. Skapa ett räkenskapsår i fliken `Räkenskapsår`.
4. Importera BAS-kontoplanen i fliken `Kontoplan`.
5. Kontrollera fliken `System` efter första start så att startup-verifieringen är grön.

## Löpande bokföring

- Skapa verifikationer i fliken `Verifikationer`.
- Bokförda verifikationer kan inte ändras direkt utan korrigeras med korrigeringsverifikation.
- Bilagor kan kopplas till en verifikation och spåras i audit-loggen.
- Utkast kan uppdateras eller makuleras innan de bokförs.
- Om en period eller momsperiod är låst måste rätt flöde användas i stället för direkt ändring.

## Moms, rapporter och SIE

- Stäng och rapportera momsperioder i fliken `Moms`.
- Generera PDF- och CSV-rapporter i fliken `Rapporter`.
- Importera och exportera SIE4 under `Arkiv -> SIE import/export...`.
- CSV-export använder semikolon som avgränsare för att fungera bra med svensk Excel-standard.
- När en momsperiod har rapporterats ska ändringar göras via korrigering, inte genom att skriva över tidigare bokningar.

## Årsbokslut

- Lås alla perioder innan årsbokslut genomförs.
- Kör `Årsbokslut...` i fliken `Räkenskapsår`.
- Systemet stänger resultatkonton mot konto `2099` som standard och skapar nästa års ingående balanser.
- Förhandsgranskningen visar blockerande fel och varningar innan bokslutet genomförs.

## Backup, restore och systeminformation

- Öppna fliken `System` för diagnostik, backup/restore och export av systemdokumentation.
- Backup innehåller databasen, bilagearkivet och rapportarkivet med checksummor.
- Restore ersätter nuvarande databas och filarkiv efter verifiering av manifest och checksummor.
- Återställ endast backupfiler som kommer från en betrodd källa.
- Systemdiagnostiken visar schema-version, senaste backup och senaste SIE-export.
- Systemdokumentationen kan exporteras som Markdown för drift- eller revisionsunderlag.

## Säkerhet och bevarande

- Databasen körs endast i lokal embedded H2-konfiguration.
- Integritetskontroller körs vid start och före känsliga operationer.
- Bokföringsdata, bilagor och rapportarkiv får inte rensas före sju års bevarandetid.
- Räkenskapsår med beroende rapportarkiv, audit-logg eller bokslutsposter kan inte raderas.

## Felsökning

- Om startvarning visas: öppna fliken `System` och kontrollera verifieringsfel eller varningar.
- Om en backup inte kan återställas: kontrollera att ZIP-filen är komplett och inte har ändrats manuellt.
- Om bokföring blockeras: kontrollera om bokföringsperiod eller momsperiod är rapporterad eller låst.
