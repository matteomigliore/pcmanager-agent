package com.matteomigliore.pcmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Sveglia periodica che RIACCENDE l'agente se il telefono lo ha ucciso.
 *
 * Serve perche' i telefoni aggressivi sul risparmio energetico (Xiaomi/HyperOS in testa)
 * terminano i servizi in background: senza questa sveglia l'agente resta spento finche'
 * qualcuno non riapre l'app a mano, e il dispositivo risulta offline per ore.
 *
 * Si usa una sveglia INESATTA (`setAndAllowWhileIdle`): passa anche in Doze e non richiede
 * il permesso "sveglie esatte" di Android 12+. Essendo one-shot, va riarmata ogni volta.
 */
object Watchdog {
    private const val INTERVALLO_MS = 15 * 60_000L // 15 minuti: compromesso tra reattivita' e batteria
    private const val REQUEST = 4711

    private fun intent(ctx: Context): PendingIntent {
        val i = Intent(ctx, WatchdogReceiver::class.java).setAction("com.matteomigliore.pcmanager.SVEGLIA")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQUEST, i, flags)
    }

    /** Programma il prossimo controllo (default 15 min; piu' breve se l'agente e' appena caduto). */
    fun programma(ctx: Context, traMs: Long = INTERVALLO_MS) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val quando = System.currentTimeMillis() + traMs
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intent(ctx))
            else am.set(AlarmManager.RTC_WAKEUP, quando, intent(ctx))
        } catch (_: Exception) { /* il telefono nega le sveglie: restano boot e apertura app */ }
    }

    /** Avvia l'agente se il telefono e' gia' collegato a un dispositivo. */
    fun assicuraAgenteAcceso(ctx: Context) {
        val token = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE).getString("token", "") ?: ""
        if (token.isEmpty()) return // non collegato: non c'e' niente da far girare
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java)) } catch (_: Exception) {}
    }
}

/**
 * Riceve la sveglia: riaccende l'agente (se e' gia' vivo, `onStartCommand` non fa nulla grazie al
 * flag `started`) e riarma subito la sveglia successiva.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Watchdog.assicuraAgenteAcceso(ctx)
        Watchdog.programma(ctx)
    }
}
