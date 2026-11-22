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
 *
 * 3.34 TAKE ALL: the 6 parts are also the queue slots Q1..Q6 of the Home page. A slot filled by SEND is "full":
 * besides the patterns it holds the guitars' and the New Ambient's steps, the sound of every Sampler track and drum
 * pad, the volumes and all the mutes. A part made the old way (not full) leaves those alone, as before.
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

    /** The analog synth notes: [step * 128 + note] = length (see AnalogSeq.grid). */
    val aGrid = IntArray(Cfg.STEPS * AnalogSeq.NOTES)
    var aEnabled = true
    var aMute = false

    // ---- 3.34 TAKE ALL: what a full slot holds besides the patterns above ----
    /** true: this part was sent by TAKE ALL (or copied from one): the fields below count. */
    @Volatile var full = false
    /** how many Home lanes were in use when it was sent (-1: not known) */
    var lanes = -1
    /** the guitars' steps, volume and mute: [0] rhythm, [1] riff */
    val gArt = Array(2) { IntArray(Cfg.STEPS) }
    val gFret = Array(2) { IntArray(Cfg.STEPS) }
    val gMute = BooleanArray(2)
    val gVol = floatArrayOf(0.75f, 0.6f)
    val amb = BooleanArray(Cfg.STEPS)
    var ambMute = false
    var ambVol = 0.7f
    /** the sound (file name) of every Sampler track and drum pad; "" = no sound */
    val tName = Array(Cfg.TRACKS) { "" }
    val pName = Array(Cfg.PADS) { "" }
    val tVol = FloatArray(Cfg.TRACKS) { 0.8f }
    val pVol = FloatArray(Cfg.PADS) { 1f }
    var bVol = 0.85f
    var aVol = 0.85f
    var cVol = 0.9f
    var cMute = false

    /** The pattern got longer: the new steps start as copies of the old ones (old until n). */
    fun extend(old: Int, n: Int) {
        if (old <= 0) return
        for (k in old until n) {
            val j = k % old
            for (i in 0 until Cfg.TRACKS) {
                tOn[i][k] = tOn[i][j]; tPitch[i][k] = tPitch[i][j]; tLvl[i][k] = tLvl[i][j]
            }
            for (i in 0 until Cfg.PADS) {
                pOn[i][k] = pOn[i][j]; pLvl[i][k] = pLvl[i][j]
            }
            bOn[k] = bOn[j]; bNote[k] = bNote[j]; bAcc[k] = bAcc[j]; bSlide[k] = bSlide[j]
            System.arraycopy(aGrid, j * AnalogSeq.NOTES, aGrid, k * AnalogSeq.NOTES, AnalogSeq.NOTES)
            for (g in 0 until 2) { gArt[g][k] = gArt[g][j]; gFret[g][k] = gFret[g][j] }
            amb[k] = amb[j]
        }
    }

    /** The meter's bar slot changed size: step [j] goes to step [k] (see MainActivity.moveBars). */
    fun moveStep(j: Int, k: Int) {
        if (j !in 0 until Cfg.STEPS || k !in 0 until Cfg.STEPS) return
        for (i in 0 until Cfg.TRACKS) {
            tOn[i][k] = tOn[i][j]; tPitch[i][k] = tPitch[i][j]; tLvl[i][k] = tLvl[i][j]
        }
        for (i in 0 until Cfg.PADS) {
            pOn[i][k] = pOn[i][j]; pLvl[i][k] = pLvl[i][j]
        }
        bOn[k] = bOn[j]; bNote[k] = bNote[j]; bAcc[k] = bAcc[j]; bSlide[k] = bSlide[j]
        System.arraycopy(aGrid, j * AnalogSeq.NOTES, aGrid, k * AnalogSeq.NOTES, AnalogSeq.NOTES)
        for (g in 0 until 2) { gArt[g][k] = gArt[g][j]; gFret[g][k] = gFret[g][j] }
        amb[k] = amb[j]
    }

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
        s.grid.copyInto(aGrid)
        aEnabled = s.enabled
        aMute = e.analog.mute
        if (full) captureMix(e)
        used = true
    }

    /** 3.34: the rest of a full slot (guitars, ambient, sounds, volumes). Runs on the audio thread too: no allocation. */
    private fun captureMix(e: Engine) {
        // while this slot still waits for one of its sounds (see applyMix) the engine holds another sound in its
        // place: the slot keeps its own names
        val names = e.bank.soundsPending !== this
        for (i in 0 until Cfg.TRACKS) {
            if (names) tName[i] = e.tracks[i].sampleName
            tVol[i] = e.tracks[i].volume
        }
        for (i in 0 until Cfg.PADS) {
            if (names) pName[i] = e.pads[i].name
            pVol[i] = e.pads[i].volume
        }
        bVol = e.bass.volume
        aVol = e.analog.volume
        cVol = e.bank.clipVol
        cMute = e.bank.clipMute
        for (g in 0 until 2) {
            val gt = if (g == 0) e.rhythmGtr else e.riffGtr
            gt.art.copyInto(gArt[g])
            gt.fret.copyInto(gFret[g])
            gMute[g] = gt.mute
            gVol[g] = gt.volume
        }
        e.ambient.steps.copyInto(amb)
        ambMute = e.ambient.mute
        ambVol = e.ambient.volume
    }

    /** 3.34: everything on screen goes into this slot, whatever it held (the whole capture, as a full slot). */
    fun grab(e: Engine) {
        if (e.bank.soundsPending === this) e.bank.soundsPending = null
        full = true
        lanes = -1
        capture(e)
    }

    /**
     * 3.34 TAKE ALL -> SEND: everything that is in use goes into this slot. What is muted now is stored silent
     * (its mute on, no steps) instead of with its pattern. [n] = the Home lanes in use (shown in the panel).
     */
    fun takeAll(e: Engine, n: Int) {
        grab(e)
        lanes = n
        for (i in 0 until Cfg.TRACKS) if (tMute[i]) tOn[i].fill(false)
        for (i in 0 until Cfg.PADS) if (pMute[i]) pOn[i].fill(false)
        if (bMute) bOn.fill(false)
        if (aMute) aGrid.fill(0)
        for (g in 0 until 2) if (gMute[g]) {
            gArt[g].fill(MetalGuitar.OFF)
            gFret[g].fill(0)
        }
        if (ambMute) amb.fill(false)
    }

    /**
     * 3.34: the rest of a full slot into the engine. Runs on the audio thread too, so no file is read and nothing
     * is allocated: a sound comes from the bank's cache (PartBank.sounds); one that is not there yet stays as it is
     * and ([request]) a background load is asked for (the slot's sounds are set again when it is done). A sound
     * whose file is gone never gets into the cache, so the current sound stays.
     */
    fun applyMix(e: Engine, request: Boolean) {
        val bk = e.bank
        val c = bk.sounds
        var miss = false
        for (i in 0 until Cfg.TRACKS) {
            val t = e.tracks[i]
            t.volume = tVol[i]
            val n = tName[i]
            if (n != t.sampleName) {
                if (n.isEmpty()) e.setSample(i, "", null)
                else {
                    val d = c[n]
                    if (d != null) e.setSample(i, n, d) else miss = true
                }
            }
        }
        for (i in 0 until Cfg.PADS) {
            val p = e.pads[i]
            p.volume = pVol[i]
            val n = pName[i]
            if (n != p.name) {
                if (n.isEmpty()) e.setPad(i, "", null)
                else {
                    val d = c[n]
                    if (d != null) e.setPad(i, n, d) else miss = true
                }
            }
        }
        e.bass.volume = bVol
        e.analog.volume = aVol
        bk.clipVol = cVol
        bk.clipMute = cMute
        for (g in 0 until 2) {
            val gt = if (g == 0) e.rhythmGtr else e.riffGtr
            gArt[g].copyInto(gt.art)
            gFret[g].copyInto(gt.fret)
            gt.mute = gMute[g]
            gt.volume = gVol[g]
        }
        amb.copyInto(e.ambient.steps)
        e.ambient.mute = ambMute
        e.ambient.volume = ambVol
        bk.soundsPending = if (miss) this else null
        if (miss && request) bk.wantSounds = true
    }

    /** 3.34: the sound names a full slot uses, into [out]. */
    fun soundNames(out: MutableSet<String>) {
        if (!full || !used) return
        for (n in tName) if (n.isNotEmpty()) out.add(n)
        for (n in pName) if (n.isNotEmpty()) out.add(n)
    }

    /** 3.34: a sound file got another name. */
    fun renameSound(old: String, new: String) {
        for (i in tName.indices) if (tName[i] == old) tName[i] = new
        for (i in pName.indices) if (pName[i] == old) pName[i] = new
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
        aGrid.copyInto(s.grid)
        s.enabled = aEnabled
        e.analog.mute = aMute
        if (full) applyMix(e, true)
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
        o.aGrid.copyInto(aGrid)
        aEnabled = o.aEnabled
        aMute = o.aMute
        full = o.full
        lanes = o.lanes
        for (g in 0 until 2) {
            o.gArt[g].copyInto(gArt[g])
            o.gFret[g].copyInto(gFret[g])
        }
        o.gMute.copyInto(gMute)
        o.gVol.copyInto(gVol)
        o.amb.copyInto(amb)
        ambMute = o.ambMute
        ambVol = o.ambVol
        o.tName.copyInto(tName)
        o.pName.copyInto(pName)
        o.tVol.copyInto(tVol)
        o.pVol.copyInto(pVol)
        bVol = o.bVol
        aVol = o.aVol
        cVol = o.cVol
        cMute = o.cMute
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
        aGrid.fill(0)
        aEnabled = true
        aMute = false
        // (3.34) an old-style part again: the guitars, sounds and volumes are left alone
        full = false
        lanes = -1
        used = true
    }

    /** How many steps are on in this part (all instruments together). */
    fun count(): Int {
        var n = 0
        for (r in tOn) for (x in r) if (x) n++
        for (r in pOn) for (x in r) if (x) n++
        for (x in bOn) if (x) n++
        for (x in aGrid) if (x > 0) n++
        if (full) {
            for (r in gArt) for (x in r) if (x == MetalGuitar.CHUG || x == MetalGuitar.OPEN) n++
            for (x in amb) if (x) n++
        }
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
        o.put("aNotes", AnalogSeq.gridToJson(aGrid))
        o.put("aEnabled", aEnabled)
        o.put("aMute", aMute)
        if (full) {
            // 3.34 TAKE ALL (a part without "full" is an old-style part)
            o.put("full", true)
            o.put("lanes", lanes)
            o.put("gArt", gridI(gArt))
            o.put("gFret", gridI(gFret))
            o.put("gMute", arrB(gMute))
            o.put("gVol", arrF(gVol))
            o.put("amb", arrB(amb))
            o.put("ambMute", ambMute)
            o.put("ambVol", ambVol.toDouble())
            o.put("tName", arrS(tName))
            o.put("pName", arrS(pName))
            o.put("tVol", arrF(tVol))
            o.put("pVol", arrF(pVol))
            o.put("bVol", bVol.toDouble())
            o.put("aVol", aVol.toDouble())
            o.put("cVol", cVol.toDouble())
            o.put("cMute", cMute)
        }
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
        val an = o.optJSONArray("aNotes")
        if (an != null) {
            AnalogSeq.gridFromJson(an, aGrid)
        } else {
            // older parts: one note per step
            val on = BooleanArray(Cfg.STEPS)
            val note = IntArray(Cfg.STEPS) { 60 }
            val len = IntArray(Cfg.STEPS) { 1 }
            readB(o.optJSONArray("aOn"), on)
            readI(o.optJSONArray("aNote"), note, 60)
            readI(o.optJSONArray("aLen"), len, 1)
            AnalogSeq.fromMono(on, note, len, aGrid)
        }
        aEnabled = o.optBoolean("aEnabled", true)
        aMute = o.optBoolean("aMute", false)
        // 3.34: an old parts.json has none of these: the part stays an old-style part
        full = o.optBoolean("full", false)
        lanes = o.optInt("lanes", -1)
        if (full) {
            readGridI(o.optJSONArray("gArt"), gArt, 0)
            readGridI(o.optJSONArray("gFret"), gFret, 0)
            readB(o.optJSONArray("gMute"), gMute)
            readF(o.optJSONArray("gVol"), gVol)
            readB(o.optJSONArray("amb"), amb)
            ambMute = o.optBoolean("ambMute", false)
            ambVol = o.optDouble("ambVol", 0.7).toFloat().coerceIn(0f, 1f)
            readS(o.optJSONArray("tName"), tName)
            readS(o.optJSONArray("pName"), pName)
            readF(o.optJSONArray("tVol"), tVol)
            readF(o.optJSONArray("pVol"), pVol)
            bVol = o.optDouble("bVol", 0.85).toFloat()
            aVol = o.optDouble("aVol", 0.85).toFloat()
            cVol = o.optDouble("cVol", 0.9).toFloat()
            cMute = o.optBoolean("cMute", false)
        }
    }

    private fun arrS(a: Array<String>): JSONArray {
        val j = JSONArray()
        for (x in a) j.put(x)
        return j
    }

    private fun readS(j: JSONArray?, a: Array<String>) {
        if (j == null) return
        for (k in 0 until minOf(j.length(), a.size)) a[k] = j.optString(k, "")
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

    // ---- 3.34 TAKE ALL: the parts are the queue slots Q1..Q6 ----
    /**
     * true: what is on screen belongs to no part (after SEND into the playing part, or RECALL), so nothing that is
     * edited now changes a slot until it is sent. A part switch keeps the screen in [last] and ends this.
     */
    @Volatile var loose = false
    /** the screen while [loose], kept across restarts */
    val work = PartSnap()
    /** the screen as it was before a RECALL or a part switch last replaced it (it can be recalled) */
    val last = PartSnap()

    /**
     * The sounds of the full slots, ready in memory (file name -> samples), so the audio thread only swaps
     * references. Never changed in place: a new map replaces it (made on a background thread).
     */
    @Volatile var sounds: HashMap<String, FloatArray> = HashMap()
    /** set by the audio thread: a slot wanted a sound that is not in [sounds] yet (the UI thread starts the load) */
    @Volatile var wantSounds = false
    /** the slot that was put on screen last and still misses a sound */
    @Volatile var soundsPending: PartSnap? = null
    /** reads a sound file as the engine wants it (set by MainActivity); null: the file is gone */
    @Volatile var soundLoader: ((String) -> FloatArray?)? = null
    private val soundLock = Any()
    private val staleSounds = HashSet<String>()      // files that changed: read again, whatever is in memory

    /**
     * UI thread: every sound a slot names gets into [sounds], on a background thread (the sounds that are loaded
     * in the engine now are taken as they are, the others are read from their files). [done] runs on that thread.
     */
    fun preloadSounds(done: (() -> Unit)? = null) {
        wantSounds = false
        val load = soundLoader ?: return
        val names = HashSet<String>()
        for (p in parts) p.soundNames(names)
        work.soundNames(names)
        last.soundNames(names)
        val live = HashMap<String, FloatArray>()
        for (t in e.tracks) {
            val d = t.data
            val n = t.sampleName
            if (d != null && n.isNotEmpty()) live[n] = d
        }
        for (p in e.pads) {
            val d = p.data
            val n = p.name
            if (d != null && n.isNotEmpty()) live[n] = d
        }
        Thread {
            try {
                synchronized(soundLock) {
                    val old = sounds
                    val m = HashMap<String, FloatArray>()
                    for (n in names) {
                        val d = (if (staleSounds.remove(n)) null else live[n] ?: old[n])
                            ?: try { load(n) } catch (ex: Throwable) { null }
                        if (d != null) m[n] = d
                    }
                    sounds = m
                }
                done?.invoke()
            } catch (ex: Throwable) {
            }
        }.start()
    }

    /** UI thread: the file of this sound changed: it is read again the next time. */
    fun forgetSound(name: String) {
        synchronized(soundLock) {
            staleSounds.add(name)
            if (!sounds.containsKey(name)) return
            val m = HashMap(sounds)
            m.remove(name)
            sounds = m
        }
    }

    /** UI thread: a sound file got another name. */
    fun renameSound(old: String, new: String) {
        for (p in parts) p.renameSound(old, new)
        work.renameSound(old, new)
        last.renameSound(old, new)
        synchronized(soundLock) {
            val d = sounds[old] ?: return
            val m = HashMap(sounds)
            m.remove(old)
            m[new] = d
            sounds = m
        }
    }

    /** UI thread: keep what is on screen where it belongs (its part, or nowhere but [work] while it is loose). */
    fun keep() {
        if (loose) {
            work.grab(e)
        } else {
            parts[cur].capture(e)
        }
    }

    /**
     * UI thread, TAKE ALL -> SEND: everything in use goes into slot i. The screen and the playing part stay as
     * they are; when i is the playing part, the screen no longer belongs to it (so the slot stays as it was sent).
     */
    fun send(i: Int, lanes: Int) {
        if (i !in 0 until N) return
        parts[i].takeAll(e, lanes)
        if (i == cur) loose = true
    }

    /**
     * UI thread, TAKE ALL -> RECALL: [src] (a slot, or [last]) replaces everything on screen. The playing part does
     * not change. What was on screen is kept first (in its part, and in [last]); after it the screen belongs to no
     * part, so editing it changes no slot until it is sent.
     */
    fun recall(src: PartSnap) {
        if (!src.used) return
        var from = src
        if (src === last) {
            from = PartSnap()
            from.copyFrom(last)
        }
        if (!loose) parts[cur].capture(e)
        last.grab(e)
        from.apply(e)
        loose = true
        version++
    }

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
        // (3.34) a loose screen belongs to no part: it is kept in [last], and the part is put on screen even when
        // it is the one that plays
        val wasLoose = loose
        if (i == cur && !wasLoose) return
        if (wasLoose) {
            last.grab(e)
            loose = false
        } else {
            parts[cur].capture(e)
        }
        val p = parts[i]
        if (!p.used) p.copyFrom(if (wasLoose) last else parts[cur])
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

    private fun barFrames(bpm: Double): Double = Pat.barFrames(bpm)      // (3.28: a bar of the meter)

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
            clipPos = (s * barFrames(bpm) / Pat.spb()).toInt()
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
            keep()
            val o = JSONObject()
            o.put("cur", cur)
            // 3.34
            o.put("loose", loose)
            if (loose) o.put("work", work.toJson())
            if (last.used) o.put("last", last.toJson())
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
        preloadSounds()
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
            // 3.34: the screen that belonged to no part, and the screen kept before the last recall
            val wj = o.optJSONObject("work")
            val lj = o.optJSONObject("last")
            if (lj != null) last.fromJson(lj)
            loose = o.optBoolean("loose", false) && wj != null
            if (loose && wj != null) {
                work.fromJson(wj)
                work.apply(e)
            } else if (parts[cur].used) parts[cur].apply(e) else parts[cur].capture(e)
            version++
        } catch (ex: Exception) {
            cur = 0
            loose = false
            parts[0].capture(e)
        }
    }
}
