package com.example.fm1burger

import android.content.Context
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class MixerScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val faders = ArrayList<FaderView>()
    private val pans = ArrayList<SliderRow>()
    private val muteBtns = ArrayList<Button>()
    private lateinit var padFader: FaderView
    private lateinit var bassFader: FaderView
    private lateinit var synthFader: FaderView
    private lateinit var drumMuteBtn: Button
    private lateinit var bassMuteBtn: Button
    private lateinit var synthMuteBtn: Button
    private lateinit var voiceFader: FaderView
    private lateinit var voiceMuteBtn: Button
    private lateinit var songFader: FaderView
    private lateinit var songMuteBtn: Button
    private lateinit var mixRecBtn: Button
    private lateinit var mixInfo: TextView
    private lateinit var masterFader: FaderView
    private lateinit var rhythmFader: FaderView
    private lateinit var riffFader: FaderView
    private lateinit var ambFader: FaderView
    private lateinit var rhythmMuteBtn: Button
    private lateinit var riffMuteBtn: Button
    private lateinit var ambMuteBtn: Button
    private var muteKey = ""

    // 3.40 CUE: a button on every strip, and the headphone mix in the Output panel
    private val cueBtns = ArrayList<Button>()                  // the fixed strips
    private val cueIdx = ArrayList<Int>()                      // their Engine.CUE_* source (-1: all the drum pads)
    private val padCueBtns = ArrayList<Button>()
    private var cueKey = -1
    private lateinit var splitBtn: Button
    private lateinit var swapBtn: Button
    private lateinit var cueMixRow: SliderRow
    private lateinit var cueVolRow: SliderRow
    private lateinit var cueInfo: TextView
    private var cueInfoSeen = ""

    // 3.38: one strip for each drum pad a Home lane shows (in Home's lane order), on pads[k].volume
    private val lanes = a.lanes
    private val padRow = ui.row()
    private val padFaders = ArrayList<FaderView>()
    private val padMuteBtns = ArrayList<Button>()
    private val padIdx = IntArray(LaneSetup.N)                 // the pad of each strip
    private val padSeenName = arrayOfNulls<String>(LaneSetup.N)   // the sound name each strip's label was made from
    private var padCount = -1                                  // -1: not built yet
    private val padScratch = IntArray(LaneSetup.N)
    private lateinit var outSpinner: Spinner
    private var outDevs: List<AudioDeviceInfo> = emptyList()
    private var lastOut = 0

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)

        val op = ui.panel()
        op.addView(ui.text("Output", 14f, Theme.ACCENT, true))
        outSpinner = ui.spinner(listOf("Default")) { idx ->
            if (idx != lastOut) {
                lastOut = idx
                e.setOutput(outDevs.getOrNull(idx - 1))
            }
        }
        op.addView(outSpinner)
        op.addView(
            ui.text(
                "Choose the tablet speaker, headphones, or a USB audio interface or instrument.",
                12f, Theme.DIM
            )
        )
        op.addView(ui.button("Rescan outputs") { scanOutputs() }, ui.fullWidth(4))
        // 3.54: the audio box (the FM-1 and the app together: master clock, SEPARATE / BOTH 3.5 / MERGED IN BURGER)
        op.addView(ui.button("AUDIO BOX: FM-1 clock and sound switches…") { a.audioBox.show() }, ui.fullWidth(4))

        // 3.40 CUE: the headphone mix
        op.addView(ui.text("Headphones (CUE \uD83C\uDFA7)", 14f, Theme.ACCENT, true), ui.fullWidth(10))
        op.addView(
            ui.text(
                "A lane in CUE is silent in the main output and plays in the headphones: a USB interface with 4 " +
                    "outputs (1-2 main, 3-4 headphones), or headphones next to another Output (the tablet speaker), " +
                    "or a split cable. Recordings and exports always get the whole mix.",
                12f, Theme.DIM
            )
        )
        cueMixRow = ui.slider("CUE MIX (main mix in the headphones)", 100, Math.round(e.cueMix * 100f), { "$it %" }) { v ->
            e.cueMix = v / 100f
        }
        op.addView(cueMixRow.view)
        cueVolRow = ui.slider("Headphone level", 100, Math.round(e.cueLevel() * 100f), { "$it %" }) { v ->
            e.cueVol = v / 100f
        }
        op.addView(cueVolRow.view)
        val sr = ui.row()
        splitBtn = ui.button("Split cable (DJ)") {
            e.cueSplit = !e.cueSplit
            e.cueSync()
            refreshCues()
        }
        sr.addView(splitBtn, ui.w(1.4f, 3))
        swapBtn = ui.button("SWAP L / R") {
            e.cueSwap = !e.cueSwap
            refreshCues()
        }
        sr.addView(swapBtn, ui.w(1f, 3))
        op.addView(sr, ui.fullWidth(4))
        op.addView(
            ui.text("Split cable: a Y cable on the one 3.5 mm jack. Left = main mix, right = headphone mix, both mono.", 12f, Theme.DIM)
        )
        cueInfo = ui.text("", 12f, Theme.TEXT)
        op.addView(cueInfo)
        content.addView(op, ui.fullWidth(0))

        val mp = ui.panel()
        mp.addView(ui.text("Record the whole mix", 14f, Theme.ACCENT, true))
        mp.addView(
            ui.text(
                "Records everything you hear (tracks, drums, bass, synth, voice) live, with no length limit. " +
                    "The ● REC MIX button at the top does the same from any page.",
                12f, Theme.DIM
            )
        )
        val mr = ui.row()
        mixRecBtn = ui.button("● Record mix") { a.toggleMixRec() }
        mr.addView(mixRecBtn, ui.w(1f, 3))
        mr.addView(ui.button("Last mix...") { a.mixDialog() }, ui.w(1f, 3))
        mr.addView(ui.button("Format...") { a.mixFormatDialog() }, ui.w(0.8f, 3))
        mp.addView(mr, ui.fullWidth(4))
        mixInfo = ui.text("", 12f, Theme.DIM)
        mp.addView(mixInfo)
        content.addView(mp, ui.fullWidth(8))

        val row = ui.row()
        for (i in 0 until Cfg.TRACKS) {
            val col = ui.panel()
            col.addView(ui.text("Track ${i + 1}", 13f, Theme.TRACK_COLORS[i], true))
            val f = FaderView(a)
            f.color = Theme.TRACK_COLORS[i]
            f.onChange = { v -> e.tracks[i].volume = v }
            col.addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
            val pan = ui.slider("Pan", 200, 100, { panText(it) }) { v ->
                e.tracks[i].pan = (v - 100) / 100f
            }
            col.addView(pan.view)
            val m = ui.button("Mute") {
                val t = e.tracks[i]
                t.mute = !t.mute
                refreshMutes()
            }
            col.addView(m, ui.fullWidth(4))
            col.addView(cueButton(i), ui.fullWidth(4))
            faders.add(f)
            pans.add(pan)
            muteBtns.add(m)
            row.addView(col, colLp())
        }

        val pcol = ui.panel()
        pcol.addView(ui.text("All drums", 13f, Theme.GREEN, true))
        padFader = FaderView(a)
        padFader.color = Theme.GREEN
        padFader.onChange = { v -> e.padVol = v }
        pcol.addView(padFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        pcol.addView(ui.text("Drum bus: all 32 drum pads together (banks A and B)", 11f, Theme.DIM))
        drumMuteBtn = ui.button("Mute") {
            val anyOn = e.pads.any { !it.mute }
            for (p in e.pads) p.mute = anyOn
            refreshMutes()
        }
        pcol.addView(drumMuteBtn, ui.fullWidth(4))
        pcol.addView(cueButton(-1), ui.fullWidth(4))
        row.addView(pcol, colLp())
        // 3.38: the strips of the drum lanes on Home (filled by syncPadStrips)
        padRow.gravity = android.view.Gravity.TOP
        row.addView(padRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val bcol = ui.panel()
        bcol.addView(ui.text("Bassline", 13f, Theme.ACCENT, true))
        bassFader = FaderView(a)
        bassFader.color = Theme.ACCENT
        bassFader.onChange = { v -> e.bass.volume = v }
        bcol.addView(bassFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        bcol.addView(ui.text("Bass synth (Synth page)", 11f, Theme.DIM))
        bassMuteBtn = ui.button("Mute") {
            e.bass.mute = !e.bass.mute
            refreshMutes()
        }
        bcol.addView(bassMuteBtn, ui.fullWidth(4))
        bcol.addView(cueButton(Engine.CUE_BASS), ui.fullWidth(4))
        row.addView(bcol, colLp())

        val scol = ui.panel()
        scol.addView(ui.text("Analog synth", 13f, Theme.TEXT, true))
        synthFader = FaderView(a)
        synthFader.color = Theme.PURPLE
        synthFader.onChange = { v -> e.analog.volume = v }
        scol.addView(synthFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        scol.addView(ui.text("Keyboard synth (Synth page)", 11f, Theme.DIM))
        synthMuteBtn = ui.button("Mute") {
            e.analog.mute = !e.analog.mute
            refreshMutes()
        }
        scol.addView(synthMuteBtn, ui.fullWidth(4))
        scol.addView(cueButton(Engine.CUE_SYNTH), ui.fullWidth(4))
        row.addView(scol, colLp())

        val vcol = ui.panel()
        vcol.addView(ui.text("Voice", 13f, Theme.PINK, true))
        voiceFader = FaderView(a)
        voiceFader.color = Theme.PINK
        voiceFader.onChange = { v -> e.vocal.volume = v }
        vcol.addView(voiceFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        vcol.addView(ui.text("Live mic (Voice page)", 11f, Theme.DIM))
        voiceMuteBtn = ui.button("Mute") {
            e.vocal.mute = !e.vocal.mute
            refreshMutes()
        }
        vcol.addView(voiceMuteBtn, ui.fullWidth(4))
        vcol.addView(cueButton(Engine.CUE_VOICE), ui.fullWidth(4))
        row.addView(vcol, colLp())

        val gcol = ui.panel()
        gcol.addView(ui.text("Song rec", 13f, Theme.BLUE, true))
        songFader = FaderView(a)
        songFader.color = Theme.BLUE
        songFader.onChange = { v -> e.bank.clipVol = v }
        gcol.addView(songFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        gcol.addView(ui.text("Part recordings (Song page)", 11f, Theme.DIM))
        songMuteBtn = ui.button("Mute") {
            e.bank.clipMute = !e.bank.clipMute
            refreshMutes()
        }
        gcol.addView(songMuteBtn, ui.fullWidth(4))
        gcol.addView(cueButton(Engine.CUE_SONG), ui.fullWidth(4))
        row.addView(gcol, colLp())

        // the newer instruments, so every lane on Home has its strip here
        fun strip(name: String, col: Int, sub: String, cue: Int, vol: () -> Float, setVol: (Float) -> Unit, toggle: () -> Unit): Pair<FaderView, Button> {
            val c = ui.panel()
            c.addView(ui.text(name, 13f, col, true))
            val f = FaderView(a)
            f.color = col
            f.value = vol()
            f.onChange = { v -> setVol(v) }
            c.addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
            c.addView(ui.text(sub, 11f, Theme.DIM))
            val m = ui.button("Mute") {
                toggle()
                refreshMutes()
            }
            c.addView(m, ui.fullWidth(4))
            c.addView(cueButton(cue), ui.fullWidth(4))
            row.addView(c, colLp())
            return Pair(f, m)
        }
        strip("Rhythm gtr", Theme.STEEL, "Power chords (Guitars page)", Engine.CUE_RHYTHM, { e.rhythmGtr.volume }, { e.rhythmGtr.volume = it }) {
            e.rhythmGtr.mute = !e.rhythmGtr.mute
        }.also { rhythmFader = it.first; rhythmMuteBtn = it.second }
        strip("Riff gtr", Theme.LIME, "Single notes (Guitars page)", Engine.CUE_RIFF, { e.riffGtr.volume }, { e.riffGtr.volume = it }) {
            e.riffGtr.mute = !e.riffGtr.mute
        }.also { riffFader = it.first; riffMuteBtn = it.second }
        strip("New Ambient", Theme.TEAL, "Creatures (New Ambient page)", Engine.CUE_AMBIENT, { e.ambient.volume }, { e.ambient.volume = it }) {
            e.ambient.mute = !e.ambient.mute
        }.also { ambFader = it.first; ambMuteBtn = it.second }

        val mcol = ui.panel()
        mcol.addView(ui.text("Master", 13f, Theme.TEXT, true))
        masterFader = FaderView(a)
        masterFader.color = Theme.TEXT
        masterFader.onChange = { v -> e.masterVol = v }
        mcol.addView(masterFader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
        row.addView(mcol, colLp())

        val hs = HorizontalScrollView(a)
        hs.isHorizontalScrollBarEnabled = true
        hs.addView(row)
        content.addView(hs, ui.fullWidth(10))
        scanOutputs()
    }

    /** 3.40: the CUE button of a strip ([src]: Engine.CUE_*, or -1 = all the drum pads). */
    private fun cueButton(src: Int): Button {
        val b = ui.button("CUE \uD83C\uDFA7") {
            if (src < 0) {
                val on = !allPadsCue()
                for (k in e.pads.indices) e.setCue(Engine.CUE_PAD + k, on)
            } else {
                e.setCue(src, !e.isCue(src))
            }
            refreshCues()
        }
        cueBtns.add(b)
        cueIdx.add(src)
        return b
    }

    private fun allPadsCue(): Boolean {
        for (k in e.pads.indices) if (!e.isCue(Engine.CUE_PAD + k)) return false
        return true
    }

    /** All the CUE states in one number (no string each tick): when it changes anywhere, the buttons follow. */
    private fun currentCueKey(): Int {
        var h = if (e.cueSplit) 3 else 1
        if (e.cueSwap) h += 4
        for (c in e.cue) h = h * 31 + (if (c) 1 else 0)
        return h
    }

    private fun refreshCues() {
        for (j in 0 until cueBtns.size) {
            val s = cueIdx[j]
            ui.styleButton(cueBtns[j], if (s < 0) allPadsCue() else e.isCue(s), Theme.BLUE)
        }
        for (j in 0 until padCueBtns.size) ui.styleButton(padCueBtns[j], e.isCue(Engine.CUE_PAD + padIdx[j]), Theme.BLUE)
        ui.styleButton(splitBtn, e.cueSplit, Theme.BLUE)
        ui.styleButton(swapBtn, e.cueSwap, Theme.BLUE)
        cueKey = currentCueKey()
    }

    /** Every mixer strip has the same width; the row scrolls sideways when there are more strips than fit. */
    private fun colLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ui.dp(118), LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        return lp
    }

    /** The drum pads Home's lanes show, in lane order, each once, into [out]; returns how many. */
    private fun homePads(out: IntArray): Int {
        var n = 0
        for (i in 0 until LaneSetup.N) {
            if (lanes.type[i] != Inst.DRUM) continue
            val k = lanes.idx[i]
            if (k !in e.pads.indices) continue
            var dup = false
            for (j in 0 until n) if (out[j] == k) { dup = true; break }
            if (!dup) out[n++] = k
        }
        return n
    }

    /** "A1 Kick": the pad and its sound, as the Home lane names it. */
    private fun padLabel(k: Int): String {
        val n = e.pads[k].name
        val sn = n.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
        return if (sn.isEmpty()) Cfg.padName(k) else "${Cfg.padName(k)} $sn"
    }

    /** 3.38: rebuilds the drum lanes' strips when Home's lanes (or a pad's sound) changed; cheap when nothing did. */
    private fun syncPadStrips() {
        val n = homePads(padScratch)
        var same = n == padCount
        if (same) for (j in 0 until n) {
            if (padScratch[j] != padIdx[j] || e.pads[padScratch[j]].name != padSeenName[j]) { same = false; break }
        }
        if (same) return
        // a finger on one of these faders: wait until it lifts
        for (f in padFaders) if (f.touching) return
        padRow.removeAllViews()
        padFaders.clear()
        padMuteBtns.clear()
        padCueBtns.clear()
        padCount = n
        val col = Inst.color(Inst.DRUM)
        for (j in 0 until n) {
            val k = padScratch[j]
            padIdx[j] = k
            padSeenName[j] = e.pads[k].name
            val c = ui.panel()
            val t = ui.text(padLabel(k), 13f, col, true)
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            c.addView(t)
            val f = FaderView(a)
            f.color = col
            f.value = e.pads[k].volume
            f.onChange = { v -> e.pads[k].volume = v }
            c.addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(240)))
            c.addView(ui.text("Drum lane on Home (this pad only)", 11f, Theme.DIM))
            val m = ui.button("Mute") {
                e.pads[k].mute = !e.pads[k].mute
                refreshMutes()
            }
            c.addView(m, ui.fullWidth(4))
            val cb = ui.button("CUE \uD83C\uDFA7") {
                e.setCue(Engine.CUE_PAD + k, !e.isCue(Engine.CUE_PAD + k))
                refreshCues()
            }
            c.addView(cb, ui.fullWidth(4))
            padFaders.add(f)
            padMuteBtns.add(m)
            padCueBtns.add(cb)
            padRow.addView(c, colLp())
        }
        refreshMutes()
        refreshCues()
    }

    /** 3.38: a fader follows its value when it changed somewhere else (Home, automation, MIDI...) and no finger is on it. */
    private fun follow(f: FaderView, v: Float) {
        if (!f.touching && f.value != v) f.value = v
    }

    private fun syncFaders() {
        for (i in 0 until Cfg.TRACKS) follow(faders[i], e.tracks[i].volume)
        follow(padFader, e.padVol)
        for (j in 0 until padFaders.size) follow(padFaders[j], e.pads[padIdx[j]].volume)
        follow(bassFader, e.bass.volume)
        follow(synthFader, e.analog.volume)
        follow(voiceFader, e.vocal.volume)
        follow(songFader, e.bank.clipVol)
        follow(rhythmFader, e.rhythmGtr.volume)
        follow(riffFader, e.riffGtr.volume)
        follow(ambFader, e.ambient.volume)
        follow(masterFader, e.masterVol)
    }

    private fun panText(v: Int): String = when {
        v == 100 -> "C"
        v < 100 -> "L${100 - v}"
        else -> "R${v - 100}"
    }

    private fun outLabel(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Tablet speaker"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth: ${d.productName}"
        else -> "Other: ${d.productName}"
    }

    private fun scanOutputs() {
        val allowed = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
        outDevs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in allowed }
        val items = listOf("Default (system choice)") + outDevs.map { outLabel(it) }
        lastOut = 0
        ui.fill(outSpinner, items)
        outSpinner.setSelection(0)
    }

    private fun refreshMutes() {
        for (i in 0 until Cfg.TRACKS) {
            ui.styleButton(muteBtns[i], e.tracks[i].mute, Theme.RED)
        }
        ui.styleButton(drumMuteBtn, e.pads.all { it.mute }, Theme.RED)
        for (j in 0 until padMuteBtns.size) ui.styleButton(padMuteBtns[j], e.pads[padIdx[j]].mute, Theme.RED)
        ui.styleButton(bassMuteBtn, e.bass.mute, Theme.RED)
        ui.styleButton(synthMuteBtn, e.analog.mute, Theme.RED)
        ui.styleButton(voiceMuteBtn, e.vocal.mute, Theme.RED)
        ui.styleButton(songMuteBtn, e.bank.clipMute, Theme.RED)
        ui.styleButton(rhythmMuteBtn, e.rhythmGtr.mute, Theme.RED)
        ui.styleButton(riffMuteBtn, e.riffGtr.mute, Theme.RED)
        ui.styleButton(ambMuteBtn, e.ambient.mute, Theme.RED)
        muteKey = currentMuteKey()
    }

    /** All mute states in one string: when it changes anywhere (Home, Synth, Pads...), the buttons here follow. */
    private fun currentMuteKey(): String =
        e.tracks.joinToString("") { if (it.mute) "1" else "0" } + e.pads.joinToString("") { if (it.mute) "1" else "0" } +
            "${e.bass.mute}${e.analog.mute}${e.vocal.mute}${e.bank.clipMute}${e.rhythmGtr.mute}${e.riffGtr.mute}${e.ambient.mute}"

    override fun onShow() {
        syncPadStrips()
        for (j in 0 until padFaders.size) padFaders[j].value = e.pads[padIdx[j]].volume
        for (i in 0 until Cfg.TRACKS) {
            faders[i].value = e.tracks[i].volume
            pans[i].set(((e.tracks[i].pan * 100).toInt() + 100).coerceIn(0, 200))
        }
        padFader.value = e.padVol
        bassFader.value = e.bass.volume
        synthFader.value = e.analog.volume
        voiceFader.value = e.vocal.volume
        songFader.value = e.bank.clipVol
        rhythmFader.value = e.rhythmGtr.volume
        riffFader.value = e.riffGtr.volume
        ambFader.value = e.ambient.volume
        masterFader.value = e.masterVol
        refreshMutes()
        cueMixRow.set(Math.round(e.cueMix * 100f))
        cueVolRow.set(Math.round(e.cueLevel() * 100f))
        refreshCues()
    }

    override fun onTick() {
        syncPadStrips()
        syncFaders()
        if (currentMuteKey() != muteKey) refreshMutes()
        if (currentCueKey() != cueKey) refreshCues()
        // the headphone level follows MASTER until its own slider is moved
        if (e.cueVol < 0f) {
            val mvp = Math.round(e.masterVol * 100f)
            if (cueVolRow.get() != mvp) cueVolRow.set(mvp)
        }
        val ci = e.cueStatus()
        if (ci != cueInfoSeen) {
            cueInfoSeen = ci
            cueInfo.text = ci
        }
        for (i in 0 until Cfg.TRACKS) faders[i].meter = e.tracks[i].peak
        masterFader.meter = e.masterPeak
        val mr = e.mixRec
        if (mr.recording) {
            val s = mr.frames / Cfg.SR
            mixRecBtn.text = "■ Stop recording"
            ui.styleButton(mixRecBtn, true, Theme.RED)
            mixInfo.text = String.format(
                Locale.US, "Recording %d:%02d · %.1f MB", s / 60, s % 60, mr.bytesWritten / 1048576.0
            )
        } else {
            mixRecBtn.text = "● Record mix"
            ui.styleButton(mixRecBtn, false)
            mixInfo.text = "Not recording."
        }
    }
}
