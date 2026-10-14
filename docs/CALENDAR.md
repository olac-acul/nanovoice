# Calendario: integrazione e prova sul telefono

## Stato

Implementati nell'app `calendar_update` e `calendar_delete`, spenti per impostazione predefinita, con approvazione
legata all'evento e alla modifica. Gli strumenti MCP corrispondenti e il payload della skill `add-voice` sono aggiornati
nel testbed NanoClaw locale (`../nanoclaw-testbed/upstream`). Le modifiche del testbed non aggiornano l'istanza in uso.

Il 14 ottobre 2026 sono passati 525 test JVM (23 nuovi), i 25 test degli strumenti telefono NanoClaw,
i 51 test dell’adattatore vocale e il controllo TypeScript dell’agent runner. Compilato l’APK release; lint Android
completato senza errori, con avvisi già presenti. Corretti anche tre errori preesistenti di lint relativi
alla compatibilità Android 8.0 e alla dichiarazione del permesso microfono. ADB non rilevava dispositivi collegati: il provider Android reale,
l'interfaccia sul dispositivo e la sincronizzazione DAVx5 restano da verificare.

## Aggiornare NanoClaw

Lato server servono gli strumenti `phone_calendar_update` e `phone_calendar_delete` della skill `add-voice`
(testbed `../nanoclaw-testbed/upstream`, `container/agent-runner/src/mcp-tools/phone.ts`), la sezione "Modificare o
cancellare eventi del calendario" delle istruzioni del canale voce e il percorso dell'interruttore in `voice.ts`
("Impostazioni → Permessi → Letture per l'agente → Modifiche al calendario"). Si pubblicano come gli altri
aggiornamenti della skill: copia dei file, sostituzione della sola sezione "Canale voce" delle istruzioni del gruppo,
`pnpm run build`, riavvio del servizio e `ncl groups restart` per caricare i nuovi strumenti.

Il test sul dispositivo (`app/src/androidTest/.../CalendarDeviceRunner.kt`) scrive solo eventi di prova in un calendario
locale temporaneo; con `-e live true` crea anche un evento nel primo calendario DAVx5 modificabile, manda una richiesta
all'agente vero e preme Approva: usarlo solo sapendolo, e rimuovere poi `dev.nanovoice.test`.

## Installare e provare

Compilare con JDK 21. Per l'uso sul telefono impiegare la release, firmata come le altre build personali:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f dev.nanovoice
```

1. In Impostazioni → Permessi → Letture per l'agente attivare **Modifiche al calendario** e concedere lettura e scrittura. Si attiva anche la lettura.
2. Creare nell'app Calendario un evento singolo di prova, in un calendario modificabile, con titolo, luogo e durata noti.
3. Chiedere gli eventi del giorno. Verificare che l'agente riceva un `event_id` e usi quello per la modifica.
4. Chiedere di spostare l'evento cambiando solo l'inizio. Controllare nella scheda evento originale, nuovi orari e durata;
   approvare. Verificare sul calendario del telefono e, dopo la sincronizzazione, sul server.
5. Chiedere una seconda modifica e rifiutare. Ripetere lasciando scadere la domanda e interrompendo la richiesta: l'evento resta invariato.
6. Durante una domanda modificare lo stesso evento dall'app Calendario: l'approvazione precedente deve essere rifiutata.
7. Provare a bloccare il telefono, spegnere l'interruttore o revocare il permesso durante la domanda: nessuna scrittura.
8. Verificare scheda e risposta vocale sia nella chat sia nel foglio dell'assistente, anche con tastiera aperta e testi lunghi.
9. Provare un evento di tutto il giorno; la fine mostrata è esclusa. Provare un evento ricorrente: deve rimandare all'app Calendario.
10. Cancellare solo l'evento di prova, prima rifiutando e poi approvando. Verificare la sincronizzazione della cancellazione.

## Limiti

Nessuna modifica di serie o singole occorrenze ricorrenti, calendari di sola lettura, invitati o promemoria.
Gli orari ambigui o inesistenti al cambio d'ora richiedono l'app Calendario. La conferma riguarda la scrittura locale;
la riuscita della sincronizzazione dipende dall'app di sincronizzazione e dalla connessione.
