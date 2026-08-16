package com.matteomigliore.pcmanager

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Schermata unica, in ordine di importanza: a che punto siamo (stato), cosa manca (permessi),
 * come si collega (codice). Il menu laterale tiene le azioni occasionali.
 *
 * Il codice del dispositivo, una volta salvato, NON si vede più: e' la chiave che identifica il
 * telefono presso il cloud, mostrarla in chiaro a chiunque apra l'app non ha senso.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sp by lazy { getSharedPreferences("pcm", Context.MODE_PRIVATE) }
    private lateinit var drawer: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_PCManager)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        applicaFontAwesome(findViewById(android.R.id.content))

        findViewById<View>(R.id.menuBtn).setOnClickListener { drawer.openDrawer(findViewById<View>(R.id.drawerPanel)) }

        findViewById<View>(R.id.save).setOnClickListener {
            val t = findViewById<EditText>(R.id.token).text.toString().trim()
            if (t.length < 10) { msg("Codice non valido: ricontrolla di averlo incollato tutto."); return@setOnClickListener }
            sp.edit().putString("token", t).apply()
            findViewById<EditText>(R.id.token).setText("")
            avviaAgente()
            msg("Collegato. Il codice resta nascosto.")
            aggiorna()
        }
        findViewById<View>(R.id.cambiaToken).setOnClickListener { mostraInserimento(true) }

        findViewById<View>(R.id.rigaUso).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.rigaAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.rigaVpn).setOnClickListener {
            val consenso = android.net.VpnService.prepare(this)
            if (consenso != null) startActivityForResult(consenso, 7001)
            else { startService(Intent(this, SiteVpnService::class.java)); msg("Filtro siti attivo.") }
        }

        findViewById<View>(R.id.voceStato).setOnClickListener { drawer.closeDrawers(); aggiorna() }
        findViewById<View>(R.id.voceAggiorna).setOnClickListener {
            drawer.closeDrawers(); msg("Controllo aggiornamenti…")
            scope.launch { withContext(Dispatchers.IO) { Updater.checkAndUpdate(this@MainActivity) } }
        }
        findViewById<View>(R.id.voceProtezione).setOnClickListener { drawer.closeDrawers(); protezione() }
        findViewById<View>(R.id.voceInfo).setOnClickListener {
            drawer.closeDrawers()
            msg("PC Manager ${Updater.currentBuild(this)} · pc.matteomigliore.com")
        }
    }

    override fun onResume() { super.onResume(); aggiorna() }

    @Deprecated("startActivityForResult: e' l'unico modo per ottenere il consenso alla VPN")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001 && resultCode == RESULT_OK) {
            startService(Intent(this, SiteVpnService::class.java)); msg("Filtro siti attivo.")
        }
    }

    /* ───────── stato ───────── */

    private fun aggiorna() {
        val collegato = !(sp.getString("token", "") ?: "").isNullOrEmpty()
        mostraInserimento(!collegato)

        segna(R.id.icoUso, R.id.statoUso, hasUsageAccess(),
            "Attivo", "Tocca per concederlo: senza, non si vede l'uso delle app")
        segna(R.id.icoAcc, R.id.statoAcc, accessibilitaAttiva(),
            "Attivo", "Tocca per attivarlo: serve a bloccare giochi e siti")
        segna(R.id.icoVpn, R.id.statoVpn, SiteVpnService.attiva,
            "Attivo", "Si accende da solo quando il profilo ha siti da bloccare")

        findViewById<TextView>(R.id.versione).text = "versione ${nomeVersione()} · build ${Updater.currentBuild(this)}"
        findViewById<TextView>(R.id.statoChip).text = if (collegato) "collegato" else "non collegato"
        findViewById<TextView>(R.id.statoChip).setTextColor(getColor(if (collegato) R.color.ok else R.color.muted))

        val owner = DeviceOwner.isOwner(this)
        findViewById<TextView>(R.id.drawerStatoDo).text =
            if (owner) "Blocco totale attivo: l'app non è disinstallabile." else "Blocco totale non attivo."

        if (collegato) caricaScheda()
    }

    /** Nome e proprietario arrivano dal cloud: l'app da sola non sa a chi appartiene il telefono. */
    private fun caricaScheda() {
        val t = sp.getString("token", "") ?: return
        scope.launch {
            val o = withContext(Dispatchers.IO) {
                try {
                    val c = URL("https://pc.matteomigliore.com/api/agent/info?token=$t").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    if (c.responseCode != 200) null
                    else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) { null }
            } ?: return@launch
            val nome = o.optString("deviceName", "—")
            val prop = o.optString("ownerName", "")
            val ini = o.optString("initials", "?").ifEmpty { "?" }
            findViewById<TextView>(R.id.nomeDispositivo).text = nome
            findViewById<TextView>(R.id.proprietario).text = if (prop.isEmpty()) "" else "di $prop"
            findViewById<TextView>(R.id.drawerNome).text = if (prop.isEmpty()) "Collegato" else prop
            findViewById<TextView>(R.id.drawerDisp).text = nome
            findViewById<TextView>(R.id.avatarTop).text = ini
            findViewById<TextView>(R.id.avatarDrawer).text = ini
        }
    }

    private fun segna(icona: Int, testo: Int, ok: Boolean, seOk: String, seNo: String) {
        val i = findViewById<TextView>(icona)
        i.text = if (ok) "\uf058" else "\uf057"   // circle-check / circle-xmark
        i.setTextColor(getColor(if (ok) R.color.ok else R.color.bad))
        findViewById<TextView>(testo).text = if (ok) seOk else seNo
    }

    private fun mostraInserimento(mostra: Boolean) {
        findViewById<View>(R.id.tokenInserimento).visibility = if (mostra) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tokenSalvato).visibility = if (mostra) View.GONE else View.VISIBLE
    }

    private fun avviaAgente() {
        try {
            val i = Intent(this, AgentService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun protezione() {
        if (DeviceOwner.isOwner(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Blocco totale")
                .setMessage("È attivo: l'app non si può disinstallare e il filtro siti non si può spegnere.\n\nRimuoverlo serve solo per cedere il telefono.")
                .setPositiveButton("Aggiorna protezioni") { _, _ -> DeviceOwner.applyProtections(this); aggiorna() }
                .setNegativeButton("Rimuovi") { _, _ -> DeviceOwner.release(this); aggiorna() }
                .setNeutralButton("Chiudi", null).show()
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Blocco totale")
                .setMessage("Rende l'agente non disinstallabile e il filtro siti non disattivabile.\n\n" +
                    "Si attiva UNA sola volta, da PC, su un telefono senza account Google (nuovo o resettato):\n\n" +
                    "adb shell dpm set-device-owner com.matteomigliore.pcmanager/.AdminReceiver")
                .setPositiveButton("Ho capito", null).show()
        }
    }

    private fun nomeVersione(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun hasUsageAccess(): Boolean = try {
        val am = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val m = am.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        m == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun accessibilitaAttiva(): Boolean = try {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        s.contains(packageName)
    } catch (_: Exception) { false }

    private fun msg(t: String) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    /** Applica il font FontAwesome a tutte le TextView marcate con tag "fa". */
    private fun applicaFontAwesome(v: View) {
        val fa: Typeface by lazy { Typeface.createFromAsset(assets, "fonts/fa-solid-900.ttf") }
        fun percorri(x: View) {
            if (x is ViewGroup) { for (i in 0 until x.childCount) percorri(x.getChildAt(i)) }
            if (x is TextView && x.tag == "fa") x.typeface = fa
        }
        percorri(v)
        // le due icone di stato cambiano a runtime: vanno comunque in FontAwesome
        for (id in listOf(R.id.icoUso, R.id.icoAcc, R.id.icoVpn))
            findViewById<TextView>(id)?.typeface = Typeface.createFromAsset(assets, "fonts/fa-solid-900.ttf")
    }
}
