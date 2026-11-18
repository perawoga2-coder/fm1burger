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

        val rp = ui.panel()
        rp.addView(ui.text("Record the mix", 14f, Theme.ACCENT, true))
        recInfo = ui.text("", 12f, Theme.DIM)
        rp.addView(recInfo, ui.fullWidth(4))
        val rr = ui.row()
        rr.addView(ui.button("● Record / ■ stop") { a.toggleMixRec() }, ui.w(1f, 3))
        rr.addView(ui.button("Format…") { a.mixFormatDialog() }, ui.w(1f, 3))
        rr.addView(ui.button("Last mix…") { a.mixDialog() }, ui.w(1f, 3))
        rp.addView(rr, ui.fullWidth(4))
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

    override fun onShow() {
        onTick()
    }

    override fun onTick() {
        val s = a.syncSummary()
        if (syncInfo.text.toString() != s) syncInfo.text = s
        val m = a.engine.mixRec
        val r = if (m.recording) "Recording now in ${MixRecorder.label(m.recBits)}" else "Next recording: ${MixRecorder.label(m.bits)}"
        if (recInfo.text.toString() != r) recInfo.text = r
    }
}
