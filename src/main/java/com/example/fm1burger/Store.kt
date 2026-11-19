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

    // ---------------- Maker Bank categories (3.16) ----------------

    companion object {
        val CATS = arrayOf("Kick", "Snare & Clap", "Hats", "Toms & Cymbals", "FM & Synth", "Loops", "Voice", "Recorded", "Other")
        private val NOTE_END = Regex(" [A-G]#?-?\\d$")
        private val STAMP = Regex("_\\d{8}_\\d{6}")
    }

    /** The category a sound sits in: the one chosen by hand, else guessed from its name and tags. */
    fun categoryOf(name: String): String {
        tags.getProperty("cat." + name)?.let { if (it in CATS) return it }
        val base = name.removeSuffix(".wav").removeSuffix(".WAV")
        val n = (base + " " + getTags(name)).lowercase(Locale.ROOT)
        fun has(vararg w: String) = w.any { n.contains(it) }
        return when {
            has("kick", "808", "bassdrum", "bass drum") || n.startsWith("bd") -> "Kick"
            has("snare", "clap", "rim") -> "Snare & Clap"
            has("hat", "hihat", "hh") -> "Hats"
            has("tom", "crash", "china", "ride", "cymbal", "perc", "cowbell", "shaker", "conga", "bongo") -> "Toms & Cymbals"
            n.startsWith("fm ") || has(" fm ", "makerhip", "synth", "bass ", "pad", "lead") || NOTE_END.containsMatchIn(base) -> "FM & Synth"
            has("loop", "break", "resample", "bar") -> "Loops"
            has("voice", "vocal", "vox", "sing") -> "Voice"
            has("rec", "take", "kit_hit", "spoon", "mic", "fm1", "sampl") || STAMP.containsMatchIn(base) -> "Recorded"
            else -> "Other"
        }
    }

    fun setCategory(name: String, cat: String?) {
        if (cat == null) tags.remove("cat." + name) else tags.setProperty("cat." + name, cat)
        saveTags()
    }

    /** The sounds of one category ("All" or null: every sound), newest first. */
    fun listCat(cat: String?, query: String = ""): List<File> =
        list(query).filter { cat == null || cat == "All" || categoryOf(it.name) == cat }

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
        tags.getProperty("cat." + f.name)?.let {
            tags.remove("cat." + f.name)
            tags.setProperty("cat." + target.name, it)
        }
        saveTags()
        return target
    }

    fun delete(f: File) {
        f.delete()
        tags.remove(f.name)
        tags.remove("cat." + f.name)
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
