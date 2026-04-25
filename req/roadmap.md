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

