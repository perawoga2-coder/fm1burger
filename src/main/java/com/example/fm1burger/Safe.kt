package com.example.fm1burger

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Safe file writing: the new content goes to a temporary file first and only replaces
 * the real file when it is complete. If the app is killed or the battery dies in the middle
 * of a save, the old file is still there instead of a broken half file.
 */
object SafeIo {

    fun writeBytes(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            try {
                out.fd.sync()
            } catch (e: Exception) {
            }
        }
        if (!tmp.renameTo(f)) {
            // some file systems do not replace on rename
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw java.io.IOException("Cannot replace ${f.name}")
            }
        }
    }

    fun writeText(f: File, text: String) {
        writeBytes(f, text.toByteArray(Charsets.UTF_8))
    }

    /** Removes temporary files left behind by a save that was interrupted. */
    fun cleanTemps(dir: File) {
        try {
            dir.walkTopDown().maxDepth(2)
                .filter { it.isFile && it.name.endsWith(".tmp") }
                .forEach { it.delete() }
        } catch (e: Exception) {
        }
    }
}

/**
 * Catches a crash, writes what happened to crash.txt, then lets Android close the app as usual.
 * On the next start the app shows the report once, so a problem can be found and fixed.
 */
object CrashLog {

    private const val NAME = "crash.txt"

    fun install(ctx: Context) {
        val dir = ctx.applicationContext.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, NAME).writeText("Burger Studio crash at $stamp on thread ${th.name}\n\n$sw")
            } catch (e: Throwable) {
            }
            prev?.uncaughtException(th, ex)
        }
    }

    /** The last crash report, or null. Reading it removes it. */
    fun take(ctx: Context): String? {
        val f = File(ctx.filesDir, NAME)
        if (!f.exists()) return null
        return try {
            val s = f.readText()
            f.delete()
            s
        } catch (e: Exception) {
            null
        }
    }
}
