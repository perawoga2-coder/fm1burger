package com.example.fm1burger

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Song parts (cues) and the song arranger.
 *
 * A part is a snapshot of every pattern in the app: the 4 track step patterns, the drum
 * sequencer, the bassline and the analog synth sequencer, plus the mute buttons.
 * Samples, sounds and effects are shared by all parts.
 *
 * The pattern you see and edit on the other pages always belongs to the part that is
 * playing now. When you jump to another part, the current pattern is kept in its part
 * first, so nothing is lost.
 *
 * A part can also hold a live recording (the whole mix, recorded in time with the bars).
 * Then the part can play its patterns, its recording, or both.
 */
object PartNames {
    val NAMES = arrayOf("Intro", "Verse", "Pre-Chorus", "Hook", "Break", "Outro")
    val SHORT = arrayOf("INTRO", "VERSE", "PRE", "HOOK", "BREAK", "OUTRO")
}

class PartSnap {
    @Volatile var used = false

    // the live recording of this part: interleaved stereo 16 bit, a whole number of bars
    @Volatile var clip: ShortArray? = null
    @Volatile var clipBars = 0
    @Volatile var mode = 0   // 0 = patterns, 1 = recording, 2 = both

    fun hasClip(): Boolean = clip != null && clipBars > 0

    val tOn = Array(Cfg.TRACKS) { BooleanArray(Cfg.STEPS) }
    val tPitch = Array(Cfg.TRACKS) { IntArray(Cfg.STEPS) }
    val tLvl = Array(Cfg.TRACKS) { FloatArray(Cfg.STEPS) { 1f } }
    val tMute = BooleanArray(Cfg.TRACKS)

    val pOn = Array(Cfg.PADS) { BooleanArray(Cfg.STEPS) }
    val pLvl = Array(Cfg.PADS) { FloatArray(Cfg.STEPS) { 1f } }
    val pMute = BooleanArray(Cfg.PADS)

    val bOn = BooleanArray(Cfg.STEPS)
    val bNote = IntArray(Cfg.STEPS)
    val bAcc = BooleanArray(Cfg.STEPS)
    val bSlide = BooleanArray(Cfg.STEPS)
    var bMute = false

    val aOn = BooleanArray(16)
    val aNote = IntArray(16) { 60 }
    val aLen = IntArray(16) { 1 }
    var aEnabled = true
    var aMute = false

    /** Copy the live patterns of the engine into this part. */
    fun capture(e: Engine) {
        for (i in 0 until Cfg.TRACKS) {
            val t = e.tracks[i]
            for (k in 0 until Cfg.STEPS) {
                tOn[i][k] = t.steps[k].on
                tPitch[i][k] = t.steps[k].pitch
                tLvl[i][k] = t.steps[k].level
            }
            tMute[i] = t.mute
        }
        for (i in 0 until Cfg.PADS) {
            val p = e.pads[i]
            for (k in 0 until Cfg.STEPS) {
                pOn[i][k] = p.on[k]
                pLvl[i][k] = p.lvl[k]
            }
            pMute[i] = p.mute
        }
        val b = e.bass
        for (k in 0 until Cfg.STEPS) {
            bOn[k] = b.on[k]
            bNote[k] = b.note[k]
            bAcc[k] = b.accent[k]
            bSlide[k] = b.slide[k]
        }
        bMute = b.mute
        val s = e.analogSeq
        for (k in 0 until 16) {
            aOn[k] = s.on[k]
            aNote[k] = s.note[k]
            aLen[k] = s.len[k]
        }
        aEnabled = s.enabled
        aMute = e.analog.mute
        used = true
    }

