# Piano di sviluppo — Assistente vocale Android per NanoClaw

> Nome provvisorio del progetto: **NanoVoice** (da rinominare).
> Questo documento è il riferimento per lo sviluppo. Leggilo per intero prima di iniziare e lavora una fase alla volta.

**Stato al 14 ottobre 2026:** le fasi 1–5 sono completate. Le sezioni delle fasi e le istruzioni per iniziare descrivono
il piano originale. Le scelte effettive includono Vosk e Piper offline, Parakeet opzionale, parola di attivazione,
classificatore locale, foto e musica. Per il comportamento attuale vedere `README.md` e `docs/PROTOCOL.md`;
le sette fasi di correzione sono in `docs/FIX-PLAN-2026-10-14.md`.

**Calendario:** implementate lettura con identificativi, modifica e cancellazione di eventi singoli, con permesso e
interruttore dedicati e approvazione legata ai dati reali. Gli eventi ricorrenti restano all'app Calendario.
Strumenti NanoClaw aggiornati nel testbed locale e nel payload `add-voice`. Restano installazione sul telefono,
aggiornamento dell'istanza NanoClaw in uso e verifica reale della sincronizzazione: `docs/CALENDAR.md`.

## 1. Obiettivo

Un'app Android che funziona da assistente vocale personale:

