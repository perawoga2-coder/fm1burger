package com.example.fm1burger

import android.app.AlertDialog
import android.content.Context
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 3.56 VACUUM FM-1: asks an FM-1 running STACK (4.0-beta or newer, on the USB cable) for its whole song and puts
 * it on Home's lanes 1 to 8, T1 on lane 1 ... T8 on lane 8 (StackSong.kt has what it means and the plan).
 *
 * Nothing is replaced without a yes: the app asks first (3.57): CLEAR EVERYTHING (an empty Home, then the song) or
 * LANES 1-8 ONLY (lanes 9 and on, and the sampler tracks and pads they show, are not touched); and (3.58 DIRECT
 * SPLIT) every bar, or one bar of the pattern only, so a longer song is built bar by bar. What could not be carried over is listed at the end, never dropped
 * silently. The lanes it makes are ordinary ones: TAKE ALL takes them as any other.
 */
class Vacuum(private val a: MainActivity) {

    private val ui = a.ui
    private val e = a.engine

    fun start() {
        if (!a.midi.connected) {
            AlertDialog.Builder(a).setTitle("VACUUM FM-1")
                .setMessage("No FM-1 is connected. Plug it in with the USB cable (it connects by itself), then press VACUUM FM-1 again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        val what = ui.text("Asking the FM-1 for its song…", 13f, Theme.DIM)
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4))
        box.addView(what)
        box.addView(bar, ui.fullWidth(8))
        val dlg = AlertDialog.Builder(a).setTitle("VACUUM FM-1").setView(box).setCancelable(false).show()
        fun say(s: String, f: Float) = a.runOnUiThread {
            what.text = s
            bar.progress = (f * 1000).toInt()
        }
        Thread {
            val link = a.sampleLink()
            var err: String? = null
            var song: StackSong? = null
            var loopFile: String? = null
            var loopBars = 1
            var loopDb = 0f
            try {
                val blob = link.dumpSong { f -> say("The song…", 0.1f * f) }
                song = StackDump.parse(blob) ?: throw IllegalStateException("The FM-1's answer was damaged on the way. Try again.")
                val lt = song.loopTrack           // 3.59: T8, or T7 since 4.04-beta
                if (song.hasLoop && lt >= 0) {
                    val on = "The loop on track ${lt + 1}…"
                    say(on, 0.1f)
                    val lp = link.dumpLoop { f -> say(on, 0.1f + 0.8f * f) }
                    if (lp != null) {
                        // 16 bits where 16384 is full level -> floats; to the engine's rate; kept in the library as any sound.
                        // 3.59: a loop can go up to 2 (two layers at full level fit): it was cut off at 1 here (hard
                        // clipping, heard as crackle). Now it is turned down to fit, and its track turned up by as much.
                        val f = FloatArray(lp.data.size) { lp.data[it] / 16384f }
                        val pk = Wav.peak(f)
                        if (pk > 1f) {
                            val g = 0.999f / pk
                            for (i in f.indices) f[i] *= g
                            loopDb = (-20.0 * Math.log10(g.toDouble())).toFloat().coerceIn(0f, 12f)
                        }
                        loopBars = VacuumPlan.loopBars(lp.data.size, lp.rate, song)
                        loopFile = a.store.saveNew("FM-1 Loop", Wav.resample(f, lp.rate, Cfg.SR), Cfg.SR).name
                    }
                }
            } catch (ex: Fm1SampleLink.NoAnswer) {
                err = "The FM-1 did not answer.\n\nVACUUM needs STACK 4.0-beta or newer on the FM-1, connected with the USB cable (Bluetooth carries no songs)."
            } catch (ex: Exception) {
                err = ex.message ?: "Something went wrong while reading the FM-1."
            }
            val sg = song
            if (err != null || sg == null) {
                a.runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(a).setTitle("VACUUM FM-1").setMessage(err ?: "No song came.").setPositiveButton("OK", null).show()
                }
                return@Thread
            }
            say("The sounds…", 0.92f)
            // two plans: on Home as it is (only lanes 1-8 change), and on an empty Home (CLEAR EVERYTHING)
            val has = { n: String -> a.store.file(n).exists() }
            val my = { t: StackTrack -> findMy(t) }
            val plan = VacuumPlan.plan(sg, a.lanes.type, a.lanes.idx, Cfg.TRACKS, Cfg.PADS, has, my, loopFile, loopBars)
            val none = IntArray(a.lanes.type.size)        // (Inst.EMPTY everywhere)
            val planAll = VacuumPlan.plan(sg, none, none, Cfg.TRACKS, Cfg.PADS, has, my, loopFile, loopBars)
            val data = HashMap<String, FloatArray?>()
            for (act in plan.acts + planAll.acts) {
                val f = act.file ?: continue
                if (!data.containsKey(f)) data[f] = try { a.store.loadEngine(f) } catch (ex: Exception) { null }
            }
            a.runOnUiThread {
                dlg.dismiss()
                // 3.57: always asked: an empty Home first, or lanes 1-8 only (what 3.56 did)
                // 3.58 DIRECT SPLIT: and where the steps go: every bar, or one bar (to build a longer song bar by bar)
                val slot = Pat.slot()
                val nBars = Cfg.STEPS / slot
                val where = android.widget.RadioGroup(a)
                where.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
                val opts = listOf("Every bar (the song repeats)") +
                    (0 until nBars).map { "Bar ${it + 1}: steps ${it * slot + 1}–${(it + 1) * slot}" }
                for ((k, t) in opts.withIndex()) {
                    val r = android.widget.RadioButton(a)
                    r.text = t
                    r.id = 100 + k
                    r.setTextColor(Theme.TEXT)
                    where.addView(r)
                }
                where.check(100)
                val head = ui.text("CLEAR EVERYTHING: every lane, pad, bassline, synth, guitar and the song list are " +
                    "cleared first, so only the FM-1's song plays. The library's sounds and the recordings kept in Q1–Q6 stay.\n\n" +
                    "LANES 1–8 ONLY: lanes 1 to 8 get the song, lanes 9 and on and the rest stay as they are.\n\n" +
                    "WHERE: one bar writes that bar's steps only, the other bars stay. A lane that already has a sound keeps it.", 13f, Theme.TEXT)
                head.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0)
                val box = ui.col()
                box.addView(head)
                box.addView(where)
                val sc = android.widget.ScrollView(a)
                sc.addView(box)
                fun bar() = where.checkedRadioButtonId - 101         // -1: every bar
                AlertDialog.Builder(a).setTitle("Where does the FM-1 song go? (STACK ${sg.version})")
                    .setView(sc)
                    .setPositiveButton("Clear everything") { _, _ ->
                        VacuumApply.clearAll(e, a.lanes)
                        apply(sg, planAll, data, bar(), true, loopDb)
                    }
                    .setNeutralButton("Lanes 1–8 only") { _, _ -> apply(sg, plan, data, bar(), false, loopDb) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    /** The library's sound for a sample the app sent to the FM-1: by the CRC kept when it was sent, else by its name. */
    private fun findMy(t: StackTrack): String? {
        val sent = a.getSharedPreferences("fm1sent", Context.MODE_PRIVATE)
        val byCrc = sent.getString(java.lang.Long.toHexString(t.myCrc), null)
        if (byCrc != null && a.store.file(byCrc).exists()) return byCrc
        if (t.name.isEmpty()) return null
        // (the name as it went: letters, digits, space, _ and - of the file's name, capitals, the first eight)
        for (f in a.store.list()) {
            val n = f.nameWithoutExtension.replace(Regex("[^A-Za-z0-9 _-]"), "").uppercase().filter { it.code in 0x20..0x7E }.take(8).trim()
            if (n == t.name) return f.name
        }
        return null
    }

    private fun apply(song: StackSong, plan: VacuumPlanResult, data: Map<String, FloatArray?>, barIn: Int = -1, cleared: Boolean = false,
                      loopDb: Float = 0f) {
        val notes = ArrayList(plan.notes)
        // the song's own: tempo, time signature (the app has no swing: the plan said so)
        if (!e.follow) e.bpm = song.bpm.coerceIn(40, 240)
        else notes.add("TEMPO ${song.bpm}: the app follows a clock now, so its tempo is the clock's.")
        val meter = StackSounds.METER[song.sig.coerceIn(0, 4)]
        if (barIn < 0 || cleared) a.setMeter(meter)
        else if (meter != Pat.meter) notes.add("TIME SIG ${StackSounds.SIG_NAME[song.sig.coerceIn(0, 4)]}: the pattern keeps its meter " +
            "(${Pat.METERS[Pat.meter]}), so its other bars stay as they are.")
        // DIRECT SPLIT: the pattern grows to hold the bar (its new bars start as copies, as the app's own length button does)
        var bar = barIn
        if (bar >= 0) {
            bar = bar.coerceAtMost(Cfg.STEPS / Pat.slot() - 1)
            val need = (bar + 1) * Pat.slot()
            if (Pat.len < need) Pat.lengths().firstOrNull { it >= need }?.let { a.setPatternLength(it) }
        }
        VacuumApply.put(e, a.lanes, song, plan, data, notes, bar, loopDb)
        // 3.59: STACK's drums are clean; the app's drum bus HEAVY (on by default) compresses, lifts and saturates them
        if (!cleared && e.drumHeavy.amount > 0.001f && plan.acts.any { it.type == VacuumPlan.DRUM })
            notes.add("Drums · HEAVY is at ${(e.drumHeavy.amount * 100).toInt()}% (Pads page): the FM-1 has no such thing, and it makes " +
                "the drums louder and dirtier than STACK's. Turn it to 0 to hear them as on the FM-1.")
        a.homeScreen.refresh()
        a.refreshCurrent()
        val at = if (bar < 0) "on lanes 1 to 8" else "on lanes 1 to 8, bar ${bar + 1} (steps ${bar * Pat.slot() + 1}–${(bar + 1) * Pat.slot()}; the other bars are as they were)"
        val head = "The FM-1's song is $at, in its own order: ${song.bpm} BPM, ${StackSounds.SIG_NAME[song.sig.coerceIn(0, 4)]}." +
            (plan.acts.firstOrNull { it.loop && it.type != VacuumPlan.EMPTY }?.takeIf { bar < 0 }?.let {
                " The loop of track ${it.lane + 1} is on lane ${it.lane + 1}, as a looping sample of ${it.fit} bar${if (it.fit > 1) "s" else ""}."
            } ?: "") +
            "\n\nCarried over: sounds, steps and their notes, LEVEL, PITCH, FILTER, EDIT's START / LENGTH / REVERSE, FIT of 1, 2 or 4 bars, " +
            "and the effects the app has (DELAY, ECHO, REVERB, OVERDRIVE, CHORUS, LO-FI, ROBOT; FLANGER as the Phaser).\n" +
            "Not carried over: SWING, ENV, LFO, ARP, DECAY, TAPE SPEED, and anything PERFORM was doing."
        val msg = TextView(a)
        msg.text = if (notes.isEmpty()) head else head + "\n\nTo know:\n• " + notes.joinToString("\n• ")
        msg.textSize = 13f
        msg.setTextColor(Theme.TEXT)
        msg.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4))
        val sc = android.widget.ScrollView(a)
        sc.addView(msg)
        AlertDialog.Builder(a).setTitle("VACUUM done").setView(sc).setPositiveButton("OK", null).show()
    }
}

