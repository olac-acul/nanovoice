# Protocollo app ↔ NanoClaw (versione 1)

WebSocket, messaggi JSON con campo `type`. I messaggi della conversazione dall'app includono `session`.
Implementazioni: `agent-client/Protocol.kt` e, nel repository NanoClaw, `src/channels/voice.ts` della skill `add-voice`.
Autenticazione: header `Authorization: Bearer <token>` nell'upgrade WebSocket (401 se errato).
`ws://` richiede un host privato ammesso e una VPN attiva sul telefono; per gli altri host serve `wss://`.

## App → NanoClaw

```json
{"type": "hello", "protocol": 1, "capabilities": ["set_timer", "tell_time", "tell_date", "set_alarm", "open_app", "call", "stop_timer", "stop_alarm", "snooze_alarm", "confirm", "choose", "calendar_read", "calendar_update", "calendar_delete"], "locale": "it-IT"}
{"type": "message", "session": "abc", "id": "m1", "text": "sposta il dentista a domani alle 10"}
{"type": "message", "session": "abc", "id": "m2", "text": "cos'è?", "image": {"mime": "image/jpeg", "data": "<base64>"}}
{"type": "action_result", "session": "abc", "action_id": "a1", "ok": true, "detail": "Evento aggiornato nel calendario del telefono."}
{"type": "cancel", "session": "abc", "reply_to": "m1"}
{"type": "push_endpoint", "url": "https://ntfy.sh/upAbCdEf..."}
{"type": "push_endpoint", "url": null}
{"type": "new_conversation"}
{"type": "warm"}
```

## NanoClaw → App

```json
{"type": "delta", "reply_to": "m1", "text": "Controllo il calendario."}
{"type": "working", "reply_to": "m1"}
{"type": "action", "reply_to": "m1", "action_id": "a1", "name": "calendar_update", "args": {"event_id": 42, "start": "2026-10-21T10:00"}}
{"type": "done", "reply_to": "m1"}
{"type": "error", "reply_to": "m1", "message": "..."}
{"type": "notify", "text": "Promemoria: riunione tra 10 minuti"}
{"type": "conversation_reset", "ok": true}
```

## Richieste e azioni

- `hello` è il primo frame dopo la connessione; `capabilities` elenca solo le azioni abilitate nelle impostazioni.
  Una modifica dei relativi interruttori riconnette il client con il nuovo elenco.
- Una sola richiesta per schermata: una nuova richiesta interrompe quella ancora in corso. Il client manda `cancel`
  anche quando scade l'attesa. Il server scarta il seguito dello scambio cancellato, comprese le azioni.
- Il telefono esegue un'azione solo se è dichiarata nelle capacità, abilitata nelle impostazioni attuali e legata,
  tramite `reply_to`, a una richiesta ancora aperta. Un'azione senza richiesta, dopo la chiusura o dopo un'interruzione
  viene rifiutata. A telefono bloccato sono ammesse solo ora, data, timer e sveglie.
- L'agente chiede le azioni con gli strumenti MCP `phone_*`. Questi scrivono il campo strutturato `voice_action`
  nell'uscita NanoClaw; l'adattatore lo converte in `action`. I marcatori `[[action:...]]` o `[[working]]`
  scritti nel testo vengono rimossi e non eseguiti.
- `action_result` viene accettato dal server solo per un'azione inviata di uno scambio vivo. Il dettaglio è limitato
  a 4 KB, neutralizzato e riportato all'agente come dati del telefono, separati dalle parole della persona.
- Le foto usate dagli strumenti appartengono al messaggio di `reply_to`: una richiesta senza foto non può utilizzare
  quelle delle richieste precedenti. L'interruttore dei servizi foto esterni rimuove le relative capacità.
- Le aggiunte di capacità restano nella versione 1. Modifiche incompatibili incrementano `protocol`.
- Una versione che il server non conosce viene rifiutata con `error` "unsupported protocol; expected N" e la chiusura
  della connessione. L'app lo spiega alla persona dicendo quale dei due aggiornare (N maggiore della sua: l'app; minore:
  il server), e lo ripete a ogni richiesta finché non parte un nuovo tentativo di connessione (anche se viene rifiutato di nuovo, nel qual caso
  il messaggio torna).

## Semantica dei frame

- `delta`: testo da accodare alla risposta di `reply_to`. NanoClaw consegna messaggi interi; dal secondo il server
  inserisce uno spazio iniziale. Non è uno streaming di singoli token del modello. Un `delta` per una richiesta che
  l'app non conosce o ha già chiuso viene mostrato come una notifica.
- `done`: chiude la risposta dopo 1,5 secondi senza altre consegne. Durante un lavoro lungo o un'azione in attesa,
  lo scambio resta aperto fino al limite di 3 minuti. Il seguito di un risultato di azione conserva lo stesso `reply_to`.
