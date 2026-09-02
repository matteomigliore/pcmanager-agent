#!/usr/bin/env bash
# Configura un telefono Android come dispositivo PC Manager, via USB.
#
# Fa tutto cio' che si puo' fare da adb: Device Owner (se il telefono e' pulito), installazione
# dell'APK, permessi che MIUI/HyperOS nasconde dietro i suoi menu, esenzione batteria, avvio.
# L'unica cosa che resta a mano e' il collegamento al cloud (QR), se non si passa un token.
#
# Uso:
#   ./configura-telefono.sh                 # installa e configura
#   ./configura-telefono.sh --owner         # tenta anche il Device Owner (telefono SENZA account)
#   ./configura-telefono.sh --token XYZ     # collega anche il dispositivo senza usare il QR
#   ./configura-telefono.sh --backup /path  # copia prima /sdcard sul PC
set -u
PKG=com.matteomigliore.pcmanager
APK_URL=https://pc.miglioresoftware.com/download/PCAgentSetup.apk
ADB=${ADB:-adb}
export MSYS_NO_PATHCONV=1   # git-bash non deve tradurre /sdcard in un percorso Windows

OWNER=0; TOKEN=""; BACKUP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --owner)  OWNER=1; shift ;;
    --token)  TOKEN="${2:-}"; shift 2 ;;
    --backup) BACKUP="${2:-}"; shift 2 ;;
    *) echo "opzione sconosciuta: $1"; exit 2 ;;
  esac
done

ok(){ echo "  [OK]   $*"; }
ko(){ echo "  [NO]   $*"; }
info(){ echo "  ->     $*"; }
titolo(){ echo; echo "== $* =="; }

titolo "1. Dispositivo"
"$ADB" start-server >/dev/null 2>&1
N=$("$ADB" devices | grep -cw device)
if [ "$N" -eq 0 ]; then
  echo "Nessun telefono visibile. Controlla: Debug USB attivo, cavo dati (non solo ricarica),"
  echo "modalita' USB = Trasferimento file, e popup 'Consenti debug USB' accettato."
  exit 1
fi
[ "$N" -gt 1 ] && { echo "Piu' di un telefono collegato: lasciane uno solo."; exit 1; }
MODEL=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ok "collegato: $MODEL"

if [ -n "$BACKUP" ]; then
  titolo "2. Backup dei file utente"
  mkdir -p "$BACKUP"
  info "copio /sdcard (foto, video, download). NON include i dati interni delle app."
  "$ADB" pull -a /sdcard "$BACKUP" >/dev/null 2>&1 && ok "copiato in $BACKUP" || ko "backup incompleto"
fi

if [ "$OWNER" -eq 1 ]; then
  titolo "3. Device Owner (blocco inaggirabile)"
  ACC=$("$ADB" shell dumpsys account 2>/dev/null | grep -c 'Account {')
  if [ "${ACC:-0}" -gt 0 ]; then
    ko "il telefono ha $ACC account configurati: Android rifiuta il Device Owner."
    info "serve un telefono appena resettato, con la configurazione iniziale saltata (nessun login)."
  else
    "$ADB" shell "dpm set-device-owner $PKG/.AdminReceiver" 2>&1 | sed 's/^/         /' | head -4
  fi
fi

titolo "4. Installazione app"
TMP_APK="${TMPDIR:-/tmp}/PCAgentSetup.apk"
if [ ! -f "$TMP_APK" ]; then
  info "scarico l'APK piu' recente"
  curl -sL -o "$TMP_APK" "$APK_URL" || { ko "download fallito"; exit 1; }
fi
"$ADB" install -r "$TMP_APK" 2>&1 | grep -qi success && ok "installata (i dati esistenti restano)" || ko "installazione fallita"
VER=$("$ADB" shell dumpsys package $PKG 2>/dev/null | grep -m1 versionName | tr -d '\r' | xargs)
ok "$VER"