/**
 * The plan put on the engine's tracks and pads and on Home's lanes: nothing here but the app's own model (a Sampler
 * track or a Drum pad with a sound of the library and steps), so what it makes is what a hand makes there, and TAKE
 * ALL, the queue and 16 Track take it as such (tools/vacuum_check checks exactly that).
 */
object VacuumApply {
    /**
     * 3.57 CLEAR EVERYTHING: nothing on Home plays any more. Every sampler track and pad: steps off, the sound back to
     * clean (its sample stays loaded); the bassline, synth, guitars, New Ambient and automation: empty; Q1-Q6: their
     * patterns blank, their recordings kept but not playing; the song list empty; every lane EMPTY (VACUUM's plan
     * then fills lanes 1 to 8). Nothing in the library is touched.
     */
    fun clearAll(e: Engine, lanes: LaneSetup) {
        val b = e.bank
        b.songOn = false
        b.songLen = 0
        b.songIdx = 0
        b.songBar = 0
        b.cueReq = -1
        for (i in 0 until PartBank.N) if (b.layerOn[i]) b.toggleLayer(i)     // (the recordings stop, they stay in Q)
        for (t in e.tracks) {
            t.clearSteps()
            t.freshSound()
            t.mute = false
        }
        for (p in e.pads) {
            p.on.fill(false)
            p.lvl.fill(1f)
            p.mute = false
        }
        e.bass.clear()
        e.analogSeq.clear()
        for (g in arrayOf(e.rhythmGtr, e.riffGtr)) {
            g.art.fill(MetalGuitar.OFF)
            g.fret.fill(0)
            g.audition(MetalGuitar.OFF, 0)
        }
        e.ambient.steps.fill(false)
        e.drumHeavy.amount = 0f        // 3.59: the drum bus clean, as STACK's drums are (HEAVY saturates them)
        for (k in e.auto.targets.indices) e.auto.clear(k)
        for (p in b.parts) {
            p.blank()
            p.used = false
        }
        b.version++
        for (i in lanes.type.indices) {
            lanes.type[i] = Inst.EMPTY
            lanes.idx[i] = 0
        }
    }

