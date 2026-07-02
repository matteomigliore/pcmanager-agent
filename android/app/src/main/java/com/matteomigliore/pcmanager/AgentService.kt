package com.matteomigliore.pcmanager

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
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
        connect()
        scope.launch { while (isActive) { try { sendUsage(); sendSnapshot() } catch (_: Exception) {}; delay(60_000) } }
        return START_STICKY
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
                // TODO: applicare le regole (vedi README: blocco via Accessibility/DeviceOwner)
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
