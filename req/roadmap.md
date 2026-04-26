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

### Arkivering och radering av företag och räkenskapsår

Svensk bokföringslag förbjuder radering av bokföringsdata under sju år. Efter sju år ställer
GDPR:s lagringsminimeringsprincip krav på att data faktiskt kan raderas.

Kravet kan sammanfattas i tre steg:

1. **Arkivering** — ett företag kan arkiveras oavsett ålder. Arkiverade företag visas inte i
   normala vyer men all data behålls intakt. Arkiveringen är reversibel.
2. **Radering av räkenskapsår** — ett enskilt räkenskapsår (med alla dess verifikationer,
   bilagor, balanser, momsperioder, rapportarkiv och revisionsloggar) kan raderas permanent
   när räkenskapsårets slutdatum är äldre än sju år. `RetentionPolicyService` kontrollerar
   villkoret innan radering tillåts.
3. **Radering av företag** — ett företag kan raderas permanent när alla dess räkenskapsår
   har raderats. Företag med kvarvarande räkenskapsår kan inte raderas.

### Förslag på angreppssätt

- Börja med arkiveringsflaggan (`archived boolean not null default false`) på `company`-tabellen
  och filtrera bort arkiverade företag i `CompanyService.listCompanies()`.
- Implementera radering av räkenskapsår som en separat operation med en explicit
  sju-års-kontroll och en bekräftelsedialog som listar vad som kommer att tas bort
  (antal verifikationer, bilagor, osv.) innan data rörs.
- Radering av räkenskapsår kräver hantering av `audit_log`-rader med FK till `attachment`
  och `voucher` (båda `ON DELETE RESTRICT`). Överväg att nolla ut dessa FK-kolumner
  (`ON DELETE SET NULL` i en migration) eller att radera i rätt ordning: audit-loggar →
  bilagor → verifikationsrader → verifikationer → räkenskapsår.

### Anläggningsregister

- Utred om applikationen ska kompletteras med ett enkelt anläggningsregister för företag som behöver hålla ordning på materiella och immateriella anläggningstillgångar.
- Funktionen är uttryckligen uppskjuten från `v1.0.0` och ska inte blockera första releasen av kärnbokföringen.

### Förslag på angreppssätt

- Börja med dokumentation av vad som menas med `anläggningsregister` i svensk bokföringskontext och vilka företagstyper som faktiskt behöver det i praktiken.
- Om funktionen bedöms motiverad: inför en enkel registervy med anskaffningsdatum, anskaffningsvärde, konto, beskrivning och avyttring/utrangering.
- Håll lösningen frikopplad från huvudboksflödet i första steget så att v1.1.0 kan införa stöd utan att skriva om verifikationsmotorn.

