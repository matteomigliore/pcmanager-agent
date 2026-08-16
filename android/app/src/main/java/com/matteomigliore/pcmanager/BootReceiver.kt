package com.matteomigliore.pcmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia l'agente al boot del telefono (se già accoppiato) e riarma la sveglia di controllo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Watchdog.assicuraAgenteAcceso(ctx)
        Watchdog.programma(ctx)
    }
}
