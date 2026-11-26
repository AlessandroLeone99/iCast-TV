package io.github.jqssun.airplay.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide log, including foreground service events when the Activity is absent. */
object AppLog {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val state = MutableStateFlow<List<String>>(emptyList())
    private var file: File? = null
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val LIMIT = 9999
    private val urls = Regex("(?i)(?:https?|mlhls)://[^\\s]+")

    private fun initialize(context: Context): File {
        file?.let { return it }
        val target = File(context.applicationContext.filesDir, "airplay_logs.txt")
        runCatching {
            if (target.exists()) target.useLines { input ->
                input.forEach {
                    if (lines.size == LIMIT) lines.removeFirst()
                    lines.addLast(it)
                }
            }
        }
        file = target
        state.value = lines.toList()
        return target
    }

    fun observe(context: Context): kotlinx.coroutines.flow.StateFlow<List<String>> = synchronized(lock) {
        initialize(context)
        state.asStateFlow()
    }

    fun add(context: Context, msg: String) = synchronized(lock) {
        val target = initialize(context)
        val line = "${date.format(Date())} ${msg.replace(urls, "[URL omitted]").replace('\n', ' ').replace('\r', ' ').take(1500)}"
        val trim = lines.size >= LIMIT
        if (trim) lines.removeFirst()
        lines.addLast(line)
        runCatching {
            if (trim) {
                // Compact in batches instead of rewriting the full log for every new line.
                repeat(1000) { if (lines.size > 1) lines.removeFirst() }
                target.writeText(lines.joinToString("\n", postfix = "\n"))
            } else {
                target.appendText(line + "\n")
            }
        }.onFailure { Log.e("iCastLog", "Could not persist diagnostics") }
        state.value = lines.toList()
    }

    fun clear(context: Context) = synchronized(lock) {
        val target = initialize(context)
        lines.clear()
        runCatching { target.writeText("") }
        state.value = emptyList()
    }
}
