package com.matteomigliore.pcmanager

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
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
