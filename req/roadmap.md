# Roadmap

Detta dokument samlar upp förbättringar som ännu inte fått någon version tilldelad.

### Anläggningsregister

- Utred om applikationen ska kompletteras med ett enkelt anläggningsregister för företag som behöver hålla ordning på materiella och immateriella anläggningstillgångar.
- Funktionen är uttryckligen uppskjuten från `v1.0.0` och ska inte blockera första releasen av kärnbokföringen.

### Kodhälsa

- Utred uppdelning av stora klasser som ligger nära eller över CodeNarc:s `ClassSize`-gräns: `VoucherPanel.groovy` (1250 rader), `SieImportExportService.groovy` (1275), `ReportDataService.groovy` (1206) och `AccountingMcpTools.groovy` (1203). Faktisk refaktorering ska planeras separat.

### Förslag på angreppssätt

- Börja med dokumentation av vad som menas med `anläggningsregister` i svensk bokföringskontext och vilka företagstyper som faktiskt behöver det i praktiken.
- Om funktionen bedöms motiverad: inför en enkel registervy med anskaffningsdatum, anskaffningsvärde, konto, beskrivning och avyttring/utrangering.
- Håll lösningen frikopplad från huvudboksflödet i första steget så att v1.1.0 kan införa stöd utan att skriva om verifikationsmotorn.
