package com.matteomigliore.pcmanager

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Debug
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
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
    private var started = false
    private var diagnosticsJob: Job? = null
    private var previousCpu: Pair<Long, Long>? = null
    private var previousNetwork: Triple<Long, Long, Long>? = null
    private val http = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        if (started) return START_STICKY
        started = true
        DeviceOwner.applyProtections(this) // se siamo Device Owner: blinda subito il telefono
        connect()
        // Lo snapshot leggero ogni 10 secondi mantiene correttamente lo stato online nel cloud.
        // Le statistiche d'uso, più costose, restano invece a cadenza di un minuto.
        scope.launch { while (isActive) { try { if (diagnosticsJob?.isActive != true) sendSnapshot(false) } catch (_: Exception) {}; delay(10_000) } }
        scope.launch { while (isActive) { try { sendUsage() } catch (_: Exception) {}; delay(60_000) } }
        // Aggiornamento automatico: come sul PC. Primo giro dopo un minuto (lascia salire la rete
        // all'avvio), poi ogni 6 ore: gli APK sono pochi MB e il controllo e' un file di testo.
        scope.launch {
            delay(60_000)
            while (isActive) { try { Updater.checkAndUpdate(this@AgentService) } catch (_: Exception) {}; delay(6 * 60 * 60_000L) }
        }
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
        if (last != null) return last
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 300_000, now)
            ?.maxByOrNull { it.lastTimeUsed }?.packageName
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
            } else if (o.optString("cmd") == "diagnostics") {
                startDiagnostics(
                    o.optInt("durationSeconds", 120).coerceIn(15, 300),
                    o.optLong("intervalMs", 2_000).coerceIn(1_000, 10_000)
                )
            }
        } catch (_: Exception) {}
    }

    /** Sessione temporanea: campiona più spesso senza lasciare un carico permanente sul telefono. */
    private fun startDiagnostics(durationSeconds: Int, intervalMs: Long) {
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            val until = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
            while (isActive && SystemClock.elapsedRealtime() < until) {
                try { sendSnapshot(true) } catch (_: Exception) {}
                delay(intervalMs)
            }
            try { sendSnapshot(false) } catch (_: Exception) {}
        }
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

    /** Stato Android reale, raccolto esclusivamente con API locali e senza privilegi root. */
    private fun sendSnapshot(diagnostic: Boolean) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val ramPct = if (mem.totalMem > 0) (100.0 * (mem.totalMem - mem.availMem) / mem.totalMem) else 0.0
        val proc = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val stat = StatFs(filesDir.absolutePath)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
        val traffic = networkRates()

        val temperatures = JSONArray()
        if (batteryTemp != null) temperatures.put(JSONObject()
            .put("hardware", "Android Battery").put("name", "Battery").put("value", batteryTemp))
        val data = JSONObject()
            .put("timestampUtc", java.time.Instant.now().toString())
            .put("temperatures", temperatures).put("fans", JSONArray()).put("loads", JSONArray())
            .put("admin", true).put("monitors", 1).put("battery", level)
            .put("deviceOwner", DeviceOwner.isOwner(this))
            .put("diagnostic", diagnostic)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("refreshRateHz", display?.mode?.refreshRate ?: 0f))
            .put("system", JSONObject()
                .put("ramPct", round1(ramPct))
                .put("ramUsedBytes", mem.totalMem - mem.availMem)
                .put("ramAvailableBytes", mem.availMem).put("ramTotalBytes", mem.totalMem)
                .put("lowMemory", mem.lowMemory).put("memoryThresholdBytes", mem.threshold)
                .put("appPssBytes", proc.totalPss.toLong() * 1024)
                .put("appPrivateDirtyBytes", proc.totalPrivateDirty.toLong() * 1024)
                .put("cpuPct", cpuUsage())
                .put("cpuCores", Runtime.getRuntime().availableProcessors())
                .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1)
                .put("powerSave", pm.isPowerSaveMode).put("charging", charging)
                .put("batteryTemperatureC", batteryTemp ?: JSONObject.NULL)
                .put("storageAvailableBytes", stat.availableBytes).put("storageTotalBytes", stat.totalBytes)
                .put("netDownKbs", traffic.first).put("netUpKbs", traffic.second)
                .put("uptimeMs", SystemClock.elapsedRealtime())
                .put("foregroundApp", currentForegroundApp() ?: ""))
        ws?.send(JSONObject().put("type", if (diagnostic) "diagnostic" else "snapshot").put("data", data).toString())
    }

    private fun round1(v: Double) = kotlin.math.round(v * 10.0) / 10.0

    /** Uso CPU complessivo da /proc/stat. Se HyperOS lo nega, il valore resta null. */
    private fun cpuUsage(): Any {
        return try {
            val p = java.io.File("/proc/stat").useLines { lines ->
                val v = lines.first().trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
                val idle = v.getOrElse(3) { 0L } + v.getOrElse(4) { 0L }
                val total = v.sum()
                total to idle
            }
            val old = previousCpu; previousCpu = p
            if (old == null || p.first <= old.first) JSONObject.NULL else {
                val total = p.first - old.first; val idle = p.second - old.second
                round1(100.0 * (total - idle).coerceAtLeast(0) / total.coerceAtLeast(1))
            }
        } catch (_: Exception) { JSONObject.NULL }
    }

    /** Traffico totale del telefono tra due campioni, espresso in KB/s. */
    private fun networkRates(): Pair<Double, Double> {
        val now = SystemClock.elapsedRealtime()
        val rx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val tx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        val old = previousNetwork; previousNetwork = Triple(now, rx, tx)
        if (old == null || now <= old.first) return 0.0 to 0.0
        val seconds = (now - old.first) / 1_000.0
        return round1((rx - old.second).coerceAtLeast(0) / 1024.0 / seconds) to
            round1((tx - old.third).coerceAtLeast(0) / 1024.0 / seconds)
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

    override fun onDestroy() { started = false; diagnosticsJob?.cancel(); scope.cancel(); ws?.close(1000, "bye"); super.onDestroy() }
}
