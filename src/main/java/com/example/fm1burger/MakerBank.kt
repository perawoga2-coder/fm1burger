package com.example.fm1burger

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Looper
import android.os.Process
import java.io.File
import java.nio.ByteOrder

/**
 * 3.25: the Maker Bank's own sounds, the same as the FM-1 firmware's (Burger Studio): every synth preset of its
 * engines (ANALOG, PHASE, LOFI, VOICE, TRIO, WHEEL, GRAIN, PHYS, NOISE, FM6, AMBIENT) rendered from the firmware's
 * own code, the DRUM engine's four kits piece by piece, and the SAMPLE engine's piano, flute and tenor sax
 * (Versilian Studios, CC0). Kept in the APK as lossless FLAC (assets/bank), written into the Maker Bank as WAV once
 * (a sound already there, or deleted by the user after that once, is left alone).
 *
 * The install is heavy (112 sounds to decode), so it must never disturb the audio: it runs at the lowest thread
 * priority, decodes into plain arrays (no boxing, so no garbage collector pauses), waits while music plays, and
 * rests a little between two sounds. Each sound is written under a temporary name and renamed when complete, so
 * closing the app midway leaves nothing broken and the next start only does what is left.
 */
object MakerBank {
    private const val VERSION = 1
    val kits = listOf("Studio Kit", "Deep Kit", "Tight Kit", "Perc Kit")
    /** a kit's 8 pieces in the order of the drum sequencer's first 8 rows */
    val pieces = listOf("Kick", "Snare", "Hat Closed", "Hat Open", "Clap", "Tom", "Rim", "Bell")

    /** one sound at a time, also when two installs run together (the start's and the pads' "kit → pads") */
    private val lock = Any()

    fun fileName(kit: String, piece: String) = "$kit $piece.wav"

    /**
     * Installs what is missing (once per bank version). Returns how many sounds were written, -1 when nothing to do.
     * Call it from a worker thread, never from the UI thread.
     *
     * @param busy     asked before each sound: while it answers true (music is playing) the install waits
     * @param progress called on the calling thread, first with (0, total) when there is work to do, then after each
     *                 sound; total counts only the sounds that were missing
     * @param cancel   asked before each sound: true stops the install (it goes on at the next start)
     */
    fun install(
        ctx: Context,
        store: Store,
        force: Boolean = false,
        busy: (() -> Boolean)? = null,
        progress: ((done: Int, total: Int) -> Unit)? = null,
        cancel: (() -> Boolean)? = null
    ): Int {
        val prefs = ctx.getSharedPreferences("makerbank", Context.MODE_PRIVATE)
        if (!force && prefs.getInt("v", 0) >= VERSION) return -1
        // never slow the UI thread down; a worker goes behind everything else (the audio thread first of all)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                // force: the user is waiting for a kit, so only "background"; else the lowest there is
                Process.setThreadPriority(
                    if (force) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_LOWEST
                )
            } catch (e: Exception) {
            }
        }
        val names = ctx.assets.list("bank") ?: return 0
        val todo = ArrayList<String>()
        for (n in names) {
            if (!n.endsWith(".flac")) continue
            if (!File(store.dir, n.removeSuffix(".flac") + ".wav").exists()) todo.add(n)
        }
        val total = todo.size
        var made = 0
        if (total > 0) {
            store.dir.mkdirs()
            progress?.invoke(0, total)
            var done = 0
            for (n in todo) {
                try {
                    // music first: wait as long as the engine plays
                    while (true) {
                        if (cancel != null && cancel()) return made
                        if (busy == null || !busy()) break
                        Thread.sleep(300)
                    }
                } catch (e: InterruptedException) {
                    return made
                }
                val out = File(store.dir, n.removeSuffix(".flac") + ".wav")
                synchronized(lock) {
                    if (!out.exists()) {
                        val d = decode(ctx, "bank/$n")
                        if (d != null) {
                            // "x.wav.part" is not listed by the library (it lists *.wav only); renamed when complete
                            val part = File(store.dir, out.name + ".part")
                            try {
                                part.delete()
                                Wav.write(part, d, Cfg.SR)
                                if (!out.exists()) {
                                    if (!part.renameTo(out)) throw java.io.IOException("Cannot write ${out.name}")
                                    made++
                                }
                            } finally {
                                if (part.exists()) part.delete()
                            }
                        }
                    }
                }
                done++
                progress?.invoke(done, total)
                try {
                    Thread.sleep(8) // a breath between two sounds: never a whole core for long
                } catch (e: InterruptedException) {
                    return made
                }
            }
        }
        prefs.edit().putInt("v", VERSION).apply()
        return made
    }

    /** an asset (FLAC, mono or stereo, 44.1 kHz) -> mono floats. No boxing: a plain array that doubles when full. */
    private fun decode(ctx: Context, asset: String): FloatArray? {
        // the asset may be compressed inside the APK, so the extractor reads a copy
        val tmp = File(ctx.cacheDir, "bank_tmp.flac")
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            ctx.assets.open(asset).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val x = MediaExtractor()
            ex = x
            x.setDataSource(tmp.absolutePath)
            var fmt: MediaFormat? = null
            for (t in 0 until x.trackCount) {
                val f = x.getTrackFormat(t)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { x.selectTrack(t); fmt = f; break }
            }
            val format = fmt ?: return null
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (ch < 1) ch = 1
            // the right size at once when the file tells its length (plus a little), else a start that doubles
            var cap = 1 shl 16
            try {
                if (format.containsKey(MediaFormat.KEY_DURATION) && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    val frames = format.getLong(MediaFormat.KEY_DURATION) * format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000000L
                    if (frames > 0L && frames < 20000000L) cap = frames.toInt() + 8192
                }
            } catch (e: Exception) {
            }
            var out = FloatArray(cap)
            var len = 0
            val chunk = ShortArray(8192)
            var held = 0 // shorts waiting in chunk (less than one frame after each pass)

            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = x.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, x.sampleTime, 0)
                            x.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nc = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (nc >= 1 && nc != ch) { ch = nc; held = 0 }
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val scale = 1f / (32768f * ch)
                        while (sb.hasRemaining()) {
                            val take = minOf(sb.remaining(), chunk.size - held)
                            sb.get(chunk, held, take)
                            held += take
                            val frames = held / ch
                            if (len + frames > out.size) {
                                var ncap = out.size
                                while (ncap < len + frames) ncap *= 2
                                out = out.copyOf(ncap)
                            }
                            var p = 0
                            for (f in 0 until frames) {
                                var s = 0
                                for (k in 0 until ch) s += chunk[p++].toInt()
                                out[len++] = s * scale
                            }
                            // keep the start of a frame cut in two by the buffer's end (never with whole frames)
                            val rest = held - p
                            if (rest > 0) System.arraycopy(chunk, p, chunk, 0, rest)
                            held = rest
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            return if (len == out.size) out else out.copyOf(len)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex?.release() } catch (e: Exception) { }
            tmp.delete()
        }
    }
}
