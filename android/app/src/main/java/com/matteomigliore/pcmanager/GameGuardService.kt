package com.matteomigliore.pcmanager

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Blocco gioco via Accessibility: rileva l'app in primo piano, e se è un gioco non consentito
 * (fuori fascia oraria o oltre il tempo massimo) mostra un avviso e riporta alla Home.
 * Traccia il tempo di gioco del giorno. Funziona su qualunque app installata dal Play Store
 * (categoria gioco) o marcata a mano nelle Restrizioni.
 */
class GameGuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentGame: String? = null
    private var startedAt = 0L
    private val suspended = HashSet<String>()
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val admin by lazy { ComponentName(this, AdminReceiver::class.java) }
    private fun isOwner() = try { dpm.isDeviceOwnerApp(packageName) } catch (_: Exception) { false }

    private fun hardBlock(pkg: String) {
        try { dpm.setPackagesSuspended(admin, arrayOf(pkg), true); suspended.add(pkg) } catch (_: Exception) {}
    }
    private fun unblockAll() {
        if (suspended.isEmpty()) return
        try { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) } catch (_: Exception) {}
        suspended.clear()
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = GameRules.load(this@GameGuardService)
            if (r.gamesEnabled && GameRules.isAllowedNow(this@GameGuardService, r)) unblockAll() // riapre alla finestra
            currentGame?.let { enforce(it) }   // controlla anche il budget durante il gioco
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() { handler.postDelayed(tick, 30_000) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val r = GameRules.load(this)
        val isGame = r.gamesEnabled && GameRules.isGame(this, pkg, r.tags)
        if (isGame) {
            if (currentGame != pkg) { accrue(); currentGame = pkg; startedAt = SystemClock.elapsedRealtime() }
            enforce(pkg)
        } else {
            accrue(); currentGame = null
        }
    }

    private fun accrue() {
        if (currentGame != null && startedAt > 0) {
            val secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            if (secs > 0) GameRules.addToday(this, secs)
            startedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun enforce(pkg: String) {
        val r = GameRules.load(this)
        if (!r.gamesEnabled) return
        accrue()
        if (GameRules.isAllowedNow(this, r)) { unblockAll(); return }
        val over = r.maxMinutes != null && GameRules.todaySeconds(this) >= r.maxMinutes * 60L
        Toast.makeText(this, if (over) "Tempo di gioco esaurito per oggi." else "Giochi non consentiti in questo orario.", Toast.LENGTH_LONG).show()
        if (isOwner()) hardBlock(pkg)               // blocco duro: sospende l'app (inaggirabile)
        performGlobalAction(GLOBAL_ACTION_HOME)       // + torna alla Home (anche senza Device Owner)
        currentGame = null
    }

    override fun onInterrupt() {}
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
}
