package com.example.fm1burger

import android.app.AlertDialog
import android.content.Context
import android.os.Process
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/**
 * 3.36 "→ MIX": a Q (1 / 2 / 4 / 8 loops of its pattern) or the whole song goes to 16 Track's INBOX as one stem per
 * lane (a box: InboxProvider.kt, and INBOX.md of 16 Track).
 *
 * The render is offline and never touches the engine that plays. On the UI thread a second Engine (never started: no
 * audio thread, no output) is made as a copy of everything that makes the sound: the sounds, every knob, the effect
 * chains, the automation lanes, the 6 parts (PartSnap) and the song. Then, on a low-priority background thread, one
 * source at a time: a fresh copy of that Engine plays the Q (as a one-entry song) or the song from bar 1 with the
 * engine's own sequencer and voices (Engine.offlineBegin / offlineBlock), and only that source is rendered, through
 * its own effects. So a stem is that lane as it sounds in the mix, before the master (master volume and soft clip).
 *
 * A stem is written before its fader: the lane's volume is the `gain` of its track row, so 16 Track's fader shows it
 * (stem x gain = the lane in the mix). A stem that would clip 16 bit is turned down and its gain turned up instead.
 */
object MixSend {
    const val PKG = "com.makerhip.track16.studio"     // (3.66: 16 Track 0.12 on the Maker Hip key; com.makerhip.track16 before)
    /** the name in front of a box's title (the app has no project or song name) */
    const val NAME = "Burger Studio"
    private const val MAX_SECONDS = 600.0
    private const val SILENT = 0.0001f           // a stem that never gets louder than -80 dB is left out

    @Volatile private var busy = false

