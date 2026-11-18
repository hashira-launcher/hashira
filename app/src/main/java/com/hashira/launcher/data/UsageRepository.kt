package com.hashira.launcher.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local counter of launches from hashira, for the "Frequent" section. No usage permissions, no network:
 * it only counts what is opened from the launcher itself. Counters "forget" gradually
 * (halved once past a certain total) so that recent usage wins.
 */
class UsageRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hashira_usage", Context.MODE_PRIVATE)
    private val _counts = MutableStateFlow(read())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    @Synchronized
    fun record(key: String) {
        val m = _counts.value.toMutableMap()
        m[key] = (m[key] ?: 0) + 1
        if (m.values.sum() > DECAY_AT) m.replaceAll { _, v -> v / 2 }
        m.entries.removeAll { it.value <= 0 }
        publish(m)
    }

    @Synchronized
    fun reset() = publish(emptyMap())

    private fun publish(m: Map<String, Int>) {
        _counts.value = m
        prefs.edit { putString("counts", m.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    }

    private fun read(): Map<String, Int> =
        (prefs.getString("counts", "") ?: "").split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            val n = if (i > 0) line.substring(i + 1).toIntOrNull() else null
            if (n == null) null else line.substring(0, i) to n
        }.toMap()

    private companion object {
        const val DECAY_AT = 300
    }
}
