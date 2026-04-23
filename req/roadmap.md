# Roadmap

Detta dokument samlar upp förbättringar som medvetet skjuts efter `v1.1.0`.

## Planerade uppföljningar

Följande punkter är medvetet uppskjutna från `v1.0.0` för att hålla första releasen fokuserad på kärnfunktioner i huvudbok, verifikationer, moms, rapporter, SIE och årsstängning.

### Bilagehantering

- Behåll bilagestöd i produkten, men gör bilagelagringen kraschsäker så att `AttachmentService.addAttachment()` inte kan lämna orfan-filer på disk om JVM eller OS faller mellan filkopiering och databasinsert.
- Detta är uttryckligen uppskjutet från `v1.0.0` och ska inte blockera första releasen av kärnbokföringen.

### Förslag på angreppssätt

- Kopiera först till en temporär staging-katalog och flytta till slutlig plats först efter lyckad transaktion.
- Alternativt: skriv metadata först som `PENDING`, färdigställ filflytt atomiskt och markera sedan raden som aktiv i samma arbetsflöde.
- Lägg till en reparations- eller städscan som kan hitta filer i bilagearkivet utan motsvarande rad i databasen.

### Anläggningsregister

- Utred om applikationen ska kompletteras med ett enkelt anläggningsregister för företag som behöver hålla ordning på materiella och immateriella anläggningstillgångar.
- Funktionen är uttryckligen uppskjuten från `v1.0.0` och ska inte blockera första releasen av kärnbokföringen.

### Förslag på angreppssätt

- Börja med dokumentation av vad som menas med `anläggningsregister` i svensk bokföringskontext och vilka företagstyper som faktiskt behöver det i praktiken.
- Om funktionen bedöms motiverad: inför en enkel registervy med anskaffningsdatum, anskaffningsvärde, konto, beskrivning och avyttring/utrangering.
- Håll lösningen frikopplad från huvudboksflödet i första steget så att v1.1.0 kan införa stöd utan att skriva om verifikationsmotorn.

### Headless MCP-läge för LLM-stödd bokföring

- Utred och inför ett alternativt körläge där applikationen inte startar Swing-gränssnittet utan exponerar bokföringsfunktionerna som en lokal MCP-server över `stdio`.
- Målet är inte att bygga om produkten till en fleranvändarserver, utan att återanvända samma domän- och servicelager i ett nytt lokalt integrationsläge.
- Lösningen ska respektera de bokföringsregler som redan finns i applikationen: registrerade verifikationer är append-only, rättelser görs med korrigeringsverifikation, stängda år kräver upplåsning innan nya rättelser registreras.
- LLM-läget ska i första hand vara ett assisterat arbetsläge där modellen föreslår, validerar och genomför åtgärder med tydliga gränser, inte ett autonomt bokföringsflöde utan kontrollpunkter.

### Föreslagen målbild

- Starta applikationen med ett nytt argument, exempelvis `--mode=mcp`, som kör samma bootstrap som idag för loggning, databas och startup-verifiering men hoppar över `MainFrame`.
- Inför en separat startpunkt eller runtime-komponent, exempelvis `AccountingMcpServer`, som binder ett begränsat antal MCP-tools mot befintliga tjänster som `VoucherService`, `VatService`, `ClosingService`, `FiscalYearService`, `ReportDataService` och `SieImportExportService`.
- Håll första versionen lokal och enkel: MCP över `stdio`, ingen HTTP/SSE-transport, ingen extern sessionshantering och ingen fleranvändarmodell.
- Låt MCP-servern returnera strikt strukturerade svar som passar ett agentflöde bättre än UI-strängar: identifierare, status, varningar, precheck-resultat, avvikelselistor och tydliga felkoder där det är praktiskt möjligt.
- Bygg en separat skill för Codex/LLM som ger modellen domänkontext, beskriver arbetsflöden och vägleder den i hur verktygen ska användas för verifikationer, momsrapportering och årsbokslut.

### Avgränsningar och principer

- Ingen väg runt befintliga spärrar: MCP-tools ska använda samma service-lager och affärsregler som GUI:t.
- Ingen direktändring av registrerade verifikationer ska exponeras i MCP-läget.
- Skrivande operationer bör delas upp i `förhandskontroll` och `bekräftad åtgärd` snarare än ett enda verktyg som både tolkar och bokför i ett steg.
- Read-only-frågor ska vara billiga och många; destruktiva eller långtgående åtgärder ska vara få, explicita och väl avgränsade.
- Åtgärder som kan ändra historik eller påverka efterföljande år, till exempel upplåsning av år, ersättningsimport av SIE eller uppdatering av ingående balanser, ska exponeras först i en senare fas eller kräva ett separat bekräftelsesteg.

### Fasindelad plan

#### Fas 1. Headless bootstrap och körlägen

- Utöka `AlipsaAccounting` med ett tydligt driftläge för `gui`, `verify-launch` och `mcp`.
- Extrahera gemensam startup till en återanvändbar bootstrap så att GUI och MCP delar initiering av loggning, databas, språk och startup-verifiering.
- Säkerställ att applikationen kan stängas ned rent i MCP-läge och släppa H2- och loggfilshandtag på samma sätt som vid verifieringsstart.
- Lägg till minst ett enkelt start- och hälsotest för headless-läget.

#### Fas 2. MCP-server och read-only-ytor