- `working`: l'agente annuncia un lavoro lungo con `voice_working`; l'app mantiene la risposta e mostra lo stato di lavoro.
- `error`: chiude la richiesta indicata. Senza `reply_to`, solo gli errori fatali di sessione (`hello required`,
  `unsupported protocol`) fanno fallire tutte le richieste; un errore su un altro frame non chiude le risposte aperte.
- `notify`: messaggio spontaneo dell'agente, anche accodato mentre il telefono era offline. Le risposte di richieste
  interrotte vengono scartate dal client. Le notifiche diventano notifiche Android se l'app non è visibile o il telefono è bloccato.
- `new_conversation`: l'adattatore scrive `/clear` nella sessione dell'app, se può azzerarla senza coinvolgere altre chat;
  restituisce `conversation_reset` con `ok: false` se non può farlo. La memoria dell'agente resta.
- `warm`: la persona ha toccato l'orb, aperto il foglio dell'assistente o la tastiera. Il server avvia subito il
  contenitore dell'agente della sessione voce, se esiste e non è già attivo, così il suo avvio si sovrappone alla frase.
  Nessuna risposta; al massimo uno ogni 30 secondi (lo limitano sia l'app sia il server). Non con il telefono bloccato.
- Un solo client connesso alla volta: una nuova connessione sostituisce la precedente.
- `image`: facoltativa, JPEG/PNG/WebP, massimo 1,5 MB decodificata. Il server verifica il tipo effettivo e consegna
  l'allegato al modello se supporta immagini. L'app ricodifica in JPEG senza EXIF: normalmente 1024 px, 2048 px per
  domande sulle scritte nella foto.

## Calendario

| Azione | Argomenti | Comportamento |
|---|---|---|
| `calendar_read` | `days` da 1 a 14, predefinito 1 | Eventi da oggi, con `event_id`, inizio, fine, luogo e indicazione delle ricorrenze. |
| `calendar_draft` | `title`, `start`, eventuali `end`, `location` | Apre una bozza nell'app Calendario; la persona salva. |
| `calendar_update` | `event_id`, almeno uno tra `title`, `start`, `end`, `location` | Legge l'evento reale, chiede approvazione e scrive la modifica. |
| `calendar_delete` | `event_id` | Legge l'evento reale, chiede approvazione e lo cancella. |
| `confirm` | `question`, massimo 200 caratteri | Domanda generica dell'agente; non autorizza successive scritture del calendario. |
| `choose` | `question`, massimo 200 caratteri; `options`, da 2 a 5 testi diversi di al massimo 60 caratteri | Domanda dell'agente con opzioni: la scheda mostra un pulsante per opzione e «Nessuna». Esito `ok` con l'opzione scelta, parola per parola, come `detail`; altrimenti `failed` (nessuna opzione, nessuna risposta in 90 secondi, richiesta interrotta). |

`calendar_update` e `calendar_delete` richiedono entrambi gli interruttori di lettura e modifica, i permessi
`READ_CALENDAR`/`WRITE_CALENDAR` e il telefono sbloccato. Il permesso di modifica è spento per impostazione predefinita.
L'agente usa l'identificativo ottenuto da `calendar_read`; non deve indovinarlo.

La scheda di approvazione contiene i dati letti dal provider e, per una modifica, i nuovi valori completi.
Si risponde con pulsanti, voce o testo. Rifiuto, 90 secondi senza risposta o richiesta non più attiva impediscono la scrittura.
Il telefono verifica di nuovo permessi, blocco, interruttori e contenuto dell'evento. Una scrittura condizionata ai valori
originali evita di sovrascrivere modifiche concorrenti della sincronizzazione. Un evento cambiato richiede una nuova approvazione.
Non serve chiamare `confirm` prima dei due strumenti.

Per `choose` si risponde anche a voce o per iscritto: con le parole di un'opzione («Gallipoli»), con un ordinale da solo o dopo un articolo («la seconda», «l'ultima», «opzione due») o con «nessuna». Qualsiasi altra frase è una nuova richiesta, che chiude la domanda senza scelta. Il server usa `choose` per `ask_user_question` di NanoClaw e restituisce l'esito come risposta alla domanda.

Gli orari sono locali nel fuso del telefono, nel formato `yyyy-MM-ddTHH:mm`. Un nuovo inizio senza nuova fine conserva
la durata effettiva. Per tutto il giorno si usa mezzanotte UTC e la fine è una data esclusa. Una stringa vuota in `location`
rimuove il luogo. Titolo massimo 200 caratteri, luogo massimo 500. I campi assenti restano invariati; `null` non è accettato.
Eventi ricorrenti, calendari non modificabili e orari inesistenti o ambigui al cambio d'ora sono rifiutati.

## Timer e sveglie

