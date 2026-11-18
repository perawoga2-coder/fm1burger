package com.example.fm1burger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val REQ_IMPORT = 10
        const val REQ_EXPORT = 11
        const val REQ_EXPORT_FILE = 12
    }

    lateinit var ui: Ui
    lateinit var store: Store
    lateinit var engine: Engine
    lateinit var recorder: Recorder
    lateinit var midi: MidiLink
    lateinit var project: Project
    lateinit var flow: RecordFlow
    lateinit var editScreen: EditScreen
    lateinit var libraryScreen: LibraryScreen

    var sel = 0
    var lastMix: File? = null
    private var pendingFile: File? = null
    private lateinit var recMixBtn: Button
    private lateinit var recPartBtn: Button
    private var cur = 0
    private var syncState = 0   // 0 off, 1 LEAD (the app sends the clock), 2 FOLLOW (the FM-1 is the boss)
    private var syncNote = ""
    private var pendingBytes: ByteArray? = null
    private var importTarget = -1
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var screens: List<Screen>
    private lateinit var tabBtns: List<Button>
    private lateinit var playBtn: Button
    private lateinit var syncBtn: Button
    private lateinit var muteAllBtn: Button
    private lateinit var bpmText: TextView
    private lateinit var bpmBar: SeekBar
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var tabScroll: HorizontalScrollView
    private var dotColor = 0
    private val cueBtns = ArrayList<Button>()
    private var cueKey = ""
    private var lastPartVersion = -1

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        SafeIo.cleanTemps(filesDir)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        store = Store(this)
        engine = Engine()
        recorder = Recorder(this)
        midi = MidiLink(this, engine)
        project = Project(this)
        flow = RecordFlow(this)

        buildUi()
        if (savedInstanceState == null) showSplash()
        engine.start()
        project.load()
        SynthSave.load(File(filesDir, "synth.json"), engine.bass, engine.analog)
        VocalSave.load(File(filesDir, "vocal.json"), engine.vocal)
        engine.bank.load(File(filesDir, "parts.json"))
        engine.bank.loadClips(filesDir)
        lastPartVersion = engine.bank.version
        showTab(0)

        val crash = CrashLog.take(this)
        if (crash != null) showCrash(crash)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        // the screen is hidden: no need to redraw 20 times a second (the audio keeps playing)
        handler.removeCallbacks(ticker)
        saveAll()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveAll()
        engine.vocal.stop()
        if (engine.mixRec.recording) engine.mixRec.stop()
        recorder.stop()
        engine.shutdown()
        midi.shutdown()
        super.onDestroy()
    }

    /** Writes the whole session to disk. Each part is saved on its own, so one failure does not lose the rest. */
    private fun saveAll() {
        try { project.save() } catch (e: Exception) { }
        try { SynthSave.save(File(filesDir, "synth.json"), engine.bass, engine.analog) } catch (e: Exception) { }
        try { VocalSave.save(File(filesDir, "vocal.json"), engine.vocal) } catch (e: Exception) { }
        try { engine.bank.save(File(filesDir, "parts.json")) } catch (e: Exception) { }
    }

    /** The intro animation over the app; it removes itself when done (or when tapped). */
    private fun showSplash() {
        try {
            val host = findViewById<FrameLayout>(android.R.id.content)
            val sp = SplashView(this) { }
            host.addView(sp, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            sp.start()
        } catch (e: Exception) {
        }
    }

    private fun showCrash(report: String) {
        val tv = ui.text(report.take(6000), 11f, Theme.DIM)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        val sc = android.widget.ScrollView(this)
        sc.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("The app closed by itself last time")
            .setMessage("Your work was saved. If it happens again, copy this report and send it along so it can be fixed.")
            .setView(sc)
            .setPositiveButton("Copy report") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FM1 Burger crash", report))
                ui.toast("Report copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- layout ----------------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)

        // ---- header: transport, status, cue parts ----
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setBackgroundColor(Theme.PANEL)
        head.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))

        val r1 = ui.row()
        playBtn = ui.button("▶") { togglePlay() }
        playBtn.textSize = 20f
        playBtn.setPadding(0, 0, 0, 0)
        ui.styleButton(playBtn, false, Theme.ACCENT, 26)
        r1.addView(playBtn, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).also { it.marginEnd = ui.dp(10) })

        // tempo block: − [ BPM 120 ] +
        val tempo = ui.row()
        tempo.background = ui.bg(Theme.PANEL2, 14, Theme.LINE, 1)
        tempo.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        val minus = ui.button("−") { changeBpm(-1) }
        minus.background = null
        minus.textSize = 18f
        minus.setPadding(ui.dp(10), 0, ui.dp(10), 0)
        val plus = ui.button("+") { changeBpm(1) }
        plus.background = null
        plus.textSize = 18f
        plus.setPadding(ui.dp(10), 0, ui.dp(10), 0)
        val bpmBox = ui.col()
        bpmBox.gravity = android.view.Gravity.CENTER
        val bpmLabel = ui.text("BPM", 9f, Theme.DIM, true)
        bpmLabel.letterSpacing = 0.15f
        bpmLabel.gravity = android.view.Gravity.CENTER
        bpmText = ui.text("120", 20f, Theme.TEXT, true)
        bpmText.gravity = android.view.Gravity.CENTER
        bpmText.setOnClickListener { bpmDialog() }
        bpmBox.addView(bpmLabel)
        bpmBox.addView(bpmText)
        tempo.addView(minus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(44)))
        tempo.addView(bpmBox, LinearLayout.LayoutParams(ui.dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        tempo.addView(plus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(44)))
        r1.addView(tempo)

        bpmBar = SeekBar(this)
        bpmBar.max = 200
        bpmBar.progress = engine.bpm - 40
        ui.tint(bpmBar)
        bpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && !engine.follow) engine.bpm = p + 40
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        r1.addView(bpmBar, ui.w(1f, 4))
        syncBtn = ui.button("SYNC") { toggleSync() }
        r1.addView(syncBtn, ui.w(0f, 3))
        muteAllBtn = ui.button("MUTE ALL") { muteAll() }
        r1.addView(muteAllBtn, ui.w(0f, 3))
        head.addView(r1)

        // status line in a soft pill, the two record buttons next to it
        val r2 = ui.row()
        val statusBox = ui.row()
        statusBox.background = ui.bg(Theme.BG, 12)
        statusBox.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        statusDot = View(this)
        statusDot.background = ui.bg(Theme.FAINT, 4)
        statusBox.addView(statusDot, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).also { it.marginEnd = ui.dp(8) })
        statusText = ui.text("", 12f, Theme.DIM)
        statusText.maxLines = 2
        statusText.ellipsize = android.text.TextUtils.TruncateAt.END
        statusBox.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r2.addView(statusBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.setMargins(0, ui.dp(3), ui.dp(3), ui.dp(3))
        })
        recPartBtn = ui.button("● REC PART") { toggleRecPart() }
        recMixBtn = ui.button("● REC MIX") { toggleMixRec() }
        r2.addView(recPartBtn, ui.w(0f, 3))
        r2.addView(recMixBtn, ui.w(0f, 3))
        head.addView(r2, ui.fullWidth(6))

        // the 6 cue parts, on every page
        val r3 = ui.row()
        for (i in 0 until PartBank.N) {
            val b = ui.button("") { cueSlot(i) }
            b.textSize = 12f
            b.maxLines = 1
            b.ellipsize = android.text.TextUtils.TruncateAt.END
            b.setPadding(ui.dp(4), 0, ui.dp(4), 0)
            b.setOnLongClickListener {
                slotDialog(i)
                true
            }
            cueBtns.add(b)
            r3.addView(b, LinearLayout.LayoutParams(0, ui.dp(42), 1f).also {
                it.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            })
        }
        head.addView(r3, ui.fullWidth(2))
        root.addView(head)

        // screens
        val tracks = TracksScreen(this)
        val steps = StepsScreen(this)
        val mixer = MixerScreen(this)
        libraryScreen = LibraryScreen(this)
        editScreen = EditScreen(this)
        val fx = FxScreen(this)
        val pads = PadsScreen(this)
        val synth = SynthScreen(this)
        val voice = VoiceScreen(this)
        val song = SongScreen(this)
        screens = listOf(tracks, steps, mixer, libraryScreen, editScreen, fx, pads, synth, voice, song)

        // ---- tab bar: pills, the open page is filled ----
        val tabRow = ui.row()
        tabRow.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        val names = listOf("Tracks", "Steps", "Mixer", "Library", "Edit", "FX", "Pads", "Synth", "Voice", "Song")
        val btns = ArrayList<Button>()
        names.forEachIndexed { i, n ->
            val b = ui.button(n) { showTab(i) }
            b.minHeight = ui.dp(36)
            b.minimumHeight = ui.dp(36)
            b.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
            btns.add(b)
            tabRow.addView(b, ui.w(0f, 3))
        }
        tabBtns = btns
        tabScroll = HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Theme.BG)
        tabScroll.addView(tabRow)
        root.addView(tabScroll)
        val line = View(this)
        line.setBackgroundColor(Theme.LINE)
        root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)))

        val content = FrameLayout(this)
        for (s in screens) content.addView(s.root)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun showTab(i: Int) {
        cur = i
        screens.forEachIndexed { idx, s -> s.root.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { idx, b -> styleTab(b, idx == i) }
        // keep the open tab visible in the scrolling tab bar
        val b = tabBtns[i]
        tabScroll.post {
            val x = b.left - (tabScroll.width - b.width) / 2
            tabScroll.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
        try {
            screens[i].onShow()
        } catch (e: Exception) {
            ui.toast("This page had a problem: ${e.message}")
        }
    }

    private fun styleTab(b: Button, active: Boolean) {
        if (active) {
            ui.styleButton(b, true, Theme.ACCENT, 18)
        } else {
            b.background = ui.touchBg(ui.bg(Theme.BG, 18), 18)
            b.setTextColor(Theme.DIM)
        }
    }

    /** Tap on the BPM number: type a tempo. */
    private fun bpmDialog() {
        if (engine.follow) {
            ui.toast("The tempo comes from the FM-1 while in FOLLOW sync")
            return
        }
        val ed = android.widget.EditText(this)
        ed.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        ed.setText(engine.bpm.toString())
        ed.setSelectAllOnFocus(true)
        val box = FrameLayout(this)
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        box.addView(ed)
        AlertDialog.Builder(this)
            .setTitle("Tempo (40 - 240 BPM)")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = ed.text.toString().trim().toIntOrNull()
                if (v == null) {
                    ui.toast("Type a number")
                } else {
                    engine.bpm = v
                    bpmBar.progress = engine.bpm - 40
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refreshCurrent() {
        try {
            screens[cur].onShow()
        } catch (e: Exception) {
        }
    }

    fun selectTrack(i: Int) {
        sel = i
        refreshCurrent()
    }

    // ---------------- header actions ----------------

    private fun togglePlay() {
        if (engine.playing) engine.stop() else engine.play()
    }

    /** A cue button: jump to that part (at the next bar while playing). */
    fun cuePart(i: Int) {
        val b = engine.bank
        if (b.songOn) {
            b.songOn = false
            ui.toast("Song mode is off now, you are playing the parts by hand")
        }
        if (i == b.cur && b.cueReq < 0) return
        b.cue(i)
    }

    /** Cue slot k (1-6 at the top): plays the part that is placed in that slot. */
    fun cueSlot(k: Int) {
        cuePart(engine.bank.slot[k].coerceIn(0, PartBank.N - 1))
    }

    /** Long press on a cue slot: choose which part goes into it. */
    fun slotDialog(k: Int) {
        val b = engine.bank
        val items = Array(PartBank.N) {
            val p = b.parts[it]
            PartNames.NAMES[it] + when {
                p.hasClip() -> "  (recorded, ${p.clipBars} bars)"
                p.used -> ""
                else -> "  (empty)"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Cue ${k + 1}: which part?")
            .setItems(items) { _, which ->
                b.slot[k] = which
                cueKey = ""
            }
            .show()
    }

    // ---------------- live recording of a part ----------------

    private fun toggleRecPart() {
        val b = engine.bank
        when (b.recState) {
            0 -> {
                b.recArm()
                if (!engine.playing) {
                    engine.play()
                    ui.toast("Recording a part. It starts now at bar 1. Play your idea, then press ■ REC PART.")
                } else {
                    ui.toast("Recording starts at the next bar 1.")
                }
            }
            1 -> {
                b.recStop()
                ui.toast("Recording cancelled")
            }
            2 -> {
                if (!b.recStopWanted) {
                    b.recStop()
                    ui.toast("The recording stops at the end of this bar")
                }
            }
        }
    }

    private fun recPartDone() {
        val b = engine.bank
        val got = b.collectRec() ?: return
        val data = got.first
        val bars = got.second
        val items = Array(PartBank.N + 1) {
            if (it < PartBank.N) {
                val p = b.parts[it]
                PartNames.NAMES[it] + (if (p.hasClip()) "  (replaces its ${p.clipBars} bar recording)" else "")
            } else {
                "Throw it away"
            }
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Recording ready: $bars bar" + (if (bars > 1) "s" else "") + ". Send it to which part?")
            .setItems(items) { _, which ->
                if (which < PartBank.N) {
                    b.setClip(filesDir, which, data, bars)
                    b.version++
                    ui.toast("Sent to ${PartNames.NAMES[which]}. It plays when that part plays (Song page: play mode).")
                }
            }
            .setCancelable(false)
            .create()
        d.show()
    }

    /** Play the song list from the top, or stop it. */
    fun playSong() {
        val b = engine.bank
        if (engine.playing && b.songOn) {
            engine.stop()
            return
        }
        if (b.songLen == 0) {
            ui.toast("The song is empty. Add some parts on the Song page first.")
            return
        }
        if (engine.follow) {
            b.songOn = true
            ui.toast("Song mode on. In FOLLOW sync, press play on the FM-1 to start the song.")
            return
        }
        b.songOn = true
        if (engine.playing) engine.stop()
        engine.play()
    }

    private fun paintCues() {
        val b = engine.bank
        val blink = (System.currentTimeMillis() / 250L) % 2L == 0L
        val key = "${b.cur}|${b.cueReq}|$blink|${b.songOn}|${b.slot.joinToString()}|${b.version}"
        if (key == cueKey) return
        cueKey = key
        for (k in cueBtns.indices) {
            val btn = cueBtns[k]
            val i = b.slot[k].coerceIn(0, PartBank.N - 1)
            val col = SongScreen.PARTCOL[i]
            btn.text = "${k + 1} " + PartNames.SHORT[i] + (if (b.parts[i].hasClip()) " ●" else "")
            when {
                i == b.cur -> {
                    btn.background = ui.touchBg(ui.bg(col, 12), 12)
                    btn.setTextColor(Theme.onColor(col))
                }
                i == b.cueReq && blink -> {
                    btn.background = ui.touchBg(ui.bg(Theme.TEXT, 12), 12)
                    btn.setTextColor(Theme.onColor(Theme.TEXT))
                }
                else -> {
                    btn.background = ui.touchBg(ui.bg(Theme.alpha(col, 28), 12, Theme.alpha(col, 150), 1), 12)
                    btn.setTextColor(if (b.parts[i].used) Theme.TEXT else Theme.DIM)
                }
            }
        }
    }

    private fun changeBpm(d: Int) {
        if (engine.follow) return
        engine.bpm = engine.bpm + d
        bpmBar.progress = engine.bpm - 40
    }

    private fun muteAll() {
        val anyOn = engine.tracks.any { !it.mute }
        for (t in engine.tracks) t.mute = anyOn
        refreshCurrent()
    }

    private fun toggleSync() {
        val now = when (syncState) {
            1 -> "LEAD"
            2 -> "FOLLOW"
            else -> "off"
        }
        val items = arrayOf(
            "FOLLOW the FM-1: the FM-1 is the boss. Start, stop and tempo come from the FM-1. Press play on the FM-1.",
            "LEAD the FM-1: the app is the boss. The play button here starts and stops the FM-1 too.",
            "Sync off"
        )
        AlertDialog.Builder(this)
            .setTitle("Sync with the FM-1 (now: $now)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startSync(2)
                    1 -> startSync(1)
                    else -> syncOff()
                }
            }
            .show()
    }

    private fun startSync(mode: Int) {
        if (midi.connected) {
            applySync(mode)
            return
        }
        val infos = midi.devices()
        if (infos.isEmpty()) {
            ui.toast("No MIDI device found. Connect the FM-1 with the USB cable and allow USB access.")
            return
        }
        if (infos.size == 1) {
            connectMidi(infos[0], mode)
        } else {
            val names = Array(infos.size) { midi.nameOf(infos[it]) }
            AlertDialog.Builder(this)
                .setTitle("Which MIDI device is the FM-1?")
                .setItems(names) { _, which -> connectMidi(infos[which], mode) }
                .show()
        }
    }

    private fun connectMidi(info: android.media.midi.MidiDeviceInfo, mode: Int) {
        syncNote = "Connecting to ${midi.nameOf(info)}..."
        midi.open(info) { ok ->
            if (!ok) {
                syncNote = ""
                ui.toast("Could not open the MIDI device")
            } else {
                applySync(mode)
            }
        }
    }

    private fun applySync(mode: Int) {
        engine.stop()
        if (mode == 2) {
            engine.follow = true
            engine.sendClock = false
            syncState = 2
        } else {
            engine.follow = false
            engine.sendClock = true
            syncState = 1
        }
        syncNote = ""
    }

    private fun syncOff() {
        engine.stop()
        engine.follow = false
        engine.sendClock = false
        syncState = 0
        syncNote = if (midi.connected) "SYNC off (MIDI stays connected for the pads)" else ""
    }

    private fun syncLine(): String = when (syncState) {
        1 -> "LEAD: the app sends the clock to ${midi.deviceName}. The play button starts the FM-1 too."
        2 -> if (midi.clockActive()) {
            "FOLLOW: clock from ${midi.deviceName} OK"
        } else {
            "FOLLOW: no clock from the FM-1 yet. Press play on the FM-1."
        }
        else -> syncNote
    }

    // ---------------- ticker ----------------

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 50)
        }
    }

    private fun tick() {
        if (syncState != 0 && !midi.connected) {
            syncOff()
            syncNote = "MIDI device disconnected"
        }
        val pt = if (engine.playing) "■" else "▶"
        if (playBtn.text.toString() != pt) playBtn.text = pt
        ui.styleButton(playBtn, engine.playing, Theme.GREEN, 26)
        val sb = when (syncState) {
            1 -> "SYNC: LEAD"
            2 -> "SYNC: FOLLOW"
            else -> "SYNC"
        }
        if (syncBtn.text.toString() != sb) syncBtn.text = sb
        ui.styleButton(syncBtn, syncState != 0)
        val allMuted = engine.tracks.all { it.mute }
        val mt = if (allMuted) "UNMUTE ALL" else "MUTE ALL"
        if (muteAllBtn.text.toString() != mt) {
            muteAllBtn.text = mt
            ui.styleButton(muteAllBtn, allMuted, Theme.RED)
        }
        val bt = if (engine.follow) String.format(Locale.US, "%.1f", engine.bpmF) else "${engine.bpm}"
        if (bpmText.text.toString() != bt) bpmText.text = bt
        bpmBar.isEnabled = !engine.follow
        if (engine.follow) bpmBar.progress = (engine.bpm - 40).coerceIn(0, 200)

        val rec = flow.statusLine()
        if (rec.isNotEmpty()) {
            statusText.setTextColor(Theme.RED)
            statusText.text = rec
        } else {
            statusText.setTextColor(Theme.DIM)
            val sl = syncLine()
            statusText.text = if (engine.playing && engine.curStep >= 0) {
                "Playing ${PartNames.NAMES[engine.bank.cur]} · step ${engine.curStep + 1} / ${Cfg.STEPS}" +
                    (if (engine.bank.songOn) " · song ${engine.bank.songIdx + 1}/${engine.bank.songLen}" else "") +
                    (if (sl.isNotEmpty()) " · $sl" else "")
            } else {
                sl.ifEmpty { "Ready. Press ▶ to play." }
            }
        }
        val mr = engine.mixRec
        if (mr.autoStopped) {
            mr.autoStopped = false
            lastMix = mr.stop()
            ui.toast("The mix recording stopped (storage full or write error). What was recorded is kept.")
            mixDialog()
        }
        val rt = if (mr.recording) "■ REC " + clock(mr.frames / Cfg.SR) else "● REC MIX"
        if (recMixBtn.text.toString() != rt) recMixBtn.text = rt
        ui.styleButton(recMixBtn, mr.recording, Theme.RED)
        if (mr.recording) {
            statusText.setTextColor(Theme.RED)
            statusText.text = "● Recording the whole mix " + clock(mr.frames / Cfg.SR) +
                " · " + String.format(Locale.US, "%.1f", mr.bytesWritten / 1048576.0) + " MB. Press ■ REC to stop."
        }
        paintCues()
        val bk = engine.bank
        if (bk.recState == 4) recPartDone()
        if (bk.recCancelled) {
            bk.recCancelled = false
            ui.toast("The part recording was shorter than 1 bar, so it was not kept")
        }
        val rp = when (bk.recState) {
            1 -> "WAIT BAR 1..."
            2 -> if (bk.recStopWanted) "STOPPING..." else "■ REC PART ${bk.recBars + 1}"
            else -> "● REC PART"
        }
        if (recPartBtn.text.toString() != rp) recPartBtn.text = rp
        ui.styleButton(recPartBtn, bk.recState == 1 || bk.recState == 2, Theme.RED)
        if (bk.recState == 2) {
            statusText.setTextColor(Theme.RED)
            statusText.text = "● Recording a part · bar ${bk.recBars + 1}. Press the button again to stop at the end of the bar."
        }
        if (bk.songEnded) {
            bk.songEnded = false
            ui.toast("End of the song")
        }
        if (bk.version != lastPartVersion) {
            lastPartVersion = bk.version
            // the patterns changed to another part: redraw the pages that show patterns
            if (cur in intArrayOf(0, 1, 2, 6, 7, 9)) screens[cur].onShow()
        }
        // the dot in front of the status line: red = recording, green = playing, grey = stopped
        val dc = when {
            statusText.currentTextColor == Theme.RED -> Theme.RED
            engine.playing -> Theme.GREEN
            else -> Theme.FAINT
        }
        if (dc != dotColor) {
            dotColor = dc
            statusDot.background = ui.bg(dc, 4)
        }
        try {
            screens[cur].onTick()
        } catch (e: Exception) {
        }
    }

    private fun clock(s: Long): String = String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    // ---------------- record the whole mix ----------------

    fun toggleMixRec() {
        val m = engine.mixRec
        if (m.recording) {
            lastMix = m.stop()
            if (lastMix != null) mixDialog()
        } else {
            val f = m.start(File(filesDir, "mixes"))
            if (f == null) {
                ui.toast("Cannot start the mix recording (not enough free storage?)")
            } else {
                ui.toast("Recording the whole mix. Press ■ REC to stop. There is no length limit.")
            }
        }
    }

    fun mixDialog() {
        val f = lastMix
        if (f == null || !f.exists()) {
            ui.toast("No mix recorded yet. Press ● REC MIX while you play.")
            return
        }
        val secs = ((f.length() - 44L) / (Cfg.SR * 4L)).toInt().coerceAtLeast(0)
        val mb = f.length() / 1048576.0
        val items = arrayOf(
            "Save to device (choose a folder)...",
            "Add to the sample library" + (if (secs > 120) " (too long, max 2 min)" else " (mixed to mono)"),
            "Keep it inside the app only",
            "Delete this mix"
        )
        AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, "%s  %d:%02d  %.1f MB", f.name, secs / 60, secs % 60, mb))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveFile(f)
                    1 -> mixToLibrary(f, secs)
                    3 -> {
                        f.delete()
                        lastMix = null
                        ui.toast("Mix deleted")
                    }
                }
            }
            .show()
    }

    private fun mixToLibrary(f: File, secs: Int) {
        if (secs > 120) {
            ui.toast("Too long for the library (max 2 minutes). Use Save to device.")
            return
        }
        Thread {
            try {
                val p = Wav.parse(f.readBytes())
                if (p == null) {
                    runOnUiThread { ui.toast("Cannot read the mix file") }
                } else {
                    val saved = store.saveNew(f.name.removeSuffix(".wav"), p.data, p.rate)
                    runOnUiThread {
                        libraryScreen.refresh()
                        ui.toast("Added ${saved.name} to the library")
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { ui.toast("Failed: ${ex.message}") }
            }
        }.start()
    }

    fun saveFile(f: File) {
        pendingFile = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/wav"
        i.putExtra(Intent.EXTRA_TITLE, f.name)
        startActivityForResult(i, REQ_EXPORT_FILE)
    }

    // ---------------- samples ----------------

    fun pickSample(title: String, cb: (File?) -> Unit) {
        val files = store.list()
        if (files.isEmpty()) {
            ui.toast("The library is empty. Record or import a sample first.")
            return
        }
        val items = arrayOf("(empty this slot)") + Array(files.size) { files[it].name }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which -> cb(if (which == 0) null else files[which - 1]) }
            .show()
    }

    /** The L button: load a sample into a track from the library, or from this device. */
    fun loadDialog(i: Int) {
        val files = store.list()
        val items = ArrayList<String>()
        items.add("Browse this device (files, downloads, SD card)...")
        items.add("(empty this slot)")
        for (f in files) items.add(f.name)
        AlertDialog.Builder(this)
            .setTitle("Load into track ${i + 1}")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        importTarget = i
                        importFiles(false)
                    }
                    1 -> assignTrack(i, null)
                    else -> assignTrack(i, files[which - 2])
                }
            }
            .show()
    }

    fun assignTrack(i: Int, f: File?) {
        if (f == null) {
            engine.setSample(i, "", null)
            refreshCurrent()
            return
        }
        Thread {
            val d = store.loadEngine(f.name)
            runOnUiThread {
                if (d == null) ui.toast("Cannot read ${f.name}") else engine.setSample(i, f.name, d)
                refreshCurrent()
            }
        }.start()
    }

    fun assignPad(i: Int, f: File?) {
        if (f == null) {
            engine.setPad(i, "", null)
            refreshCurrent()
            return
        }
        Thread {
            val d = store.loadEngine(f.name)
            runOnUiThread {
                if (d == null) ui.toast("Cannot read ${f.name}") else engine.setPad(i, f.name, d)
                refreshCurrent()
            }
        }.start()
    }

    fun reloadByName(name: String) {
        for (i in 0 until Cfg.TRACKS) if (engine.tracks[i].sampleName == name) assignTrack(i, store.file(name))
        for (i in 0 until Cfg.PADS) if (engine.pads[i].name == name) assignPad(i, store.file(name))
    }

    fun renameRefs(old: String, new: String) {
        for (t in engine.tracks) if (t.sampleName == old) t.sampleName = new
        for (p in engine.pads) if (p.name == old) p.name = new
    }

    fun openEdit(f: File) {
        editScreen.pendingOpen = f
        showTab(4)
    }

    // ---------------- import / export ----------------

    fun importFiles(multiple: Boolean = true) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        startActivityForResult(i, REQ_IMPORT)
    }

    fun saveBytes(name: String, bytes: ByteArray) {
        pendingBytes = bytes
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/wav"
        i.putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_EXPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && (resultCode != RESULT_OK || data == null)) {
            importTarget = -1
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_IMPORT) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            val target = importTarget
            importTarget = -1
            if (uris.isEmpty()) return
            ui.toast(if (uris.size == 1) "Importing..." else "Importing ${uris.size} files...")
            // reading big files on the screen thread would freeze the app, so do it in the background
            Thread {
                val imported = ArrayList<File>()
                for (u in uris) {
                    val f = importUri(u)
                    if (f != null) imported.add(f)
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (target >= 0 && imported.isNotEmpty()) assignTrack(target, imported[0])
                    libraryScreen.refresh()
                    if (imported.size > 1) ui.toast("Imported ${imported.size} files")
                }
            }.start()
        } else if (requestCode == REQ_EXPORT) {
            val uri = data.data ?: return
            val b = pendingBytes ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(b) }
                ui.toast("Exported")
            } catch (e: Exception) {
                ui.toast("Export failed: ${e.message}")
            }
            pendingBytes = null
        } else if (requestCode == REQ_EXPORT_FILE) {
            val uri = data.data ?: return
            val src = pendingFile ?: return
            pendingFile = null
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out, 65536) }
                    }
                    runOnUiThread { ui.toast("Mix saved") }
                } catch (ex: Exception) {
                    runOnUiThread { ui.toast("Save failed: ${ex.message}") }
                }
            }.start()
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
        }
        return "Imported.wav"
    }

    private fun toastLater(msg: String) {
        runOnUiThread { ui.toast(msg) }
    }

    /** Background thread: copies one picked file into the library. */
    private fun importUri(uri: Uri): File? {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.size > 40 * 1024 * 1024) {
                toastLater("File unreadable or too large (max 40 MB)")
                return null
            }
            val p = Wav.parse(bytes)
            if (p == null) {
                toastLater("Only WAV files (16 bit, 24 bit or float) can be imported")
                return null
            }
            var base = displayName(uri)
            if (base.lowercase(Locale.ROOT).endsWith(".wav")) base = base.substring(0, base.length - 4)
            val f = store.saveNew(base.ifEmpty { "Imported" }, p.data, p.rate)
            toastLater("Imported ${f.name} (stereo files are mixed to mono)")
            return f
        } catch (e: OutOfMemoryError) {
            toastLater("This file is too big to import")
            return null
        } catch (e: Exception) {
            toastLater("Import failed: ${e.message}")
            return null
        }
    }
}
