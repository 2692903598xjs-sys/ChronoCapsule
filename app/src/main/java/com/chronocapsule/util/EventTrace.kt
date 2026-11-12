package com.chronocapsule.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量事件追踪：同时写 Log 和 filesDir/event_trace.txt。
 *
 * 为什么要落盘：部分国产 ROM（实测 iQOO / OriginOS）不把普通应用的
 * logcat 暴露给 adb shell，`adb logcat | grep 本应用` 永远是空的，
 * 真机上出问题只能靠这个文件（debug 包用 `adb shell run-as` 取）。
 *
 * 只记事件类型、包名、类名、决策结果，不记 OCR 文本和聊天内容。
 * 文件超过上限直接删掉重来，不做滚动。
 */
object EventTrace {

    private const val FILE_NAME = "event_trace.txt"
    private const val MAX_BYTES = 256 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${timeFormat.format(Date())} [$tag] $message\n")
        }
    }
}
