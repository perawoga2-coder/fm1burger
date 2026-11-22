package com.example.fm1burger

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 3.25: the Maker Bank's own sounds, the same as the FM-1 firmware's (Burger Studio): every synth preset of its
 * engines (ANALOG, PHASE, LOFI, VOICE, TRIO, WHEEL, GRAIN, PHYS, NOISE, FM6, AMBIENT) rendered from the firmware's
 * own code, the DRUM engine's four kits piece by piece, and the SAMPLE engine's piano, flute and tenor sax
 * (Versilian Studios, CC0). Kept in the APK as lossless FLAC (assets/bank), written into the Maker Bank as WAV once
 * (a sound already there, or deleted by the user after that once, is left alone).
 */
object MakerBank {
    private const val VERSION = 1
    val kits = listOf("Studio Kit", "Deep Kit", "Tight Kit", "Perc Kit")
    /** a kit's 8 pieces in the order of the drum sequencer's first 8 rows */
    val pieces = listOf("Kick", "Snare", "Hat Closed", "Hat Open", "Clap", "Tom", "Rim", "Bell")

    fun fileName(kit: String, piece: String) = "$kit $piece.wav"

    /** Installs what is missing (once per bank version). Returns how many sounds were written, -1 when nothing to do. */
    fun install(ctx: Context, store: Store, force: Boolean = false): Int {
        val prefs = ctx.getSharedPreferences("makerbank", Context.MODE_PRIVATE)
        if (!force && prefs.getInt("v", 0) >= VERSION) return -1
        val names = ctx.assets.list("bank") ?: return 0
        var made = 0
        for (n in names) {
            if (!n.endsWith(".flac")) continue
            val out = File(store.dir, n.removeSuffix(".flac") + ".wav")
            if (out.exists()) continue
            val d = decode(ctx, "bank/$n") ?: continue
            Wav.write(out, d, Cfg.SR)
            made++
        }
        prefs.edit().putInt("v", VERSION).apply()
        return made
    }

    /** an asset (FLAC, mono or stereo, 44.1 kHz) -> mono floats */
    private fun decode(ctx: Context, asset: String): FloatArray? {
        val tmp = File(ctx.cacheDir, "bank_tmp.flac")
        try {
            ctx.assets.open(asset).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val ex = MediaExtractor()
            ex.setDataSource(tmp.absolutePath)
            var fmt: MediaFormat? = null
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ex.selectTrack(t); fmt = f; break }
            }
            val format = fmt ?: return null
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ArrayList<Float>(1 shl 17)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.remaining() >= ch) {
                        var s = 0
                        for (c in 0 until ch) s += sb.get().toInt()
                        out.add(s / (32768f * ch))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop()
            codec.release()
            ex.release()
            return out.toFloatArray()
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }
}
