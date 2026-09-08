package com.matteomigliore.pcmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Esito dell'installazione dell'aggiornamento.
 *
 * Con Device Owner l'installazione è silenziosa e qui non arriva nulla da fare. Senza, Android
 * chiede conferma all'utente: il sistema ce lo comunica con STATUS_PENDING_USER_ACTION e ci
 * consegna l'Intent da mostrare. Senza questo passaggio l'aggiornamento resterebbe fermo in
 * silenzio sui telefoni non gestiti.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val conferma = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            conferma?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(conferma) }
            catch (_: Exception) { Toast.makeText(ctx, "Apri PC Manager e riprova l'aggiornamento.", Toast.LENGTH_LONG).show() }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(ctx, "PC Manager aggiornato.", Toast.LENGTH_LONG).show()
        } else {
            val dettaglio = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120)
            Toast.makeText(ctx, "Installazione non riuscita${if (dettaglio.isNullOrBlank()) "." else ": $dettaglio"}", Toast.LENGTH_LONG).show()
        }
    }
}