    /** Put this part into the live patterns of the engine. */
    fun apply(e: Engine) {
        for (i in 0 until Cfg.TRACKS) {
            val t = e.tracks[i]
            for (k in 0 until Cfg.STEPS) {
                t.steps[k].on = tOn[i][k]
                t.steps[k].pitch = tPitch[i][k]
                t.steps[k].level = tLvl[i][k]
            }
            t.mute = tMute[i]
        }
        for (i in 0 until Cfg.PADS) {
            val p = e.pads[i]
            for (k in 0 until Cfg.STEPS) {
                p.on[k] = pOn[i][k]
                p.lvl[k] = pLvl[i][k]
            }
            p.mute = pMute[i]
        }
        val b = e.bass
        for (k in 0 until Cfg.STEPS) {
            b.on[k] = bOn[k]
            b.note[k] = bNote[k]
            b.accent[k] = bAcc[k]
            b.slide[k] = bSlide[k]
        }
        b.mute = bMute
        val s = e.analogSeq
        for (k in 0 until 16) {
            s.on[k] = aOn[k]
            s.note[k] = aNote[k]
            s.len[k] = aLen[k]
        }
        s.enabled = aEnabled
        e.analog.mute = aMute
    }

    fun copyFrom(o: PartSnap) {
        for (i in 0 until Cfg.TRACKS) {
            o.tOn[i].copyInto(tOn[i])
            o.tPitch[i].copyInto(tPitch[i])
            o.tLvl[i].copyInto(tLvl[i])
        }
        o.tMute.copyInto(tMute)
        for (i in 0 until Cfg.PADS) {
            o.pOn[i].copyInto(pOn[i])
            o.pLvl[i].copyInto(pLvl[i])
        }
        o.pMute.copyInto(pMute)
        o.bOn.copyInto(bOn)
        o.bNote.copyInto(bNote)
        o.bAcc.copyInto(bAcc)
        o.bSlide.copyInto(bSlide)
        bMute = o.bMute
        o.aOn.copyInto(aOn)
        o.aNote.copyInto(aNote)
        o.aLen.copyInto(aLen)
        aEnabled = o.aEnabled
        aMute = o.aMute
        used = o.used
    }

    /** An empty pattern: every step off, nothing muted. */
    fun blank() {
        for (i in 0 until Cfg.TRACKS) {
            tOn[i].fill(false)
            tPitch[i].fill(0)
            tLvl[i].fill(1f)
        }
        tMute.fill(false)
        for (i in 0 until Cfg.PADS) {
            pOn[i].fill(false)
            pLvl[i].fill(1f)
        }
        pMute.fill(false)
        bOn.fill(false)
        bNote.fill(0)
        bAcc.fill(false)
        bSlide.fill(false)
        bMute = false
        aOn.fill(false)
        aNote.fill(60)
        aLen.fill(1)
        aEnabled = true
        aMute = false
        used = true
    }

    /** How many steps are on in this part (all instruments together). */
    fun count(): Int {
        var n = 0
        for (r in tOn) for (x in r) if (x) n++
        for (r in pOn) for (x in r) if (x) n++
        for (x in bOn) if (x) n++
        for (x in aOn) if (x) n++
        return n
    }

