package com.matteomigliore.pcmanager

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Pairing + permessi. Incolli il token del dispositivo (creato dall'app web),
 * concedi "Accesso ai dati sull'utilizzo", e avvii l'agente.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sp = getSharedPreferences("pcm", Context.MODE_PRIVATE)
        val tokenInput = findViewById<EditText>(R.id.token)
        val status = findViewById<TextView>(R.id.status)
        tokenInput.setText(sp.getString("token", ""))

        findViewById<Button>(R.id.save).setOnClickListener {
            sp.edit().putString("token", tokenInput.text.toString().trim()).apply()
            Toast.makeText(this, "Token salvato", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.grant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.start).setOnClickListener {
            if (!hasUsageAccess()) { Toast.makeText(this, "Concedi prima l'accesso all'utilizzo", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java))
            status.text = "Agente avviato ✓"
        }
        findViewById<Button>(R.id.accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Attiva 'PC Manager — Blocco giochi'", Toast.LENGTH_LONG).show()
        }

        // Filtro siti: Android esige un consenso esplicito alla VPN, e puo' chiederlo SOLO
        // un'activity. Da qui in poi il servizio la accende e spegne da solo secondo le regole.
        findViewById<Button>(R.id.vpn).setOnClickListener {
            val consenso = android.net.VpnService.prepare(this)
            if (consenso != null) startActivityForResult(consenso, 7001)
            else { startService(Intent(this, SiteVpnService::class.java)); Toast.makeText(this, "Filtro siti attivo.", Toast.LENGTH_SHORT).show() }
        }

        findViewById<Button>(R.id.doApply).setOnClickListener {
            if (DeviceOwner.isOwner(this)) {
                DeviceOwner.applyProtections(this)
                Toast.makeText(this, "Blocco totale attivo ✓", Toast.LENGTH_SHORT).show()
                refreshDoStatus()
            } else showProvisioningHelp()
        }
        findViewById<Button>(R.id.doRelease).setOnClickListener {
            if (!DeviceOwner.isOwner(this)) { Toast.makeText(this, "L'app non è Device Owner.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle("Rimuovere il blocco totale?")
                .setMessage("L'agente tornerà disinstallabile e i giochi non saranno più bloccati in modo inaggirabile.")
                .setPositiveButton("Rimuovi") { _, _ -> DeviceOwner.release(this); refreshDoStatus(); Toast.makeText(this, "Device Owner rimosso.", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Annulla", null).show()
        }
        refreshDoStatus()
    }

    @Deprecated("startActivityForResult: qui serve, e' l'unico modo per il consenso VPN")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001 && resultCode == RESULT_OK) {
            startService(Intent(this, SiteVpnService::class.java))
            Toast.makeText(this, "Filtro siti attivo.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); refreshDoStatus() }

    private fun refreshDoStatus() {
        val owner = DeviceOwner.isOwner(this)
        val t = findViewById<TextView>(R.id.doStatus)
        t.text = "Blocco totale (Device Owner): " + if (owner) "ATTIVO ✓" else "non attivo"
        t.setTextColor(if (owner) 0xFF22C55E.toInt() else 0xFFF59E0B.toInt())
        findViewById<Button>(R.id.doApply).text = if (owner) "Aggiorna protezioni" else "Come attivare il blocco totale"
        findViewById<Button>(R.id.doRelease).isEnabled = owner
    }

    /** Istruzioni per il provisioning Device Owner (richiede telefono senza account + PC con adb). */
    private fun showProvisioningHelp() {
        val cmd = "adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver"
        AlertDialog.Builder(this)
            .setTitle("Attivare il blocco totale")
            .setMessage(
                "Serve una volta sola, da un PC con l'app appena installata e NESSUN account Google sul telefono " +
                "(telefono nuovo o resettato).\n\n1. Abilita Debug USB (Opzioni sviluppatore).\n" +
                "2. Collega il telefono al PC via USB.\n3. Esegui:\n\n$cmd\n\n" +
                "Da quel momento i giochi sono bloccati in modo inaggirabile e l'agente non è disinstallabile."
            )
            .setPositiveButton("Copia comando") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, "Comando copiato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
