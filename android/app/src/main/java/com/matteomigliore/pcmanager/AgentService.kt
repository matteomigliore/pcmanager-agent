package com.matteomigliore.pcmanager

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service: si connette al cloud via WebSocket, invia l'uso app (UsageStats)
 * e lo stato (batteria/online), riceve le regole. È l'equivalente Android dell'agente PC.
 */
class AgentService : Service() {
    companion object {
        const val WS_URL = "wss://pc.matteomigliore.com/agent"
        const val CH = "pcm_agent"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        DeviceOwner.applyProtections(this) // se siamo Device Owner: blinda subito il telefono
        connect()
        scope.launch { while (isActive) { try { sendUsage(); sendSnapshot() } catch (_: Exception) {}; delay(60_000) } }
        // Enforcement "duro" via Device Owner: rileva il gioco in primo piano e lo sospende,
        // SENZA dipendere dall'Accessibilità (che un ragazzo potrebbe disattivare).
        scope.launch { while (isActive) { try { if (DeviceOwner.isOwner(this@AgentService)) enforceOwner() } catch (_: Exception) {}; delay(5_000) } }
        return START_STICKY
    }

    /* ── enforcement Device Owner (blocco duro, indipendente dall'Accessibilità) ── */
    private val ownerSuspended = HashSet<String>()
    private var ownerGamePkg: String? = null
    private var ownerGameSince = 0L

    private fun enforceOwner() {
        val r = GameRules.load(this)
        val fg = currentForegroundApp()
        // Accredito il tempo di gioco SOLO se l'Accessibilità è spenta: se è attiva ci pensa
        // già GameGuardService, e conterei doppio.
        val accessOff = !isAccessibilityEnabled()
        if (r.gamesEnabled && fg != null && GameRules.isGame(this, fg, r.tags)) {
            if (accessOff) {
                if (ownerGamePkg == fg && ownerGameSince > 0L) {
                    val secs = ((SystemClock.elapsedRealtime() - ownerGameSince) / 1000).toInt()
                    if (secs > 0) GameRules.addToday(this, secs)
                }
                ownerGamePkg = fg; ownerGameSince = SystemClock.elapsedRealtime()
            }
            if (!GameRules.isAllowedNow(this, r)) {
                DeviceOwner.suspend(this, arrayOf(fg), true); ownerSuspended.add(fg)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            ownerGamePkg = null; ownerGameSince = 0L
        }
        // Torna in fascia consentita → riapri i giochi sospesi.
        if (GameRules.isAllowedNow(this, r) && ownerSuspended.isNotEmpty()) {
            DeviceOwner.suspend(this, ownerSuspended.toTypedArray(), false); ownerSuspended.clear()
        }
    }

    /** Pacchetto in primo piano ora, dagli eventi UsageStats (ultimi 10s). */
    private fun currentForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 10_000, now)
        val e = UsageEvents.Event(); var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName }
        return last
    }

    /** Il nostro servizio di Accessibilità è attualmente attivo? */
    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains("$packageName/")
    }

    private fun token(): String =
        getSharedPreferences("pcm", Context.MODE_PRIVATE).getString("token", "") ?: ""

    private fun connect() {
        val t = token(); if (t.isEmpty()) return
        val req = Request.Builder().url("$WS_URL?token=" + java.net.URLEncoder.encode(t, "UTF-8")).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { handleCmd(text) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnectSoon() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) { reconnectSoon() }
        })
    }
    private fun reconnectSoon() { scope.launch { delay(5_000); connect() } }

    private fun handleCmd(text: String) {
        try {
            val o = JSONObject(text)
            if (o.optString("cmd") == "rules") {
                getSharedPreferences("pcm", MODE_PRIVATE).edit()
                    .putString("rules", o.optJSONObject("rules")?.toString() ?: "{}").apply()
                // Le regole aggiornate vengono lette da GameGuardService (Accessibilità) e da
                // enforceOwner() (Device Owner) alla prossima valutazione.
            }
        } catch (_: Exception) {}
    }

    /** Uso app nell'ultimo minuto, dai contatori UsageStats (richiede permesso utente). */
    private fun sendUsage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis(); val start = end - 60_000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return
        val items = JSONArray()
        for (s in stats) {
            val secs = (s.totalTimeInForeground / 1000).toInt()
            if (secs <= 0) continue
            val app = appLabel(s.packageName)
            items.put(JSONObject().put("winUser", "").put("app", app).put("seconds", minOf(secs, 60)))
        }
        if (items.length() > 0) ws?.send(JSONObject().put("type", "usage").put("items", items).toString())
    }

    /** Stato sintetico: online + batteria (i telefoni non hanno le temp CPU dei PC). */
    private fun sendSnapshot() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val data = JSONObject()
            .put("timestampUtc", java.time.Instant.now().toString())
            .put("temperatures", JSONArray()).put("fans", JSONArray()).put("loads", JSONArray())
            .put("admin", true).put("monitors", 1).put("battery", level)
            .put("deviceOwner", DeviceOwner.isOwner(this))
        ws?.send(JSONObject().put("type", "snapshot").put("data", data).toString())
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun notif(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "PC Manager", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("PC Manager attivo")
            .setContentText("Monitoraggio in corso")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).build()
    }

    override fun onDestroy() { scope.cancel(); ws?.close(1000, "bye"); super.onDestroy() }
}
