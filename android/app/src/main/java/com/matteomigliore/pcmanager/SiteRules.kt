package com.matteomigliore.pcmanager

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Regole sui siti, le stesse che il PC applica: elenco di domini con fasce orarie facoltative,
 * in due modalità.
 *  · block  → i domini elencati sono vietati (sempre, o fuori dalle loro fasce)
 *  · allow  → SOLO i domini elencati sono ammessi, tutto il resto è vietato
 *
 * Finora `siteRules` e `siteMode` arrivavano al telefono e venivano buttate via: il blocco siti
 * valeva solo sul PC.
 */
object SiteRules {
    data class Regola(val dominio: String, val finestre: List<Pair<Int, Int>>)
    data class Regole(val modo: String, val regole: List<Regola>) {
        val attive: Boolean get() = regole.isNotEmpty()
    }

    fun load(ctx: Context): Regole {
        val s = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE).getString("rules", "{}") ?: "{}"
        return try {
            val o = JSONObject(s)
            val out = mutableListOf<Regola>()
            o.optJSONArray("siteRules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val dom = r.optString("domain").trim().lowercase().removePrefix("www.")
                    if (dom.isEmpty()) continue
                    val fin = mutableListOf<Pair<Int, Int>>()
                    r.optJSONArray("windows")?.let { w ->
                        for (j in 0 until w.length()) {
                            val x = w.getJSONObject(j); fin.add(toMin(x.optString("s")) to toMin(x.optString("e")))
                        }
                    }
                    out.add(Regola(dom, fin))
                }
            }
            Regole(o.optString("siteMode", "block").ifEmpty { "block" }, out)
        } catch (_: Exception) { Regole("block", emptyList()) }
    }

    private fun toMin(hhmm: String): Int {
        val p = hhmm.split(":"); return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun oraCorrente(): Int {
        val c = Calendar.getInstance(); return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Il dominio della regola vale anche per i sottodomini (youtube.com copre m.youtube.com). */
    private fun combacia(host: String, dominio: String): Boolean =
        host == dominio || host.endsWith(".$dominio")

    /**
     * Motivo del blocco, o null se il sito è permesso. Stessa logica del PC:
     *  · block: elencato senza fasce = sempre vietato; con fasce = permesso solo dentro
     *  · allow: non elencato = vietato; elencato con fasce = solo dentro quelle
     */
    fun motivoBlocco(ctx: Context, host0: String): String? {
        val r = load(ctx)
        if (!r.attive) return null
        val host = host0.lowercase().removePrefix("www.")
        val regola = r.regole.firstOrNull { combacia(host, it.dominio) }
        val ora = oraCorrente()
        val dentro = { f: List<Pair<Int, Int>> -> f.any { ora >= it.first && ora < it.second } }

        return if (r.modo == "allow") {
            when {
                regola == null -> "Questo sito non è tra quelli permessi dal tuo profilo."
                regola.finestre.isEmpty() -> null
                dentro(regola.finestre) -> null
                else -> "Consentito solo negli orari: " + testoFinestre(regola.finestre)
            }
        } else {
            when {
                regola == null -> null
                regola.finestre.isEmpty() -> "Questo sito è bloccato dal tuo profilo."
                dentro(regola.finestre) -> null
                else -> "Consentito solo negli orari: " + testoFinestre(regola.finestre)
            }
        }
    }

    private fun testoFinestre(f: List<Pair<Int, Int>>): String =
        f.joinToString(", ") { "${hhmm(it.first)}-${hhmm(it.second)}" }

    private fun hhmm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
}