    // ---------------- JSON ----------------

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("used", used)
        o.put("mode", mode)
        o.put("clipBars", clipBars)
        o.put("tOn", grid(tOn))
        o.put("tPitch", gridI(tPitch))
        o.put("tLvl", gridF(tLvl))
        o.put("tMute", arrB(tMute))
        o.put("pOn", grid(pOn))
        o.put("pLvl", gridF(pLvl))
        o.put("pMute", arrB(pMute))
        o.put("bOn", arrB(bOn))
        o.put("bNote", arrI(bNote))
        o.put("bAcc", arrB(bAcc))
        o.put("bSlide", arrB(bSlide))
        o.put("bMute", bMute)
        o.put("aOn", arrB(aOn))
        o.put("aNote", arrI(aNote))
        o.put("aLen", arrI(aLen))
        o.put("aEnabled", aEnabled)
        o.put("aMute", aMute)
        return o
    }

    fun fromJson(o: JSONObject) {
        used = o.optBoolean("used", false)
        mode = o.optInt("mode", 0).coerceIn(0, 2)
        clipBars = o.optInt("clipBars", 0).coerceAtLeast(0)
        readGrid(o.optJSONArray("tOn"), tOn)
        readGridI(o.optJSONArray("tPitch"), tPitch, 0)
        readGridF(o.optJSONArray("tLvl"), tLvl)
        readB(o.optJSONArray("tMute"), tMute)
        readGrid(o.optJSONArray("pOn"), pOn)
        readGridF(o.optJSONArray("pLvl"), pLvl)
        readB(o.optJSONArray("pMute"), pMute)
        readB(o.optJSONArray("bOn"), bOn)
        readI(o.optJSONArray("bNote"), bNote, 0)
        readB(o.optJSONArray("bAcc"), bAcc)
        readB(o.optJSONArray("bSlide"), bSlide)
        bMute = o.optBoolean("bMute", false)
        readB(o.optJSONArray("aOn"), aOn)
        readI(o.optJSONArray("aNote"), aNote, 60)
        readI(o.optJSONArray("aLen"), aLen, 1)
        aEnabled = o.optBoolean("aEnabled", true)
        aMute = o.optBoolean("aMute", false)
    }

    private fun arrB(a: BooleanArray): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x)
        return j
    }

    private fun arrI(a: IntArray): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x)
        return j
    }

    private fun arrF(a: FloatArray): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x.toDouble())
        return j
    }

    private fun grid(g: Array<BooleanArray>): JSONArray {
        val j = JSONArray()
        for (r in g) j.put(arrB(r))
        return j
    }

    private fun gridI(g: Array<IntArray>): JSONArray {
        val j = JSONArray()
        for (r in g) j.put(arrI(r))
        return j
    }

    private fun gridF(g: Array<FloatArray>): JSONArray {
        val j = JSONArray()
        for (r in g) j.put(arrF(r))
        return j
    }

    private fun readB(j: JSONArray?, a: BooleanArray) {
        if (j == null) return
        for (k in 0 until minOf(j.length(), a.size)) a[k] = j.optBoolean(k, false)
    }

    private fun readI(j: JSONArray?, a: IntArray, def: Int) {
        if (j == null) return
        for (k in 0 until minOf(j.length(), a.size)) a[k] = j.optInt(k, def)
    }

    private fun readF(j: JSONArray?, a: FloatArray) {
        if (j == null) return
        for (k in 0 until minOf(j.length(), a.size)) a[k] = j.optDouble(k, 1.0).toFloat()
    }

    private fun readGrid(j: JSONArray?, g: Array<BooleanArray>) {
        if (j == null) return
        for (i in 0 until minOf(j.length(), g.size)) readB(j.optJSONArray(i), g[i])
    }

    private fun readGridI(j: JSONArray?, g: Array<IntArray>, def: Int) {
        if (j == null) return
        for (i in 0 until minOf(j.length(), g.size)) readI(j.optJSONArray(i), g[i], def)
    }

    private fun readGridF(j: JSONArray?, g: Array<FloatArray>) {
        if (j == null) return
        for (i in 0 until minOf(j.length(), g.size)) readF(j.optJSONArray(i), g[i])
    }
}

/**
 * The 6 cue parts and the song list. The audio thread does the actual switching,
 * always on the first step of a bar (or right away when "jump now" is chosen),
 * so the music never stumbles.
 */
class PartBank(private val e: Engine) {

    companion object {
        const val N = 6
        const val MAX_SONG = 64
        const val MAX_BARS = 32
        const val MAX_REC_BARS = 64
        val MODE_NAMES = arrayOf("patterns", "recording", "patterns + recording")
    }

    val parts = Array(N) { PartSnap() }

    // cue slots 1-6 at the top of the app: which part each slot plays
    val slot = IntArray(N) { it }

    // playback of the recordings
    @Volatile var clipVol = 0.9f
    @Volatile var clipMute = false
    var outL = 0f
    var outR = 0f
    private var clipData: ShortArray? = null
    private var clipPos = 0
    private var barInPart = 0
    private var partChanged = false

    // live recording of a part (the whole mix, from the start of a bar to the end of a bar)
    @Volatile var recState = 0        // 0 idle, 1 waiting for bar 1, 2 recording, 4 finished
    @Volatile var recStopWanted = false
    @Volatile var recBars = 0
    @Volatile var recCancelled = false
    @Volatile var recFrames = 0L
    private var recStartAt = -1
    private var recStopAt = -1
    private var recTrim = -1L
    private val recChunks = ConcurrentLinkedQueue<ShortArray>()

