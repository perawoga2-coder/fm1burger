package com.example.fm1burger

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.Properties

/** The sample library: WAV files in the app folder plus a small tag list. */
class Store(ctx: Context) {

    val dir = File(ctx.filesDir, "samples")
    private val tagFile = File(ctx.filesDir, "tags.properties")
    private val tags = Properties()

    init {
        dir.mkdirs()
        try {
            if (tagFile.exists()) tagFile.inputStream().use { tags.load(it) }
        } catch (e: Exception) {
        }
    }

    fun safeName(s: String): String =
        s.trim().replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "_").take(60)

    fun list(query: String = ""): List<File> {
        val q = query.trim().lowercase(Locale.ROOT)
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) == "wav" }
            .filter {
                q.isEmpty() ||
                    it.name.lowercase(Locale.ROOT).contains(q) ||
                    getTags(it.name).lowercase(Locale.ROOT).contains(q)
            }
            .sortedByDescending { it.lastModified() }
    }

    fun file(name: String): File = File(dir, name)

    fun uniqueFile(base: String): File {
        var b = safeName(base)
        if (b.isEmpty()) b = "Sample"
        var f = File(dir, "$b.wav")
        var n = 1
        while (f.exists()) {
            f = File(dir, "${b}_$n.wav")
            n++
        }
        return f
    }

    fun saveNew(base: String, data: FloatArray, rate: Int): File {
        val f = uniqueFile(base)
        Wav.write(f, data, rate)
        return f
    }

    fun overwrite(f: File, data: FloatArray, rate: Int) {
        Wav.write(f, data, rate)
    }

    /** Sample as the engine wants it: mono, 44.1 kHz. */
    fun loadEngine(name: String): FloatArray? {
        val f = file(name)
        if (!f.exists()) return null
        val p = Wav.read(f) ?: return null
        return Wav.resample(p.data, p.rate, Cfg.SR)
    }

    fun loadRaw(name: String): PcmData? {
        val f = file(name)
        if (!f.exists()) return null
        return Wav.read(f)
    }

    fun describe(f: File): String {
        val i = Wav.info(f) ?: return "Unsupported file"
        val ch = if (i.channels == 1) "mono" else "${i.channels} ch"
        return String.format(
            Locale.US, "%.1f kHz · %s · %d bit · %.1f s · %d KB",
            i.rate / 1000.0, ch, i.bits, i.seconds, f.length() / 1024
        )
    }

    // ---------------- tags ----------------

    fun getTags(name: String): String = tags.getProperty(name, "")

    fun setTags(name: String, t: String) {
        if (t.isBlank()) tags.remove(name) else tags.setProperty(name, t.trim())
        saveTags()
    }

    private fun saveTags() {
        try {
            tagFile.outputStream().use { tags.store(it, null) }
        } catch (e: Exception) {
        }
    }

    fun rename(f: File, newBase: String): File? {
        val n = safeName(newBase)
        if (n.isEmpty()) return null
        val target = File(dir, "$n.wav")
        if (target.exists()) return null
        if (!f.renameTo(target)) return null
        val t = getTags(f.name)
        tags.remove(f.name)
        if (t.isNotEmpty()) tags.setProperty(target.name, t)
        saveTags()
        return target
    }

    fun delete(f: File) {
        f.delete()
        tags.remove(f.name)
        saveTags()
    }

    // ---------------- export for the FM-1 ----------------

    /** Converts to mono 16 bit at the chosen rate. Optionally cuts the end to fit the budget. */
    fun fm1Bytes(p: PcmData, rate: Int, trim: Boolean): Pair<ByteArray, Boolean> {
        var d = Wav.resample(p.data, p.rate, rate)
        var cut = false
        val maxSamples = (Cfg.FM1_BUDGET - 44) / 2
        if (trim && d.size > maxSamples) {
            d = d.copyOf(maxSamples)
            cut = true
        }
        return Pair(Wav.toBytes(d, rate), cut)
    }

    fun fm1Size(p: PcmData, rate: Int): Long {
        val n = (p.data.size.toLong() * rate / p.rate)
        return 44L + n * 2L
    }
}
