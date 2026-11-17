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

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        store = Store(this)
        engine = Engine()
        recorder = Recorder(this)
        midi = MidiLink(this, engine)
        project = Project(this)
        flow = RecordFlow(this)

        buildUi()
        engine.start()
        project.load()
        showTab(0)
        handler.post(ticker)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        project.save()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        project.save()
        recorder.stop()
        engine.shutdown()
        midi.shutdown()
        super.onDestroy()
    }

    // ---------------- layout ----------------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setBackgroundColor(Theme.PANEL)
        head.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))

        val r1 = ui.row()
        playBtn = ui.button("▶") { togglePlay() }
        r1.addView(playBtn, LinearLayout.LayoutParams(ui.dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        r1.addView(ui.button("−") { changeBpm(-1) }, ui.w(0f, 2))
        bpmText = ui.text("BPM 120", 14f, Theme.TEXT, true)
        bpmText.gravity = android.view.Gravity.CENTER
        r1.addView(bpmText, LinearLayout.LayoutParams(ui.dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
        r1.addView(ui.button("+") { changeBpm(1) }, ui.w(0f, 2))
        bpmBar = SeekBar(this)
        bpmBar.max = 200
        bpmBar.progress = engine.bpm - 40
        bpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && !engine.follow) engine.bpm = p + 40
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        r1.addView(bpmBar, ui.w(1f, 4))
        syncBtn = ui.button("SYNC") { toggleSync() }
        r1.addView(syncBtn, ui.w(0f, 2))
        muteAllBtn = ui.button("MUTE ALL") { muteAll() }
        r1.addView(muteAllBtn, ui.w(0f, 2))
        head.addView(r1)

        statusText = ui.text("", 12f, Theme.DIM)
        statusText.maxLines = 2
        head.addView(statusText)
        root.addView(head)

        // screens
        val tracks = TracksScreen(this)
        val steps = StepsScreen(this)
        val mixer = MixerScreen(this)
        libraryScreen = LibraryScreen(this)
        editScreen = EditScreen(this)
        val fx = FxScreen(this)
        val pads = PadsScreen(this)
        screens = listOf(tracks, steps, mixer, libraryScreen, editScreen, fx, pads)

        // tabs
        val tabRow = ui.row()
        val names = listOf("Tracks", "Steps", "Mixer", "Library", "Edit", "FX", "Pads")
        val btns = ArrayList<Button>()
        names.forEachIndexed { i, n ->
            val b = ui.button(n) { showTab(i) }
            btns.add(b)
            tabRow.addView(b, ui.w(0f, 2))
        }
        tabBtns = btns
        val tabScroll = HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabRow)
        root.addView(tabScroll)

        val content = FrameLayout(this)
        for (s in screens) content.addView(s.root)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun showTab(i: Int) {
        cur = i
        screens.forEachIndexed { idx, s -> s.root.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { idx, b -> ui.styleButton(b, idx == i) }
        screens[i].onShow()
    }

    fun refreshCurrent() {
        screens[cur].onShow()
    }

    fun selectTrack(i: Int) {
        sel = i
        refreshCurrent()
    }

    // ---------------- header actions ----------------

    private fun togglePlay() {
        if (engine.playing) engine.stop() else engine.play()
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
        playBtn.text = if (engine.playing) "■" else "▶"
        ui.styleButton(playBtn, engine.playing)
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
        bpmText.text = if (engine.follow) String.format(Locale.US, "BPM %.1f", engine.bpmF) else "BPM ${engine.bpm}"
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
                "Playing · step ${engine.curStep + 1} / ${Cfg.STEPS}" + (if (sl.isNotEmpty()) " · $sl" else "")
            } else {
                sl.ifEmpty { "Ready. Press ▶ to play." }
            }
        }
        screens[cur].onTick()
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
            val imported = ArrayList<File>()
            for (u in uris) {
                val f = importUri(u)
                if (f != null) imported.add(f)
            }
            val target = importTarget
            importTarget = -1
            if (target >= 0 && imported.isNotEmpty()) assignTrack(target, imported[0])
            libraryScreen.refresh()
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

    private fun importUri(uri: Uri): File? {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.size > 40 * 1024 * 1024) {
                ui.toast("File unreadable or too large (max 40 MB)")
                return null
            }
            val p = Wav.parse(bytes)
            if (p == null) {
                ui.toast("Only WAV files (16 bit, 24 bit or float) can be imported")
                return null
            }
            var base = displayName(uri)
            if (base.lowercase(Locale.ROOT).endsWith(".wav")) base = base.substring(0, base.length - 4)
            val f = store.saveNew(base.ifEmpty { "Imported" }, p.data, p.rate)
            ui.toast("Imported ${f.name} (stereo files are mixed to mono)")
            return f
        } catch (e: Exception) {
            ui.toast("Import failed: ${e.message}")
            return null
        }
    }
}
