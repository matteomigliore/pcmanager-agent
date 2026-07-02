package com.matteomigliore.pcmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Riavvia l'agente al boot del telefono (se già accoppiato). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val token = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE).getString("token", "") ?: ""
        if (token.isNotEmpty()) ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java))
    }
}
