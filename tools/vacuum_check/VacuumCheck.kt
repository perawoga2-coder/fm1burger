import com.example.fm1burger.*
import java.io.File

/**
 * 3.56: VACUUM, then TAKE ALL, checked off the tablet (3.57: and CLEAR EVERYTHING first). Not part of the app: it is compiled with the app's sources
 * and run on a computer (tools/vacuum_check/README.md).
 *
 * stack_song.txt is a song as STACK 4.0-beta's simulator sent it (command 30's seven answers): three drums with
 * steps, a bass with notes, a filter, a level and a reverb, the piano, the lead, a pad on track 7 and the loop on
 * track 8, at 110 BPM with swing 58. The check reads it, plans, puts it on a real Engine and LaneSetup, and then
 * does what TAKE ALL's SEND and RECALL do (PartBank.send / recall, unchanged), and looks at what came back.
 */
var bad = 0
fun check(ok: Boolean, what: String) {
    println((if (ok) "  ok   " else "FAIL   ") + what)
    if (!ok) bad++
}

/** the lanes TAKE ALL counts (HomeScreen.lanesInUse's rule for Sampler and Drum lanes) */
fun inUse(e: Engine, lanes: LaneSetup): Int = (0 until LaneSetup.N).count { i ->
    val k = lanes.idx[i]
    when (lanes.type[i]) {
        Inst.SAMPLE -> e.tracks[k].data != null && (e.tracks[k].loop || e.tracks[k].steps.any { it.on })
        Inst.DRUM -> e.pads[k].data != null && e.pads[k].on.any { it }
        else -> false
    }
}