    // CUE LAYERS: each cue button switches the recording of its part on or off, on its own.
    // Several can play together, each looping its own length, in time with the bars.
    val layerOn = BooleanArray(N)
    @Volatile var layerVersion = 0
    private val layerWant = IntArray(N)        // 0 nothing, 1 start at the next bar, 2 stop at the next bar
    private val layerData = arrayOfNulls<ShortArray>(N)
    private val layerPos = IntArray(N)
    private val layerBar = IntArray(N)

    /** UI thread: switch the recording of part i on or off (it starts / stops at the next bar). */
    fun toggleLayer(i: Int): Boolean {
        if (i !in 0 until N) return false
        if (layerOn[i]) {
            layerOn[i] = false
            layerWant[i] = 2
        } else {
            if (!parts[i].hasClip()) return false
            layerOn[i] = true
            layerWant[i] = 1
        }
        layerVersion++
        return true
    }

    /** True while the cue of part i waits for the next bar to start or stop. */
    fun layerPending(i: Int): Boolean = i in 0 until N && layerWant[i] != 0

    @Volatile var cur = 0
    @Volatile var cueReq = -1          // part waiting to start at the next bar
    @Volatile var jumpNow = false      // true = switch on the next step instead of the next bar
    @Volatile var version = 0          // goes up every time the live pattern changes part

    // song arranger
    val songPart = IntArray(MAX_SONG)
    val songBars = IntArray(MAX_SONG) { 4 }
    @Volatile var songLen = 0
    @Volatile var songOn = false
    @Volatile var songLoop = false
    @Volatile var songIdx = 0
    @Volatile var songBar = 0          // bar inside the current song entry (0 based)
    @Volatile var songEnded = false

    /** Ask to jump to part i (UI thread). */
    fun cue(i: Int) {
        if (i in 0 until N) cueReq = i
    }

    /** Audio thread: switch the live pattern to part i, keeping the current pattern first. */
    fun switchTo(i: Int) {
        if (i !in 0 until N) return
        if (i == cur) return
        parts[cur].capture(e)
        val p = parts[i]
        if (!p.used) p.copyFrom(parts[cur])
        p.apply(e)
        cur = i
        partChanged = true
        version++
    }

    /** Audio thread: true when the patterns must stay quiet because the part plays its recording only. */
    fun silentNow(): Boolean {
        val p = parts[cur]
        // "recording only" keeps the patterns quiet only while that recording really plays
        return p.mode == 1 && p.hasClip() && (songOn || layerOn[cur])
    }

    private fun barFrames(bpm: Double): Double = Cfg.SR * 60.0 * 4.0 / bpm

    /** Audio thread, at the first step of every bar (also the very first bar). [f] = frame in this block. */
    fun barStart(f: Int, first: Boolean) {
        // the cue layers: start / stop on the bar, restart each loop when its length is over
        for (i in 0 until N) {
            when (layerWant[i]) {
                1 -> {
                    layerWant[i] = 0
                    layerBar[i] = 0
                    layerData[i] = parts[i].clip
                    layerPos[i] = 0
                }
                2 -> {
                    layerWant[i] = 0
                    layerData[i] = null
                }
                else -> if (layerOn[i] && !first) {
                    val bars = parts[i].clipBars
                    layerBar[i]++
                    if (bars > 0 && layerBar[i] % bars == 0) {
                        layerData[i] = parts[i].clip
                        layerPos[i] = 0
                    }
                }
            }
            if (first && layerOn[i]) {
                layerData[i] = parts[i].clip
                layerPos[i] = 0
                layerBar[i] = 0
                layerWant[i] = 0
            }
        }
        if (first || partChanged) barInPart = 0 else barInPart++
        partChanged = false
        val p = parts[cur]
        val c = p.clip
        // the part plays its own recording only in Song mode; otherwise the cue switches decide
        if (songOn && c != null && p.clipBars > 0 && p.mode != 0) {
            if (barInPart % p.clipBars == 0) {
                clipData = c
                clipPos = 0
            }
        } else {
            clipData = null
        }
        // live recording
        if (recState == 1) {
            recState = 2
            recStartAt = f
            recBars = 0
            recFrames = 0L
        } else if (recState == 2 && !first) {
            recBars++
            if (recStopWanted || recBars >= MAX_REC_BARS) recStopAt = f
        }
    }

