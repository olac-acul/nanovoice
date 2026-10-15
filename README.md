# NanoVoice

*English, in short:* an Android voice assistant, in Italian, for a self-hosted [NanoClaw](https://github.com/nanocoai/nanoclaw) agent. Simple requests (time, date, timers, alarms, apps, calls, music, notes) are handled on the phone, offline; the rest goes to your NanoClaw instance over a token-protected WebSocket. Speech recognition and synthesis run on the phone. The server side is the `add-voice` skill of [skills-for-nanoclaw](https://github.com/olac-acul/skills-for-nanoclaw). Apache-2.0. The rest of this document is in Italian.

Assistente vocale Android per [NanoClaw](https://github.com/nanocoai/nanoclaw). Le richieste semplici (ora, data, timer, sveglia, aprire un'app,
preparare una chiamata) sono gestite sul telefono, anche offline; le altre vanno a un'istanza NanoClaw self-hosted.
Piano completo: `PIANO_SVILUPPO.md`.

Stato: **Fasi 1-5 complete** (chat, router locale, NanoClaw via WebSocket, voce offline, assistente di sistema, azioni
locali, risveglio push). Sono presenti anche parola di attivazione, classificatore sul telefono, Parakeet opzionale,
musica, foto e scanner di codici a barre. Il modello linguistico locale resta da valutare.
Modifica e cancellazione di singoli eventi del calendario sono implementate; manca la prova su un dispositivo.

## Moduli

| Modulo | Contenuto |
|---|---|
| `app` | UI Compose (chat, impostazioni, foglio dell'assistente), `ChatEngine`, `ChatViewModel`, `AppContainer` (DI manuale) |
| `core` | `PhoneAction`, `ActionResult`, `ActionExecutor`, eventi e modifiche del calendario (Kotlin puro) |
| `local-router` | normalizzazione, parser di numeri, durate e orari, matcher, `LocalRouter` (Kotlin puro) |
| `actions` | `AndroidActionExecutor`, `AndroidCalendarEditor`, frasi (`SpokenPhrases`) per lingua |
| `voice` | Vosk (riconoscimento), Piper/sherpa-onnx (sintesi), gestione dei modelli scaricabili |
| `agent-client` | client WebSocket per NanoClaw (`OkHttpAgentClient`), protocollo, validazione dell'URL (Kotlin puro) |
| `link-preview` | anteprime dei link: lettura dei metadati e download prudente (Kotlin puro) |

Aggiungere una lingua: implementare `LocaleRules` (matcher + parser della risposta) in `local-router`
e `SpokenPhrases` in `actions`, poi registrarle in `LocalRouter.forLocale` e `AppContainer`.

## Build

Richiede JDK 17+ (testato con 21; JDK 25 non è supportato da AGP) e Android SDK (`local.properties` con `sdk.dir`).

```
export JAVA_HOME=/percorso/jdk-21
./gradlew build
```

Per usare l'app sul telefono servono la build release e la compilazione anticipata (vedi **Voce → Velocità**):
`tools/install-phone.sh` fa entrambe le cose, installando sopra l'app già presente (i dati restano se la chiave di firma è la stessa).
Le impostazioni personali (JDK, SDK, telefono) stanno in un file non tracciato, `.dev-env`.

## Collegamento a NanoClaw

**Abbinamento con un QR:** nel pannello di NanoClaw, pagina *Phone*, "Pair the app with a QR code"; nell'app, Impostazioni → Server NanoClaw → "Scansiona il QR del pannello". Il QR contiene un codice che vale una volta sola e per cinque minuti, non il token: l'app lo scambia con il token dopo aver mostrato il server e chiesto conferma (formato e regole in `docs/PROTOCOL.md`).

A mano: Impostazioni → Server NanoClaw: indirizzo (`ws://host:8767` in rete locale/VPN, `wss://` su internet) e token.
Il token è cifrato con una chiave dell'Android Keystore. `ws://` è accettato solo per host privati
(loopback, reti private, Tailscale/`.ts.net`, nomi senza punto) e con una VPN attiva; altrimenti l'app chiede la VPN o `wss://`.
Lato server serve la skill `add-voice` di [skills-for-nanoclaw](https://github.com/olac-acul/skills-for-nanoclaw) (canale WebSocket per NanoClaw, con uno script che lo attiva e mostra indirizzo e token da inserire nell'app). Protocollo: `docs/PROTOCOL.md`.

- **Quando va al server:** quando il router locale non riconosce la richiesta o resta sotto la soglia di confidenza.
  Se il server non è raggiungibile l'app lo dice e le funzioni locali continuano a funzionare.
- **Stesso agente di un altro canale:** il canale dell'app può essere collegato allo stesso gruppo di agenti di un
  altro canale (per esempio Telegram). Conversazioni separate, memoria e strumenti condivisi. Le regole di stile per la
  voce stanno in una sezione delle istruzioni del gruppo che vale solo per il canale `voice`.
- **Lavori lunghi:** prima di una ricerca l'agente chiama `voice_working`; l'app legge subito "Cerco sul Web..." e mostra
  una riga animata finché non arriva la risposta, nella stessa conversazione.
- **Interrompere:** il pulsante di invio diventa "stop" mentre una richiesta è in corso. L'app smette di aspettare,
  scarta quello che arriva dopo e rifiuta le azioni di quella richiesta. Sul server l'agente finisce comunque il lavoro:
  NanoClaw non ha un modo per fermarlo senza farlo ripetere.
- **Nuova conversazione:** il pulsante con la fiamma svuota la chat e chiede al server di ripartire. L'utente dell'app
  non è amministratore, quindi è l'adattatore a scrivere `/clear` nella sessione dell'app. La memoria resta.
- **Notifiche:** i messaggi che l'agente manda da solo arrivano come notifica di sistema quando l'app non è in primo
  piano. Con UnifiedPush (per esempio ntfy) il server sveglia il telefono anche ad app chiusa; la push non contiene
  testo, che arriva poi dal server.

## Azioni sul telefono

Ora, data, timer, sveglia, apertura di un'app e chiamata, sia dal router locale sia richieste dall'agente con gli
strumenti MCP `phone_*`. La chiamata apre solo il tastierino con il numero: non telefona mai da sola.
Le sveglie e i timer impostati sono nuovi. Si possono fermare ("ferma il timer", "spegni la sveglia"), saltare ("disattiva la sveglia delle 7") e posticipare ("posticipa la sveglia di dieci minuti"); un timer ancora in corso non si può fermare da un'altra app, quindi si apre la schermata dei timer dell'Orologio.

Sono disponibili anche musica (Navidrome e Tempo), volume, torcia, navigazione, link, note Nextcloud,
bozze SMS/Signal ed eventi, batteria e letture facoltative di calendario, posizione e notifiche.
Le letture private si abilitano nelle impostazioni. Le azioni dell'agente devono appartenere a una richiesta ancora
aperta; con il telefono bloccato sono disponibili solo ora, data, timer e sveglie.

### Modificare il calendario

In **Impostazioni → Permessi → Letture per l'agente → Modifiche al calendario** si abilitano le modifiche e si concedono lettura e scrittura.
L'interruttore è spento per impostazione predefinita e abilita anche la lettura. L'agente legge prima gli eventi con
`phone_calendar_read`, che restituisce gli `event_id`, poi usa `phone_calendar_update` o `phone_calendar_delete`.
Il telefono mostra i dati reali e i nuovi valori in una scheda Approva / Non approvare, letta anche a voce dopo una
richiesta parlata. Si può rispondere con i pulsanti, a voce o scrivendo. Non serve una chiamata separata a `phone_confirm`.

L'approvazione vale solo per quell'evento e quella modifica: un rifiuto, 90 secondi senza risposta, l'interruzione,
il blocco del telefono, la revoca del permesso o un evento cambiato durante l'attesa impediscono la scrittura.
Gli eventi ricorrenti e i calendari non modificabili si gestiscono nell'app Calendario. Spostare solo l'inizio conserva
la durata; per eventi di tutto il giorno la fine è una data esclusa. Gli orari inesistenti o ambigui al cambio d'ora
sono rifiutati. Le modifiche passano dal [provider calendario Android](https://developer.android.com/identity/providers/calendar-provider);
la sincronizzazione configurata sul telefono (per esempio DAVx5) le porta al server.

Il lato NanoClaw richiede gli strumenti aggiornati della skill `add-voice`. Aggiornamento e verifica sul dispositivo:
[docs/CALENDAR.md](docs/CALENDAR.md).

## Voce

Tutto offline, senza servizi Google: **riconoscimento** con Vosk (modello italiano piccolo, ZIP da 48 MB) e **sintesi**
con Piper tramite sherpa-onnx. Al primo avvio una schermata scarica i due modelli (con hash SHA-256 fissati nel codice).
Vosk è necessario; Parakeet (circa 670 MB) può rifare la trascrizione per maggiore accuratezza ed è scaricabile e rimovibile
dalle impostazioni. Le voci Piper si gestiscono dalla libreria delle voci. Il riconoscitore di sistema è stato escluso:
su GrapheneOS è spesso solo inglese (Transcribro include `whisper tiny.en`).

- **Voce:** Serena (Piper high, dataset sintetico CC BY 4.0 `committa/serena-synthetic-it-27h`). Provate anche Paola, Dii,
  Riccardo e Miro: Serena è la più pulita (30-48 dB tra voce e rumore, senza guadagno); Miro risultava gracchiante.
  Il file è quello originale di Piper (114 MB, fp32): l'app aggiunge i metadati ONNX che sherpa-onnx richiede e costruisce
  `tokens.txt` dal JSON, togliendo i cinque dittonghi a due caratteri (`RawPiperInstaller`, `PiperFiles`).
- **Cosa non viene letto:** gli URL (restano nel testo e diventano link). Le cifre sì, perché `espeak-ng` le legge bene;
  prima della lettura l'app riscrive solo le date con le barre ("21/10/2026") e gli ordinali col simbolo ("il 3° piano").
- **Audio:** flusso multimediale (`USAGE_MEDIA`), non "assistente": quest'ultimo ha un volume separato, di solito basso.
- **Libreria nativa:** l'AAR di sherpa-onnx non è su Maven Central; la build lo scarica e ne verifica l'hash (`downloadSherpa`).
  Solo arm64.
- **Velocità:** le build di debug sono `debuggable`, quindi Android non le compila in AOT e decomprimere i modelli è lento.
  Per usare l'app: `./gradlew :app:installRelease` (firmata con la chiave di debug) e poi
  `adb shell cmd package compile -m speed -f dev.nanovoice`.
- **Attivazione:** “Zanzibar”, facoltativa. Il servizio mostra una notifica permanente, si ferma mentre l'app ascolta
  o parla e richiede che NanoVoice sia l'assistente predefinito.

## Interfaccia

- **Temi:** chiaro, scuro o automatico, con palette ricavata dall'icona; in alternativa i colori di sistema
  (Material You, Android 12+), che valgono anche per l'orb del microfono.
- **Microfono:** un orb con quattro stati (pronto, ascolto, penso, parlo). In chat si tiene premuto per parlare; nel
  foglio dell'assistente si tocca.
- **Impostazioni** divise in categorie: Aspetto, Voce e ascolto, Server NanoClaw, Notifiche, Permessi.
- **Anteprime dei link:** sotto un messaggio compare una scheda per il primo link. La pagina viene scaricata dal telefono,
  quindi il sito vede l'indirizzo di rete: si può disattivare in Aspetto. Solo `https`, solo host pubblici (niente reti
  private né Tailscale, controllati anche a ogni reindirizzamento), senza cookie, con limiti di tempo e di dimensione.
  Le anteprime dei link dell'agente si scaricano solo dopo un tocco.
- Il tasto "?" elenca cosa si può chiedere.

## Scelta del client WebSocket

**OkHttp**: già usato da gran parte dell'ecosistema Android, WebSocket integrato con ping/pong e
riconnessione semplice da costruire sopra, nessun vincolo multipiattaforma che giustifichi Ktor.

## Licenza

Apache License 2.0 (vedi `LICENSE`). Componenti di terzi, modelli e voci scaricabili con le loro licenze: vedi `NOTICE`.
Nessun codice è copiato da Dicio (GPL-3.0), usato solo come riferimento architetturale.

Sicurezza: segnalazioni private come descritto in `SECURITY.md`.
