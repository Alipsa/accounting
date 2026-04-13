# Användarmanual för Alipsa Accounting

## Kom igång

1. Starta programmet.
2. Registrera företagsuppgifter under `Arkiv -> Företagsuppgifter...`.
3. Skapa ett räkenskapsår i fliken `Räkenskapsår`.
4. Importera BAS-kontoplanen i fliken `Kontoplan`.

## Löpande bokföring

- Skapa verifikationer i fliken `Verifikationer`.
- Bokförda verifikationer kan inte ändras direkt utan korrigeras med korrigeringsverifikation.
- Bilagor kan kopplas till en verifikation och spåras i audit-loggen.

## Moms, rapporter och SIE

- Stäng och rapportera momsperioder i fliken `Moms`.
- Generera PDF- och CSV-rapporter i fliken `Rapporter`.
- Importera och exportera SIE4 under `Arkiv -> SIE import/export...`.

## Årsbokslut

- Lås alla perioder innan årsbokslut genomförs.
- Kör `Årsbokslut...` i fliken `Räkenskapsår`.
- Systemet stänger resultatkonton mot konto `2099` som standard och skapar nästa års ingående balanser.

## Backup, restore och systeminformation

- Öppna fliken `System` för diagnostik, backup/restore och export av systemdokumentation.
- Backup innehåller databasen, bilagearkivet och rapportarkivet med checksummor.
- Restore ersätter nuvarande databas och filarkiv efter verifiering av manifest och checksummor.

## Säkerhet och bevarande

- Databasen körs endast i lokal embedded H2-konfiguration.
- Integritetskontroller körs vid start och före känsliga operationer.
- Bokföringsdata, bilagor och rapportarkiv får inte rensas före sju års bevarandetid.
