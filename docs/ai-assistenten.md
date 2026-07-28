# AI-assistenten – installation och konfiguration

Det här dokumentet beskriver vad som krävs för att koppla en AI-klient till Alipsa Bokförings inbyggda MCP-server, både via den inbyggda launchern i appen och manuellt (inklusive desktop-klienter som Claude Desktop).

Se även avsnittet [AI-assisterad bokföring](../README.md#ai-assisterad-bokföring) i README och skill-filen [`skill/accounting-mcp.md`](../skill/accounting-mcp.md) som styr hur AI-klienten faktiskt använder verktygen.

## Så fungerar det

- Alipsa Bokföring startar en lokal MCP-server (`http://127.0.0.1:48652/mcp`) så länge desktopappen körs. Servern lyssnar bara på `127.0.0.1` — den är inte nåbar från nätverket.
- Varje anrop kräver `Authorization: Bearer <token>`. Endpoint och token visas under **Inställningar → AI / MCP**.
- Servern använder samma H2-databas, valideringar och affärsregler som appens UI. AI-klienten kan fylla den osparade verifikationsvyn, men aldrig bokföra eller spara — det gör bara du, i appen.
- Stängs appen slutar servern svara. Anslutningsfel i AI-klienten då är förväntat och ofarligt.
- Token lagras i operativsystemets användarpreferenser (`UserPreferencesService`) och kan regenereras under **Inställningar → AI / MCP**. Regenerering rensar samtidigt alla token-bärande filer i AI-arbetsytan (se nedan), så redan startade CLI-sessioner måste startas om med ett nytt token.

Vilken AI-leverantör den anslutna klienten i sin tur skickar data till styrs helt av klienten — se [privacy-policy.md](privacy-policy.md#local-mcp-endpoint) och kontrollera leverantörens egen integritetspolicy innan du ansluter.

## Förutsättningar

Oavsett anslutningssätt krävs:

1. Alipsa Bokföring körs (`./gradlew run` eller en installerad release) och MCP-statusen under **Inställningar → AI / MCP** visar `Running`/`Igång`.
2. En AI-klient som stödjer MCP över HTTP (Streamable HTTP) med anpassade headers för Bearer-token-autentisering. Klienter som bara stödjer stdio-baserade MCP-servrar eller OAuth-baserade fjärranslutningar (utan möjlighet att sätta en egen `Authorization`-header) fungerar inte mot den här servern.
3. Nätverksåtkomst till `127.0.0.1` — servern körs alltid lokalt och kan inte exponeras mot andra maskiner.

För den inbyggda launchern (nästa avsnitt) tillkommer:

4. En installerad CLI-binär för vald klient (`claude`, `codex`, `kimi` eller `vibe`) tillgänglig i `PATH` eller på en känd sökväg.
5. En terminalemulator som launchern kan öppna (`gnome-terminal`/`konsole`/`xterm` på Linux, `wt.exe` eller `cmd.exe` på Windows, `Terminal.app` via `osascript` på macOS). På Windows föredras Windows Terminal om det finns, annars faller launchern tillbaka på Kommandotolken (`cmd.exe`). `conhost.exe` anropas aldrig direkt.

## Alternativ 1: Den inbyggda AI-assistent-launchern

Det här är den enklaste vägen för CLI-klienter och det som `README.md` refererar till som **Starta AI-assistent**.

1. Öppna **Inställningar → AI / MCP**.
2. I sektionen **Starta AI-assistent**, välj klient i listan: `Claude Code`, `Codex`, `Kimi` eller `Vibe`. Kimi och Vibe är märkta **experimentella** — deras instruktionsfilskonvention (`AGENTS.md`) är inte fullt verifierad mot en riktig installation, till skillnad från Claude Code och Codex.
3. Fyll i sökvägen till klientens CLI-binär, eller klicka **Hitta** för att låta appen söka igenom `PATH` och vanliga installationsplatser.
4. Välj terminal och dess sökväg, eller klicka **Hitta** för att söka automatiskt.
5. Klicka **Starta AI-assistent**. Knappen är inaktiv om MCP-servern inte är igång.

Vad som händer i bakgrunden:

- En isolerad, projekt-scopad arbetsyta skapas under appens datakatalog (skild från din vanliga hemkatalog och skild från själva bokföringsdatan), med behörighet `0700`.
- Ett unikt körbart wrapper-skript skrivs för den här sessionen och tas bort igen vid nästa uppstart/token-rotation.
- Klientens MCP-konfiguration och instruktionsfil skrivs i arbetsytan, enligt tabellen nedan. Konfigurationen gäller alltså bara den här arbetsytan — inte klientens globala inställningar.
- En ny terminal öppnas i arbetsytan och startar klienten (för Claude Code med sessionsnamnet "Alipsa Accounting AI Assistant").

| Klient      | Konfigurationsfil (i arbetsytan) | Instruktionsfil                               | Status        |
|-------------|----------------------------------|-----------------------------------------------|---------------|
| Claude Code | `.mcp.json`                      | `.claude/skills/accounting/accounting-mcp.md` | Verifierad    |
| Codex       | `.codex/config.toml`             | `AGENTS.md`                                   | Verifierad    |
| Kimi        | `.kimi-code/mcp.json`            | `AGENTS.md`                                   | Experimentell |
| Vibe        | `.vibe/config.toml`              | `AGENTS.md`                                   | Experimentell |

Claude Code och Kimi använder samma JSON-form (se `AiClientConfigWriter.bearerJson`):

```json
{
  "mcpServers": {
    "accounting": {
      "type": "http",
      "url": "http://127.0.0.1:48652/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Codex och Vibe använder TOML. Codex refererar bara till namnet på en miljövariabel (`ACCOUNTING_MCP_TOKEN`) — själva token skrivs aldrig i Codex-konfigurationsfilen, bara i det tillfälliga wrapper-skriptet:

```toml
[mcp_servers.accounting]
url = "http://127.0.0.1:48652/mcp"
bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"
```

Vibe:

```toml
[[mcp_servers]]
name = "accounting"
transport = "streamable-http"
url = "http://127.0.0.1:48652/mcp"
headers = { Authorization = "Bearer <token>" }
```

Servern implementerar det faktiska Streamable-HTTP-MCP-protokollet (initialize-handskakning plus `Mcp-Session-Id` på varje efterföljande anrop) och avvisar anrop utan giltig session. Vibes `transport = "http"` är ett enklare, sessionslöst läge och fungerar inte mot den här servern — använd alltid `"streamable-http"`.

För Claude Code seedas dessutom arbetsytans `settings.local.json` med förhandsgodkännande för de rena läs-verktygen (`get_active_context`, `list_accounts`, `get_trial_balance` osv., se `AiWorkspaceMcpSettings.READ_ONLY_TOOLS`), så klienten inte behöver bekräfta varje enskild uppslagning. Skriv-verktyg (`set_active_voucher_draft`, `create_correction_voucher`, `book_vat_transfer`, `close_fiscal_year`, `import_sie`, `export_sie`, `save_accounting_instruction`) är medvetet undantagna och kräver alltid explicit godkännande i klienten.

## Alternativ 2: Manuell HTTP-MCP-konfiguration

Använd det här om du vill koppla en annan MCP-kompatibel CLI-klient än de fyra ovan, eller om du föredrar att sköta konfigurationen själv i stället för att använda launchern.

1. Hämta endpoint (`http://127.0.0.1:48652/mcp`) och token från **Inställningar → AI / MCP**.
2. Lägg till en HTTP-MCP-server i klientens egen konfiguration, med `Authorization: Bearer <token>` som header. De flesta MCP-klienter med stöd för fjärranslutna servrar accepterar samma form som JSON-exemplet ovan.
3. Installera skill-filen `skill/accounting-mcp.md` (från källkoden eller från en releasezip) i klientens skill-/instruktionskatalog. Den styr arbetsflödet (kontext först, förslag, godkännande, sedan skriv) — utan den känner klienten bara till verktygsnamnen, inte hur de bör användas tillsammans. Se avsnittet **Release** i `README.md` för exempel på symlänkning till `~/.claude/skills/accounting` respektive `~/.agents/skills/accounting`.
4. Starta om klienten efter varje token-rotation, eftersom en regenererad token gör den gamla ogiltig omedelbart.

## Alternativ 3: Desktop-klienter (t.ex. Claude Desktop)

MCP-servern i Alipsa Bokföring är en vanlig Streamable-HTTP-MCP-server med Bearer-token-autentisering — den gör ingen skillnad på om anropande klient är en CLI eller en GUI-app. En AI-assistents skrivbordsversion (till exempel Claude Desktop) bör därför i princip kunna anslutas på samma sätt som CLI-klienterna ovan, förutsatt att den versionen av desktop-appen tillåter att man manuellt lägger till en fjärransluten MCP-server med en egen `Authorization`-header i sin konfiguration.

**Detta är inte något projektet testat eller officiellt stödjer** — till skillnad från Claude Code, Codex, Kimi och Vibe finns ingen inbyggd launcher-integration för desktop-klienter, och stödet för anpassade headers på fjärranslutna MCP-servrar varierar mellan desktop-appar och versioner. Betrakta stegen nedan som en utgångspunkt att verifiera själv, inte en garanterad instruktion.

Generellt tillvägagångssätt:

1. Hitta desktop-klientens MCP-konfigurationsfil. För Claude Desktop är det normalt:
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Windows: `%APPDATA%\Claude\claude_desktop_config.json`

   (Claude Desktop saknar för närvarande ett officiellt Linux-paket.)

2. Lägg till en post under `mcpServers`, med samma form som Claude Code-exemplet ovan:

   ```json
   {
     "mcpServers": {
       "accounting": {
         "type": "http",
         "url": "http://127.0.0.1:48652/mcp",
         "headers": { "Authorization": "Bearer <token>" }
       }
     }
   }
   ```

   Hämta `<token>` från **Inställningar → AI / MCP** i Alipsa Bokföring. Starta om desktop-klienten efter ändringen.

3. Ge klienten samma instruktioner som skill-filen `skill/accounting-mcp.md` innehåller — till exempel genom att klistra in innehållet som ett projekt-/anpassat instruktionsfält i klienten, om appen inte har ett eget skill-/plugin-koncept som läser filen direkt.

4. Kontrollera att appen faktiskt kör MCP-servern (**Inställningar → AI / MCP** visar `Running`) innan du testar anslutningen i desktop-klienten.

Om desktop-klienten bara stödjer OAuth-baserade fjärranslutningar (utan möjlighet att ange en egen header), eller bara stödjer lokala stdio-servrar, går den här vägen inte att använda mot Alipsa Bokförings server utan en separat proxy — vilket projektet varken tillhandahåller eller rekommenderar man sätter upp själv, eftersom det ökar angreppsytan mot en server som annars bara är nåbar från den egna datorn.

Kom ihåg att en ansluten desktop-klient, precis som en CLI-klient, kan skicka bokföringsdata vidare till sin AI-leverantörs molntjänst som en del av sin normala funktion. Läs klientens och leverantörens integritetspolicy innan du ansluter, se även [privacy-policy.md](privacy-policy.md).

## Byte av token

- **Inställningar → AI / MCP → Regenerera** skapar en ny token och rensar samtidigt alla filer i AI-arbetsytan som innehöll den gamla token.
- Uppdatera manuellt konfigurerade klienter (alternativ 2 och 3) med den nya token och starta om dem.
- CLI-klienter startade via launchern (alternativ 1) får automatiskt rätt token vid nästa **Starta AI-assistent**, eftersom konfigurationsfilen skrivs om vid varje launch.

## Felsökning

| Symptom                                                                                 | Trolig orsak                                                                                                                                       |
|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **Starta AI-assistent** är inaktiv                                                      | MCP-servern är inte igång ännu, eller appen har inte hunnit starta klart. Vänta tills statusen under **Inställningar → AI / MCP** visar `Running`. |
| "Hittade ingen binär"                                                                   | CLI:t är inte installerat eller ligger inte i `PATH`. Ange sökvägen manuellt.                                                                      |
| AI-klienten får anslutningsfel                                                          | Appen är stängd, eller så pekar klienten mot fel port/token. Kontrollera att appen körs och att token stämmer med **Inställningar → AI / MCP**.    |
| Anslutningen fungerade tidigare men slutade fungera                                     | Token har regenererats. Uppdatera klientens konfiguration eller starta om den via launchern.                                                       |
| Skriv-verktyg (spara verifikation, bokför moms, stäng år) kräver bekräftelse varje gång | Avsiktligt — endast läs-verktyg förhandsgodkänns i Claude Codes arbetsyta.                                                                         |

## Säkerhet

- Servern binder endast till `127.0.0.1` och kan inte nås från andra maskiner på nätverket.
- All skrivaccess går via appens egna tjänstelager, samma valideringar som i UI:t. AI-klienten kan aldrig spara eller bokföra direkt.
- Token behandlas som en hemlighet: filer som innehåller den skrivs med endast-ägar-behörigheter och rensas vid regenerering och vid appens start/avslut.
- Se [privacy-policy.md](privacy-policy.md) för en fullständig beskrivning av vilken nätverkstrafik appen genererar, inklusive MCP-endpointen.