    /**
     * The plan on the engine and the lanes. [bar] -1: the song's steps in every bar of the pattern (3.56). [bar] 0 and
     * on (3.58 DIRECT SPLIT): only that bar's steps are written, the other bars stay; a lane that already plays a sound
     * of the same kind (Sampler / Drum) keeps it, with its settings, and only takes the steps (a different sound is
     * said, by name); a lane that does not gets its sound and the rest of its steps off. A track or pad that a lane
     * keeps is never given to another lane.
     */
    fun put(e: Engine, lanes: LaneSetup, song: StackSong, plan: VacuumPlanResult, data: Map<String, FloatArray?>,
            notes: MutableList<String>, bar: Int = -1, loopDb: Float = 0f) {
        val slot = Pat.slot()
        val bars = Pat.bars()
        // DIRECT SPLIT: which lanes keep their own track or pad, and what every other lane may take
        val keep = BooleanArray(8)
        val acts = plan.acts.toMutableList()
        if (bar >= 0) {
            val taken = HashSet<Pair<Int, Int>>()
            for (i in 8 until lanes.type.size) taken.add(lanes.type[i] to lanes.idx[i])
            for (act in plan.acts) {
                if (act.type == VacuumPlan.EMPTY) continue
                val ty = lanes.type[act.lane]
                val k = lanes.idx[act.lane]
                val has = when {
                    act.type == VacuumPlan.SAMPLE && ty == Inst.SAMPLE -> e.tracks[k].data != null
                    act.type == VacuumPlan.DRUM && ty == Inst.DRUM -> e.pads[k].data != null
                    else -> false
                }
                if (has) {
                    keep[act.lane] = true
                    taken.add(ty to k)
                    acts[act.lane] = LaneAct(act.lane, act.type, k, act.track, act.file, act.semis, act.loop, act.fit)
                }
            }
            for (act in plan.acts) {
                if (act.type == VacuumPlan.EMPTY || keep[act.lane]) continue
                val inst = if (act.type == VacuumPlan.SAMPLE) Inst.SAMPLE else Inst.DRUM
                var k = act.idx
                if ((inst to k) in taken) {
                    val n = if (inst == Inst.SAMPLE) Cfg.TRACKS else Cfg.PADS
                    k = (0 until n).firstOrNull { (inst to it) !in taken } ?: -1
                }
                if (k < 0) {
                    notes.add("T${act.lane + 1} ${act.track.label}: no ${if (inst == Inst.SAMPLE) "Sampler track" else "Drum pad"} is free. Lane ${act.lane + 1} stays as it was.")
                    acts[act.lane] = LaneAct(act.lane, VacuumPlan.EMPTY, 0, act.track, null, act.semis, act.loop, act.fit)
                    keep[act.lane] = true          // (nothing is written there)
                    continue
                }
                taken.add(inst to k)
                acts[act.lane] = LaneAct(act.lane, act.type, k, act.track, act.file, act.semis, act.loop, act.fit)
            }
        }
        // the steps of the bar(s) written: every bar, or the one of DIRECT SPLIT
        val barsTo = if (bar >= 0) listOf(bar) else (0 until bars).toList()
        if (bar < 0 && song.pages > bars)          // (3.64: STACK's pages past this pattern's bars are not here)
            notes.add("STACK's pattern is ${song.pages} bars and this one is $bars: STACK's " +
                (if (bars + 1 == song.pages) "bar ${song.pages}" else "bars ${bars + 1}-${song.pages}") + " did not come. " +
                "Make this pattern longer and VACUUM again to bring " + (if (bars + 1 == song.pages) "it." else "them."))
        for (act in acts) {
            val s = act.track
            val v = s.set
            val kept = bar >= 0 && keep[act.lane]
            if (kept && act.type == VacuumPlan.EMPTY) continue       // (DIRECT SPLIT: no room: the lane as it was)
            val d = if (act.file != null) data[act.file] else null
            if (!kept && act.type != VacuumPlan.EMPTY && d == null) {
                notes.add("T${act.lane + 1} ${s.label}: \"${act.file}\" could not be read. Lane ${act.lane + 1} is left empty.")
            }
            if (act.type == VacuumPlan.EMPTY || (!kept && d == null)) {
                if (bar < 0) {                     // (DIRECT SPLIT: a lane that gets nothing stays as it was)
                    lanes.type[act.lane] = Inst.EMPTY
                    lanes.idx[act.lane] = 0
                }
                continue
            }
            if (act.type == VacuumPlan.SAMPLE) {
                val t = e.tracks[act.idx]
                if (kept) {
                    if (t.sampleName != act.file) notes.add("T${act.lane + 1} ${s.label}: lane ${act.lane + 1} keeps its sound " +
                        "\"${t.sampleName.removeSuffix(".wav")}\" (one sound a lane); only the steps of this bar came.")
                    if (act.loop || t.loop) {
                        if (act.loop) notes.add("T${act.lane + 1}: lane ${act.lane + 1} keeps its loop; the new loop is in the library as \"${act.file?.removeSuffix(".wav")}\".")
                        continue
                    }
                    val want = v[StackTrack.PITCH] + act.semis
                    val rest = want - t.pitch
                    for (b in barsTo) for (i in 0 until slot) {
                        val st = t.steps[(b * slot + i).coerceIn(0, Cfg.STEPS - 1)]
                        val j = song.pageOf(b) * 16 + i     // (3.64: bar b gets STACK's page b, round again)
                        st.on = i < song.steps && s.on[j]
                        st.pitch = if (i >= song.steps) 0 else if (s.hit) rest else s.note[j] - 60 + rest
                        st.level = 1f
                    }
                    continue
                }
                t.freshSound()
                t.clearSteps()
                e.setSample(act.idx, act.file!!, d!!)
                t.mute = false
                t.volume = VacuumPlan.trackVolume(v[StackTrack.LEVEL])
                if (act.loop) {                  // the loop: it goes round with its bars, at the tempo's length
                    t.loop = true
                    t.fitBars = act.fit          // 3.59: 1 or more whole bars (was always 1: a longer loop ran too fast)
                    t.boostDb = loopDb           // (a loop over full level was turned down in its file: as loud as it was)
                } else {
                    // STACK's middle C is the sound's own note: the track's pitch brings the sample there (as far as
                    // its knob goes), the rest and each step's note go on the steps
                    val want = v[StackTrack.PITCH] + act.semis
                    t.pitch = want.coerceIn(-24, 24)
                    val rest = want - t.pitch
                    for (b in barsTo) for (i in 0 until song.steps) {
                        val st = t.steps[(b * slot + i).coerceIn(0, Cfg.STEPS - 1)]
                        val j = song.pageOf(b) * 16 + i
                        st.on = s.on[j]
                        st.pitch = if (s.hit) rest else s.note[j] - 60 + rest
                        st.level = 1f
                    }
                    t.start = v[StackTrack.ED_START] / 100f
                    t.end = (t.start + (1f - t.start) * v[StackTrack.ED_LEN] / 100f).coerceIn(t.start + 0.01f, 1f)
                    t.reverse = v[StackTrack.ED_REV] != 0
                    when (v[StackTrack.FIT]) {   // (a quarter and half a bar: the app fits whole bars only)
                        3 -> { t.loop = true; t.fitBars = 1 }
                        4 -> { t.loop = true; t.fitBars = 2 }
                        5 -> { t.loop = true; t.fitBars = 4 }
                    }
                    VacuumPlan.filter(v[StackTrack.FILTER])?.let { (k, cut) ->
                        t.fx.on[k] = true
                        t.fx.a[k] = cut
                        t.fx.b[k] = 0f           // 3.59: no resonance (Q 0.7), as STACK's filter (it had a +3.5 dB bump)
                    }
                    for (q in 0..1) {
                        val kn = v[StackTrack.FX_AK + q]
                        if (kn in -3..3) continue
                        val amt = ((kotlin.math.abs(kn) - 3) / 50f).coerceIn(0f, 1f)
                        // the nearest effect the app's chain has (FLANGER: its Phaser); ROBOT is the track's own
                        val k = when (v[StackTrack.FX_A + q]) { 1 -> 3; 2 -> FxChain.ECHO; 3 -> 2; 6 -> 6; 7 -> 4; 8 -> 5; 9 -> 7; else -> -1 }
                        if (v[StackTrack.FX_A + q] == 5) t.robot = 0.1f + 0.9f * amt
                        if (k >= 0) {
                            t.fx.on[k] = true
                            t.fx.a[k] = (0.15f + 0.6f * amt).coerceIn(0f, 1f)
                            if (k == 2) t.fx.b[k] = if (kn < 0) 0.3f else 0.8f      // REVERB: ROOM left, HALL right
                        }
                    }
                }
                lanes.type[act.lane] = Inst.SAMPLE
                lanes.idx[act.lane] = act.idx
            } else {
                val p = e.pads[act.idx]
                if (kept) {
                    if (p.name != act.file) notes.add("T${act.lane + 1} ${s.label}: lane ${act.lane + 1} keeps its sound " +
                        "\"${p.name.removeSuffix(".wav")}\" (one sound a lane); only the steps of this bar came.")
                    for (b in barsTo) for (i in 0 until slot) {
                        val q = (b * slot + i).coerceIn(0, Cfg.STEPS - 1)
                        p.on[q] = i < song.steps && s.on[song.pageOf(b) * 16 + i]
                        p.lvl[q] = 1f
                    }
                    continue
                }
                e.setPad(act.idx, act.file!!, d!!)
                p.mute = false
                p.volume = VacuumPlan.padVolume(v[StackTrack.LEVEL])
                for (k in 0 until Cfg.STEPS) {
                    p.on[k] = false
                    p.lvl[k] = 1f
                }
                for (b in barsTo) for (i in 0 until song.steps) p.on[(b * slot + i).coerceIn(0, Cfg.STEPS - 1)] = s.on[song.pageOf(b) * 16 + i]
                lanes.type[act.lane] = Inst.DRUM
                lanes.idx[act.lane] = act.idx
            }
        }
    }
}