    /** After a jump in the middle of a bar: start the recording of the new part at the same place. */
    private fun clipFromStep(s: Int, bpm: Double) {
        partChanged = false
        barInPart = 0
        val p = parts[cur]
        val c = p.clip
        if (songOn && c != null && p.clipBars > 0 && p.mode != 0) {
            clipData = c
            clipPos = (s * barFrames(bpm) / Cfg.STEPS).toInt()
        } else {
            clipData = null
        }
    }

    /** Audio thread, every frame: the recording of the current part. Result in outL / outR. */
    fun renderClip() {
        val c = clipData
        if (c == null) {
            outL = 0f
            outR = 0f
            return
        }
        val i = clipPos * 2
        if (i + 1 >= c.size) {
            clipData = null
            outL = 0f
            outR = 0f
            return
        }
        val g = if (clipMute) 0f else clipVol / 32768f
        outL = c[i] * g
        outR = c[i + 1] * g
        clipPos++
    }

    /** Audio thread, every frame: all the cue layers that are on, added to outL / outR. */
    fun renderLayers() {
        val g = if (clipMute) 0f else clipVol / 32768f
        for (k in 0 until N) {
            val d = layerData[k] ?: continue
            // the part already plays its own recording: do not play it twice
            if (k == cur && clipData === d) continue
            val i = layerPos[k] * 2
            if (i + 1 >= d.size) {
                layerData[k] = null
                continue
            }
            outL += d[i] * g
            outR += d[i + 1] * g
            layerPos[k]++
        }
    }

    /** Audio thread, after every block: keeps the recorded part of the block. */
    fun feedRec(out: ShortArray) {
        if (recState != 2) return
        val n = out.size / 2
        val from = if (recStartAt >= 0) recStartAt else 0
        val to = if (recStopAt >= 0) recStopAt else n
        recStartAt = -1
        if (to > from) {
            recChunks.add(out.copyOfRange(from * 2, to * 2))
            recFrames += (to - from).toLong()
        }
        if (recStopAt >= 0) {
            recStopAt = -1
            recTrim = -1L
            recState = 4
        }
    }

    /** Audio thread: the transport stopped. */
    fun onTransportStop(bpm: Double) {
        clipData = null
        for (i in 0 until N) {
            layerData[i] = null
            if (layerWant[i] == 2) layerWant[i] = 0
            if (layerOn[i]) layerWant[i] = 1      // start again from the top at the next Play
        }
        if (recState == 1) {
            recState = 0
        } else if (recState == 2) {
            if (recBars >= 1) {
                recTrim = (recBars * barFrames(bpm)).toLong()
                recStartAt = -1
                recStopAt = -1
                recState = 4
            } else {
                recState = 0
                recChunks.clear()
                recCancelled = true
            }
        }
    }

    /** UI: arm the recording. It starts at the next bar 1. */
    fun recArm() {
        if (recState != 0) return
        recChunks.clear()
        recFrames = 0L
        recBars = 0
        recStopWanted = false
        recCancelled = false
        recTrim = -1L
        recState = 1
    }

    /** UI: stop at the end of the bar that is playing now (or cancel if it has not started). */
    fun recStop() {
        if (recState == 1) recState = 0 else if (recState == 2) recStopWanted = true
    }

