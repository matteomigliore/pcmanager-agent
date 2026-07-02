# PC Manager — Agente Android (scaffold)

App nativa Android che fa per i telefoni ciò che l'agente Windows fa per i PC:
si connette al cloud (`wss://pc.matteomigliore.com/agent?token=...`), invia
**uso app** (via UsageStats) e stato (batteria/online), e riceve le **regole**
(restrizioni gioco/app). Riusa lo stesso protocollo WebSocket dell'agente PC.

## ⚠️ Stato: scaffold da completare in Android Studio
Questo NON è compilato/testato qui: va aperto in **Android Studio** (Giraffe+),
con Android SDK. È un punto di partenza realistico, non una release.

## Cosa funziona già nel disegno
- Pairing: incolli il **token dispositivo** (creato dall'app web → "Aggiungi PC"/dispositivo) → salvato in `SharedPreferences`.
- **Monitoraggio uso app**: `UsageStatsManager` (permesso *Accesso ai dati sull'utilizzo*, da concedere a mano: è una limitazione Android).
- **Foreground service** persistente con notifica (obbligatoria su Android moderno) + riavvio al boot.
- **WebSocket** OkHttp: invia `usage`/`snapshot`, riceve `rules`.

## Blocco giochi — implementato via Accessibility Service
- `GameGuardService` (Accessibility) rileva il **gioco in primo piano** e, se non consentito
  (fuori fascia oraria o oltre il tempo massimo), mostra un avviso e **riporta alla Home**
  (blocco "morbido", come i parental control). Traccia il tempo di gioco del giorno.
- **Rilevamento "gioco" automatico**: usa `ApplicationInfo.CATEGORY_GAME` (categoria del Play
  Store) → riconosce qualsiasi gioco installato dallo store; più i **tag manuali** dalle Restrizioni.
- Permessi da concedere a mano (limitazione Android): *Accesso ai dati sull'utilizzo* (monitoraggio)
  e *Accessibilità → PC Manager — Blocco giochi* (blocco).

### Note onestà
- Il blocco è "morbido" (Home + avviso): efficace per i ragazzi, ma un utente esperto può
  disattivare l'Accessibilità. Per un blocco "duro" servono **Device Owner / MDM**
  (`DevicePolicyManager.setPackagesSuspended`, telefono provisionato) — passo successivo opzionale.
- Le regole arrivano dal cloud (stesse dei PC) e sono salvate in `SharedPreferences` ("rules").

## Ottenere l'APK installabile
### Opzione A — Pipeline Azure DevOps (consigliata, senza PC con Android Studio)
1. Azure DevOps → Pipelines → New pipeline → **Existing YAML** → `android-agent/azure-pipelines.yml`.
2. **Run pipeline** (trigger manuale). Builda un **APK debug** (auto-firmato).
3. Apri la run → **Artifacts → apk → `app-debug.apk`**: scaricalo.

### Opzione B — Android Studio (locale)
Apri la cartella `android-agent`, sincronizza Gradle, `Build > Build APK(s)`.

## Installazione sul telefono
1. Trasferisci `app-debug.apk` sul telefono e aprilo → consenti **"Installa app sconosciute"** per il file manager/browser.
2. Apri **PC Manager**, incolla il **token** (creato nell'app web → aggiungi dispositivo), tocca **Salva**.
3. **Concedi accesso all'utilizzo** (monitoraggio) e **Avvia agente**.
4. **Attiva blocco giochi (Accessibilità)** → abilita "PC Manager — Blocco giochi" (blocco morbido).

## Blocco "duro" (inaggirabile) — Device Owner
Su un telefono **senza account Google configurati** (nuovo o reset di fabbrica), via USB con adb:
```
adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver
```
(La stessa cosa si attiva dall'app: **Attiva/aggiorna blocco totale** → mostra il comando.)

Appena diventa Device Owner, l'agente applica **da solo** le protezioni (`DeviceOwner.applyProtections`):
- **sospende i giochi** fuori fascia/oltre il tempo (blocco vero, non solo Home);
- **enforcement dal foreground service** (`AgentService.enforceOwner`) che rileva il gioco in primo
  piano via UsageStats e lo sospende **anche se l'Accessibilità è spenta** — quindi il ragazzo
  non aggira il blocco disattivando l'Accessibilità;
- **anti-bypass**: `setUninstallBlocked` (non disinstallabile) + restrizioni utente
  (`DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_DEBUGGING_FEATURES`,
  `DISALLOW_ADD_USER`, `DISALLOW_UNINSTALL_APPS`, `DISALLOW_APPS_CONTROL`) +
  `setPermittedAccessibilityServices` limitato al nostro servizio.

Per cedere/disinstallare il telefono: **Rimuovi blocco totale (Device Owner)** nell'app
(`DeviceOwner.release`) toglie le protezioni e rilascia il Device Owner. Senza Device Owner
resta il solo blocco morbido (Home) via Accessibilità.

## File principali
- `app/src/main/java/.../MainActivity.kt` — pairing + permessi.
- `app/src/main/java/.../AgentService.kt` — foreground service: UsageStats + WebSocket.
- `app/src/main/AndroidManifest.xml` — permessi e componenti.
