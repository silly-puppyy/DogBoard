package io.github.sillypuppyy.dogboard.perf

/**
 * The keyboard's own record of what happened to its languages (loads, failures, heals, switches, the layout check),
 * the last [SIZE] events, kept in memory for Advanced › Copy a report. Android's log buffer on a phone is small and
 * shared (256 KB on the user's Pixel, 2026-11-24): the keyboard's lines were gone long before a report read them.
 * Events, never what was typed. Also written to the Android log under DogBoardPerf.
 */
object EventLog {
    private const val SIZE = 200
    private val lines = ArrayDeque<String>()
    private val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)

    @Synchronized
    fun add(event: String) {
        if (lines.size >= SIZE) lines.removeFirst()
        lines.addLast("${time.format(java.util.Date())} $event")
        android.util.Log.i("DogBoardPerf", event)
    }

    @Synchronized
    fun all(): List<String> = lines.toList()
}
