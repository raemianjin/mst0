package com.mystt.app.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면(LogScreen)에서 볼 수 있는 인메모리 로그 + Logcat 동시 출력. */
object AppLogger {
    data class Line(val time: Long, val level: String, val tag: String, val msg: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    private const val MAX = 800
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun add(level: String, tag: String, msg: String) {
        val l = Line(System.currentTimeMillis(), level, tag, msg)
        val cur = _lines.value
        _lines.value = (cur + l).let { if (it.size > MAX) it.takeLast(MAX) else it }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("INFO", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add("WARN", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t); add("ERROR", tag, msg + (t?.let { " :: ${it.message}" } ?: ""))
    }
    fun fmtTime(t: Long): String = fmt.format(Date(t))
    fun clear() { _lines.value = emptyList() }
}
