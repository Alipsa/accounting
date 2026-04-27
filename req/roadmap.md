# Roadmap

Detta dokument samlar upp förbättringar som ännu inte fått någon version tilldelad.

### Anläggningsregister

- Utred om applikationen ska kompletteras med ett enkelt anläggningsregister för företag som behöver hålla ordning på materiella och immateriella anläggningstillgångar.
- Funktionen är uttryckligen uppskjuten från `v1.0.0` och ska inte blockera första releasen av kärnbokföringen.

### Förslag på angreppssätt

- Börja med dokumentation av vad som menas med `anläggningsregister` i svensk bokföringskontext och vilka företagstyper som faktiskt behöver det i praktiken.
- Om funktionen bedöms motiverad: inför en enkel registervy med anskaffningsdatum, anskaffningsvärde, konto, beskrivning och avyttring/utrangering.
- Håll lösningen frikopplad från huvudboksflödet i första steget så att v1.1.0 kan införa stöd utan att skriva om verifikationsmotorn.

