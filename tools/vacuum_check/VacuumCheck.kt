import com.example.fm1burger.*
import java.io.File

/**
 * 3.56: VACUUM, then TAKE ALL, checked off the tablet (3.57: and CLEAR EVERYTHING first; 3.58: DIRECT SPLIT). Not part of the app: it is compiled with the app's sources
 * and run on a computer (tools/vacuum_check/README.md).
 *
 * stack_song.txt is a song as STACK 4.0-beta's simulator sent it (command 30's seven answers): three drums with
 * steps, a bass with notes, a filter, a level and a reverb, the piano, the lead, a pad on track 7 and the loop on
 * track 8, at 110 BPM with swing 58. The check reads it, plans, puts it on a real Engine and LaneSetup, and then
 * does what TAKE ALL's SEND and RECALL do (PartBank.send / recall, unchanged), and looks at what came back.
 *
 * 3.59: stack_song_405.txt is a song as STACK 4.05-beta's simulator sent it (app/tests/dump.txt, after its first
 * take): 3/4 at 100 BPM, the loop moved to track 7 (kind 3, the head's flag bit 3), track 8 its own sound again. And a
 * loop of 1 to 4 bars (since 4.02-beta) gets the FIT of as many bars.
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

/** command 30's answers, one a line -> the record */
fun readRecord(f: File): ByteArray {
    val rec = java.io.ByteArrayOutputStream()
    for (line in f.readLines()) {
        val b = line.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
        if (b.size < 10) continue
        rec.write(StackDump.unpack7(b.copyOfRange(9, b.size - 1), 0))      // (F0 7D 46 4C 1E, piece 2, pieces 2: 9 bytes)
    }
    return rec.toByteArray()
}

