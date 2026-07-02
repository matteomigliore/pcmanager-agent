package com.matteomigliore.pcmanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONObject
import java.util.Calendar

/** Regole gioco ricevute dal cloud (rules.json) + stato tempo del giorno. Condivise tra service. */
object GameRules {
    data class Rules(
        val gamesEnabled: Boolean,
        val windows: List<Pair<Int, Int>>, // minuti dall'inizio giornata [start,end)
        val maxMinutes: Int?,
        val tags: Set<String>,
    )

    fun load(ctx: Context): Rules {
        val s = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE).getString("rules", "{}") ?: "{}"
        return try {
            val o = JSONObject(s)
            val wins = mutableListOf<Pair<Int, Int>>()
            o.optJSONArray("windows")?.let { for (i in 0 until it.length()) {
                val w = it.getJSONObject(i); wins.add(toMin(w.optString("s")) to toMin(w.optString("e")))
            } }
            val tags = mutableSetOf<String>()
            o.optJSONArray("tags")?.let { for (i in 0 until it.length()) tags.add(it.getString(i).trim().lowercase()) }
            Rules(
                o.optBoolean("gamesEnabled", false), wins,
                if (o.isNull("maxMinutes")) null else o.optInt("maxMinutes"), tags
            )
        } catch (_: Exception) { Rules(false, emptyList(), null, emptySet()) }
    }

    private fun toMin(hhmm: String): Int {
        val p = hhmm.split(":"); return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** Un'app è "gioco" se il Play Store la marca CATEGORY_GAME, o se è nei tag manuali. */
    fun isGame(ctx: Context, pkg: String, tags: Set<String>): Boolean {
        return try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) return true
            val label = ctx.packageManager.getApplicationLabel(ai).toString().lowercase()
            tags.any { it == pkg.lowercase() || (it.isNotEmpty() && label.contains(it)) }
        } catch (_: Exception) { false }
    }

    fun isAllowedNow(ctx: Context, r: Rules): Boolean {
        val c = Calendar.getInstance(); val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val inWin = r.windows.isEmpty() || r.windows.any { now >= it.first && now < it.second }
        val under = r.maxMinutes == null || todaySeconds(ctx) < r.maxMinutes * 60
        return inWin && under
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun todaySeconds(ctx: Context): Long {
        val sp = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE)
        val raw = sp.getString("gametime", "") ?: ""
        val p = raw.split(":"); return if (p.size == 2 && p[0] == today()) p[1].toLongOrNull() ?: 0 else 0
    }
    fun addToday(ctx: Context, secs: Int) {
        val sp = ctx.getSharedPreferences("pcm", Context.MODE_PRIVATE)
        sp.edit().putString("gametime", "${today()}:${todaySeconds(ctx) + secs}").apply()
    }
}