    /** UI: take the finished recording. Returns the audio and its number of bars, or null. */
    fun collectRec(): Pair<ShortArray, Int>? {
        if (recState != 4) return null
        val bars = recBars
        var total = 0L
        for (c in recChunks) total += c.size / 2
        if (recTrim in 1 until total) total = recTrim
        val outA = ShortArray((total * 2).toInt())
        var j = 0
        for (c in recChunks) {
            val n = minOf(c.size, outA.size - j)
            if (n <= 0) break
            System.arraycopy(c, 0, outA, j, n)
            j += n
        }
        recChunks.clear()
        recState = 0
        if (bars < 1 || total <= 0L) return null
        return Pair(outA, bars)
    }

    // ---------------- recordings on disk ----------------

    fun clipFile(dir: File, i: Int): File = File(File(dir, "parts"), "part_${i + 1}.wav")

    /** UI: put a recording into part i and write it to disk in the background. */
    fun setClip(dir: File, i: Int, data: ShortArray?, bars: Int) {
        val p = parts[i]
        if (data == null || bars < 1) {
            p.clip = null
            p.clipBars = 0
            p.mode = 0
            try {
                clipFile(dir, i).delete()
            } catch (ex: Exception) {
            }
            return
        }
        if (!p.used) {
            if (i == cur) p.capture(e) else p.blank()
        }
        p.clipBars = bars
        p.clip = data
        if (p.mode == 0) p.mode = 1
        val f = clipFile(dir, i)
        Thread { writeWav(f, data) }.start()
    }