fun main(args: Array<String>) {
    // the seven answers -> the record
    val rec = java.io.ByteArrayOutputStream()
    rec.write(readRecord(File(args[0])))
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
    check(Math.abs(kick.volume - 0.8f) < 1e-4f && Math.abs(kick.volume * e.padVol - 0.64f) < 1e-4f,
        "3.59: the kick's LEVEL 80 -> pad volume ${kick.volume}: with the Pads master, STACK's own gain 0.64")
    check(bass.fx.b[0] == 0f, "3.59: the bass's Low-pass has no resonance (as STACK's filter)")
    check(Math.abs(loop.volume - 0.8f) < 1e-4f && loop.boostDb == 0f, "3.59: the loop's LEVEL 80 -> volume ${loop.volume}, no boost (its peak fit)")

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

    // --- 3.58 DIRECT SPLIT: bar 1 on an empty Home, then a changed song into bar 2, lanes 1-8 only
    val e5 = Engine()
    e5.bank.soundLoader = { n -> data(n) }
    val lanes5 = LaneSetup()
    Pat.len = 16
    VacuumApply.clearAll(e5, lanes5)
    VacuumApply.put(e5, lanes5, song, plan4, files4, ArrayList(), 0)
    val k5 = lanes5.idx[0]
    val b5 = e5.tracks[lanes5.idx[3]]
    check((0 until 16).map { e5.pads[k5].on[it] } == song.tracks[0].on.toList() && (16 until 64).none { e5.pads[k5].on[it] }, "SPLIT bar 1: the kick's steps in bar 1 only")
    check((0 until 16).map { b5.steps[it].on } == song.tracks[3].on.toList() && (16 until 64).none { b5.steps[it].on }, "SPLIT bar 1: the bass's steps in bar 1 only")
    val bar1Kick = e5.pads[k5].on.copyOf(16)
    val bar1Bass = (0 until 16).map { b5.steps[it].on to b5.steps[it].pitch }
    val bassPitch = b5.pitch
    e5.setPad(k5, "Other.wav", data("Other.wav"))          // the user changed lane 1's sound meanwhile
    val song2 = StackDump.parse(rec.toByteArray())!!
    for (i in 0 until 16) { song2.tracks[0].on[i] = i % 4 == 1; song2.tracks[3].on[i] = i % 2 == 0 }
    Pat.len = 32
    val plan5 = VacuumPlan.plan(song2, lanes5.type, lanes5.idx, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop 2.wav")
    val files5 = HashMap<String, FloatArray?>()
    for (a in plan5.acts) a.file?.let { files5[it] = data(it) }
    val notes5 = ArrayList<String>()
    val lanesBefore = (0 until LaneSetup.N).map { lanes5.type[it] to lanes5.idx[it] }
    VacuumApply.put(e5, lanes5, song2, plan5, files5, notes5, 1)
    check((0 until LaneSetup.N).map { lanes5.type[it] to lanes5.idx[it] } == lanesBefore, "SPLIT bar 2: every lane keeps its own track or pad")
    check(e5.pads[k5].on.copyOf(16).contentEquals(bar1Kick) && b5.steps.take(16).map { it.on to it.pitch } == bar1Bass, "SPLIT bar 2: bar 1 is as it was")
    check((16 until 32).map { e5.pads[k5].on[it] } == (0 until 16).map { it % 4 == 1 }, "SPLIT bar 2: the kick's new steps are in bar 2")
    check((16 until 32).map { b5.steps[it].on } == (0 until 16).map { it % 2 == 0 } && b5.pitch == bassPitch, "SPLIT bar 2: the bass's new steps, its sound and pitch kept")
    check(e5.pads[k5].name == "Other.wav" && notes5.any { it.startsWith("T1") && it.contains("keeps its sound") }, "SPLIT bar 2: lane 1 keeps the user's sound, and it is said")
    val loop5 = e5.tracks[lanes5.idx[7]]
    check(loop5.sampleName == "FM-1 Loop.wav" && loop5.loop && notes5.any { it.startsWith("T8") && it.contains("keeps its loop") }, "SPLIT bar 2: the loop lane keeps its loop, and it is said")
    // --- 3.62: patterns up to 8 bars (128 steps); SPLIT can fill bar 8
    check(Cfg.STEPS == 128 && Pat.MAX_BARS == 8, "3.62: room for 8 bars (${Cfg.STEPS} steps, ${Pat.MAX_BARS} bar buttons)")
    val m0 = Pat.meter
    Pat.meter = 0
    check(Pat.lengths().toList() == listOf(16, 32, 64, 128), "3.62: 4/4 offers 1, 2, 4 and 8 bars: ${Pat.lengths().toList()}")
    Pat.meter = Pat.METERS.indexOf("5/4")
    check(Pat.lengths().toList() == listOf(32, 64, 128), "3.62: 5/4 (a 32-step slot) offers 1, 2 and 4 bars: ${Pat.lengths().toList()}")
    Pat.meter = 0
    Pat.len = 128
    check(Pat.bars() == 8 && Pat.playLen() == 128 && Pat.stepAt(127) == 127 && Pat.stepAt(128) == 0 && Pat.nextStep(127) == 0,
        "3.62: an 8-bar pattern plays steps 1-128, then bar 1 again")
    VacuumApply.put(e5, lanes5, song2, plan5, files5, ArrayList(), 7)
    check((112 until 128).map { e5.pads[k5].on[it] } == (0 until 16).map { it % 4 == 1 }, "3.62 SPLIT bar 8: the kick's steps are in bar 8 (steps 113-128)")
    check(e5.pads[k5].on.copyOf(16).contentEquals(bar1Kick) && (64 until 112).none { e5.pads[k5].on[it] }, "3.62 SPLIT bar 8: bar 1 kept, bars 5-7 untouched")
    Pat.meter = m0
    // --- 3.59: a loop of 1..N whole bars (STACK 4.02-beta and on): its FIT is as many bars (the nearest the app has)
    // 110 BPM, 16 steps: a bar is 16 x 15 / 110 = 2.1818 s; at 22 050 Hz, 48 109 samples
    val barS = 16 * 15.0 / 110 * 22050
    check(song.barSeconds == 16 * 15.0 / 110, "a bar of the song: ${song.barSeconds} s")
    for ((nb, fit) in listOf(1 to 1, 2 to 2, 3 to 4, 4 to 4)) {
        val got = VacuumPlan.loopBars((barS * nb).toInt(), 22050, song)
        check(got == nb && VacuumPlan.fitOf(got) == fit, "a loop of $nb bar(s): $got bars -> FIT $fit")
    }
    check(VacuumPlan.loopBars((barS * 2 * 1.004).toInt(), 22050, song) == 2, "a loop 0.4 % long of 2 bars: still 2")
    check(VacuumPlan.fitOf(5) == 4 && VacuumPlan.fitOf(6) == 8 && VacuumPlan.fitOf(11) == 8, "5 bars -> FIT 4, 6 and more -> 8")
    for (nb in listOf(2, 4)) {
        val eb = Engine()
        val lb = LaneSetup()
        val pb = VacuumPlan.plan(song, lb.type, lb.idx, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav",
            VacuumPlan.loopBars((barS * nb).toInt(), 22050, song))
        val fb = HashMap<String, FloatArray?>()
        for (a in pb.acts) a.file?.let { fb[it] = data(it) }
        VacuumApply.put(eb, lb, song, pb, fb, ArrayList())
        val tb = eb.tracks[lb.idx[7]]
        check(tb.loop && tb.fitBars == nb && pb.notes.none { it.contains("bars long") }, "a $nb-bar loop: lane 8 loops, FIT $nb bars (${tb.fitBars})")
    }
    val p3 = VacuumPlan.plan(song, lanes2.type, lanes2.idx, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav", 3)
    check(p3.acts[7].fit == 4 && p3.notes.any { it.startsWith("T8") && it.contains("3 bars long") && it.contains("4 bars") },
        "a 3-bar loop: FIT 4 (the app has 1, 2, 4, 8), and said: " + p3.notes.first { it.contains("bars long") })

    // --- 3.59: STACK 4.05-beta, the loop on track 7 (the simulator's record)
    val f405 = if (args.size > 1) File(args[1]) else File(File(args[0]).parentFile, "stack_song_405.txt")
    val rec405 = readRecord(f405)
    val s405 = StackDump.parse(rec405)
    check(s405 != null, "4.05-beta: the record is read")
    s405 ?: return
    println("4.05-beta: " + s405.version + ", " + s405.bpm + " BPM, " + StackSounds.SIG_NAME[s405.sig] + ": " + s405.tracks.joinToString { it.label })
    check(s405.version == "4.05-BETA" && s405.bpm == 100 && s405.sig == 1 && s405.steps == 12, "4.05-beta: 3/4 (12 steps) at 100 BPM")
    check(s405.hasLoop && rec405[7].toInt() and 8 != 0 && s405.loopTrack == 6 && s405.tracks[7].kind != 3,
        "4.05-beta: the loop is on track 7 (kind 3, flag bit 3), track 8 its own again (${s405.tracks[7].label})")
    // its loop (LOOP INFO): 39 690 samples at 22 050 Hz, 1.8 s: one bar of 3/4 at 100
    check(VacuumPlan.loopBars(39690, 22050, s405) == 1 && VacuumPlan.loopBars(2 * 39690, 22050, s405) == 2, "4.05-beta: its loop is 1 bar (and twice as long: 2)")
    val e6 = Engine()
    e6.bank.soundLoader = { n -> data(n) }
    val lanes6 = LaneSetup()
    val none6 = IntArray(lanes6.type.size)
    // (track 8 is EMPTY in the simulator's song: here it gets a sound of its own, the PIANO of stack_song.txt)
    val s6 = StackSong(s405.bpm, s405.sig, s405.steps, s405.swing, s405.layers, s405.hasLoop, s405.version,
        s405.tracks.mapIndexed { i, t -> if (i == 7) song.tracks[4] else t })
    val plan6 = VacuumPlan.plan(s6, none6, none6, Cfg.TRACKS, Cfg.PADS, { true }, { null }, "FM-1 Loop.wav", 2)
    check(plan6.acts[6].loop && plan6.acts[6].type == VacuumPlan.SAMPLE && plan6.acts[6].fit == 2 && !plan6.acts[7].loop,
        "4.05-beta: the plan puts the loop on lane 7, a Sampler track that loops; lane 8 is no loop")
    check(plan6.notes.none { it.startsWith("T7") && it.contains("not carried over") }, "4.05-beta: nothing said of the sound that sleeps under the loop")
    val files6 = HashMap<String, FloatArray?>()
    for (a in plan6.acts) a.file?.let { files6[it] = data(it) }
    val notes6 = ArrayList(plan6.notes)
    VacuumApply.clearAll(e6, lanes6)
    VacuumApply.put(e6, lanes6, s6, plan6, files6, notes6, -1, 3f)
    println("4.05-beta lanes: " + (0 until 8).joinToString { "${it + 1}=${lanes6.label(it)}" })
    val l7 = e6.tracks[lanes6.idx[6]]
    check(lanes6.type[6] == Inst.SAMPLE && l7.sampleName == "FM-1 Loop.wav" && l7.loop && l7.fitBars == 2 && l7.boostDb == 3f,
        "4.05-beta: lane 7 is the loop, looping, FIT 2 bars, its boost (${l7.sampleName}, ${l7.fitBars}, ${l7.boostDb} dB)")
    val m8 = StackSounds.MAP[s6.tracks[7].name]
    val l8ok = when (lanes6.type[7]) {
        Inst.SAMPLE -> e6.tracks[lanes6.idx[7]].let { it.sampleName == m8?.file && !it.loop }
        Inst.DRUM -> e6.pads[lanes6.idx[7]].name == m8?.file
        else -> false
    }
    check(l8ok, "4.05-beta: lane 8 is track 8's own sound (${m8?.file}), no loop")
    check((0 until 8).map { lanes6.type[it] to lanes6.idx[it] }.distinct().size == 8, "4.05-beta: no two lanes share a track or a pad")
    check(e6.drumHeavy.amount == 0f, "3.59: CLEAR EVERYTHING: the drum bus HEAVY is off (STACK's drums are clean)")

    // 3.60: ERASE a slot (stack/docs/PROTOCOL.md, app/my_samples.c: command 14; Burger Studio bs19: SMP_ERASE)
    fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
    for (k in 0 until Fm1SampleLink.SLOTS)
        check(hex(Fm1SampleLink.eraseMessage(k)) == "F0 7D 46 4C 0E 0${k} F7", "3.60: ERASE USR${k + 1} is " + hex(Fm1SampleLink.eraseMessage(k)))
    check(runCatching { Fm1SampleLink.eraseMessage(3) }.isFailure && runCatching { Fm1SampleLink.eraseMessage(-1) }.isFailure,
        "3.60: ERASE of a slot other than USR1..USR3 is never sent")

    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    if (bad != 0) System.exit(1)
}
