# Procedura rapida: agente PC Manager su un telefono supervisionato

Giro chiuso, senza reset e senza perdere niente: si sospende Family Link il tempo di
installare, poi si rimette tutto com'era. Da ripetere identica su ogni telefono.
Tempo: ~10 minuti a telefono, di cui 2 di lavoro tecnico.

**Serve:** un PC con cavo dati, `adb`
(<https://dl.google.com/android/repository/platform-tools-latest-windows.zip>) e il telefono.

---

## 1. Disattivare Family Link

Dal **tuo** telefono (app Family Link) o da <https://familylink.google.com>:

1. Seleziona il figlio -> **Controlli** / Impostazioni account.
2. **Interrompi supervisione** e conferma con la tua password Google.
3. Sul telefono del figlio la supervisione decade entro pochi istanti (se tarda, riavvialo).

> Se il figlio ha **meno di 13 anni** questa voce può non esserci: Google propone di
> eliminare o trasferire l'account invece di sospendere la supervisione. In quel caso
> **fermati** e usa la variante in fondo (installazione manuale, senza toccare Family Link).

## 2. Attivare le Opzioni sviluppatore

Ora che la supervisione è sospesa la voce non è più bloccata.

1. Impostazioni -> **Info sul telefono** -> 7 tocchi su **Versione HyperOS**.
2. Impostazioni aggiuntive -> **Opzioni sviluppatore** -> **Debug USB** ON.
3. *(solo se un permesso viene rifiutato al passo 3)* nella stessa schermata, in fondo:
   **Attiva ottimizzazione MIUI** -> OFF, poi riavvia.
4. Collega il cavo, imposta USB su **Trasferimento file**, accetta *Consenti debug USB*
   spuntando **Consenti sempre da questo computer**.

## 3. Installazione agente  *(la eseguo io)*

```bash
./scripts/configura-telefono.sh --token <TOKEN>
```

Lo script fa tutto: installa l'APK, concede **Dati sull'utilizzo** (il permesso che lo scudo
Sicurezza Xiaomi blocca a mano), notifiche, **esenzione batteria**, **avvio automatico MIUI**,
**Accessibilità** per il blocco giochi, collega il dispositivo e verifica che risulti online.

Il token si crea in dashboard -> **Telefono -> Installazione**, intestandolo al figlio.
Senza `--token` il collegamento si fa dall'app con **Inquadra QR**.

## 4. Riattivare l'ottimizzazione MIUI

Solo se l'avevi disattivata al passo 2.3: Opzioni sviluppatore -> **Attiva ottimizzazione
MIUI** -> ON, e riavvia.

> Riattivandola, MIUI può revocare i permessi concessi mentre era spenta. Dopo il riavvio
> rilancia lo script (è idempotente): ricontrolla e ripristina cio' che fosse saltato.

## 5. Riabilitare Family Link

1. App Family Link -> **Aggiungi/riprendi supervisione** sull'account del figlio.
2. Riaccetta la supervisione sul telefono del figlio (chiede la password del genitore).

> Family Link può disattivare i servizi di **Accessibilità** quando riprende il controllo:
> è il pezzo che serve al blocco giochi. Verificalo (passo 6); se lo spegne, sul telefono
> resta il monitoraggio (uso app e siti) ma non il blocco.

## 6. Verifica finale

- Dashboard: il telefono è **online** con la versione corretta.
- Sul telefono: notifica **"PC Manager attivo"** presente.
- Se serve, ricontrollo tutto in un colpo: `./scripts/configura-telefono.sh`

---

## Variante senza toccare Family Link

Se la supervisione non si può sospendere (under-13):

1. Sul telefono scarica e installa
   <https://pc.miglioresoftware.com/download/PCAgentSetup.apk>
   (Family Link chiede l'approvazione del genitore per le origini sconosciute).
2. App -> **Inquadra QR** con il codice della dashboard.
3. Concedi a mano **Dati sull'utilizzo** e **Accessibilità**.

Si ottiene il monitoraggio; il blocco resta morbido e i permessi vanno concessi a mano
perchè senza Debug USB lo script non può intervenire.
