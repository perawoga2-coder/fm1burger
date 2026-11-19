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
    private lateinit var devList: TextView
    private lateinit var devPanel: android.widget.LinearLayout
    private lateinit var devInfo: TextView
    private lateinit var storeBtn: android.widget.Button
    private lateinit var labsBtn: android.widget.Button
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
            "and makes the buffer a little bigger by itself if the sound breaks up. Tap it again to start over small. " +
            "Safe is the old big buffer, for slow tablets.",
            12f, Theme.DIM), ui.fullWidth(4))
        val lr = ui.row()
        for ((k, n) in listOf("Low", "Normal", "Safe").withIndex()) {
            val b = ui.button(n) {
                a.engine.latencyMode = k              // (the same one again: starts over from the small buffer)
                refreshButtons()
                ui.toast("Audio latency: $n (started over)")
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
        op.addView(ui.text("The audio output (tablet speaker, headphones, a USB interface) and the master volume are on the Mixer.",
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
                    ui.toast(if (n < 0) "Could not add the FM bank" else if (n == 0) "Already in the Maker Bank" else "Added $n FM sounds")
                }
            }.start()
        }, ui.w(1f, 3))
        fr.addView(ui.button("Import .syx bank") { a.importSyx() }, ui.w(1f, 3))
        fp.addView(fr, ui.fullWidth(4))
        content.addView(fp, ui.fullWidth(10))

        // Devices: everything for one piece of gear in one place (the rest of the app needs no device)
        val dv = ui.panel()
        dv.addView(ui.text("Devices", 14f, Theme.ACCENT, true))
        dv.addView(ui.text("Plug in any instrument (USB, or Bluetooth MIDI): it connects by itself. A new one asks once " +
            "whether to follow its tempo, lead it or just play the pads, and is remembered. Its sound can be sampled with S " +
            "on a sampler track; a device that takes samples into slots of its own shows up in Library > Devices….",
            12f, Theme.DIM), ui.fullWidth(4))
        devList = ui.text("", 12f, Theme.TEXT)
        dv.addView(devList, ui.fullWidth(4))
        dv.addView(ui.button("Forget the devices (they ask again)") {
            a.hub.forgetAll()
            onTick()
            ui.toast("Forgotten: every device asks again when plugged in")
        }, ui.fullWidth(4))
        dv.addView(ui.text("Firmware for the M-VAVE FM-1 that takes samples from the app (optional)", 12f, Theme.DIM), ui.fullWidth(10))
        val dr = ui.row()
        dr.addView(ui.button("Firmware installer (Chrome)") {
            try {
                a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://perawoga2-coder.github.io/Fm1sampa/")))
            } catch (ex: Exception) {
                ui.toast("perawoga2-coder.github.io/Fm1sampa")
            }
        }, ui.w(1f, 3))
        dr.addView(ui.button("Save MakerHip_FM.syx") {
            try {
                a.saveBytes(FmBank.SYX, FmBank.syxBytes(a), "application/octet-stream")
            } catch (ex: Exception) {
                ui.toast("Could not read the bank")
            }
        }, ui.w(1f, 3))
        dv.addView(dr, ui.fullWidth(4))
        content.addView(dv, ui.fullWidth(10))

        // Developer: only the owner sees it (early access with the owner's code)
        devPanel = ui.panel()
        devPanel.addView(ui.text("Developer (only you see this)", 14f, Theme.ACCENT, true))
        devInfo = ui.text("", 12f, Theme.DIM)
        devPanel.addView(devInfo, ui.fullWidth(4))
        val d1 = ui.row()
        d1.addView(ui.button("Make an invite code") {
            val code = Features.newInvite()
            android.app.AlertDialog.Builder(a)
                .setTitle("Invite code")
                .setMessage("$code\n\nGive it to one tester: it unlocks the early access on their device.")
                .setPositiveButton("Copy") { _, _ ->
                    val cm = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Burger Studio invite", code))
                    ui.toast("Copied $code")
                }
                .setNegativeButton("Close", null)
                .show()
        }, ui.w(1f, 3))
        storeBtn = ui.button("") {
            Features.storePreview = !Features.storePreview
            onTick()
            ui.toast(if (Features.storePreview) "Store preview: you see the free version (Pro features ask)" else "Store preview off: everything open")
        }
        d1.addView(storeBtn, ui.w(1f, 3))
        devPanel.addView(d1, ui.fullWidth(4))
        val d2 = ui.row()
        labsBtn = ui.button("") {
            Features.labsOn = !Features.labsOn
            onTick()
        }
        d2.addView(labsBtn, ui.w(1f, 3))
        d2.addView(ui.button("Lock the app again") {
            android.app.AlertDialog.Builder(a)
                .setMessage("The app shows the invite page again (your owner code opens it).")
                .setPositiveButton("Lock") { _, _ ->
                    Features.lockAgain()
                    Features.showGate(a, a.findViewById(android.R.id.content))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, ui.w(1f, 3))
        devPanel.addView(d2, ui.fullWidth(4))
        content.addView(devPanel, ui.fullWidth(10))

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
        val names = a.hub.rememberedNames()
        val dl = "Connected now: " + (if (a.midi.connected) a.midi.deviceName else "none") +
            (a.hub.slotDevice?.let { " · $it takes samples" } ?: "") +
            (a.hub.synced?.let { " · $it follows the app's tempo" } ?: "") +
            "\nRemembered: " + (if (names.isEmpty()) "none yet" else names.joinToString(", "))
        if (devList.text.toString() != dl) devList.text = dl
        val own = Features.owner
        val vis = if (own) android.view.View.VISIBLE else android.view.View.GONE
        if (devPanel.visibility != vis) devPanel.visibility = vis
        if (own) {
            val di = "Early access: " + (if (Features.GATE) "on (invite codes)" else "off") +
                " · Free / Pro split: " + (if (Features.SELLING) "on" else "off until the sale starts") +
                "\nLABS (hidden features: Quick sample into a device): " + (if (Features.labsOn) "shown to you" else "hidden")
            if (devInfo.text.toString() != di) devInfo.text = di
            storeBtn.text = if (Features.storePreview) "Store preview: ON" else "Store preview: off"
            ui.styleButton(storeBtn, Features.storePreview, Theme.YELLOW)
            labsBtn.text = if (Features.labsOn) "LABS: shown" else "LABS: hidden"
            ui.styleButton(labsBtn, Features.labsOn, Theme.TEAL)
        }
    }
}
