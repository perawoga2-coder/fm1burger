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
        const val REQ_SYX = 13

        // page numbers (the order of the screens list)
        const val TAB_TRACKS = 0
        const val TAB_STEPS = 1
        const val TAB_MIXER = 2
        const val TAB_LIBRARY = 3
        const val TAB_SETUP = 14
        const val TAB_AUTO = 15

        // the 5 sections of the bottom bar, and the pages inside each one (3.0 Lab)
        val SECTIONS = arrayOf(
            intArrayOf(10, 2),                      // Home: lanes, mixer
            intArrayOf(0, 1, 3),                    // Sampler: sampler, steps, library
            intArrayOf(6, 13, 7, 12, 11, 8),        // Instruments: drums, bassline, analog, guitars, ambient, voice
            intArrayOf(9, 15),                      // Song, automation
            intArrayOf(14)                          // Setup
        )
        val SECTION_NAMES = arrayOf("⌂ Home", "Sampler", "Instruments", "Song", "Setup")
        const val TAB_EDIT = 4
        const val TAB_FX = 5
        const val TAB_PADS = 6
        const val TAB_SYNTH = 7
        const val TAB_VOICE = 8
        const val TAB_SONG = 9
        const val TAB_HOME = 10
        const val TAB_AMBIENT = 11
        const val TAB_GUITAR = 12
        const val TAB_BASS = 13
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
    lateinit var synthScreen: SynthScreen
    lateinit var bassScreen: SynthScreen
    lateinit var homeScreen: HomeScreen
    lateinit var tracksScreen: TracksScreen
    lateinit var voiceScreen: VoiceScreen
    lateinit var guitarScreen: GuitarScreen

    /** What the 16 lanes of the Home page show (saved with the project). */
    val lanes = LaneSetup()

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
    private lateinit var homeFab: Button
    private lateinit var recBtn: Button
    private lateinit var segRow: LinearLayout
    private var cueRow: View? = null
    private val navBtns = ArrayList<Button>()
    private val lastInSection = intArrayOf(10, 0, 6, 9, 14)
    private var shownSection = -1
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
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.let { engine.nativeRate = it }
            am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.let { engine.burst = it }
        } catch (ex: Exception) {
        }
        engine.start()
        project.load()
        SynthSave.load(File(filesDir, "synth.json"), engine.bass, engine.analog)
        VocalSave.load(File(filesDir, "vocal.json"), engine.vocal)
        AmbientSave.load(File(filesDir, "ambient.json"), engine.ambient)
        GuitarSave.load(File(filesDir, "guitars.json"), engine.rhythmGtr, engine.riffGtr, engine.drumHeavy)
        engine.bank.load(File(filesDir, "parts.json"))
        engine.bank.loadClips(filesDir)
        lastPartVersion = engine.bank.version
        installRockKit()
        showTab(TAB_HOME)

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
        try { AmbientSave.save(File(filesDir, "ambient.json"), engine.ambient) } catch (e: Exception) { }
        try { GuitarSave.save(File(filesDir, "guitars.json"), engine.rhythmGtr, engine.riffGtr, engine.drumHeavy) } catch (e: Exception) { }
        try { engine.bank.save(File(filesDir, "parts.json")) } catch (e: Exception) { }
    }

    /** The intro animation over the app; it removes itself when done (or when tapped). */
    private fun showSplash() {
        try {
            val host = findViewById<FrameLayout>(android.R.id.content)
            // when the intro ends (or is skipped) the jingle stops with it
            val sp = SplashView(this) { engine.previewStop() }
            host.addView(sp, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            sp.start()
            engine.previewPlay(SplashView.jingle())
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
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Burger Studio crash", report))
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
        minus.setOnLongClickListener {
            changeBpm(-10)
            true
        }
        minus.background = null
        minus.textSize = 18f
        minus.setPadding(ui.dp(10), 0, ui.dp(10), 0)
        val plus = ui.button("+") { changeBpm(1) }
        plus.setOnLongClickListener {
            changeBpm(10)
            true
        }
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
        bpmBar.visibility = View.GONE                    // 3.0: tap the number, or − / + (hold for ±10)
        r1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        syncBtn = ui.button("SYNC") { toggleSync() }
        r1.addView(syncBtn, ui.w(0f, 3))
        muteAllBtn = ui.button("MUTE ALL") { muteAll() }
        r1.addView(muteAllBtn, ui.w(0f, 3))
        muteAllBtn.visibility = View.GONE                // 3.0: on the Setup page
        recBtn = ui.button("● REC") { recPressed() }
        recBtn.setOnLongClickListener {
            mixFormatDialog()
            true
        }
        r1.addView(recBtn, ui.w(0f, 3))
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
        recMixBtn.setOnLongClickListener {
            mixFormatDialog()
            true
        }
        r2.addView(recPartBtn, ui.w(0f, 3))
        r2.addView(recMixBtn, ui.w(0f, 3))
        recPartBtn.visibility = View.GONE                // 3.0: one REC button in the first row
        recMixBtn.visibility = View.GONE
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
        cueRow = r3                                      // 3.0: the cues show on Home and Song only
        root.addView(head)

        // screens
        val tracks = TracksScreen(this)
        tracksScreen = tracks
        val steps = StepsScreen(this)
        val mixer = MixerScreen(this)
        libraryScreen = LibraryScreen(this)
        editScreen = EditScreen(this)
        val fx = FxScreen(this)
        val pads = PadsScreen(this)
        val synth = SynthScreen(this, 1)          // the Analog synth page
        synthScreen = synth
        bassScreen = SynthScreen(this, 0)         // the Bassline page
        val voice = VoiceScreen(this)
        voiceScreen = voice
        val song = SongScreen(this)
        homeScreen = HomeScreen(this)
        val ambient = AmbientScreen(this)
        guitarScreen = GuitarScreen(this)
        val setup = SetupScreen(this)
        val autoScreen = AutomationScreen(this)
        screens = listOf(tracks, steps, mixer, libraryScreen, editScreen, fx, pads, synth, voice, song, homeScreen, ambient, guitarScreen, bassScreen, setup, autoScreen)

        // ---- tab bar: pills, the open page is filled ----
        val tabRow = ui.row()
        tabRow.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        val names = listOf("Sampler", "Steps", "Mixer", "Library", "Edit", "FX", "Drums", "Analog synth", "Voice", "Song", "Lanes", "New Ambient", "Guitars", "Bassline", "Setup", "Automation")
        val btns = ArrayList<Button>()
        names.forEachIndexed { i, n ->
            val b = ui.button(n) { showTab(i) }
            b.minHeight = ui.dp(36)
            b.minimumHeight = ui.dp(36)
            b.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
            btns.add(b)
            // 3.0: the buttons are put into the bar per section (see showTab)
        }
        tabBtns = btns
        // the editor and the effects now live inside the Sampler page
        tracks.attachEmbedded(editScreen, fx)
        btns[TAB_EDIT].visibility = View.GONE
        btns[TAB_FX].visibility = View.GONE
        tabScroll = HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Theme.BG)
        tabScroll.addView(tabRow)
        segRow = tabRow
        root.addView(tabScroll)
        val line = View(this)
        line.setBackgroundColor(Theme.LINE)
        root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)))

        val content = FrameLayout(this)
        for (s in screens) content.addView(s.root)

        // a floating Home button on every page except Home itself
        homeFab = ui.button("⌂  Home") { showTab(TAB_HOME) }
        homeFab.textSize = 14f
        homeFab.typeface = Ui.BOLD
        homeFab.setPadding(ui.dp(18), 0, ui.dp(20), 0)
        ui.styleButton(homeFab, true, Theme.ACCENT, 24)
        homeFab.elevation = ui.dp(8).toFloat()
        val fl = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, ui.dp(48))
        fl.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        fl.setMargins(0, 0, ui.dp(16), ui.dp(16))
        content.addView(homeFab, fl)

        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        homeFab.visibility = View.GONE                   // 3.0: the bottom bar has Home

        // ---- the bottom bar: 5 sections ----
        val line2 = View(this)
        line2.setBackgroundColor(Theme.LINE)
        root.addView(line2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)))
        val nav = ui.row()
        nav.setBackgroundColor(Theme.PANEL)
        nav.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6))
        for (k in SECTION_NAMES.indices) {
            val b = ui.button(SECTION_NAMES[k]) { openSection(k) }
            b.textSize = 12f
            b.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10))
            navBtns.add(b)
            nav.addView(b, ui.w(1f, 2))
        }
        root.addView(nav)
        setContentView(root)
    }

    /** Bottom bar: open a section on the page used last in it. */
    fun openSection(k: Int) {
        showTab(lastInSection[k])
    }

    private fun sectionOf(i: Int): Int {
        for (k in SECTIONS.indices) if (i in SECTIONS[k]) return k
        return 0
    }

    /** The phone's back button: from any page go back to Home first, from Home leave the app. */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (cur != TAB_HOME) showTab(TAB_HOME) else super.onBackPressed()
    }

    fun showTab(i0: Int) {
        // Edit and FX are sections of the Sampler page now
        val i = if (i0 == TAB_EDIT || i0 == TAB_FX) TAB_TRACKS else i0
        cur = i
        // 3.0: the section, its page bar and the bottom bar
        val sec = sectionOf(i)
        lastInSection[sec] = i
        if (sec != shownSection) {
            shownSection = sec
            segRow.removeAllViews()
            for (idx in SECTIONS[sec]) segRow.addView(tabBtns[idx], ui.w(0f, 3))
            tabScroll.visibility = if (SECTIONS[sec].size > 1) View.VISIBLE else View.GONE
        }
        for (k in navBtns.indices) {
            if (k == sec) ui.styleButton(navBtns[k], true, Theme.ACCENT, 14)
            else {
                navBtns[k].background = ui.touchBg(ui.bg(Theme.PANEL, 14), 14)
                navBtns[k].setTextColor(Theme.DIM)
            }
        }
        cueRow?.visibility = if (sec == 0 || sec == 3) View.VISIBLE else View.GONE
        homeFab.visibility = if (i == TAB_HOME) View.GONE else View.VISIBLE
        screens.forEachIndexed { idx, s -> s.root.visibility = if (idx == i) View.VISIBLE else View.GONE }
        paintTabs()
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

    /** The Synth page tells us when it switches between bassline and analog synth. */
    fun onSynthModeChanged() {
        if (::tabBtns.isInitialized) paintTabs()
    }

    private fun paintTabs() {
        tabBtns.forEachIndexed { idx, b -> styleTab(b, idx == cur) }
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

    /** Opens the Guitars page on the rhythm (0) or the riff (1) guitar. */
    fun openGuitar(mode: Int) {
        guitarScreen.showMode(mode)
        showTab(TAB_GUITAR)
    }

    /** First start: put the Rock Heavy kit into the library, and on the pads when they are all empty. */
    private fun installRockKit() {
        val padsEmpty = !project.hadPads
        Thread {
            val ok = try {
                RockKit.install(this, store)
                true
            } catch (ex: Exception) {
                false
            }
            try {
                FmBank.install(this, store)
            } catch (ex: Exception) {
            }
            runOnUiThread {
                if (ok && !isFinishing) {
                    if (padsEmpty) for (i in RockKit.pads.indices) assignPad(i, store.file(RockKit.fileName(RockKit.pads[i])))
                    libraryScreen.refresh()
                }
            }
        }.start()
    }

    /** Opens the Synth page on the bassline (0) or the analog synth (1). */
    fun openSynth(mode: Int) {
        showTab(if (mode == 0) TAB_BASS else TAB_SYNTH)
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
        when {
            // a part plays alone (PREPARE): the big play button switches to the full mix
            engine.playing && engine.prepare >= 0 -> engine.play()
            engine.playing -> engine.stop()
            else -> engine.play()
        }
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

    /**
     * Cue slot k (1-6 at the top): an on / off switch of its own for the recording of the part in that slot.
     * Lit = on. Several can be on together; each one starts and stops at the next bar.
     * (Switching the patterns to another part is on the Song page.)
     */
    fun cueSlot(k: Int) {
        val b = engine.bank
        val i = b.slot[k].coerceIn(0, PartBank.N - 1)
        if (!b.toggleLayer(i)) {
            ui.toast("${PartNames.NAMES[i]} has no recording yet. Record one with ● REC PART and send it to ${PartNames.NAMES[i]}.")
            return
        }
        cueKey = ""
        if (b.layerOn[i] && !engine.playing) ui.toast("Cue ${k + 1} on: ${PartNames.NAMES[i]} plays when you press ▶")
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
                    ui.toast("Sent to ${PartNames.NAMES[which]}. Tap its cue at the top to play it, tap again to stop.")
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
        val key = "${b.layerOn.joinToString()}|${b.layerVersion}|$blink|${(0 until PartBank.N).map { b.layerPending(it) }}|" +
            "${b.slot.joinToString()}|${b.version}|${b.parts.map { it.hasClip() }}"
        if (key == cueKey) return
        cueKey = key
        for (k in cueBtns.indices) {
            val btn = cueBtns[k]
            val i = b.slot[k].coerceIn(0, PartBank.N - 1)
            val col = SongScreen.PARTCOL[i]
            val has = b.parts[i].hasClip()
            btn.text = "${k + 1} " + PartNames.SHORT[i] + (if (has) " ●" else "")
            val on = b.layerOn[i]
            // waiting for the next bar: blink between on and off
            val lit = if (b.layerPending(i) && engine.playing) blink else on
            if (lit) {
                btn.background = ui.touchBg(ui.bg(col, 12), 12)
                btn.setTextColor(Theme.onColor(col))
            } else {
                btn.background = ui.touchBg(ui.bg(Theme.alpha(col, 28), 12, Theme.alpha(col, if (has) 150 else 60), 1), 12)
                btn.setTextColor(if (has) Theme.TEXT else Theme.FAINT)
            }
        }
    }

    private fun changeBpm(d: Int) {
        if (engine.follow) return
        engine.bpm = engine.bpm + d
        bpmBar.progress = engine.bpm - 40
    }

    /** The one REC button: stops what is recording, otherwise asks what to record. */
    private fun recPressed() {
        val b = engine.bank
        when {
            engine.mixRec.recording -> toggleMixRec()
            b.recState == 1 || b.recState == 2 -> toggleRecPart()
            else -> AlertDialog.Builder(this)
                .setTitle("Record")
                .setItems(arrayOf(
                    "The whole mix (${MixRecorder.label(engine.mixRec.bits)}, no time limit)" + (if (engine.stemsOn) " + stems" else ""),
                    "A part, from bar 1 (send it to a cue)",
                    "Stems with the mix: " + (if (engine.stemsOn) "ON (tap to turn off)" else "off (tap to turn on)"),
                    "Mix format…",
                    "Last mix…",
                    "Export MIDI (patterns / song)…"
                )) { _, w ->
                    when (w) {
                        0 -> toggleMixRec()
                        1 -> toggleRecPart()
                        2 -> toggleStems()
                        3 -> mixFormatDialog()
                        4 -> mixDialog()
                        else -> exportMidiDialog()
                    }
                }
                .show()
        }
    }

    fun openSyncMenu() = toggleSync()

    fun muteAllTracks() = muteAll()

    // ---------------- Send to FM-1 (Burger Studio firmware: user sample slots over USB) ----------------

    private val fm1Link by lazy { Fm1SampleLink(midi) }

    /** Puts a sample (44.1 kHz mono) into USR1..USR3 of the FM-1 running the Burger Studio firmware. */
    fun sendToFm1(name: String, data: FloatArray) {
        if (data.isEmpty()) {
            ui.toast("The sample is empty")
            return
        }
        if (!midi.connected) {
            AlertDialog.Builder(this)
                .setTitle("Send to FM-1")
                .setMessage("Connect the FM-1 (running the Burger Studio firmware) by USB, then connect it in MIDI sync.")
                .setPositiveButton("MIDI sync…") { _, _ -> openSyncMenu() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        ui.toast("Asking the FM-1…")
        Thread {
            val slots = try { fm1Link.info() } catch (ex: Exception) { null }
            runOnUiThread {
                if (slots == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Send to FM-1")
                        .setMessage("The FM-1 does not answer. Send to FM-1 needs the Burger Studio firmware on the FM-1 " +
                            "(install it from perawoga2-coder.github.io/Fm1sampa in Chrome). The official firmware cannot take samples this way.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val secs = data.size / Cfg.SR.toDouble()
                val items = Array(Fm1SampleLink.SLOTS) { k -> slots.getOrNull(k) ?: "USR${k + 1}" }
                AlertDialog.Builder(this)
                    .setTitle(String.format(Locale.US, "Send %s (%.1f s%s) to", name, secs,
                        if (secs > Fm1SampleLink.MAX_SECONDS) String.format(Locale.US, ", cut to %.1f s", Fm1SampleLink.MAX_SECONDS) else ""))
                    .setItems(items) { _, w -> uploadToFm1(w, name, data) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun uploadToFm1(slot: Int, name: String, data: FloatArray) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4))
        box.addView(ui.text("Writing into USR${slot + 1}. Keep the cable in.", 13f, Theme.DIM))
        box.addView(bar, ui.fullWidth(8))
        val dlg = AlertDialog.Builder(this).setTitle("Send to FM-1").setView(box).setCancelable(false).show()
        val nm = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").ifEmpty { "BURGER" }
        Thread {
            val res = try {
                Result.success(fm1Link.upload(slot, nm, data) { f -> runOnUiThread { bar.progress = (f * 1000).toInt() } })
            } catch (ex: Exception) {
                Result.failure(ex)
            }
            runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) {}
                val ok = res.getOrNull()
                AlertDialog.Builder(this)
                    .setTitle(if (ok != null) "On the FM-1 now" else "Send to FM-1 failed")
                    .setMessage(if (ok != null)
                        "${nm.uppercase().take(8)} ($ok) is in USR${slot + 1}.\n\nOn the FM-1: a track with the SAMPLE engine, SET = USR${slot + 1}, " +
                            "plays it on the keys; the SLICE engine, SRC = USR${slot + 1}, chops it on its hits (DIV AUTO)."
                    else (res.exceptionOrNull()?.message ?: "?"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    /** Pattern length for every sequencer: 1, 2 or 4 bars. A longer pattern starts as copies of the bars it has. */
    fun patternLengthDialog() {
        val items = arrayOf("1 bar (16 steps)", "2 bars (32 steps)", "4 bars (64 steps)")
        AlertDialog.Builder(this)
            .setTitle("Pattern length (now ${Pat.bars()} bar${if (Pat.bars() > 1) "s" else ""})")
            .setItems(items) { _, w -> setPatternLength(Pat.LENGTHS[w]) }
            .show()
    }

    fun setPatternLength(n: Int) {
        val old = Pat.len
        if (n == old || n !in Pat.LENGTHS) return
        if (n > old) {
            val e = engine
            fun <T> rep(get: (Int) -> T, set: (Int, T) -> Unit) {
                for (k in old until n) set(k, get(k % old))
            }
            for (t in e.tracks) rep({ t.steps[it] }, { k, st ->
                t.steps[k].on = st.on
                t.steps[k].pitch = st.pitch
                t.steps[k].level = st.level
            })
            for (p in e.pads) {
                rep({ p.on[it] }, { k, v -> p.on[k] = v })
                rep({ p.lvl[it] }, { k, v -> p.lvl[k] = v })
            }
            val b = e.bass
            rep({ b.on[it] }, { k, v -> b.on[k] = v })
            rep({ b.note[it] }, { k, v -> b.note[k] = v })
            rep({ b.accent[it] }, { k, v -> b.accent[k] = v })
            rep({ b.slide[it] }, { k, v -> b.slide[k] = v })
            val q = e.analogSeq
            for (k in old until n) q.copyStep(k % old, k)
            for (g in arrayOf(e.rhythmGtr, e.riffGtr)) {
                rep({ g.art[it] }, { k, v -> g.art[k] = v })
                rep({ g.fret[it] }, { k, v -> g.fret[k] = v })
            }
            rep({ e.ambient.steps[it] }, { k, v -> e.ambient.steps[k] = v })
            for (ps in e.bank.parts) ps.extend(old, n)
            e.auto.extend(old, n)
        }
        Pat.len = n
        if (Pat.view >= Pat.bars()) Pat.view = 0
        ui.toast(if (n > old) "Pattern: ${n / Pat.BAR} bars. The new bars start as copies; change them with the bar buttons."
                 else "Pattern: ${n / Pat.BAR} bar${if (n > Pat.BAR) "s" else ""}. The steps after it are kept, not played.")
        refreshCurrent()
    }

    fun syncSummary(): String {
        val s = syncLine()
        val devs = if (midi.connected) "Connected: ${midi.deviceName}" else "No MIDI device connected"
        return (if (s.isNotEmpty()) "$s\n" else "Sync off.\n") + devs
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
        val devs = if (midi.connected) midi.deviceName else "none"
        val items = arrayOf(
            "FOLLOW: the other device is the boss. Clock, Start, Stop, Continue and Song Position come from it.",
            "LEAD: the app is the boss. It sends Clock, Start, Stop, Continue and Song Position to every connected device.",
            "Sync off",
            "Connect another MIDI device (connected: $devs)",
            "Clock while stopped (LEAD): " + (if (engine.clockIdle) "ON" else "OFF") + "  · tap to switch",
            "MIDI Thru (pass incoming MIDI on to the other devices): " + (if (midi.thru) "ON" else "OFF"),
            "Disconnect all MIDI devices"
        )
        AlertDialog.Builder(this)
            .setTitle("MIDI sync (now: $now)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startSync(2)
                    1 -> startSync(1)
                    2 -> syncOff()
                    3 -> connectAnother()
                    4 -> {
                        engine.clockIdle = !engine.clockIdle
                        ui.toast(if (engine.clockIdle) "LEAD sends the clock all the time, also while stopped (standard)" else "LEAD sends the clock only while playing")
                    }
                    5 -> {
                        midi.thru = !midi.thru
                        ui.toast(if (midi.thru) "MIDI Thru on" else "MIDI Thru off")
                    }
                    else -> {
                        syncOff()
                        midi.close()
                        syncNote = ""
                        ui.toast("All MIDI devices disconnected")
                    }
                }
            }
            .show()
    }

    /** Adds one more MIDI device: the clock (LEAD) goes to all of them. */
    private fun connectAnother() {
        val infos = midi.freeDevices()
        if (infos.isEmpty()) {
            ui.toast("No other MIDI device found. Connect it by USB (a hub works) or pair it in the Bluetooth settings.")
            return
        }
        val names = Array(infos.size) { midi.nameOf(infos[it]) }
        AlertDialog.Builder(this)
            .setTitle("Connect which MIDI device?")
            .setItems(names) { _, which ->
                midi.open(infos[which]) { ok ->
                    ui.toast(if (ok) "Connected: ${midi.nameOf(infos[which])} (${midi.count} MIDI devices)" else "Could not open the MIDI device")
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
        val preparing = engine.playing && engine.prepare >= 0
        ui.styleButton(playBtn, engine.playing, if (preparing) Theme.YELLOW else Theme.GREEN, 26)
        val sb = when (syncState) {
            1 -> "LEAD"
            2 -> "FOLLOW"
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
                "Playing ${PartNames.NAMES[engine.bank.cur]} · step ${engine.curStep + 1} / ${Pat.len}" +
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
        // the one REC button shows whatever is recording
        val rb = when {
            mr.recording -> "■ MIX " + clock(mr.frames / Cfg.SR)
            bk.recState == 1 -> "WAIT…"
            bk.recState == 2 -> "■ PART ${bk.recBars + 1}"
            else -> "● REC"
        }
        if (recBtn.text.toString() != rb) recBtn.text = rb
        ui.styleButton(recBtn, mr.recording || bk.recState == 1 || bk.recState == 2, Theme.RED)
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
            if (cur in intArrayOf(0, 1, 2, 6, 7, 9, TAB_HOME, TAB_BASS)) screens[cur].onShow()
        }
        if (preparing && statusText.currentTextColor != Theme.RED) {
            statusText.setTextColor(Theme.YELLOW)
            statusText.text = "PREPARE · only ${Engine.prepName(engine.prepare)} plays · step ${engine.curStep + 1} / ${Pat.len}" +
                " · not in the master, not recorded. Press ▶ at the top to play everything."
        }
        // the dot in front of the status line: red = recording, yellow = preparing, green = playing, grey = stopped
        val dc = when {
            statusText.currentTextColor == Theme.RED -> Theme.RED
            preparing -> Theme.YELLOW
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

    private var stemDir: File? = null

    fun toggleMixRec() {
        val m = engine.mixRec
        if (m.recording) {
            engine.recGate = 0
            lastMix = m.stop()
            val sd = stemDir
            stemDir = null
            if (sd != null) {
                for (r in engine.stemRecs) r.stop()
                engine.recGate = 1
                packStems(sd)
            } else {
                engine.recGate = 1
                if (lastMix != null) mixDialog()
            }
        } else {
            val withStems = engine.stemsOn
            if (withStems) engine.recGate = 0
            val f = m.start(File(filesDir, "mixes"))
            if (f == null) {
                engine.recGate = 1
                ui.toast("Cannot start the mix recording (not enough free storage?)")
                return
            }
            if (withStems) {
                val sd = File(filesDir, "stems/" + f.nameWithoutExtension.replace("Mix_", "Stems_"))
                sd.mkdirs()
                java.util.Arrays.fill(engine.stemPeak, 0f)
                var ok = true
                for ((k, r) in engine.stemRecs.withIndex()) {
                    r.bits = m.recBits
                    if (r.start(sd, String.format(Locale.US, "%02d_%s", k + 1, Engine.STEM_NAMES[k])) == null) ok = false
                }
                if (ok) {
                    stemDir = sd
                    engine.recGate = 3
                } else {
                    for (r in engine.stemRecs) r.stop()
                    sd.deleteRecursively()
                    engine.recGate = 1
                    ui.toast("Stems could not start (storage?). Recording the mix only.")
                }
            }
            ui.toast("Recording the whole mix" + (if (stemDir != null) " + ${Engine.STEM_NAMES.size} stems" else "") +
                " in ${MixRecorder.label(m.recBits)}. Press ■ REC to stop.")
        }
    }

    /** After a recording with stems: drop the silent ones, pack the rest (and the mix) into one .zip to save. */
    private fun packStems(sd: File) {
        ui.toast("Packing the stems…")
        val mix = lastMix
        Thread {
            var zip: File? = null
            var n = 0
            try {
                val files = sd.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    val k = (f.name.take(2).toIntOrNull() ?: 0) - 1
                    if (k in engine.stemPeak.indices && engine.stemPeak[k] < 0.00002f) f.delete()
                }
                val keep = sd.listFiles()?.sortedBy { it.name } ?: emptyList()
                val z = File(sd.parentFile, sd.name + ".zip")
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(z), 1 shl 16)).use { zo ->
                    zo.setLevel(java.util.zip.Deflater.BEST_SPEED)
                    val all = ArrayList<File>(keep)
                    if (mix != null && mix.exists()) all.add(0, mix)
                    val buf = ByteArray(1 shl 16)
                    for (f in all) {
                        zo.putNextEntry(java.util.zip.ZipEntry(sd.name + "/" + f.name))
                        java.io.FileInputStream(f).use { inp ->
                            while (true) {
                                val r = inp.read(buf)
                                if (r <= 0) break
                                zo.write(buf, 0, r)
                            }
                        }
                        zo.closeEntry()
                        n++
                    }
                }
                sd.deleteRecursively()
                zip = z
            } catch (ex: Exception) {
                zip = null
            }
            runOnUiThread {
                val z = zip
                if (z == null) {
                    ui.toast("Could not pack the stems")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Stems ready")
                        .setMessage("${n - (if (mix != null) 1 else 0)} stems + the mix in ${z.name} " +
                            String.format(Locale.US, "(%.1f MB). ", z.length() / 1048576.0) +
                            "Every file starts at the same moment, so they line up in any DAW. Silent parts were left out.")
                        .setPositiveButton("Save to device…") { _, _ -> saveFile(z, "application/zip") }
                        .setNeutralButton("The mix…") { _, _ -> mixDialog() }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }.start()
    }

    fun toggleStems() {
        engine.stemsOn = !engine.stemsOn
        ui.toast(if (engine.stemsOn) "Stems on: the next mix recording also writes every part to its own file (.zip)"
                 else "Stems off: the next recording is the mix only")
        refreshCurrent()
    }

    /** Export the patterns (or the song) as a MIDI file for a DAW. */
    fun exportMidiDialog() {
        val b = engine.bank
        val items = ArrayList<String>()
        items.add("This pattern (${Pat.bars()} bar${if (Pat.bars() > 1) "s" else ""})")
        if (b.songLen > 0) items.add("The whole song (${b.songLen} parts in the list)")
        AlertDialog.Builder(this)
            .setTitle("Export MIDI")
            .setItems(items.toTypedArray()) { _, w ->
                val bytes = try { MidiExport.build(engine, w == 1) } catch (ex: Exception) { null }
                if (bytes == null) {
                    ui.toast("Nothing to export: no notes in the patterns")
                } else {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(java.util.Date())
                    saveBytes("BurgerStudio_${if (w == 1) "song" else "pattern"}_$stamp.mid", bytes, "audio/midi")
                }
            }
            .show()
    }

    /** Long press on REC MIX: the format of the next mix recording. */
    fun mixFormatDialog() {
        val m = engine.mixRec
        val opts = intArrayOf(MixRecorder.BITS_24, MixRecorder.FLOAT_32, MixRecorder.BITS_16)
        val items = arrayOf(
            "24 bit: studio quality, about 15 MB a minute",
            "32 bit float: never clips, best to finish in a DAW, about 20 MB a minute",
            "16 bit: smaller (CD quality, with dither), about 10 MB a minute"
        )
        AlertDialog.Builder(this)
            .setTitle("Mix recording format (now: ${MixRecorder.label(m.bits)})")
            .setItems(items) { _, which ->
                m.bits = opts[which]
                ui.toast("The next mix recording will be ${MixRecorder.label(m.bits)}" + (if (m.recording) " (this one stays ${MixRecorder.label(m.recBits)})" else ""))
            }
            .show()
    }

    fun mixDialog() {
        val f = lastMix
        if (f == null || !f.exists()) {
            ui.toast("No mix recorded yet. Press ● REC MIX while you play.")
            return
        }
        val secs = (Wav.info(f)?.seconds ?: 0.0).toInt().coerceAtLeast(0)
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

    fun saveFile(f: File, mime: String = "audio/wav") {
        pendingFile = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
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
        showTab(TAB_TRACKS)
        tracksScreen.scrollToEditor()
    }

    // ---------------- import / export ----------------

    fun importFiles(multiple: Boolean = true) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        startActivityForResult(i, REQ_IMPORT)
    }

    /** Library: pick a DX7 / FM-1 .syx file to turn into samples. */
    fun importSyx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_SYX)
    }

    /** Choose the voices of a bank and the note, then render them into the library. */
    private fun syxDialog(bank: String, voices: List<FmRender.Voice>) {
        val names = Array(voices.size) { "${it + 1}. ${voices[it].name}" }
        val checked = BooleanArray(voices.size) { true }
        AlertDialog.Builder(this)
            .setTitle("$bank: ${voices.size} FM voices. Which ones become samples?")
            .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Next") { _, _ ->
                val pick = voices.filterIndexed { i, _ -> checked[i] }
                if (pick.isEmpty()) {
                    ui.toast("Nothing chosen")
                    return@setPositiveButton
                }
                val notes = intArrayOf(36, 48, 60, 72)
                val labels = arrayOf("C2 (bass)", "C3", "C4 (middle)", "C5 (bells, leads)")
                AlertDialog.Builder(this)
                    .setTitle("Record them on which note? (the Sampler can pitch them later)")
                    .setItems(labels) { _, w -> renderSyx(bank, pick, notes[w]) }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderSyx(bank: String, pick: List<FmRender.Voice>, note: Int) {
        ui.toast("Turning ${pick.size} FM voices into samples...")
        val nn = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val nName = nn[note % 12] + (note / 12 - 1)
        Thread {
            var made = 0
            for (v in pick) {
                try {
                    val d = FmRender.render(v.p, note, 100, 1.2, 1.8)
                    if (d.isNotEmpty()) {
                        store.saveNew("$bank ${v.name} $nName", d, Cfg.SR)
                        made++
                    }
                } catch (ex: Exception) {
                } catch (ex: OutOfMemoryError) {
                    break
                }
            }
            runOnUiThread {
                libraryScreen.refresh()
                ui.toast("$made FM sounds added to the Library. Load them into the Sampler with L.")
            }
        }.start()
    }

    fun saveBytes(name: String, bytes: ByteArray, mime: String = "audio/wav") {
        pendingBytes = bytes
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
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
        } else if (requestCode == REQ_SYX) {
            val uri = data.data ?: return
            Thread {
                val voices = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.size > 1_000_000) null else FmRender.readSyx(bytes)
                } catch (ex: Exception) {
                    null
                }
                val bank = displayName(uri).removeSuffix(".syx").removeSuffix(".SYX").take(20)
                runOnUiThread {
                    if (voices == null) ui.toast("This is not a DX7 / FM-1 .syx bank (32 voices) or single voice")
                    else syxDialog(bank, voices)
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
                    runOnUiThread { ui.toast("Saved ${src.name}") }
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
