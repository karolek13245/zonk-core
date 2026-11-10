package com.example.aiapp

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Workload" = share of the last 60 seconds this phone spent answering requests. */
class HostStats(private val prefs: SharedPreferences) {
    companion object {
        @Volatile
        private var instance: HostStats? = null

        /** One counter for the whole app: the screen and the sharing service (which runs on other threads) use the same one. */
        fun shared(prefs: SharedPreferences): HostStats =
            instance ?: synchronized(this) { instance ?: HostStats(prefs).also { instance = it } }
    }

    var requestsToday by mutableIntStateOf(0)
        private set
    var lastReplySec by mutableStateOf<Float?>(null)
        private set
    var workload by mutableIntStateOf(0)
        private set

    private val intervals = ArrayList<LongArray>() // [start, end]; end == 0 while running

    init {
        requestsToday = if (prefs.getString("reqDate", "") == today()) prefs.getInt("reqCount", 0) else 0
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun start(): Long {
        val t = System.currentTimeMillis()
        intervals.add(longArrayOf(t, 0L))
        return t
    }

    @Synchronized
    fun finish(startedAt: Long) {
        val now = System.currentTimeMillis()
        intervals.firstOrNull { it[0] == startedAt && it[1] == 0L }?.set(1, now)
        lastReplySec = (now - startedAt) / 1000f
        val base = if (prefs.getString("reqDate", "") == today()) requestsToday else 0
        requestsToday = base + 1
        prefs.edit().putString("reqDate", today()).putInt("reqCount", requestsToday).apply()
        tick()
    }

    @Synchronized
    fun tick() {
        val now = System.currentTimeMillis()
        val from = now - 60_000
        intervals.removeAll { it[1] != 0L && it[1] < from }
        var busyMs = 0L
        for (iv in intervals) {
            val s = maxOf(iv[0], from)
            val e = if (iv[1] == 0L) now else iv[1]
            if (e > s) busyMs += e - s
        }
        workload = (busyMs * 100 / 60_000).toInt().coerceIn(0, 100)
    }
}
