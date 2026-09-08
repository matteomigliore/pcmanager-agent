package com.matteomigliore.pcmanager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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

    private const val VERSION_URL = "https://pc.miglioresoftware.com/download/android-version.txt"
    private const val APK_URL = "https://pc.miglioresoftware.com/download/PCAgentSetup.apk"

    /** Build installata: la stessa che il telefono comunica al cloud. */
    fun currentBuild(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    sealed class Esito {
        data class Aggiornato(val build: Int) : Esito()
        data class GiaAggiornato(val build: Int) : Esito()
        data class Fallito(val motivo: String) : Esito()

        fun messaggio(): String = when (this) {
            is Aggiornato -> "Aggiornamento build $build avviato. Sarà installato automaticamente quando Android lo consente."
            is GiaAggiornato -> "Nessun aggiornamento: hai già la build $build."
            is Fallito -> "Aggiornamento non riuscito: $motivo"
        }
    }

    /**
     * Controlla e, se c'è una build più recente, scarica e installa.
     * Non lancia mai: un aggiornamento fallito non deve fermare il monitoraggio.
     */
    fun checkAndUpdate(ctx: Context): Esito {
        return try {
            // Il parametro rende il controllo corretto anche su proxy o telefoni che hanno
            // conservato una vecchia risposta prima dell'introduzione di Cache-Control no-store.
            val ultima = leggiTesto("$VERSION_URL?t=${System.currentTimeMillis()}")?.trim()?.toIntOrNull()
                ?: return Esito.Fallito("non riesco a leggere la versione disponibile")
            val corrente = currentBuild(ctx)
            if (ultima <= corrente) return Esito.GiaAggiornato(corrente)
            val apk = scarica(ctx, "$APK_URL?t=${System.currentTimeMillis()}")
                ?: return Esito.Fallito("download dell'APK non riuscito")
            val pacchetto = infoApk(ctx, apk)
                ?: return Esito.Fallito("il file scaricato non è un APK valido")
            val buildApk = buildDi(pacchetto)
            if (pacchetto.packageName != ctx.packageName)
                return Esito.Fallito("il pacchetto pubblicato non appartiene a PC Manager")
            if (buildApk != ultima)
                return Esito.Fallito("server incoerente: dichiara build $ultima ma offre build $buildApk")
            installa(ctx, apk)
            Esito.Aggiornato(ultima)
        } catch (e: Exception) {
            Esito.Fallito(e.message?.take(120) ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun infoApk(ctx: Context, apk: java.io.File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33)
            ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0))
        else ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)

    private fun buildDi(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode

    private fun leggiTesto(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            useCaches = false
            if (responseCode !in 200..299) { disconnect(); return@run null }
            inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
        }
    } catch (_: Exception) { null }

    private fun scarica(ctx: Context, url: String): java.io.File? = try {
        val f = java.io.File(ctx.cacheDir, "agent-update.apk")
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = true
            useCaches = false
            if (responseCode !in 200..299) { disconnect(); return@run null }
            inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
            disconnect()
        }
        if (f.length() > 100_000) f else null   // scarto risposte di errore travestite da APK
    } catch (_: Exception) { null }

    private fun installa(ctx: Context, apk: java.io.File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        // Da Android 12 chiediamo esplicitamente l'installazione senza intervento. Android la
        // concede quando l'app sta aggiornando sé stessa; in caso contrario il receiver apre
        // comunque la conferma di sistema, quindi il percorso di ripiego resta sempre valido.
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { s ->
            s.openWrite("agent", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                // Deve essere un Intent esplicito. setPackage() limita soltanto la ricerca al
                // nostro pacchetto, ma InstallResultReceiver non ha (correttamente) un
                // intent-filter: con l'Intent implicito la richiesta di conferma andava persa.
                ctx, sessionId, Intent(ctx, InstallResultReceiver::class.java).setAction(PKG_ACTION), flags
            )
            s.commit(intent.intentSender)
        }
    }

    private const val PKG_ACTION = "com.matteomigliore.pcmanager.INSTALL_RESULT"
}
