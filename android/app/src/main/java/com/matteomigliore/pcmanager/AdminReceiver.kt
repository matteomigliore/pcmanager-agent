package com.matteomigliore.pcmanager

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin necessario per il blocco "duro" (sospensione pacchetti + protezioni anti-bypass)
 * quando l'app è Device Owner. Provisioning:
 * `adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver`
 * (su un telefono senza account configurati). Senza Device Owner si usa il blocco morbido.
 */
class AdminReceiver : DeviceAdminReceiver() {
    // All'attivazione dell'admin (subito dopo il set-device-owner) blinda il telefono.
    override fun onEnabled(context: Context, intent: Intent) {
        DeviceOwner.applyProtections(context)
    }
    // Messaggio mostrato se qualcuno prova a disattivare l'amministratore.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disattivando togli il controllo genitori: i giochi non saranno più bloccati."
}