titolo "5. Permessi (quelli che MIUI nasconde)"
# Uso app: e' il permesso che la Sicurezza Xiaomi blocca con lo scudo. Da adb passa.
"$ADB" shell "appops set $PKG GET_USAGE_STATS allow" >/dev/null 2>&1
[ "$("$ADB" shell "cmd appops get $PKG GET_USAGE_STATS" 2>/dev/null | grep -c allow)" -gt 0 ] \
  && ok "dati sull'utilizzo" || ko "dati sull'utilizzo"
# Notifiche: senza, la notifica del servizio resta nascosta e MIUI lo chiude piu' facilmente.
"$ADB" shell "cmd appops set $PKG POST_NOTIFICATION allow" >/dev/null 2>&1 && ok "notifiche"
# Batteria: esenta dal Doze, altrimenti sparisce dopo qualche ora.
"$ADB" shell "dumpsys deviceidle whitelist +$PKG" >/dev/null 2>&1
[ "$("$ADB" shell dumpsys deviceidle whitelist 2>/dev/null | grep -c $PKG)" -gt 0 ] \
  && ok "esente da ottimizzazioni batteria" || ko "esenzione batteria"
# Avvio automatico MIUI (op proprietaria 10008): senza, HyperOS impedisce l'avvio in background.
"$ADB" shell "cmd appops set $PKG 10008 allow" >/dev/null 2>&1
"$ADB" shell "cmd appops get $PKG 10008" 2>/dev/null | grep -qi allow \
  && ok "avvio automatico MIUI" || info "avvio automatico: verifica a mano in Sicurezza"
# Accessibilita': serve al blocco giochi/siti.
ATT=$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
SRV="$PKG/.GameGuardService"
case "$ATT" in
  *"$SRV"*) ok "accessibilita' gia' attiva" ;;
  null|"") "$ADB" shell "settings put secure enabled_accessibility_services $SRV" >/dev/null 2>&1
           "$ADB" shell "settings put secure accessibility_enabled 1" >/dev/null 2>&1
           ok "accessibilita' attivata" ;;
  *)       "$ADB" shell "settings put secure enabled_accessibility_services $ATT:$SRV" >/dev/null 2>&1
           "$ADB" shell "settings put secure accessibility_enabled 1" >/dev/null 2>&1
           ok "accessibilita' aggiunta ai servizi esistenti" ;;
esac

if [ -n "$TOKEN" ]; then
  titolo "6. Collegamento al cloud"
  "$ADB" shell "am force-stop $PKG" >/dev/null 2>&1
  PREFS="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name=\"token\">$TOKEN</string></map>"
  if "$ADB" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/pcm.xml'" <<< "$PREFS" 2>/dev/null; then
    ok "token scritto"
  else
    ko "scrittura token non riuscita: collega con il QR dall'app"
  fi
fi

titolo "7. Avvio e verifica"
"$ADB" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
sleep 4
FG=$("$ADB" shell dumpsys activity services $PKG 2>/dev/null | grep -c 'isForeground=true')
[ "${FG:-0}" -gt 0 ] && ok "servizio agente attivo" || ko "servizio non attivo"
APPUID=$("$ADB" shell dumpsys package $PKG 2>/dev/null | grep -m1 -oE 'userId=[0-9]+|appId=[0-9]+' | grep -oE '[0-9]+' | head -1)
CONN=$("$ADB" shell "cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null" | tr -d '\r' | awk -v u="${APPUID:-0}" '$8==u && $4=="01"{n++} END{print n+0}')
if [ "${CONN:-0}" -gt 0 ]; then ok "collegato al cloud ($CONN connessioni attive)"
else info "nessuna connessione: se non hai passato --token, collega ora inquadrando il QR dall'app"; fi

echo
echo "Fatto. Se il telefono non e' ancora collegato: app -> Inquadra QR (dashboard: Telefono -> Installazione)."
