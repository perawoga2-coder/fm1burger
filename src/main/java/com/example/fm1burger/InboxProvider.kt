package com.example.fm1burger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

/**
 * 3.36 → MIX: the Maker Hip INBOX. Boxes of stems (one WAV per track) that 16 Track reads and SPLITs into its tracks.
 * A box is a folder files/inbox/<id>/ with manifest.json and one WAV per track (MixSend.kt writes it, whole, then
 * renames it into place, so a half-written box is never seen).
 *
 * Authority com.makerhip.burger.inbox; reading and writing need com.makerhip.permission.INBOX (signature: only apps
 * signed with the same key). The paths:
 *   query    /boxes                     one row per box, newest first
 *   query    /boxes/<id>/tracks         one row per track, in order
 *   openFile /boxes/<id>/tracks/<idx>   the track's WAV, read only
 *   delete   /boxes/<id>                removes the box
 */
class InboxProvider : ContentProvider() {

    companion object {
        const val AUTH = "com.makerhip.burger.inbox"
        val BOX_COLS = arrayOf("_id", "title", "kind", "bpm", "bars", "meter", "rate", "created", "tracks")
        val TRACK_COLS = arrayOf("idx", "name", "color", "gain", "pan")
        private val ID_OK = Regex("[A-Za-z0-9_-]{1,40}")
        private val FILE_OK = Regex("[0-9]{1,3}\\.wav")

        fun root(ctx: Context): File = File(ctx.filesDir, "inbox")

        fun validId(id: String?): Boolean = id != null && ID_OK.matches(id)

        /** the folder of box [id], or null: a bad id, or no such box */
        fun boxDir(ctx: Context, id: String?): File? {
            if (!validId(id)) return null
            val r = root(ctx)
            val d = File(r, id!!)
            return try {
                if (d.isDirectory && d.canonicalFile.parentFile == r.canonicalFile) d else null
            } catch (e: Exception) { null }
        }

        fun manifest(dir: File): JSONObject? = try {
            val f = File(dir, "manifest.json")
            if (f.isFile) JSONObject(f.readText()) else null
        } catch (e: Exception) { null }

        fun notifyChanged(ctx: Context) {
            try { ctx.contentResolver.notifyChange(Uri.parse("content://$AUTH/boxes"), null) } catch (e: Exception) { }
        }
    }

    override fun onCreate(): Boolean = true

    /** the path as its parts, or null when it is not one of ours (only plain names: no "..", nothing empty) */
    private fun parts(uri: Uri): List<String>? {
        if (uri.authority?.endsWith(".inbox") != true) return null
        val seg = uri.pathSegments ?: return null
        if (seg.isEmpty() || seg[0] != "boxes") return null
        for (s in seg) if (s.isEmpty() || s == "." || s == ".." || s.contains('/') || s.contains('\\')) return null
        return seg
    }

    private fun cursor(all: Array<String>, projection: Array<out String>?, rows: List<Array<Any?>>): Cursor {
        val cols = if (projection == null || projection.isEmpty()) all else projection.filter { it in all }.toTypedArray()
        val c = MatrixCursor(cols, rows.size)
        for (r in rows) c.addRow(Array(cols.size) { r[all.indexOf(cols[it])] })
        return c
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val seg = parts(uri) ?: return null
        if (seg.size == 1) {
            val rows = ArrayList<Array<Any?>>()
            val dirs = try { root(ctx).listFiles() } catch (e: Exception) { null }
            if (dirs != null) for (d in dirs) {
                if (!d.isDirectory || !validId(d.name)) continue
                val m = manifest(d) ?: continue
                val tr = m.optJSONArray("tracks")
                rows.add(arrayOf<Any?>(d.name, m.optString("title", ""), m.optString("kind", "q"), m.optInt("bpm", 120),
                    m.optInt("bars", 0), m.optString("meter", "4/4"), m.optInt("rate", Cfg.SR), m.optLong("created", 0L),
                    tr?.length() ?: 0))
            }
            rows.sortByDescending { it[7] as Long }                 // newest first
            val c = cursor(BOX_COLS, projection, rows)
            try { c.setNotificationUri(ctx.contentResolver, Uri.parse("content://$AUTH/boxes")) } catch (e: Exception) { }
            return c
        }
        if (seg.size == 3 && seg[2] == "tracks") {
            val d = boxDir(ctx, seg[1]) ?: return null
            val tr = manifest(d)?.optJSONArray("tracks") ?: return null
            val rows = ArrayList<Array<Any?>>()
            for (i in 0 until tr.length()) {
                val t = tr.optJSONObject(i) ?: continue
                rows.add(arrayOf<Any?>(i, t.optString("name", "Track ${i + 1}"), t.optInt("color", -1),
                    t.optDouble("gain", 0.8).toFloat().coerceIn(0f, 1.2f), t.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f)))
            }
            return cursor(TRACK_COLS, projection, rows)
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("the inbox is read only")
        val ctx = context ?: throw FileNotFoundException()
        val seg = parts(uri)
        if (seg == null || seg.size != 4 || seg[2] != "tracks") throw FileNotFoundException(uri.toString())
        val d = boxDir(ctx, seg[1]) ?: throw FileNotFoundException(uri.toString())
        val idx = seg[3].toIntOrNull() ?: throw FileNotFoundException(uri.toString())
        val tr = manifest(d)?.optJSONArray("tracks") ?: throw FileNotFoundException(uri.toString())
        if (idx !in 0 until tr.length()) throw FileNotFoundException(uri.toString())
        val name = tr.optJSONObject(idx)?.optString("file", "") ?: ""
        if (!FILE_OK.matches(name)) throw FileNotFoundException(uri.toString())
        val f = File(d, name)
        // (the file is inside its box, whatever the manifest says)
        if (!f.isFile || f.canonicalFile.parentFile != d.canonicalFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        val seg = parts(uri) ?: return 0
        if (seg.size != 2) return 0
        val d = boxDir(ctx, seg[1]) ?: return 0
        val ok = try { d.deleteRecursively() } catch (e: Exception) { false }
        if (ok) notifyChanged(ctx)
        return if (ok) 1 else 0
    }

    override fun getType(uri: Uri): String? {
        val seg = parts(uri) ?: return null
        return when {
            seg.size == 1 -> "vnd.android.cursor.dir/vnd.makerhip.inbox.box"
            seg.size == 2 -> "vnd.android.cursor.item/vnd.makerhip.inbox.box"
            seg.size == 3 && seg[2] == "tracks" -> "vnd.android.cursor.dir/vnd.makerhip.inbox.track"
            seg.size == 4 && seg[2] == "tracks" -> "audio/x-wav"
            else -> null
        }
    }

    // the boxes are made by this app only (MixSend.kt)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