fun main(args: Array<String>) {
    // the seven answers -> the record
    val rec = java.io.ByteArrayOutputStream()
    for (line in File(args[0]).readLines()) {
        val b = line.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
        if (b.size < 10) continue
        rec.write(StackDump.unpack7(b.copyOfRange(9, b.size - 1), 0))      // (F0 7D 46 4C 1E, piece 2, pieces 2: 9 bytes)
    }
    val song = StackDump.parse(rec.toByteArray())
    check(song != null, "the record is read: its magic, version, length and CRC are right")
    song ?: return
    check(song.bpm == 110 && song.sig == 2 && song.steps == 16 && song.swing == 58, "tempo 110, 4/4 (16 steps), swing 58")
    check(song.hasLoop && song.tracks[7].kind == 3, "track 8 is the looper, with a loop")
    check(song.tracks.map { it.name } == listOf("KICK ROCK", "SNARE ROCK", "HAT ROCK", "SUB BASS", "PIANO", "SAW LEAD", "STRINGS", "LOOP"),
        "the eight tracks' names: " + song.tracks.joinToString { it.name })
    val damaged = rec.toByteArray().also { it[100] = (it[100] + 1).toByte() }
    check(StackDump.parse(damaged) == null, "a damaged record is refused")
    check(StackSounds.MAP.size == 69, "all 69 of STACK's sounds have one of the app's")

    val e = Engine()
    val lanes = LaneSetup()                      // Home as it starts: lanes 9+ show pads A4.. and the instruments
    val before = (8 until LaneSetup.N).map { lanes.type[it] to lanes.idx[it] }
    val sound = HashMap<String, FloatArray>()
    fun data(n: String) = sound.getOrPut(n) { FloatArray(2000) { 0.1f } }
    e.bank.soundLoader = { n -> data(n) }
    // something of the user's on a pad that a later lane shows: it must stay
    val keepPad = lanes.idx[9]
    e.setPad(keepPad, "Mine.wav", data("Mine.wav"))
    e.pads[keepPad].on[3] = true

    Pat.meter = StackSounds.METER[song.sig]
    val plan = VacuumPlan.plan(song, lanes.type, lanes.idx, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav")
    val files = HashMap<String, FloatArray?>()
    for (a in plan.acts) a.file?.let { files[it] = data(it) }
    val notes = ArrayList(plan.notes)
    VacuumApply.put(e, lanes, song, plan, files, notes)

    println("the lanes: " + (0 until 8).joinToString { "${it + 1}=${lanes.label(it)}" })
    check((0 until 8).all { lanes.type[it] == Inst.SAMPLE || lanes.type[it] == Inst.DRUM || lanes.type[it] == Inst.EMPTY },
        "lanes 1-8 are ordinary lanes (Sampler, Drum or Empty): no other kind")
    check((0..2).all { lanes.type[it] == Inst.DRUM } && (3..7).all { lanes.type[it] == Inst.SAMPLE }, "T1-T3 drums -> Drum pads, T4-T8 -> Sampler tracks, in STACK's order")
    check((0 until 8).map { lanes.type[it] to lanes.idx[it] }.distinct().size == 8, "no two lanes share a track or a pad")
    check((8 until LaneSetup.N).map { lanes.type[it] to lanes.idx[it] } == before, "lanes 9 and on are as they were")
    check(e.pads[keepPad].name == "Mine.wav" && e.pads[keepPad].on[3], "a pad a later lane shows was not taken")
    val kick = e.pads[lanes.idx[0]]
    check(kick.name == "Rock Kick.wav" && (0 until 16).map { kick.on[it] } == song.tracks[0].on.toList(), "the kick: the app's Rock Kick, its steps")
    val bass = e.tracks[lanes.idx[3]]
    check(bass.sampleName == "Sub Bass C3.wav", "the bass: the app's Sub Bass C3")
    check(bass.pitch == 2 + 12, "its pitch: PITCH 2 and the octave that makes STACK's middle C the sample's C3 (${bass.pitch})")
    check(bass.steps[0].on && bass.steps[0].pitch == -12 && bass.steps[3].on && bass.steps[3].pitch == 0, "its steps' notes (step 1 an octave down)")
    check(Math.abs(bass.volume - 70 * 70 / 8000f) < 1e-4f, "its level 70 -> volume ${bass.volume}")
    check(bass.fx.on[0] && bass.fx.a[0] in 0.3f..0.95f, "its FILTER -20 -> the Low-pass, cutoff ${bass.fx.a[0]}")
    check(bass.fx.on[2], "its REVERB -> the app's Reverb")
    val loop = e.tracks[lanes.idx[7]]
    check(loop.sampleName == "FM-1 Loop.wav" && loop.loop && loop.fitBars == 1, "track 8's loop -> a Sampler track that loops, a bar long")
    check(notes.any { it.contains("SWING") }, "said: the app has no swing")

    // --- TAKE ALL: SEND to Q1, as its panel does (PartBank.send, untouched), then the screen wiped, then RECALL
    val n = inUse(e, lanes)
    check(n >= 8, "TAKE ALL counts the eight vacuumed lanes as in use ($n with the user's own pad)")
    fun preload() {                              // (as MainActivity.preloadBank: the slots' sounds read off the audio thread)
        val done = java.util.concurrent.CountDownLatch(1)
        e.bank.preloadSounds { done.countDown() }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        e.bank.soundsPending?.let {
            it.applyMix(e, false)
            e.bank.soundsPending = null
        }
    }
    e.bank.send(0, n)
    preload()
    val p = e.bank.parts[0]
    check(p.used && p.full, "Q1 holds a whole part after SEND")
    val bassSteps = bass.steps.map { it.on to it.pitch }
    val kickOn = kick.on.clone()
    // (a slot keeps the lanes' sounds, steps and volumes; a track's own settings, its LOOP among them, stay with the
    // track, for a vacuumed lane as for a hand-made one)
    for (t in e.tracks) { t.clearSteps(); t.volume = 0.1f; e.setSample(t.index, "", null) }
    for (q in e.pads) { java.util.Arrays.fill(q.on, false); q.name = ""; q.data = null }
    e.bank.recall(p)
    preload()
    check(bass.steps.map { it.on to it.pitch } == bassSteps, "RECALL: the bass's steps and notes are back")
    check(kick.on.contentEquals(kickOn), "RECALL: the kick's steps are back")
    check(bass.sampleName == "Sub Bass C3.wav" && bass.data != null && kick.name == "Rock Kick.wav" && kick.data != null, "RECALL: their sounds are back, by their library names")
    check(loop.sampleName == "FM-1 Loop.wav" && loop.data != null && loop.loop, "RECALL: the loop's sound is back on its looping track")

    // --- more than five tracks that are no drums: the extras go to pads, said by name
    val many = StackSong(song.bpm, song.sig, song.steps, song.swing, song.layers, song.hasLoop, song.version,
        song.tracks.mapIndexed { i, t -> if (i < 3) song.tracks[3] else t })
    val lanes2 = LaneSetup()
    val plan2 = VacuumPlan.plan(many, lanes2.type, lanes2.idx, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav")
    check(plan2.acts.count { it.type == VacuumPlan.SAMPLE } == 5 && plan2.acts.count { it.type == VacuumPlan.DRUM } == 3, "8 tracks that are no drums: 5 Sampler tracks, 3 pads")
    check(plan2.acts[7].type == VacuumPlan.SAMPLE && plan2.acts[7].loop, "the loop keeps a Sampler track (only one loops)")
    check(plan2.notes.any { it.contains("play from Drum pads") && it.contains("T") }, "said by name: " + plan2.notes.first { it.contains("Drum pads") })
    check(plan2.acts.none { it.type == VacuumPlan.EMPTY }, "no track is dropped")
    // a sound that is not in the library: the lane is left empty, and said
    val plan3 = VacuumPlan.plan(song, lanes2.type, lanes2.idx, Cfg.TRACKS, Cfg.PADS, { it != "Rock Kick.wav" }, { null }, null)
    check(plan3.acts[0].type == VacuumPlan.EMPTY && plan3.notes.any { it.startsWith("T1") }, "a sound not in the library: its lane empty, and said")

    // --- 3.57 CLEAR EVERYTHING: Home full of the user's work, cleared, then the song on an empty Home
    val e4 = Engine()
    e4.bank.soundLoader = { n -> data(n) }
    val lanes4 = LaneSetup()
    for (k in 0 until Cfg.PADS) { e4.setPad(k, "Old$k.wav", data("Old$k.wav")); e4.pads[k].on[k % 16] = true }
    for (t in e4.tracks) { e4.setSample(t.index, "OldT${t.index}.wav", data("OldT${t.index}.wav")); t.steps[2].on = true; t.loop = true }
    e4.bass.on[0] = true; e4.bass.note[0] = 5
    e4.analogSeq.grid[0] = 60
    e4.rhythmGtr.art[0] = MetalGuitar.CHUG; e4.riffGtr.art[1] = MetalGuitar.OPEN
    e4.ambient.steps[4] = true
    e4.bank.parts[2].pOn[0][0] = true; e4.bank.parts[2].used = true
    e4.bank.songLen = 3; e4.bank.songOn = true
    val none = IntArray(lanes4.type.size)
    val plan4 = VacuumPlan.plan(song, none, none, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav")
    val files4 = HashMap<String, FloatArray?>()
    for (a in plan4.acts) a.file?.let { files4[it] = data(it) }
    VacuumApply.clearAll(e4, lanes4)
    VacuumApply.put(e4, lanes4, song, plan4, files4, ArrayList())
    val used = (0 until 8).map { lanes4.type[it] to lanes4.idx[it] }.toSet()
    check((8 until LaneSetup.N).all { lanes4.type[it] == Inst.EMPTY }, "CLEAR EVERYTHING: lanes 9 and on are empty")
    check((0 until Cfg.PADS).filter { (Inst.DRUM to it) !in used }.all { k -> e4.pads[k].on.none { it } }, "CLEAR EVERYTHING: no other pad has a step on")
    check((0 until Cfg.TRACKS).filter { (Inst.SAMPLE to it) !in used }.all { k -> e4.tracks[k].steps.none { it.on } && !e4.tracks[k].loop },
        "CLEAR EVERYTHING: no other sampler track has a step on or loops")
    check(e4.bass.on.none { it } && e4.analogSeq.grid.all { it == 0 }, "CLEAR EVERYTHING: the bassline and the synth are empty")
    check(e4.rhythmGtr.art.all { it == MetalGuitar.OFF } && e4.riffGtr.art.all { it == MetalGuitar.OFF } && e4.ambient.steps.none { it },
        "CLEAR EVERYTHING: the guitars and New Ambient are empty")
    check(e4.bank.songLen == 0 && !e4.bank.songOn && e4.bank.parts.all { q -> !q.used && q.pOn.all { r -> r.none { it } } },
        "CLEAR EVERYTHING: the song list is empty, Q1-Q6 hold no pattern")
    val kick4 = e4.pads[lanes4.idx[0]]
    check(lanes4.type[0] == Inst.DRUM && kick4.name == "Rock Kick.wav" && (0 until 16).map { kick4.on[it] } == song.tracks[0].on.toList(),
        "CLEAR EVERYTHING: then the FM-1's song is on lanes 1-8 (the kick: ${kick4.name})")
    check((0 until 8).map { lanes4.type[it] to lanes4.idx[it] }.distinct().size == 8, "CLEAR EVERYTHING: no two lanes share a track or a pad")
    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    if (bad != 0) System.exit(1)
}