    private fun writeWav(f: File, d: ShortArray) {
        try {
            f.parentFile?.mkdirs()
            val dataLen = d.size * 2
            val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray())
            b.putInt(36 + dataLen)
            b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray())
            b.putInt(16)
            b.putShort(1.toShort())
            b.putShort(2.toShort())
            b.putInt(Cfg.SR)
            b.putInt(Cfg.SR * 4)
            b.putShort(4.toShort())
            b.putShort(16.toShort())
            b.put("data".toByteArray())
            b.putInt(dataLen)
            for (x in d) b.putShort(x)
            SafeIo.writeBytes(f, b.array())
        } catch (ex: Exception) {
        }
    }

    private fun readWav(f: File): ShortArray? {
        return try {
            if (!f.exists() || f.length() <= 44L) return null
            val raf = RandomAccessFile(f, "r")
            val bytes = ByteArray((f.length() - 44L).toInt())
            raf.seek(44L)
            raf.readFully(bytes)
            raf.close()
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val out = ShortArray(sb.remaining())
            sb.get(out)
            out
        } catch (ex: Exception) {
            null
        }
    }

    /** Loads the recordings of all parts in the background (at start-up). */
    fun loadClips(dir: File) {
        Thread {
            for (i in 0 until N) {
                val p = parts[i]
                if (p.clipBars <= 0) continue
                val d = readWav(clipFile(dir, i))
                if (d == null) {
                    p.clipBars = 0
                    p.mode = 0
                } else {
                    p.clip = d
                }
            }
            version++
        }.start()
    }

    /** Audio thread, at start: begin the song from its first entry. */
    fun songStart() {
        songIdx = 0
        songBar = 0
        songEnded = false
        cueReq = -1
        if (songLen > 0) switchTo(songPart[0].coerceIn(0, N - 1))
    }

    /**
     * Audio thread, at every new bar (not the very first one).
     * Returns false when the song is over and playback must stop.
     */
    fun onBar(): Boolean {
        if (songOn && songLen > 0) {
            songBar++
            val idx = songIdx.coerceIn(0, songLen - 1)
            if (songBar >= songBars[idx].coerceIn(1, MAX_BARS)) {
                songBar = 0
                var next = idx + 1
                if (next >= songLen) {
                    if (songLoop) {
                        next = 0
                    } else {
                        songEnded = true
                        return false
                    }
                }
                songIdx = next
                switchTo(songPart[next].coerceIn(0, N - 1))
            }
            return true
        }
        val q = cueReq
        if (q >= 0) {
            cueReq = -1
            switchTo(q)
        }
        return true
    }

    /** Audio thread, on every step that is not the start of a bar. */
    fun onStep(s: Int, bpm: Double) {
        if (songOn) return
        if (!jumpNow) return
        val q = cueReq
        if (q >= 0) {
            cueReq = -1
            val before = cur
            switchTo(q)
            if (cur != before) clipFromStep(s, bpm)
        }
    }

    /** Audio thread, while stopped: jumps happen right away. */
    fun whileStopped() {
        val q = cueReq
        if (q >= 0) {
            cueReq = -1
            switchTo(q)
        }
    }

    fun songAdd(part: Int, bars: Int) {
        if (songLen >= MAX_SONG) return
        songPart[songLen] = part.coerceIn(0, N - 1)
        songBars[songLen] = bars.coerceIn(1, MAX_BARS)
        songLen++
    }

    fun songRemove(i: Int) {
        if (i !in 0 until songLen) return
        for (k in i until songLen - 1) {
            songPart[k] = songPart[k + 1]
            songBars[k] = songBars[k + 1]
        }
        songLen--
        if (songIdx >= songLen) songIdx = 0
    }

    fun songMove(i: Int, d: Int) {
        val j = i + d
        if (i !in 0 until songLen || j !in 0 until songLen) return
        val p = songPart[i]
        songPart[i] = songPart[j]
        songPart[j] = p
        val b = songBars[i]
        songBars[i] = songBars[j]
        songBars[j] = b
    }

    /** Bars in the whole song. */
    fun songTotalBars(): Int {
        var n = 0
        for (k in 0 until songLen) n += songBars[k]
        return n
    }

    // ---------------- save / load ----------------

    /** UI thread: keep the live pattern in its part and write everything to disk. */
    fun save(f: File) {
        try {
            parts[cur].capture(e)
            val o = JSONObject()
            o.put("cur", cur)
            o.put("jumpNow", jumpNow)
            o.put("songLoop", songLoop)
            o.put("clipVol", clipVol.toDouble())
            o.put("clipMute", clipMute)
            val sl = JSONArray()
            for (x in slot) sl.put(x)
            o.put("slots", sl)
            val ps = JSONArray()
            for (p in parts) ps.put(p.toJson())
            o.put("parts", ps)
            val sp = JSONArray()
            val sb = JSONArray()
            for (k in 0 until songLen) {
                sp.put(songPart[k])
                sb.put(songBars[k])
            }
            o.put("songPart", sp)
            o.put("songBars", sb)
            SafeIo.writeText(f, o.toString())
        } catch (ex: Exception) {
        }
    }

    /** UI thread at start-up, after the project is loaded. */
    fun load(f: File) {
        try {
            if (!f.exists()) {
                // first time: the pattern you already have becomes the Intro
                cur = 0
                parts[0].capture(e)
                return
            }
            val o = JSONObject(f.readText())
            jumpNow = o.optBoolean("jumpNow", false)
            songLoop = o.optBoolean("songLoop", false)
            clipVol = o.optDouble("clipVol", 0.9).toFloat()
            clipMute = o.optBoolean("clipMute", false)
            val sl = o.optJSONArray("slots")
            if (sl != null) {
                for (k in 0 until minOf(sl.length(), N)) slot[k] = sl.optInt(k, k).coerceIn(0, N - 1)
            }
            val ps = o.optJSONArray("parts")
            if (ps != null) {
                for (i in 0 until minOf(ps.length(), N)) {
                    val j = ps.optJSONObject(i) ?: continue
                    parts[i].fromJson(j)
                }
            }
            val sp = o.optJSONArray("songPart")
            val sb = o.optJSONArray("songBars")
            songLen = 0
            if (sp != null) {
                for (k in 0 until minOf(sp.length(), MAX_SONG)) {
                    songPart[k] = sp.optInt(k, 0).coerceIn(0, N - 1)
                    songBars[k] = (sb?.optInt(k, 4) ?: 4).coerceIn(1, MAX_BARS)
                    songLen++
                }
            }
            cur = o.optInt("cur", 0).coerceIn(0, N - 1)
            if (parts[cur].used) parts[cur].apply(e) else parts[cur].capture(e)
            version++
        } catch (ex: Exception) {
            cur = 0
            parts[0].capture(e)
        }
    }
}
