package com.matteomigliore.pcmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Riaccende l'agente quando il telefono si avvia E quando l'app viene aggiornata.
 *
 * Il caso "aggiornamento" e' essenziale: installando un nuovo APK Android ferma i servizi
 * dell'app, e senza questo il telefono restava offline finche' qualcuno non rientrava nell'app
 * a ricollegare il token a mano.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Watchdog.assicuraAgenteAcceso(ctx)
                Watchdog.programma(ctx)
            }
        }
    }
}