| Azione | Argomenti | Comportamento |
|---|---|---|
| `set_timer` | `seconds` | Nuovo timer nell'app Orologio. |
| `set_alarm` | `hour`, `minute`, `tomorrow` | Nuova sveglia nell'app Orologio. |
| `stop_timer` | nessuno | Ferma un timer che suona. Un timer ancora in corso non si può fermare da un'altra app (Android non lo permette): si apre la schermata dei timer dell'Orologio. |
| `stop_alarm` | eventuali `hour`, `minute` | Senza orario spegne la sveglia che suona. Con l'orario chiede all'Orologio di saltare il prossimo squillo di quella sveglia, che lo fa solo entro ventiquattro ore. |
| `snooze_alarm` | eventuale `minutes` (1-60) | Posticipa la sveglia che suona. |

Sono ammesse anche a telefono bloccato. Il suono di una sveglia e quello di un timer non si distinguono dall'esterno: per spegnere una sveglia il telefono prova prima a fermare un timer che suona, così non salta per errore una sveglia ancora da venire.

Le altre capacità sono dichiarate da `PhoneActionHandler.capabilities`: azioni base, controlli, link, bozze, stato,
letture private e strumenti foto. La chiamata apre il tastierino; SMS e Signal aprono una bozza.

## Abbinamento con un QR (versione 1)

Per non digitare indirizzo e token, il pannello del server mostra un QR e l'app lo legge. Il QR non contiene il token:
contiene un **codice di abbinamento** che vale una volta sola e per pochi minuti, e che l'app scambia con il token.

**Contenuto del QR** (un solo testo):

```
nanovoice://pair?v=1&server=<indirizzo ws:// o wss:// del canale, codificato>&code=<codice>
```

- `v=1`: versione del formato. L'app rifiuta una versione che non conosce e dice di aggiornare.
- `server`: lo stesso indirizzo che si digiterebbe nelle impostazioni, con le stesse regole: `ws://` solo per host privati
  (rete locale, VPN), `wss://` per il resto. L'app lo controlla con lo stesso controllo delle impostazioni e rifiuta il QR
  se non passa.
- `code`: 16 caratteri dell'alfabeto Crockford base32 (cifre e lettere maiuscole senza I, L, O, U), cioè 80 bit casuali. Il
  server li accetta anche in minuscolo e con trattini o spazi.

**Scambio.** L'app manda `POST <server con http:// o https://>/pair`, `Content-Type: application/json`:

```json
{"code": "7H2KQ9M4XB5TD3WE", "device": "Pixel 8"}
```

`device` è facoltativo (fino a 40 caratteri stampabili) e serve solo a mostrare nel pannello quale telefono si è abbinato.

Risposte:

| Stato | Corpo | Significato |
|---|---|---|
| 200 | `{"token": "<token>"}` | Abbinato. Il codice non vale più. |
| 401 | `{"error": "invalid_code"}` | Codice sbagliato, scaduto, già usato o mai creato: la stessa risposta per tutti, perché chi prova non impari quale. |
| 429 | `{"error": "too_many_attempts"}` | Troppi tentativi da questo indirizzo. |
| 400, 413 | `{"error": "bad_request"}` | Corpo non valido o più grande di 1 KiB. |

**Regole del server**

- Il codice vale **5 minuti** e **una volta sola**. Ne esiste uno solo alla volta: crearne un altro annulla il precedente.
- Dopo **5 tentativi sbagliati** il codice viene annullato. Gli errori contano anche per il blocco per indirizzo già usato per
  gli accessi con token.
- Il server tiene solo l'impronta (SHA-256) del codice, in un file privato (`data/voice-pairing.json`, permessi 600).
- Il token restituito è quello in uso. Non lo mostra nessuna pagina del pannello: arriva solo a chi ha il codice.
- Il pannello chiede di riscrivere la password prima di mostrare il QR, e il QR sparisce da solo allo scadere o appena
  l'app si è abbinata.

**Regole dell'app**

- Il codice non viene salvato né scritto nei registri; dopo lo scambio si salvano solo indirizzo e token, cifrato come sempre.
- Prima di salvare, l'app mostra a quale server si sta abbinando e chiede conferma. Un QR non conferma da solo.
- Se lo scambio fallisce non cambia nulla delle impostazioni.

## Risveglio con UnifiedPush

- L'app manda `push_endpoint` dopo `hello`, anche con `url: null` quando il push è spento, e ogni volta che cambia.
- Il server accetta solo endpoint HTTPS pubblici, controllati anche dopo la risoluzione DNS, senza reindirizzamenti.
  L'endpoint è conservato in un file con permessi 600; sul telefono è cifrato.
- Per un telefono non collegato il server invia `POST` con corpo `wake`, al massimo ogni 15 secondi. Il corpo non contiene
  testo privato. L'app si collega e riceve i messaggi accodati tramite `notify`.