- le richieste **semplici** (es. dire l'ora, impostare un timer) vengono gestite **in locale** sul telefono, anche offline;
- tutte le altre vengono inoltrate a un'istanza **NanoClaw** self-hosted (agente basato su Claude Agent SDK), che risponde in streaming e può chiedere al telefono di eseguire azioni;
- a regime l'app può essere impostata come **assistente predefinito** di Android.

Il progetto open source **Dicio** (assistente vocale Android in Kotlin) è usato come **riferimento architetturale**, non come base di codice.

## 2. Vincoli e decisioni

- **Linguaggio / UI:** Kotlin, Jetpack Compose, Material 3.
- **Asincronia:** Coroutines e Flow.
- **Serializzazione:** kotlinx.serialization.
- **Rete:** WebSocket (OkHttp o Ktor client: scegli uno e motiva la scelta in un commento nel README).
- **Dependency injection:** manuale all'inizio (un semplice `AppContainer`); valutare Hilt solo se la complessità lo richiede.
- **minSdk:** 26. **targetSdk / compileSdk:** ultima versione stabile disponibile.
- **Lingua iniziale:** italiano. Il codice deve però permettere di aggiungere altre lingue (frasi e parser separati per locale).
- **Niente dipendenze dai Google Play Services.** Per le notifiche push future: UnifiedPush.
- **Licenza: DA DECIDERE.** Finché non è decisa, **non copiare codice da Dicio** (è GPL-3.0): studiarne l'approccio e riscrivere in modo indipendente.

## 3. Architettura

### Moduli Gradle

| Modulo | Responsabilità |
|---|---|
| `app` | UI Compose (conversazione, impostazioni), wiring delle dipendenze |
| `core` | Modelli condivisi, interfacce, utilità (nessuna dipendenza Android dove possibile) |
| `local-router` | Riconoscimento delle richieste semplici ed estrazione dei parametri (Kotlin puro, testabile su JVM) |
| `actions` | Esecutori delle azioni sul telefono (timer, ora, …) |
| `agent-client` | Connessione WebSocket a NanoClaw e protocollo |
| `voice` | STT e TTS (fase 3) |
| `assistant` | `VoiceInteractionService` e servizi collegati (fase 4) |

I moduli `voice` e `assistant` si creano solo quando si arriva alle rispettive fasi.

### Flusso di una richiesta

```
input (testo o voce)
   └─> LocalRouter.match(testo)
          ├─ LocalIntent riconosciuto → ActionExecutor → risposta testuale (+ TTS)
          └─ null / confidenza bassa  → AgentClient (NanoClaw) → delta in streaming
                                              └─ eventuali "action" → ActionExecutor
```

Punto chiave: **gli esecutori di azioni sono condivisi**. Un timer impostato su decisione del router locale e uno richiesto da NanoClaw passano dallo stesso codice.

### Interfacce principali (bozza)

```kotlin
sealed interface LocalIntent {
    data object TellTime : LocalIntent
    data class SetTimer(val duration: Duration?) : LocalIntent // null = chiedere la durata
}

data class MatchResult(val intent: LocalIntent, val confidence: Float)

interface LocalMatcher {
    fun match(text: String): MatchResult? // null = non riconosciuto
}

interface ActionExecutor {
    suspend fun execute(action: PhoneAction): ActionResult
}
```

`PhoneAction` rappresenta un'azione eseguibile sul telefono, indipendentemente da chi l'ha richiesta (router locale o NanoClaw).

## 4. Protocollo app ↔ NanoClaw

WebSocket, messaggi JSON con campo `type`. Ogni messaggio dall'app include `session`.

**App → NanoClaw**

```json
{"type": "hello", "protocol": 1, "capabilities": ["set_timer", "tell_time"], "locale": "it-IT"}
{"type": "message", "session": "abc", "id": "m1", "text": "riassumimi le email di oggi"}
{"type": "action_result", "session": "abc", "action_id": "a1", "ok": true, "detail": "timer impostato"}
```

**NanoClaw → App**

```json
{"type": "delta", "reply_to": "m1", "text": "Oggi hai ricevuto "}
{"type": "action", "reply_to": "m1", "action_id": "a1", "name": "set_timer", "args": {"seconds": 600}}
{"type": "done", "reply_to": "m1"}
{"type": "error", "reply_to": "m1", "message": "..."}
{"type": "notify", "text": "Promemoria: riunione tra 10 minuti"}
```

Regole:

- `hello` è il primo messaggio dopo la connessione; le `capabilities` dicono all'agente quali azioni il telefono sa eseguire.
- Un'`action` con `name` non presente nelle capabilities va rifiutata con `action_result` `ok: false`.
- Il protocollo è versionato (`protocol`); modifiche incompatibili incrementano la versione.
- Documentare il protocollo in `docs/PROTOCOL.md` e mantenerlo aggiornato.

## 5. Fasi di sviluppo

### Fase 1 — Chat testuale + router locale (NIENTE rete, NIENTE voce)

Obiettivo: verificare la catena testo → router → azione → risposta.

Attività:

1. Creare il progetto multi-modulo (`app`, `core`, `local-router`, `actions`), con build funzionante e README.
2. Schermata di conversazione in Compose: lista di messaggi (utente / assistente), campo di testo, invio.
3. `local-router`:
   - matcher per **dire l'ora**: "che ore sono", "che ora è", "dimmi l'ora", "mi dici l'ora?" e varianti ragionevoli;
   - matcher per **impostare un timer**: "metti un timer di 10 minuti", "imposta un timer da dieci minuti", "timer 30 secondi", "un timer di un'ora e venti", "metti un timer" (senza durata);
   - **parser delle durate in italiano**, con numeri in cifre e in lettere: "dieci", "ventitré", "un minuto e mezzo", "un'ora e un quarto", "mezz'ora", "un'ora e venti";
   - normalizzazione del testo (minuscole, apostrofi, accenti, punteggiatura);
   - frasi e regole organizzate per locale, per poter aggiungere altre lingue.
4. `actions`:
   - `TellTime`: risposta in linguaggio naturale adatta al TTS ("sono le dieci e un quarto", "è mezzogiorno", "è l'una e cinque");
   - `SetTimer`: tramite intent `AlarmClock.ACTION_SET_TIMER` (con `EXTRA_SKIP_UI`), gestendo il caso in cui nessuna app lo supporti;
   - timer senza durata: l'assistente chiede "Per quanto tempo?" e la risposta successiva viene interpretata come durata.
5. Richieste non riconosciute: per ora risposta fissa "Non so ancora rispondere a questo" (sarà sostituita da NanoClaw nella fase 2).

Criteri di completamento:

- test unitari JVM per il parser delle durate e per i matcher (almeno 30 casi, inclusi casi negativi che NON devono essere riconosciuti, es. "quanto dura un timer?", "che ore erano ieri?");
- dall'app, scrivendo "che ore sono" si ottiene l'ora corretta; scrivendo "metti un timer di due minuti" parte il timer nell'app Orologio;
- `./gradlew build` e i test passano senza warning rilevanti.

### Fase 2 — Collegamento a NanoClaw

1. Lato NanoClaw (repository separato, TypeScript): aggiungere un **canale WebSocket** che implementa il protocollo della sezione 4, preferibilmente come skill installabile secondo le convenzioni del progetto NanoClaw (verificare la documentazione per i contributi). Un gruppo dedicato alla voce, con `CLAUDE.md` che chiede risposte brevi, senza markdown, adatte alla lettura ad alta voce.
2. Lato app: modulo `agent-client` con connessione, riconnessione automatica con backoff, gestione di `delta`/`action`/`done`/`error`.
3. Impostazioni: URL del server e token di autenticazione (salvato in modo sicuro, es. EncryptedSharedPreferences o DataStore cifrato).
4. Le richieste non riconosciute dal router vanno a NanoClaw; la risposta appare in streaming nella chat.
5. Soglia di confidenza del router configurabile: sotto soglia, la richiesta va a NanoClaw.
6. Server non raggiungibile: messaggio chiaro, il router locale continua a funzionare.

Rete consigliata per l'uso personale: Tailscale o WireGuard, senza esporre il server su internet.

### Fase 3 — Voce (push-to-talk)

1. Modulo `voice` con interfaccia `SpeechToText` e due implementazioni: `SpeechRecognizer` di Android (rapida da integrare) e Vosk (offline, modello italiano scaricabile).
2. TTS con `TextToSpeech` di sistema; con NanoClaw, iniziare a parlare per frasi complete man mano che arrivano i `delta`.
3. Pulsante "tieni premuto per parlare", gestione dei permessi microfono e dell'audio focus.
4. Feedback immediato per le richieste lente ("ci penso…").

### Fase 4 — Assistente predefinito

1. Implementare `VoiceInteractionService`, `VoiceInteractionSessionService` e `RecognitionService` necessari perché l'app sia selezionabile come assistente digitale nelle impostazioni di Android.
2. Attivazione con pressione lunga del tasto home / gesto dell'assistente, con una UI compatta sovrapposta.

### Fase 5 — Più azioni e notifiche

- Nuove azioni locali: data di oggi, sveglie, chiamare un contatto, aprire un'app.
- Notifiche dall'agente quando l'app è chiusa tramite UnifiedPush.
- Più avanti, da valutare: parola di attivazione, classificatore on-device per il router, LLM locale.

## 6. Convenzioni

- Codice e identificatori in inglese; stringhe dell'interfaccia in risorse localizzate (italiano per primo).
- Logica di dominio (router, parser, protocollo) in Kotlin puro e coperta da test JVM.
- Commit piccoli e descrittivi; una fase alla volta, con verifica dei criteri prima di passare alla successiva.
- Nessun segreto nel repository.
- Aggiornare questo documento quando una decisione cambia.

## 7. Istruzioni per iniziare

Inizia dalla **Fase 1**. Prima di scrivere codice:

1. proponi la struttura del progetto e l'elenco dei file che creerai;
2. elenca le scelte tecniche ancora aperte (es. OkHttp vs Ktor) con una raccomandazione;
3. attendi conferma, poi procedi un punto alla volta, eseguendo build e test dopo ogni passo.
