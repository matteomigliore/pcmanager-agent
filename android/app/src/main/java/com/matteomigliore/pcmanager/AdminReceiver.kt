package com.matteomigliore.pcmanager

import android.app.admin.DeviceAdminReceiver

/**
 * Device Admin necessario per il blocco "duro" (sospensione pacchetti) quando l'app è
 * Device Owner. Provisioning: `adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver`
 * (su un telefono senza account configurati). Senza Device Owner si usa il blocco morbido.
 */
class AdminReceiver : DeviceAdminReceiver()
