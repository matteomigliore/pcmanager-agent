package com.matteomigliore.pcmanager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Poteri Device Owner: protezioni anti-bypass + blocco "duro" dei giochi (sospensione pacchetti).
 * Attivo solo se l'app è stata provisionata come Device Owner
 * (`adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver` su telefono resettato).
 * Senza Device Owner tutte le funzioni sono no-op e resta il blocco "morbido" (Home) via Accessibilità.
 */
object DeviceOwner {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun isOwner(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (_: Exception) { false }

    // Restrizioni utente che chiudono le vie di fuga tipiche di un ragazzo.
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,        // no reset di fabbrica
        UserManager.DISALLOW_SAFE_BOOT,            // no avvio in modalità provvisoria
        UserManager.DISALLOW_DEBUGGING_FEATURES,   // no opzioni sviluppatore / adb
        UserManager.DISALLOW_ADD_USER,             // no nuovi profili utente
        UserManager.DISALLOW_UNINSTALL_APPS,       // no disinstallazione app
        UserManager.DISALLOW_APPS_CONTROL,         // no "forza arresto"/"cancella dati"/disabilita app
    )

    /** Applica tutte le protezioni che rendono l'agente inaggirabile. Idempotente. */
    fun applyProtections(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val admin = admin(ctx)
        try { dpm.setUninstallBlocked(admin, ctx.packageName, true) } catch (_: Exception) {}
        for (r in RESTRICTIONS) try { dpm.addUserRestriction(admin, r) } catch (_: Exception) {}
        // consenti come servizio di accessibilità SOLO il nostro (blocca tool di terzi che aggirino il blocco)
        try { dpm.setPermittedAccessibilityServices(admin, listOf(ctx.packageName)) } catch (_: Exception) {}
    }

    /** Sospende/riattiva pacchetti (blocco duro): l'app non si può avviare finché sospesa. */
    fun suspend(ctx: Context, pkgs: Array<String>, on: Boolean) {
        if (!isOwner(ctx) || pkgs.isEmpty()) return
        try { dpm(ctx).setPackagesSuspended(admin(ctx), pkgs, on) } catch (_: Exception) {}
    }

    /**
     * Rimuove tutte le protezioni e RILASCIA il Device Owner: da usare per disinstallare
     * l'app o cedere il telefono. Dopo questo l'agente torna gestibile/disinstallabile.
     */
    fun release(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val admin = admin(ctx)
        for (r in RESTRICTIONS) try { dpm.clearUserRestriction(admin, r) } catch (_: Exception) {}
        try { dpm.setUninstallBlocked(admin, ctx.packageName, false) } catch (_: Exception) {}
        try { dpm.setPermittedAccessibilityServices(admin, null) } catch (_: Exception) {}
        try { @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(ctx.packageName) } catch (_: Exception) {}
    }
}