    /** 16 Track is on this device (the manifest's <queries> lets us see it) */
    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) { false }

    // ---------------- the questions ----------------

    /** Q [i]: how many loops of its pattern? */
    fun askQ(a: MainActivity, i: Int) {
        val bank = a.engine.bank
        if (i !in 0 until PartBank.N) return
        if (!bank.parts[i].used && !(i == bank.cur && !bank.loose)) {
            a.ui.toast("Q${i + 1} ${PartNames.NAMES[i]} is empty")
            return
        }
        val pb = Pat.bars()
        val loops = intArrayOf(1, 2, 4, 8)
        val items = Array(loops.size) {
            val b = loops[it] * pb
            "${loops[it]} loop" + (if (loops[it] == 1) "" else "s") + " · $b bar" + (if (b == 1) "" else "s") +
                " · " + time(b * Pat.barSeconds(a.engine.bpmF))
        }
        AlertDialog.Builder(a)
            .setTitle("Q${i + 1} ${PartNames.NAMES[i]} → MIX: how long?")
            .setItems(items) { _, w ->
                start(a, "q", "$NAME · Q${i + 1} ${PartNames.NAMES[i]}", intArrayOf(i), intArrayOf(loops[w] * pb))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The whole song, as it is arranged on the Song page. */
    fun askSong(a: MainActivity) {
        val bank = a.engine.bank
        val n = bank.songLen
        if (n <= 0) {
            a.ui.toast("The song is empty. Add some parts first.")
            return
        }
        val parts = IntArray(n) { bank.songPart[it].coerceIn(0, PartBank.N - 1) }
        val bars = IntArray(n) { bank.songBars[it].coerceIn(1, PartBank.MAX_BARS) }
        val total = bars.sum()
        AlertDialog.Builder(a)
            .setTitle("Whole song → MIX")
            .setMessage("The song as it is arranged: $n part" + (if (n == 1) "" else "s") + " · $total bars · " +
                time(total * Pat.barSeconds(a.engine.bpmF)) + " at BPM ${a.engine.bpm}.\n" +
                "Every lane that plays goes to 16 Track's INBOX as its own stem, from bar 1.")
            .setPositiveButton("Send") { _, _ -> start(a, "song", "$NAME · whole song", parts, bars) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun time(secs: Double): String {
        val s = Math.round(secs).toInt()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ---------------- what a stem is ----------------

    /** One stem: a source of the engine ([src], [pad]: see Engine.offlineBlock) and its track row. */
    private class Stem(val src: Int, val pad: Int, val name: String, val color: Int, val gain: Float, val pan: Float,
                       val stereo: Boolean, val right: Boolean, val mul: Float)

    private fun soundName(file: String): String {
        var n = file.substringBeforeLast('.')
        if (n.startsWith("Drum_")) n = n.substring(5)
        return n.replace('_', ' ').trim()
    }

    /** does part [p] play the source (a Home lane type and its number)? [t] holds what a part does not (the screen) */
    private fun plays(t: Engine, p: PartSnap, type: Int, k: Int): Boolean {
        if (type == Inst.SONG) return p.hasClip() && p.mode != 0 && !(if (p.full) p.cMute else t.bank.clipMute)
        if (p.mode == 1 && p.hasClip()) return false            // this part plays its recording only
        return when (type) {
            Inst.SAMPLE -> !p.tMute[k] && (t.tracks[k].loop || p.tOn[k].any { it })
            Inst.DRUM -> !p.pMute[k] && p.pOn[k].any { it }
            Inst.BASS -> !p.bMute && p.bOn.any { it }
            Inst.SYNTH -> !p.aMute && p.aEnabled && p.aGrid.any { it > 0 }
            Inst.AMBIENT ->
                if (p.full) !p.ambMute && (t.ambient.free || p.amb.any { it })
                else !t.ambient.mute && (t.ambient.free || t.ambient.steps.any { it })
            Inst.RHYTHM, Inst.RIFF -> {
                val g = if (type == Inst.RHYTHM) 0 else 1
                val gt = if (g == 0) t.rhythmGtr else t.riffGtr
                if (p.full) !p.gMute[g] && p.gArt[g].any { it == MetalGuitar.CHUG || it == MetalGuitar.OPEN }
                else !gt.mute && gt.art.any { it == MetalGuitar.CHUG || it == MetalGuitar.OPEN }
            }
            else -> false
        }
    }

    /** The stem of a source, or null when no part of [ps] plays it. Name and volume: of the first part that does. */
    private fun stemOf(t: Engine, ps: List<PartSnap>, type: Int, k: Int): Stem? {
        val p = ps.firstOrNull { plays(t, it, type, k) } ?: return null
        val col = Inst.color(type)
        return when (type) {
            Inst.SAMPLE -> {
                val tr = t.tracks[k]
                val snd = soundName(if (p.full) p.tName[k] else tr.sampleName)
                // the engine pans with equal power (centre = 0.707 each side); 16 Track's pan only turns the far side
                // down. So the near side's level goes into the stem and the pan gives the far side: the same picture.
                val ang = (tr.pan.coerceIn(-1f, 1f) + 1f) * 0.7853982f
                val cl = cos(ang)
                val sr = sin(ang)
                val mul = if (tr.pan <= 0f) cl else sr
                val pan = if (tr.pan <= 0f) sr / cl - 1f else 1f - cl / sr
                Stem(k, 0, ("Sampler ${k + 1} $snd").trim(), col, if (p.full) p.tVol[k] else tr.volume, pan, false, false, mul)
            }
            Inst.DRUM -> {
                val snd = soundName(if (p.full) p.pName[k] else t.pads[k].name)
                Stem(Engine.PREP_PADS, k, ("${Cfg.padName(k)} $snd").trim(), col, if (p.full) p.pVol[k] else t.pads[k].volume,
                    0f, false, false, 1f)
            }
            Inst.BASS -> Stem(Engine.PREP_BASS, 0, "Bassline", col, if (p.full) p.bVol else t.bass.volume, 0f, false, false, 1f)
            Inst.SYNTH -> Stem(Engine.PREP_SYNTH, 0, "Analog synth", col, if (p.full) p.aVol else t.analog.volume, 0f, false, false, 1f)
            // the guitars sit a little apart in the mix: mono, the near side's level, and the pan that gives the far side
            Inst.RHYTHM -> Stem(Engine.PREP_RHYTHM, 0, "Rhythm gtr", col, if (p.full) p.gVol[0] else t.rhythmGtr.volume,
                Engine.GTR_FAR / Engine.GTR_NEAR - 1f, false, false, 1f)
            Inst.RIFF -> Stem(Engine.PREP_RIFF, 0, "Riff gtr", col, if (p.full) p.gVol[1] else t.riffGtr.volume,
                1f - Engine.GTR_FAR / Engine.GTR_NEAR, false, true, 1f)
            Inst.AMBIENT -> Stem(Engine.PREP_AMBIENT, 0, "New Ambient " + NewAmbient.NAMES[t.ambient.kind.coerceIn(0, NewAmbient.N_KIND - 1)],
                col, if (p.full) p.ambVol else t.ambient.volume, 0f, true, false, 1f)
            Inst.SONG -> Stem(Engine.OFF_CLIP, 0, "Song rec", col, if (p.full) p.cVol else t.bank.clipVol, 0f, true, false, 1f)
            else -> null
        }
    }

    /** The stems: the Home lanes from the top, then whatever else plays without a lane (a drum pad, say). */
    private fun stems(t: Engine, lanes: LaneSetup, ps: List<PartSnap>): List<Stem> {
        val l = ArrayList<Stem>()
        val seen = HashSet<Int>()
        fun add(type: Int, k: Int) {
            if (type == Inst.EMPTY || type == Inst.VOICE) return
            if (k !in 0 until maxOf(1, Inst.slots(type))) return
            if (!seen.add(type * 100 + k)) return
            val s = stemOf(t, ps, type, k)
            if (s != null) l.add(s)
        }
        for (i in 0 until LaneSetup.N) add(lanes.type[i], lanes.idx[i])
        for (k in 0 until Cfg.TRACKS) add(Inst.SAMPLE, k)
        for (k in 0 until Cfg.PADS) add(Inst.DRUM, k)
        for (ty in intArrayOf(Inst.BASS, Inst.SYNTH, Inst.RHYTHM, Inst.RIFF, Inst.AMBIENT, Inst.SONG)) add(ty, 0)
        return l
    }

    // ---------------- the copy of the engine ----------------

    private fun copyFx(s: FxChain, d: FxChain) {
        s.on.copyInto(d.on)
        s.a.copyInto(d.a)
        s.b.copyInto(d.b)
        d.order = s.order.clone()
        d.intensity = s.intensity
    }

    private fun copyGtr(s: MetalGuitar, d: MetalGuitar) {
        d.gain = s.gain
        d.tone = s.tone
        d.tight = s.tight
        d.volume = s.volume
        d.mute = s.mute
        d.tuning = s.tuning
        d.octave = s.octave
        s.art.copyInto(d.art)
        s.fret.copyInto(d.fret)
    }

    /**
     * Everything that makes the sound of [s] into [d] (an Engine that never runs an audio thread). [s] is only read.
     * The samples and the recordings are shared (they are never changed in place), everything else is copied.
     */
    private fun copyEngine(s: Engine, d: Engine) {
        d.bpmF = s.bpmF
        d.padVol = s.padVol
        for (i in 0 until Cfg.TRACKS) {
            val a = s.tracks[i]
            val b = d.tracks[i]
            b.sampleName = a.sampleName
            b.data = a.data
            b.volume = a.volume
            b.pan = a.pan
            b.mute = a.mute
            b.loop = a.loop
            b.pitch = a.pitch
            b.start = a.start
            b.end = a.end
            b.reverse = a.reverse
            b.boostDb = a.boostDb
            b.pingPong = a.pingPong
            b.loopA = a.loopA
            b.loopB = a.loopB
            b.xfadeMs = a.xfadeMs
            b.fitBars = a.fitBars
            b.micro = a.micro
            b.robot = a.robot
            b.keepPitch = a.keepPitch
            b.slices = a.slices
            a.sliceOf.copyInto(b.sliceOf)
            a.sliceRev.copyInto(b.sliceRev)
            for (k in 0 until Cfg.STEPS) {
                b.steps[k].on = a.steps[k].on
                b.steps[k].pitch = a.steps[k].pitch
                b.steps[k].level = a.steps[k].level
            }
            copyFx(a.fx, b.fx)
        }
        for (i in 0 until Cfg.PADS) {
            val a = s.pads[i]
            val b = d.pads[i]
            b.name = a.name
            b.data = a.data
            a.on.copyInto(b.on)
            a.lvl.copyInto(b.lvl)
            b.volume = a.volume
            b.mute = a.mute
        }
        copyFx(s.drumFx, d.drumFx)
        d.drumHeavy.amount = s.drumHeavy.amount
        val sb = s.bass
        val db = d.bass
        sb.on.copyInto(db.on)
        sb.note.copyInto(db.note)
        sb.accent.copyInto(db.accent)
        sb.slide.copyInto(db.slide)
        db.saw = sb.saw
        db.sound = sb.sound
        db.smp = sb.smp
        db.cutoff = sb.cutoff
        db.res = sb.res
        db.envMod = sb.envMod
        db.decay = sb.decay
        db.accentAmt = sb.accentAmt
        db.drive = sb.drive
        db.volume = sb.volume
        db.boostDb = sb.boostDb
        db.mute = sb.mute
        db.key = sb.key
        db.octave = sb.octave
        val sa = s.analog
        val da = d.analog
        da.wave = sa.wave
        da.detune = sa.detune
        da.attack = sa.attack
        da.decay = sa.decay
        da.sustain = sa.sustain
        da.release = sa.release
        da.cutoff = sa.cutoff
        da.res = sa.res
        da.filtEnv = sa.filtEnv
        da.volume = sa.volume
        da.mute = sa.mute
        copyFx(sa.fx, da.fx)
        da.voicing = sa.voicing
        da.octave = sa.octave
        da.portamento = sa.portamento
        da.drive = sa.drive
        da.lfoWave = sa.lfoWave
        da.lfoRate = sa.lfoRate
        da.lfoSync = sa.lfoSync
        da.lfoPitch = sa.lfoPitch
        da.lfoCutoff = sa.lfoCutoff
        da.delayTime = sa.delayTime
        da.delayFb = sa.delayFb
        da.bpm = sa.bpm
        da.boostDb = sa.boostDb
        s.analogSeq.grid.copyInto(d.analogSeq.grid)
        d.analogSeq.enabled = s.analogSeq.enabled
        d.analogSeq.chord = s.analogSeq.chord
        val sm = s.ambient
        val dm = d.ambient
        dm.kind = sm.kind
        dm.density = sm.density
        dm.pitch = sm.pitch
        dm.tone = sm.tone
        dm.wander = sm.wander
        dm.space = sm.space
        dm.volume = sm.volume
        dm.mute = sm.mute
        dm.free = sm.free
        dm.key = sm.key
        sm.steps.copyInto(dm.steps)
        copyGtr(s.rhythmGtr, d.rhythmGtr)
        copyGtr(s.riffGtr, d.riffGtr)
        for (k in s.auto.lanes.indices) s.auto.lanes[k].copyInto(d.auto.lanes[k])
        d.auto.enabled = s.auto.enabled
        val x = s.bank
        val y = d.bank
        for (i in 0 until PartBank.N) {
            val p = x.parts[i]
            val q = y.parts[i]
            q.copyFrom(p)
            q.clip = p.clip
            q.clipBars = p.clipBars
            q.mode = p.mode
        }
        y.clipVol = x.clipVol
        y.clipMute = x.clipMute
        y.sounds = x.sounds
        y.cur = x.cur
        y.loose = false
        y.songLen = minOf(x.songLen, PartBank.MAX_SONG)
        x.songPart.copyInto(y.songPart)
        x.songBars.copyInto(y.songBars)
        y.songOn = x.songOn
        y.songLoop = false
    }

    // ---------------- the job ----------------

    /**
     * UI thread: the picture of the engine is taken now (so what is edited while it renders changes nothing), the
     * render runs on a background thread. [parts] / [bars]: the song to play (a Q: one entry).
     */
    private fun start(a: MainActivity, kind: String, title: String, parts: IntArray, bars: IntArray) {
        if (busy) {
            a.ui.toast("Still rendering the last → MIX")
            return
        }
        if (!installed(a)) {
            a.ui.toast("16 Track is not on this device")
            return
        }
        val live = a.engine
        val bpmF = live.bpmF
        val meter = Pat.meter
        val len = Pat.len
        val totalBars = bars.sum()
        val frames = Math.round(totalBars * Pat.barFrames(bpmF))
        if (totalBars < 1 || frames < 1L || frames > (MAX_SECONDS * Cfg.SR).toLong()) {
            a.ui.toast("Too long for → MIX: " + time(frames.toDouble() / Cfg.SR) + " (at most " + time(MAX_SECONDS) + ")")
            return
        }
        val tpl: Engine
        val list: List<Stem>
        try {
            tpl = Engine()
            copyEngine(live, tpl)
            // the part that is on screen is the screen itself (what is edited belongs to it): as it is now
            val lb = live.bank
            if (!lb.loose && lb.soundsPending !== lb.parts[lb.cur]) tpl.bank.parts[lb.cur].capture(live)
            val tb = tpl.bank
            tb.songLen = parts.size
            for (k in parts.indices) {
                tb.songPart[k] = parts[k]
                tb.songBars[k] = bars[k]
            }
            tb.songOn = true
            // the parts that play (an empty part plays as a copy of the one before it)
            val ps = ArrayList<PartSnap>()
            for (k in parts.indices) {
                val p = tb.parts[parts[k]]
                if (p.used) { if (!ps.contains(p)) ps.add(p) }
            }
            list = stems(tpl, a.lanes, ps)
        } catch (ex: Throwable) {
            a.ui.toast("→ MIX could not start: " + (ex.message ?: ex.javaClass.simpleName))
            return
        }
        if (list.isEmpty()) {
            a.ui.toast("Nothing to send: no lane plays there (everything is empty or muted)")
            return
        }
        busy = true
        val job = Job(a, tpl, list, kind, title, Math.round(bpmF).toInt(), bpmF, totalBars, meter, len, frames.toInt())
        job.dialog = try {
            AlertDialog.Builder(a)
                .setTitle("→ MIX")
                .setMessage("Rendering stems 1/${list.size}…")
                .setCancelable(false)
                .setPositiveButton("Hide", null)
                .setNegativeButton("Cancel") { _, _ -> job.cancel = true }
                .show()
        } catch (ex: Exception) { null }
        val th = Thread { job.run() }
        th.name = "burger-mix-send"
        th.isDaemon = true
        th.priority = Thread.MIN_PRIORITY
        th.start()
    }

    private class Job(val a: MainActivity, val tpl: Engine, val list: List<Stem>, val kind: String, val title: String,
                      val bpm: Int, val bpmF: Double, val bars: Int, val meter: Int, val len: Int, val frames: Int) {
        @Volatile var cancel = false
        @Volatile var dialog: AlertDialog? = null
        private val ctx: Context = a.applicationContext
        private val buf = FloatArray(Engine.BLOCK * 2)
        private val bytes = ByteArray(Engine.BLOCK * 2 * 4)
        private val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private fun ui(f: () -> Unit) {
            a.runOnUiThread {
                try {
                    if (!a.isFinishing && !a.isDestroyed) f()
                } catch (ex: Exception) {
                }
            }
        }

        private fun finish(msg: String) {
            ui {
                try { dialog?.dismiss() } catch (ex: Exception) { }
                a.ui.toast(msg)
            }
        }

        fun run() {
            var tmp: File? = null
            try {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (ex: Exception) { }
                // every sound the parts name, in memory (the slots' cache, what is loaded now, else read from its file)
                val m = HashMap<String, FloatArray>(tpl.bank.sounds)
                for (t in tpl.tracks) { val d = t.data; if (d != null && t.sampleName.isNotEmpty()) m[t.sampleName] = d }
                for (p in tpl.pads) { val d = p.data; if (d != null && p.name.isNotEmpty()) m[p.name] = d }
                val names = HashSet<String>()
                for (p in tpl.bank.parts) p.soundNames(names)
                for (n in names) if (!m.containsKey(n)) {
                    val d = try { a.store.loadEngine(n) } catch (ex: Throwable) { null }
                    if (d != null) m[n] = d
                }
                tpl.bank.sounds = m

                val created = System.currentTimeMillis()
                val root = InboxProvider.root(ctx)
                var id = "b$created"
                while (File(root, id).exists()) id += "x"
                tmp = File(root, ".tmp-$id")                // (a name the provider never lists)
                tmp.deleteRecursively()
                if (!tmp.mkdirs()) throw java.io.IOException("no room for the box")
                val raw = File(tmp, "stem.f32")
                val tracks = JSONArray()
                for ((k, st) in list.withIndex()) {
                    if (cancel) break
                    val shown = k + 1
                    ui { dialog?.setMessage("Rendering stems $shown/${list.size}…\n${st.name}") }
                    val peak = render(st, raw)
                    if (cancel) break
                    if (peak < 0f) throw IllegalStateException("the meter or the pattern length changed. Send it again.")
                    if (peak >= SILENT) {
                        // too loud for 16 bit: the stem is turned down and its fader up (as far as it goes)
                        val k16 = if (peak > 0.98f) 0.98f / peak else 1f
                        val file = "${tracks.length()}.wav"
                        writeWav(raw, File(tmp, file), st.stereo, k16)
                        val o = JSONObject()
                        o.put("name", st.name)
                        o.put("color", st.color)
                        o.put("gain", (st.gain / k16).coerceIn(0f, 1.2f).toDouble())
                        o.put("pan", st.pan.coerceIn(-1f, 1f).toDouble())
                        o.put("file", file)
                        o.put("channels", if (st.stereo) 2 else 1)
                        tracks.put(o)
                    }
                    raw.delete()
                }
                if (cancel) {
                    finish("→ MIX cancelled")
                    return
                }
                if (tracks.length() == 0) {
                    finish("Nothing to send: every lane there is silent")
                    return
                }
                val o = JSONObject()
                o.put("id", id)
                o.put("title", title)
                o.put("kind", kind)
                o.put("bpm", bpm)
                o.put("bars", bars)
                o.put("meter", Pat.METERS[meter.coerceIn(0, Pat.METERS.size - 1)])
                o.put("rate", Cfg.SR)
                o.put("created", created)
                o.put("tracks", tracks)
                SafeIo.writeText(File(tmp, "manifest.json"), o.toString())
                if (!tmp.renameTo(File(root, id))) throw java.io.IOException("the box could not be put in the inbox")
                tmp = null
                InboxProvider.notifyChanged(ctx)
                val n = tracks.length()
                finish("In 16 Track's INBOX: $title ($n stem" + (if (n == 1) "" else "s") + ")" +
                    (if (n > 16) ". 16 Track takes the first 16." else ""))
            } catch (ex: Throwable) {
                finish("→ MIX failed: " + (ex.message ?: ex.javaClass.simpleName))
            } finally {
                try { tmp?.deleteRecursively() } catch (ex: Exception) { }
                busy = false
            }
        }

        /**
         * One stem into [raw] (32 bit floats: mono, or stereo interleaved), before its fader. Returns its peak
         * (-1: the meter or the pattern length was changed meanwhile, so the bars no longer fit).
         */
        private fun render(st: Stem, raw: File): Float {
            // a fresh engine for every stem: every voice, effect tail and glide starts from nothing, as after Play
            val e = Engine()
            copyEngine(tpl, e)
            val bk = e.bank
            val first = bk.songPart[0].coerceIn(0, PartBank.N - 1)
            bk.cur = first
            if (bk.parts[first].used) bk.parts[first].apply(e)
            e.offlineBegin()
            // the fader: what the engine gives has the lane's volume in it
            val mul = st.mul * (if (st.gain >= 0.02f) 1f / st.gain else 1f)
            val ch = if (st.stereo) 2 else 1
            val side = if (st.right) 1 else 0
            var peak = 0f
            var done = 0
            var blocks = 0
            BufferedOutputStream(FileOutputStream(raw), 1 shl 16).use { out ->
                while (done < frames) {
                    if (cancel) return 0f
                    if (Pat.meter != meter || Pat.len != len) return -1f
                    val n = minOf(Engine.BLOCK, frames - done)
                    e.offlineBlock(st.src, st.pad, buf, n)
                    bb.clear()
                    if (st.stereo) {
                        for (i in 0 until n * 2) {
                            val v = buf[i] * mul
                            val av = abs(v)
                            if (av > peak) peak = av
                            bb.putFloat(v)
                        }
                    } else {
                        for (i in 0 until n) {
                            val v = buf[i * 2 + side] * mul
                            val av = abs(v)
                            if (av > peak) peak = av
                            bb.putFloat(v)
                        }
                    }
                    out.write(bytes, 0, n * ch * 4)
                    done += n
                    // someone is playing by hand: leave the processor to the audio thread a little more
                    if (++blocks % 64 == 0 && Live.busy()) try { Thread.sleep(2) } catch (ex: InterruptedException) { }
                }
            }
            return peak
        }

        /** [raw] (floats) as a 16 bit WAV at the engine's rate, exactly [frames] long, every sample times [k]. */
        private fun writeWav(raw: File, f: File, stereo: Boolean, k: Float) {
            val ch = if (stereo) 2 else 1
            val dataLen = frames * ch * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt(36 + dataLen)
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1.toShort())
            h.putShort(ch.toShort())
            h.putInt(Cfg.SR)
            h.putInt(Cfg.SR * ch * 2)
            h.putShort((ch * 2).toShort())
            h.putShort(16.toShort())
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(dataLen)
            val o16 = ByteArray(bytes.size / 2)
            val ob = ByteBuffer.wrap(o16).order(ByteOrder.LITTLE_ENDIAN)
            var left = frames * ch                       // samples still to write
            BufferedInputStream(FileInputStream(raw), 1 shl 16).use { inp ->
                BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { out ->
                    out.write(h.array())
                    while (left > 0) {
                        val n = minOf(left, bytes.size / 4)
                        var got = 0
                        while (got < n * 4) {
                            val r = inp.read(bytes, got, n * 4 - got)
                            if (r < 0) break
                            got += r
                        }
                        // (a short file cannot happen; if it did, the rest is silence, so the length stays exact)
                        if (got < n * 4) java.util.Arrays.fill(bytes, got, n * 4, 0.toByte())
                        bb.clear()
                        ob.clear()
                        for (i in 0 until n) {
                            ob.putShort(Math.round(bb.getFloat() * k * 32767f).coerceIn(-32768, 32767).toShort())
                        }
                        out.write(o16, 0, n * 2)
                        left -= n
                    }
                }
            }
        }
    }
}
