package com.example.fm1burger

import android.widget.ScrollView
import android.widget.TextView

/**
 * Setup: the things you set once and then forget - MIDI sync, recording the mix and its format,
 * where the sound goes, FM banks, and about the app.
 */
class SetupScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    override val root = ScrollView(a)
    private val content = ui.col()
    private lateinit var syncInfo: TextView
    private lateinit var recInfo: TextView
    private lateinit var latInfo: TextView
    private lateinit var stemBtn: android.widget.Button
    private val latBtns = ArrayList<android.widget.Button>()

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(32))
        root.addView(content)
        content.addView(ui.title("Setup"))

        val mp = ui.panel()
        mp.addView(ui.text("MIDI sync", 14f, Theme.ACCENT, true))
        syncInfo = ui.text("", 12f, Theme.DIM)
        mp.addView(syncInfo, ui.fullWidth(4))
        mp.addView(ui.button("MIDI sync: follow / lead / devices / thru…") { a.openSyncMenu() }, ui.fullWidth(6))
        content.addView(mp, ui.fullWidth(10))

        val lp = ui.panel()
        lp.addView(ui.text("Audio latency", 14f, Theme.ACCENT, true))
        lp.addView(ui.text("How quickly a tap is heard. Low uses the tablet's fast audio path at its own sample rate " +
            "and makes the buffer a little bigger by itself if the sound breaks up. Safe is the old big buffer, for slow tablets.",
            12f, Theme.DIM), ui.fullWidth(4))
        val lr = ui.row()
        for ((k, n) in listOf("Low", "Normal", "Safe").withIndex()) {
            val b = ui.button(n) {
                a.engine.latencyMode = k
                refreshButtons()
                ui.toast("Audio latency: $n")
            }
            latBtns.add(b)
            lr.addView(b, ui.w(1f, 3))
        }
        lp.addView(lr, ui.fullWidth(4))
        latInfo = ui.text("", 12f, Theme.TEXT)
        lp.addView(latInfo, ui.fullWidth(4))
        content.addView(lp, ui.fullWidth(10))

        val rp = ui.panel()
        rp.addView(ui.text("Record the mix", 14f, Theme.ACCENT, true))
        recInfo = ui.text("", 12f, Theme.DIM)
        rp.addView(recInfo, ui.fullWidth(4))
        val rr = ui.row()
        rr.addView(ui.button("● Record / ■ stop") { a.toggleMixRec() }, ui.w(1f, 3))
        rr.addView(ui.button("Format…") { a.mixFormatDialog() }, ui.w(1f, 3))
        rr.addView(ui.button("Last mix…") { a.mixDialog() }, ui.w(1f, 3))
        rp.addView(rr, ui.fullWidth(4))
        val sr = ui.row()
        stemBtn = ui.button("Stems: off") {
            a.toggleStems()
            refreshButtons()
        }
        sr.addView(stemBtn, ui.w(1f, 3))
        sr.addView(ui.button("Export MIDI…") { a.exportMidiDialog() }, ui.w(1f, 3))
        rp.addView(sr, ui.fullWidth(4))
        rp.addView(ui.text("Stems: with the mix, every part (sampler 1-4, drums, bassline, synth, guitars, New Ambient, voice, " +
            "song recordings) goes into its own file, all the same length, packed in one .zip for a DAW. " +
            "Export MIDI writes the notes of the pattern or of the whole song.", 11f, Theme.DIM), ui.fullWidth(4))
        content.addView(rp, ui.fullWidth(10))

        val op = ui.panel()
        op.addView(ui.text("Sound out and levels", 14f, Theme.ACCENT, true))
        op.addView(ui.text("The audio output (tablet speaker, headphones, the FM-1 over USB) and the master volume are on the Mixer.",
            12f, Theme.DIM), ui.fullWidth(4))
        val orow = ui.row()
        orow.addView(ui.button("Open the Mixer") { a.showTab(MainActivity.TAB_MIXER) }, ui.w(1f, 3))
        orow.addView(ui.button("Mute / unmute all sampler tracks") { a.muteAllTracks() }, ui.w(1f, 3))
        op.addView(orow, ui.fullWidth(4))
        content.addView(op, ui.fullWidth(10))

        val fp = ui.panel()
        fp.addView(ui.text("FM sound banks", 14f, Theme.ACCENT, true))
        val fr = ui.row()
        fr.addView(ui.button("MakerHip FM bank (32)") {
            Thread {
                val n = try { FmBank.install(a, a.store) } catch (ex: Exception) { -1 }
                a.runOnUiThread {
                    a.libraryScreen.refresh()
                    ui.toast(if (n < 0) "Could not add the FM bank" else if (n == 0) "Already in the Library" else "Added $n FM sounds")
                }
            }.start()
        }, ui.w(1f, 3))
        fr.addView(ui.button("Import .syx bank") { a.importSyx() }, ui.w(1f, 3))
        fp.addView(fr, ui.fullWidth(4))
        fp.addView(ui.button("Save MakerHip_FM.syx (for the FM-1)") {
            try {
                a.saveBytes(FmBank.SYX, FmBank.syxBytes(a), "application/octet-stream")
            } catch (ex: Exception) {
                ui.toast("Could not read the bank")
            }
        }, ui.fullWidth(4))
        content.addView(fp, ui.fullWidth(10))

        val ab = ui.panel()
        ab.addView(ui.text("About", 14f, Theme.ACCENT, true))
        val ver = try {
            a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: ""
        } catch (ex: Exception) {
            ""
        }
        ab.addView(ui.text("Burger Studio Lab $ver · MakerHip", 13f, Theme.TEXT), ui.fullWidth(4))
        ab.addView(ui.text(
            "This is the experimental 3.x line. It is a separate app with its own projects and sounds, " +
                "so Burger Studio 2.x and FM1 Burger stay as they are on the same device.\n\n" +
                "The FM renderer follows the open DX7 engine of Google's Music Synthesizer for Android (Apache License 2.0). " +
                "The drum kit, the FM bank, the guitars and the New Ambient creatures are made for this app.",
            11f, Theme.DIM), ui.fullWidth(4))
        content.addView(ab, ui.fullWidth(10))
    }

    private fun refreshButtons() {
        val e = a.engine
        for (k in latBtns.indices) ui.styleButton(latBtns[k], e.latencyMode == k)
        stemBtn.text = if (e.stemsOn) "Stems: ON" else "Stems: off"
        ui.styleButton(stemBtn, e.stemsOn, Theme.GREEN)
    }

    override fun onShow() {
        refreshButtons()
        onTick()
    }

    override fun onTick() {
        val s = a.syncSummary()
        if (syncInfo.text.toString() != s) syncInfo.text = s
        val m = a.engine.mixRec
        val r = if (m.recording) "Recording now in ${MixRecorder.label(m.recBits)}" else "Next recording: ${MixRecorder.label(m.bits)}"
        if (recInfo.text.toString() != r) recInfo.text = r
        val e = a.engine
        val li = String.format(java.util.Locale.US, "Now: %d Hz · buffer %d frames = %.1f ms · fast path: %s · glitches: %d",
            e.outRate, e.outFrames, e.latencyMs(), if (e.outFast) "yes" else "no", e.underruns) +
            (if (e.outRate != Cfg.SR) " · engine 44.1 kHz, resampled" else "")
        if (latInfo.text.toString() != li) latInfo.text = li
    }
}
