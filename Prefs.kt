package com.loopguard.tiktok

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class EndAction { CLOSE, BACKGROUND }

data class HistoryEntry(
    val time: Long,
    val title: String,
    val loops: Int,
    val action: String,
    val reason: String
)

/** Settings + statistics, stored in SharedPreferences (read by both the UI and the service). */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("loopguard", Context.MODE_PRIVATE)

    companion object {
        /** Rough estimate: one broken doomscroll session ≈ 10 minutes not spent scrolling. */
        const val EST_MINUTES_PER_SESSION = 10
        private const val MAX_HISTORY = 200
    }

    // ---- Settings ----
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var loopLimitEnabled: Boolean
        get() = sp.getBoolean("loopLimitEnabled", true)
        set(v) = sp.edit().putBoolean("loopLimitEnabled", v).apply()

    var loopLimit: Int
        get() = sp.getInt("loopLimit", 3)
        set(v) = sp.edit().putInt("loopLimit", v.coerceAtLeast(1)).apply()

    var timeLimitEnabled: Boolean
        get() = sp.getBoolean("timeLimitEnabled", false)
        set(v) = sp.edit().putBoolean("timeLimitEnabled", v).apply()

    var timeLimitMin: Int
        get() = sp.getInt("timeLimitMin", 10)
        set(v) = sp.edit().putInt("timeLimitMin", v).apply()

    var action: EndAction
        get() = EndAction.valueOf(sp.getString("action", EndAction.CLOSE.name)!!)
        set(v) = sp.edit().putString("action", v.name).apply()

    var lockout: Boolean
        get() = sp.getBoolean("lockout", true)
        set(v) = sp.edit().putBoolean("lockout", v).apply()

    var scheduleAll: Boolean
        get() = sp.getBoolean("scheduleAll", true)
        set(v) = sp.edit().putBoolean("scheduleAll", v).apply()

    /** Minutes after midnight. */
    var startMin: Int
        get() = sp.getInt("startMin", 21 * 60)
        set(v) = sp.edit().putInt("startMin", v).apply()

    var endMin: Int
        get() = sp.getInt("endMin", 2 * 60)
        set(v) = sp.edit().putInt("endMin", v).apply()

    var halveLong: Boolean
        get() = sp.getBoolean("halveLong", false)
        set(v) = sp.edit().putBoolean("halveLong", v).apply()

    var chime: Boolean
        get() = sp.getBoolean("chime", true)
        set(v) = sp.edit().putBoolean("chime", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    // ---- Stats ----
    var loopsIntercepted: Int
        get() = sp.getInt("loopsIntercepted", 0)
        private set(v) = sp.edit().putInt("loopsIntercepted", v).apply()

    var sessionsBroken: Int
        get() = sp.getInt("sessionsBroken", 0)
        private set(v) = sp.edit().putInt("sessionsBroken", v).apply()

    val minutesSaved: Int get() = sessionsBroken * EST_MINUTES_PER_SESSION

    /** Consecutive days with at least one intervention. */
    var streak: Int
        get() = sp.getInt("streak", 0)
        private set(v) = sp.edit().putInt("streak", v).apply()

    private var lastDay: String
        get() = sp.getString("lastDay", "") ?: ""
        set(v) = sp.edit().putString("lastDay", v).apply()

    // ---- Schedule ----
    fun isActiveNow(): Boolean {
        if (scheduleAll) return true
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (startMin <= endMin) now in startMin until endMin
        else now >= startMin || now < endMin // window crosses midnight (e.g. 21:00–02:00)
    }

    // ---- History ----
    fun history(): List<HistoryEntry> {
        val arr = JSONArray(sp.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(
                o.getLong("t"), o.getString("title"), o.getInt("loops"),
                o.getString("action"), o.getString("reason")
            )
        }
    }

    fun interventionsToday(): Int = history().count { dayKey(it.time) == dayKey(System.currentTimeMillis()) }

    fun recordIntervention(title: String, loops: Int, action: String, reason: String) {
        val now = System.currentTimeMillis()
        val arr = JSONArray(sp.getString("history", "[]"))
        val list = ArrayList<JSONObject>()
        list.add(
            JSONObject().put("t", now).put("title", title).put("loops", loops)
                .put("action", action).put("reason", reason)
        )
        for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) list.add(arr.getJSONObject(i))
        sp.edit().putString("history", JSONArray(list).toString()).apply()

        loopsIntercepted += loops
        sessionsBroken += 1

        val today = dayKey(now)
        if (lastDay != today) {
            streak = if (lastDay == dayKey(now - 24L * 3600 * 1000)) streak + 1 else 1
            lastDay = today
        }
    }

    private fun dayKey(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}