- Välj ett MCP-upplägg som fungerar väl med `stdio` i JVM/Groovy-miljö. Om ett moget bibliotek saknas bör en liten intern adapter över MCP:s JSON-RPC-kontrakt övervägas i stället för att dra in ett tungt serverramverk.
- Börja med följande read-only-tools för att minska risk:
- Hämta aktivt företag och grundinställningar.
- Lista räkenskapsår, perioder, konton, momsperioder och nyliga verifikationer.
- Hämta huvudbok, provbalans, momsunderlag och bokslutsförhandskontroller i maskinläsbart format.
- Exponera gärna vissa större datamängder som resurser eller paginerade svar i stället för ett enda stort verktygssvar.

#### Fas 3. Verifikationsflöde för LLM

- Inför följande tvåstegsflöde för verifikationer:
- Ett verktyg som bygger eller validerar ett verifikationsförslag utifrån datum, text, konto, momsantaganden och rader.
- Ett separat verktyg som faktiskt registrerar verifikationen när underlaget redan är explicit strukturerat.
- Lägg till hjälptools för att hämta kontoplan, föreslå momskonto, tolka obalanser och hitta sannolika fel innan registrering.
- Returnera alltid balansstatus, påverkade konton, skapad verifikationsidentitet och eventuella varningar om datum, momsperiod eller låst år.
- Om modellen behöver korrigera en registrerad verifikation ska MCP-ytan använda korrigeringsverifikationer i stället för update/delete.

#### Fas 4. Moms och årsbokslut

- Inför följande read-only-tools för momsdiagnostik:
- Beräkna momsutfall för vald period.
- Visa vilka verifikationer och konton som driver momsrapporten.
- Peka ut öppna avvikelser, exempelvis saknade momskoder eller oväntade saldon.
- Lägg därefter till bekräftade write-tools för att skapa momsöverföring eller motsvarande nuvarande momsarbetsflöde, men bara efter att preview/precheck redan körts.
- Gör samma sak för årsbokslut med följande uppdelning:
- Preview-tool som returnerar blockerare, varningar, differenser och om ingående balanser i nästa år riskerar att bli inaktuella.
- Confirm-tool som faktiskt genomför bokslut och årsavslut när alla villkor är uppfyllda.
- Upplåsning av år bör inte ligga i första leveransen av MCP-läget utan komma först när hela varnings- och konsekvenskedjan kan exponeras tydligt.

#### Fas 5. Skill för bokföringsassistenten

- Skapa en ny skill, exempelvis `accounting-mcp`, som inte duplicerar all domänkunskap i `SKILL.md` utan håller huvudfilen kort och pekar vidare till referensmaterial.
- Skillen bör innehålla följande delar:
- Ett arbetsflöde för hur modellen först samlar kontext om företag, år, konton och tidigare verifikationer innan den föreslår bokning.
- Regler för hur modellen ska skilja på förslag, validering och faktisk bokföring.
- Stöd för momsrapportering med tydlig ordning: samla underlag, förklara avvikelser, kör preview, be om bekräftelse, genomför.
- Stöd för årsbokslut med samma princip: kör förhandskontroller först, förklara blockerare, genomför först efter uttryckligt klartecken.
- Referensmaterial som beskriver lokal domänmodell, viktiga services, centrala begrepp och vilka verktyg som är read-only respektive write.
- Skillen bör även instruera modellen att vara försiktig med rättsliga påståenden och hellre beskriva bokföringsmässiga konsekvenser än att påstå juridisk säkerhet utan uttryckligt underlag.

#### Fas 6. Testbarhet och acceptanskriterier

- Lägg till integrationstester för MCP-tools på samma nivå som service- och UI-testerna, med fokus på kontrakt och affärsregler snarare än transportdetaljer.
- Verifiera att samma fel uppstår i MCP-läge som i GUI när året är stängt, verifikationen är obalanserad eller momsperioden inte kan användas.
- Lägg till ett mindre end-to-end-scenario för följande flöden:
- föreslå och registrera en verifikation,
- förhandsgranska momsrapport,
- genomföra momssteg,
- köra bokslutsförhandskontroll.
- När skillen väl finns bör den forward-testas med riktiga uppgifter i ett isolerat testhem så att instruktionerna faktiskt räcker för agenten.

### Rekommenderad första leverans

- Första leveransen bör vara medvetet smal och omfatta:
- `--mode=mcp` med bootstrap och ren shutdown.
- Ett litet antal read-only-tools.
- Ett tvåstegsflöde för verifikation: `preview` och `post`.
- En första version av skillen för verifikationer.
- Moms och årsbokslut bör komma efter att verifikationsflödet visat att verktygsgränssnitt, felhantering och skillinstruktioner fungerar i praktiken.

### Varför detta bör vara ett separat spår

- Idén är stark eftersom den återanvänder ett redan domändrivet system och flyttar intelligensen till ett lager ovanpå befintliga tjänster i stället för att bygga en separat AI-produkt.
- Samtidigt är detta större än en ren integrationsdetalj: det kräver tydliga kontrakt, ett säkert verktygsgränssnitt, bättre maskinläsbara svar och en genomtänkt modell för preview kontra commit.
- Det bör därför drivas som ett separat roadmapspår efter `v1.1.0`, inte som en liten bieffekt av GUI-arbetet.

## Motiv för uppskjutning

- Problemet är verkligt men kräver ett större omtag av fil/DB-commit-flödet än vad som var rimligt inom `v1.0.0`.
- Nuvarande fas 5-fixar täcker integritet och traversal-skydd, men inte recovery för avbrutna skrivningar mellan filsystem och databas.
- Anläggningsregister bedöms inte som en nödvändig del av kärnleveransen för ett första huvudboksfokuserat system, men kan vara ett relevant komplement för vissa företag i en senare version.
