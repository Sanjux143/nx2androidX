package com.nx.android.data

import android.content.Context
import java.io.File

/**
 * The app's own log: everything the port and the tools say also lands in a file (files/nx2a/log.txt), so it can
 * be read off the phone (or shared out of Settings) when something went wrong.
 */
object AppLog {

    private var file: File? = null
    private var writer: java.io.BufferedWriter? = null

    fun init(ctx: Context) {
        if (file != null) return
        init(File(ctx.filesDir, "nx2a/log.txt"))
    }

    /** The log file itself: the app's own directory in the app, a temp file in the tests */
    fun init(f: File) {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
        f.parentFile?.mkdirs()
        file = f
    }

    fun line(s: String) {
        val f = file ?: return
        try {
            val w = writer ?: java.io.BufferedWriter(java.io.FileWriter(f, true)).also { writer = it }
            w.write(s + "\n")
            w.flush()
        } catch (e: Exception) {
        }
    }

    /** The log file itself: the bundle takes it as it is */
    fun logFile(): File? = file

    /** The log's text (the tail, for sharing) */
    fun text(maxLines: Int = 400): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    fun clear() {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
        file?.delete()
    }
}
