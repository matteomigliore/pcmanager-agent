package com.matteomigliore.pcmanager

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blocco siti sul telefono.
 *
 * Su Android un'app NON può leggere la cronologia del browser (sandbox), quindi l'indirizzo si
 * ricava dalla barra degli indirizzi tramite il servizio di Accessibilità che già usiamo per i
 * giochi: nessun permesso in più, nessuna VPN, nessun consumo di batteria aggiuntivo.
 *
 * Quando il sito non è ammesso si avvisa e si torna indietro (o alla Home): la pagina non resta
 * aperta. Gli stessi host servono anche a popolare "Siti visitati", che sul telefono finora era
 * sempre vuoto.
 *
 * Limite dichiarato: si copre ciò che passa dalla barra degli indirizzi dei browser. Le pagine
 * aperte DENTRO altre app (webview di social) non espongono un indirizzo leggibile.
 */
object SiteGuard {

    /** Browser noti e id della barra degli indirizzi. Il fallback cerca comunque un nodo con URL. */
    private val BARRE = listOf(
        "com.android.chrome:id/url_bar",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.opera.browser:id/url_field",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
    )

    private var ultimoHost = ""
    private var ultimoAvvisoMs = 0L
    private var ultimoInvioMs = 0L
    private var ultimaLetturaMs = 0L

    fun ePossibileBrowser(pkg: String): Boolean =
        pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser") ||
        pkg.contains("emmx") || pkg.contains("opera") || pkg.contains("duckduckgo") ||
        pkg.contains("samsung") && pkg.contains("sbrowser")

    /** Estrae l'host dalla barra degli indirizzi della finestra attiva. */
    fun hostCorrente(svc: AccessibilityService): String? {
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        try {
            for (id in BARRE) {
                val n = root.findAccessibilityNodeInfosByViewId(id).firstOrNull() ?: continue
                val t = n.text?.toString() ?: continue
                hostDa(t)?.let { return it }
            }
            return cerca(root, 0)
        } catch (_: Exception) { return null } finally { try { root.recycle() } catch (_: Exception) {} }
    }

    /** Ripiego per i browser non elencati: primo campo di testo che somiglia a un indirizzo. */
    private fun cerca(n: AccessibilityNodeInfo?, prof: Int): String? {
        if (n == null || prof > 6) return null
        val t = n.text?.toString()
        if (!t.isNullOrBlank() && (t.startsWith("http") || t.contains(".") && !t.contains(" ")))
            hostDa(t)?.let { return it }
        for (i in 0 until n.childCount) cerca(n.getChild(i), prof + 1)?.let { return it }
        return null
    }

    private fun hostDa(testo: String): String? {
        var s = testo.trim().lowercase()
        if (s.isEmpty() || s.contains(" ")) return null
        if (!s.startsWith("http")) s = "https://$s"
        return try {
            val h = java.net.URI(s).host?.removePrefix("www.")
            if (h.isNullOrEmpty() || !h.contains(".")) null else h
        } catch (_: Exception) { null }
    }

    /** Valuta il sito in primo piano: blocca se non ammesso, e registra la visita. */
    fun controlla(svc: AccessibilityService, pkg: String) {
        if (!ePossibileBrowser(pkg)) return
        // Gli eventi di contenuto arrivano a raffica durante il caricamento di una pagina:
        // senza freno si scandirebbe l'albero delle viste decine di volte al secondo.
        val adesso = SystemClock.elapsedRealtime()
        if (adesso - ultimaLetturaMs < 700) return
        ultimaLetturaMs = adesso
        val host = hostCorrente(svc) ?: return
        val ora = SystemClock.elapsedRealtime()
        if (host != ultimoHost || ora - ultimoInvioMs > 60_000) {
            ultimoHost = host; ultimoInvioMs = ora
            registraVisita(svc, host)
        }
        val motivo = SiteRules.motivoBlocco(svc, host) ?: return
        // Un avviso ogni 5s: senza freno, ogni evento della pagina ne genererebbe uno.
        if (ora - ultimoAvvisoMs > 5_000) {
            ultimoAvvisoMs = ora
            Toast.makeText(svc, "$host — $motivo", Toast.LENGTH_LONG).show()
        }
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        ultimoHost = ""
    }

    /** Accoda l'host: AgentService lo invia al cloud insieme agli altri (tipo "sites"). */
    private fun registraVisita(ctx: Context, host: String) {
        try {
            val sp = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE)
            val o = JSONObject(sp.getString("sitequeue", "{}") ?: "{}")
            o.put(host, o.optInt(host, 0) + 1)
            sp.edit().putString("sitequeue", o.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Svuota la coda e restituisce gli host da inviare. */
    fun svuotaCoda(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            val sp = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE)
            val o = JSONObject(sp.getString("sitequeue", "{}") ?: "{}")
            for (k in o.keys()) out.put(JSONObject().put("host", k).put("url", "").put("visits", o.optInt(k, 1)))
            sp.edit().remove("sitequeue").apply()
        } catch (_: Exception) {}
        return out
    }
}
