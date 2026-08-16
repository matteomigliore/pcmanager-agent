package com.matteomigliore.pcmanager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento automatico dell'agente telefono.
 *
 * Perché esiste: finora l'APK andava reinstallato a mano su OGNI telefono a ogni correzione,
 * quindi in pratica i telefoni restavano indietro. Il PC si aggiorna da solo da sempre: qui si
 * porta il telefono allo stesso livello.
 *
 * Due strade, scelte in base a com'è installato l'agente:
 *  · Device Owner  → installazione SILENZIOSA via PackageInstaller (nessun tocco sul telefono:
 *                    è il caso dei telefoni dei ragazzi, dove l'agente non si deve poter evitare).
 *  · installazione normale → si apre la richiesta di installazione di sistema, che l'utente conferma.
 *
 * Il controllo è volutamente banale (un file di testo con il numero di build): stessa logica del
 * PC, nessun servizio in più da tenere in piedi.
 */
object Updater {

    private const val VERSION_URL = "https://pc.matteomigliore.com/download/android-version.txt"
    private const val APK_URL = "https://pc.matteomigliore.com/download/PCAgentSetup.apk"

    /** Build installata: la stessa che il telefono comunica al cloud. */
    fun currentBuild(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    /**
     * Controlla e, se c'è una build più recente, scarica e installa.
     * Non lancia mai: un aggiornamento fallito non deve fermare il monitoraggio.
     */
    fun checkAndUpdate(ctx: Context) {
        try {
            val ultima = leggiTesto(VERSION_URL)?.trim()?.toIntOrNull() ?: return
            if (ultima <= currentBuild(ctx)) return
            val apk = scarica(ctx, APK_URL) ?: return
            installa(ctx, apk)
        } catch (_: Exception) { /* riprova al giro successivo */ }
    }

    private fun leggiTesto(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
        }
    } catch (_: Exception) { null }

    private fun scarica(ctx: Context, url: String): java.io.File? = try {
        val f = java.io.File(ctx.cacheDir, "agent-update.apk")
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = true
            inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
            disconnect()
        }
        if (f.length() > 100_000) f else null   // scarto risposte di errore travestite da APK
    } catch (_: Exception) { null }

    private fun installa(ctx: Context, apk: java.io.File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { s ->
            s.openWrite("agent", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                ctx, sessionId, Intent("$PKG_ACTION").setPackage(ctx.packageName), flags
            )
            s.commit(intent.intentSender)
        }
    }

    private const val PKG_ACTION = "com.matteomigliore.pcmanager.INSTALL_RESULT"
}
