package com.martin.ruby.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One line in the on-screen log. */
data class LogEntry(val timeMillis: Long, val message: String)

/**
 * A short, in-memory log of what the background sensing is doing, shown on the main
 * screen so problems can be spotted without a computer. It keeps the newest
 * [MAX_ENTRIES] lines and is cleared when the app's process ends.
 */
object SensingLog {
    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(message: String) {
        val entry = LogEntry(System.currentTimeMillis(), message)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
