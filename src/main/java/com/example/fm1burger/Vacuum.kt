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
 * LANES 1-8 ONLY (lanes 9 and on, and the sampler tracks and pads they show, are not touched). What could not be carried over is listed at the end, never dropped
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
            try {
                val blob = link.dumpSong { f -> say("The song…", 0.1f * f) }
                song = StackDump.parse(blob) ?: throw IllegalStateException("The FM-1's answer was damaged on the way. Try again.")
                if (song.hasLoop && song.tracks.any { it.kind == 3 }) {
                    say("The loop on track 8…", 0.1f)
                    val lp = link.dumpLoop { f -> say("The loop on track 8…", 0.1f + 0.8f * f) }
                    if (lp != null) {
                        // 16 bits where 16384 is full level -> floats; to the engine's rate; kept in the library as any sound
                        val f = FloatArray(lp.data.size) { (lp.data[it] / 16384f).coerceIn(-1f, 1f) }
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
            val plan = VacuumPlan.plan(sg, a.lanes.type, a.lanes.idx, Cfg.TRACKS, Cfg.PADS, has, my, loopFile)
            val none = IntArray(a.lanes.type.size)        // (Inst.EMPTY everywhere)
            val planAll = VacuumPlan.plan(sg, none, none, Cfg.TRACKS, Cfg.PADS, has, my, loopFile)
            val data = HashMap<String, FloatArray?>()
            for (act in plan.acts + planAll.acts) {
                val f = act.file ?: continue
                if (!data.containsKey(f)) data[f] = try { a.store.loadEngine(f) } catch (ex: Exception) { null }
            }
            a.runOnUiThread {
                dlg.dismiss()
                // 3.57: always asked: an empty Home first, or lanes 1-8 only (what 3.56 did)
                AlertDialog.Builder(a).setTitle("Where does the FM-1 song go? (STACK ${sg.version})")
                    .setMessage("CLEAR EVERYTHING: every lane, pad, bassline, synth, guitar and the song list are " +
                        "cleared first, so only the FM-1's song plays. The library's sounds and the recordings kept in Q1–Q6 stay.\n\n" +
                        "LANES 1–8 ONLY: lanes 1 to 8 are replaced, lanes 9 and on and the rest stay as they are.")
                    .setPositiveButton("Clear everything") { _, _ ->
                        VacuumApply.clearAll(e, a.lanes)
                        apply(sg, planAll, data)
                    }
                    .setNeutralButton("Lanes 1–8 only") { _, _ -> apply(sg, plan, data) }
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

    private fun apply(song: StackSong, plan: VacuumPlanResult, data: Map<String, FloatArray?>) {
        val notes = ArrayList(plan.notes)
        // the song's own: tempo, time signature (the app has no swing: the plan said so)
        if (!e.follow) e.bpm = song.bpm.coerceIn(40, 240)
        else notes.add("TEMPO ${song.bpm}: the app follows a clock now, so its tempo is the clock's.")
        a.setMeter(StackSounds.METER[song.sig.coerceIn(0, 4)])
        VacuumApply.put(e, a.lanes, song, plan, data, notes)
        a.homeScreen.refresh()
        a.refreshCurrent()
        val head = "The FM-1's song is on lanes 1 to 8, in its own order: ${song.bpm} BPM, ${StackSounds.SIG_NAME[song.sig.coerceIn(0, 4)]}." +
            (if (plan.acts.any { it.loop && it.type != VacuumPlan.EMPTY }) " The loop of track 8 is on lane 8, as a looping sample." else "") +
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

    fun put(e: Engine, lanes: LaneSetup, song: StackSong, plan: VacuumPlanResult, data: Map<String, FloatArray?>, notes: MutableList<String>) {
        val slot = Pat.slot()
        val bars = Pat.bars()
        for (act in plan.acts) {
            val s = act.track
            val v = s.set
            val d = if (act.file != null) data[act.file] else null
            if (act.type != VacuumPlan.EMPTY && d == null) {
                notes.add("T${act.lane + 1} ${s.label}: \"${act.file}\" could not be read. Lane ${act.lane + 1} is left empty.")
            }
            if (act.type == VacuumPlan.EMPTY || d == null) {
                lanes.type[act.lane] = Inst.EMPTY
                lanes.idx[act.lane] = 0
                continue
            }
            if (act.type == VacuumPlan.SAMPLE) {
                val t = e.tracks[act.idx]
                t.freshSound()
                t.clearSteps()
                e.setSample(act.idx, act.file!!, d)
                t.mute = false
                t.volume = VacuumPlan.trackVolume(v[StackTrack.LEVEL])
                if (act.loop) {                  // the loop: it goes round with the bar, at the tempo's length
                    t.loop = true
                    t.fitBars = 1
                    t.volume = 0.8f
                } else {
                    // STACK's middle C is the sound's own note: the track's pitch brings the sample there (as far as
                    // its knob goes), the rest and each step's note go on the steps
                    val want = v[StackTrack.PITCH] + act.semis
                    t.pitch = want.coerceIn(-24, 24)
                    val rest = want - t.pitch
                    for (b in 0 until bars) for (i in 0 until song.steps) {
                        val st = t.steps[(b * slot + i).coerceIn(0, Cfg.STEPS - 1)]
                        st.on = s.on[i]
                        st.pitch = if (s.hit) rest else s.note[i] - 60 + rest
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
                e.setPad(act.idx, act.file!!, d)
                p.mute = false
                p.volume = VacuumPlan.padVolume(v[StackTrack.LEVEL])
                for (k in 0 until Cfg.STEPS) {
                    p.on[k] = false
                    p.lvl[k] = 1f
                }
                for (b in 0 until bars) for (i in 0 until song.steps) p.on[(b * slot + i).coerceIn(0, Cfg.STEPS - 1)] = s.on[i]
                lanes.type[act.lane] = Inst.DRUM
                lanes.idx[act.lane] = act.idx
            }
        }
    }
}
