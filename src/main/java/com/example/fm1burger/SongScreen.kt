package com.example.fm1burger

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Song page.
 *  - (3.35) the Q overview, MPC style: 6 big pads Q1 Intro .. Q6 Outro (the 6 parts, the slots of TAKE ALL).
 *    Tap a pad while stopped: that Q plays (it loops). Tap another one while playing: it comes in at the end
 *    of the bar. Tap the pad that plays: stop. Long press: the actions of that part.
 *  - Song builder: put parts in order with a number of bars each, then play the whole song.
 */
class SongScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val bank = e.bank
    override val root = ScrollView(a)
    private val content = ui.col()

    private val padBox = ArrayList<LinearLayout>()
    private val padName = ArrayList<TextView>()
    private val padSub = ArrayList<TextView>()
    private val padKey = Array(PartBank.N) { "" }
    private lateinit var jumpBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var recInfo: TextView
    private lateinit var songBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var playSongBtn: Button
    private lateinit var info: TextView
    private lateinit var songInfo: TextView
    private lateinit var mixSongBtn: Button
    private var mixCheckMs = 0L
    private val listBox = ui.col()
    private val rowViews = ArrayList<LinearLayout>()
    private var lastKey = ""

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        // ---------------- 3.35: the Q pads ----------------
        for (rowNo in 0 until 2) {
            val r = ui.row()
            for (c in 0 until 3) {
                val i = rowNo * 3 + c
                val pad = ui.col()
                pad.gravity = Gravity.CENTER
                pad.isClickable = true
                pad.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                val name = ui.text(qName(i), 16f, Theme.TEXT, true)
                name.gravity = Gravity.CENTER
                val sub = ui.text("", 11f, Theme.DIM)
                sub.gravity = Gravity.CENTER
                pad.addView(name)
                pad.addView(sub)
                pad.setOnClickListener { tapQ(i) }
                pad.setOnLongClickListener {
                    padMenu(i)
                    true
                }
                padBox.add(pad)
                padName.add(name)
                padSub.add(sub)
                val lp = LinearLayout.LayoutParams(0, ui.dp(104), 1f)
                lp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                r.addView(pad, lp)
            }
            content.addView(r, ui.fullWidth(if (rowNo == 0) 0 else 2))
        }

        info = ui.text("", 13f, Theme.TEXT)
        content.addView(info, ui.fullWidth(6))
        content.addView(
            ui.text(
                "Tap a Q: it plays and loops. While it plays, tap another Q: it comes in at the end of the bar. " +
                    "Tap the Q that plays: stop. Long press a Q: copy, clear, its recording.\n" +
                    "Q1 to Q6 are the slots of TAKE ALL on Home (SEND → fills one). A screen that was not sent is not lost " +
                    "when a Q starts: it is kept as \"Last screen\" in TAKE ALL. An empty Q starts as a copy of what was playing.\n" +
                    "Whatever you edit on the other pages goes into the Q that is on screen. " +
                    "Live recording: press ● REC PART at the top while the music plays, press it again and it stops at " +
                    "the end of the bar, then choose the part to send it to. " +
                    "The 6 cue buttons at the top are cue 1 to 6: long press one to choose which part it plays.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val r1 = ui.row()
        jumpBtn = ui.button("") {
            bank.jumpNow = !bank.jumpNow
            lastKey = ""
        }
        r1.addView(jumpBtn, ui.w(1f, 3))
        r1.addView(ui.button("Copy this part to...") { copyDialog(bank.cur) }, ui.w(1f, 3))
        r1.addView(ui.button("Clear this part") { clearDialog(bank.cur) }, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(4))

        content.addView(ui.text("Recording of this part", 14f, Theme.ACCENT, true), ui.fullWidth(10))
        recInfo = ui.text("", 13f, Theme.TEXT)
        content.addView(recInfo, ui.fullWidth(2))
        val r2 = ui.row()
        modeBtn = ui.button("") { cycleMode(bank.cur) }
        r2.addView(modeBtn, ui.w(1.4f, 3))
        r2.addView(ui.button("Save to device") { exportClip(bank.cur) }, ui.w(1f, 3))
        r2.addView(ui.button("Delete recording") { deleteClip(bank.cur) }, ui.w(1f, 3))
        content.addView(r2, ui.fullWidth(4))

        // ---------------- song builder ----------------
        content.addView(ui.title("Song builder"), ui.fullWidth(16))
        content.addView(
            ui.text(
                "Add parts in the order you want, and set how many bars each one plays (1 bar = 16 steps). " +
                    "Then press Play song. The song plays from the top and stops at the end, or starts again if Loop is on.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        val t = ui.row()
        playSongBtn = ui.button("▶ Play song") { a.playSong() }
        t.addView(playSongBtn, ui.w(1.3f, 3))
        songBtn = ui.button("") {
            bank.songOn = !bank.songOn
            if (bank.songOn && bank.songLen == 0) ui.toast("The song is empty. Add some parts below first.")
            lastKey = ""
        }
        t.addView(songBtn, ui.w(1f, 3))
        loopBtn = ui.button("") {
            bank.songLoop = !bank.songLoop
            lastKey = ""
        }
        t.addView(loopBtn, ui.w(1f, 3))
        content.addView(t, ui.fullWidth(4))

        songInfo = ui.text("", 13f, Theme.TEXT)
        content.addView(songInfo, ui.fullWidth(6))
        // 3.36: the whole song to 16 Track's INBOX as stems (shown only when 16 Track is on this device: onShow)
        mixSongBtn = ui.button("Whole song → MIX") { MixSend.askSong(a) }
        mixSongBtn.visibility = android.view.View.GONE
        content.addView(mixSongBtn, ui.fullWidth(4))

        content.addView(ui.text("Add a part to the end of the song:", 12f, Theme.DIM), ui.fullWidth(8))
        for (rowNo in 0 until 2) {
            val r = ui.row()
            for (c in 0 until 3) {
                val i = rowNo * 3 + c
                r.addView(ui.button("+ " + PartNames.NAMES[i]) {
                    if (bank.songLen >= PartBank.MAX_SONG) {
                        ui.toast("The song is full (${PartBank.MAX_SONG} parts)")
                    } else {
                        bank.songAdd(i, defaultBars(i))
                        rebuildList()
                    }
                }, ui.w(1f, 3))
            }
            content.addView(r, ui.fullWidth(2))
        }
        val r3 = ui.row()
        r3.addView(ui.button("Song from cues 1-6") { songFromCues() }, ui.w(1f, 3))
        r3.addView(ui.button("Quick song (ready-made order)") { quickSong() }, ui.w(1f, 3))
        r3.addView(ui.button("Clear the song") { clearSong() }, ui.w(1f, 3))
        content.addView(r3, ui.fullWidth(4))

        content.addView(listBox, ui.fullWidth(8))
        rebuildList()
    }

    private fun defaultBars(i: Int): Int {
        val p = bank.parts[i]
        if (p.hasClip()) return p.clipBars.coerceIn(1, PartBank.MAX_BARS)
        return when (i) {
            1, 3 -> 8
            else -> 4
        }
    }

    // ---------------- 3.35: the Q pads ----------------

    private fun qName(i: Int): String = "Q${i + 1} ${PartNames.NAMES[i]}"

    /** Q i is what sounds now: the transport runs (not PREPARE) and the screen is this part. */
    private fun isPlayingQ(i: Int): Boolean = e.playing && e.prepare < 0 && i == bank.cur && !bank.loose

    /**
     * A tap on a pad. Stopped: that Q plays. Playing: another Q comes in at the end of the bar (the cue), the Q
     * that plays stops the transport at once.
     * The screen is never lost: it is its own part (kept there by the switch), or a loose screen, which the switch
     * keeps as "Last screen" (PartBank.switchTo).
     */
    private fun tapQ(i: Int) {
        if (isPlayingQ(i)) {
            bank.cueReq = -1          // nothing comes in after the stop
            e.stop()
            lastKey = ""
            return
        }
        if (bank.songOn) {
            bank.songOn = false
            ui.toast("Song mode is off now, you are playing the Qs by hand")
        }
        if (bank.loose) ui.toast("The screen that was in no Q is kept as \"Last screen\" (TAKE ALL → RECALL)")
        if (e.playing && e.prepare < 0) {
            bank.cue(i)               // at the end of this bar (or the next step with "Jump: right away")
        } else {
            // stopped (or PREPARE, where the parts wait): the part goes on screen as the transport starts
            if (i != bank.cur || bank.loose) bank.cue(i)
            e.play()
        }
        lastKey = ""
    }

    /** Long press on a pad: the actions of that part. */
    private fun padMenu(i: Int) {
        val p = bank.parts[i]
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        names.add("Copy to...")
        acts.add { copyDialog(i) }
        names.add("Clear")
        acts.add { clearDialog(i) }
        if (p.hasClip()) {
            names.add("Plays: ${PartBank.MODE_NAMES[p.mode]} (tap to change)")
            acts.add { cycleMode(i) }
            names.add("Save the recording to the device")
            acts.add { exportClip(i) }
            names.add("Delete the recording")
            acts.add { deleteClip(i) }
        }
        // 3.36: this Q to 16 Track's INBOX as stems (only when 16 Track is on this device)
        if (MixSend.installed(a) && (p.used || (i == bank.cur && !bank.loose))) {
            names.add("→ MIX (stems to 16 Track)")
            acts.add { MixSend.askQ(a, i) }
        }
        AlertDialog.Builder(a)
            .setTitle(qName(i))
            .setItems(names.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun paintPads() {
        val cur = bank.cur
        val loose = bank.loose
        val running = e.playing && e.prepare < 0
        val wait = if (running) bank.cueReq else -1
        val blink = (System.currentTimeMillis() / 250L) % 2L == 0L
        val beat = onBeat()
        for (i in 0 until PartBank.N) {
            val p = bank.parts[i]
            val col = PARTCOL[i]
            val onScreen = i == cur && !loose
            val plays = running && onScreen
            // what it holds: the part on screen is counted live (its snapshot is only taken when it is left)
            val live = if (onScreen && !p.full) a.homeScreen.lanesInUse() else -1
            val holds = when {
                live > 0 -> "$live lane" + (if (live == 1) "" else "s")
                live == 0 -> ""
                !p.used -> ""
                p.full && p.lanes >= 0 -> "${p.lanes} lane" + (if (p.lanes == 1) "" else "s")
                else -> "${p.count()} steps"
            }
            val lit = holds.isNotEmpty() || p.hasClip()
            var sub = if (holds.isEmpty()) "empty" else holds
            if (p.hasClip()) sub += "\n● rec ${p.clipBars} bars"
            sub += when {
                plays -> "\n▶ playing"
                i == wait -> "\nnext"
                onScreen -> "\non screen"
                else -> ""
            }
            val state = when {
                plays -> if (beat) 5 else 4
                i == wait -> if (blink) 3 else 2
                onScreen -> 1
                else -> 0
            }
            val key = "$state|$lit|$sub"
            if (key == padKey[i]) continue
            padKey[i] = key
            padSub[i].text = sub
            val fg: Int
            when (state) {
                5 -> {
                    padBox[i].background = ui.bg(col, 14, Theme.TEXT, 3)
                    fg = Theme.onColor(col)
                }
                4 -> {
                    padBox[i].background = ui.bg(Theme.alpha(col, 120), 14, col, 3)
                    fg = Theme.TEXT
                }
                3 -> {
                    padBox[i].background = ui.bg(Theme.alpha(col, if (lit) 60 else 20), 14, Theme.TEXT, 4)
                    fg = Theme.TEXT
                }
                2 -> {
                    padBox[i].background = ui.bg(Theme.alpha(col, if (lit) 60 else 20), 14, Theme.alpha(col, 90), 1)
                    fg = Theme.TEXT
                }
                1 -> {
                    padBox[i].background = ui.bg(Theme.alpha(col, if (lit) 90 else 30), 14, col, 4)
                    fg = Theme.TEXT
                }
                else -> {
                    padBox[i].background = if (lit) ui.bg(Theme.alpha(col, 60), 14, col, 2)
                    else ui.bg(Theme.PANEL2, 14, Theme.alpha(col, 60), 1)
                    fg = if (lit) Theme.TEXT else Theme.FAINT
                }
            }
            padName[i].setTextColor(fg)
            padSub[i].setTextColor(if (state == 5) fg else if (lit || state != 0) Theme.DIM else Theme.FAINT)
        }
    }

    /** true in the first half of every beat of the bar that plays (the playing pad pulses with it) */
    private fun onBeat(): Boolean {
        val s = e.curStep
        if (s < 0) return true
        val i = s % Pat.slot()
        return Pat.accent(i) || (i > 0 && Pat.accent(i - 1))
    }

    // ---------------- recording of a part ----------------

    private fun cycleMode(c: Int) {
        val p = bank.parts[c]
        if (!p.hasClip()) {
            ui.toast("This part has no recording yet. Press ● REC PART at the top to record one.")
            return
        }
        p.mode = (p.mode + 1) % 3
        lastKey = ""
        ui.toast("${PartNames.NAMES[c]} plays: ${PartBank.MODE_NAMES[p.mode]} (from the next bar)")
    }

    private fun exportClip(c: Int) {
        val f = bank.clipFile(a.filesDir, c)
        if (!bank.parts[c].hasClip() || !f.exists()) {
            ui.toast("This part has no recording yet")
            return
        }
        a.saveFile(f)
    }

    private fun deleteClip(c: Int) {
        if (!bank.parts[c].hasClip()) {
            ui.toast("This part has no recording")
            return
        }
        AlertDialog.Builder(a)
            .setTitle("Delete the recording of ${PartNames.NAMES[c]}?")
            .setMessage("The patterns of this part stay.")
            .setPositiveButton("Delete") { _, _ ->
                bank.setClip(a.filesDir, c, null, 0)
                bank.version++
                lastKey = ""
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun songFromCues() {
        val doIt = {
            bank.songLen = 0
            for (k in 0 until PartBank.N) {
                val i = bank.slot[k].coerceIn(0, PartBank.N - 1)
                val p = bank.parts[i]
                if (p.used || p.hasClip()) bank.songAdd(i, defaultBars(i))
            }
            rebuildList()
            if (bank.songLen == 0) ui.toast("The cues have no parts with something in them yet")
        }
        if (bank.songLen == 0) {
            doIt()
        } else {
            AlertDialog.Builder(a)
                .setTitle("Replace your song with cue 1 to 6 in order?")
                .setPositiveButton("Replace") { _, _ -> doIt() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------------- part actions ----------------

    private fun copyDialog(from: Int) {
        val targets = (0 until PartBank.N).filter { it != from }
        val items = Array(targets.size) {
            val k = targets[it]
            PartNames.NAMES[k] + (if (bank.parts[k].used) "  (will be replaced)" else "  (empty)")
        }
        AlertDialog.Builder(a)
            .setTitle("Copy ${PartNames.NAMES[from]} to")
            .setItems(items) { _, which ->
                val k = targets[which]
                bank.keep()      // (3.34: a screen that belongs to no part does not go into this one)
                bank.parts[k].copyFrom(bank.parts[from])
                if (k == bank.cur && !bank.loose) {
                    // (3.35: copied into the part that is on screen, so the screen shows it)
                    bank.parts[k].apply(e)
                    bank.version++
                    a.preloadBank()
                }
                lastKey = ""
                ui.toast("${PartNames.NAMES[from]} copied to ${PartNames.NAMES[k]}")
            }
            .show()
    }

    private fun clearDialog(c: Int) {
        if (c != bank.cur || bank.loose) {
            // (3.35: a part that is not on screen: only what it holds is cleared, the screen stays)
            AlertDialog.Builder(a)
                .setTitle("Clear ${qName(c)}?")
                .setMessage("Everything this Q holds is cleared. Its recording stays. What is on screen does not change.")
                .setPositiveButton("Clear") { _, _ ->
                    bank.parts[c].blank()
                    bank.parts[c].used = false
                    bank.version++
                    lastKey = ""
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val gtr = android.widget.CheckBox(a)
        gtr.text = "The guitars too (they are shared by every part)"
        gtr.isChecked = true
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        box.addView(gtr)
        AlertDialog.Builder(a)
            .setTitle("Clear ${PartNames.NAMES[c]}?")
            .setMessage("All steps of this part are turned off (tracks, drums, bassline, synth). Samples and sounds stay.")
            .setView(box)
            .setPositiveButton("Clear") { _, _ ->
                if (gtr.isChecked) for (g in arrayOf(e.rhythmGtr, e.riffGtr)) {
                    g.art.fill(MetalGuitar.OFF)
                    g.fret.fill(0)
                    g.audition(MetalGuitar.OFF, 0)
                }
                bank.parts[c].blank()
                bank.parts[c].apply(e)
                bank.version++
                lastKey = ""
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- song list ----------------

    private fun quickSong() {
        val doIt = {
            bank.songLen = 0
            val order = intArrayOf(0, 1, 2, 3, 1, 2, 3, 4, 3, 5)
            val bars = intArrayOf(4, 8, 4, 8, 8, 4, 8, 4, 8, 4)
            for (k in order.indices) bank.songAdd(order[k], bars[k])
            rebuildList()
            ui.toast("Intro, Verse, Pre, Hook, Verse, Pre, Hook, Break, Hook, Outro")
        }
        if (bank.songLen == 0) {
            doIt()
        } else {
            AlertDialog.Builder(a)
                .setTitle("Replace your song with the ready-made order?")
                .setPositiveButton("Replace") { _, _ -> doIt() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun clearSong() {
        if (bank.songLen == 0) return
        AlertDialog.Builder(a)
            .setTitle("Clear the whole song list?")
            .setMessage("Only the order is cleared. The parts and their patterns stay.")
            .setPositiveButton("Clear") { _, _ ->
                bank.songLen = 0
                bank.songIdx = 0
                rebuildList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildList() {
        listBox.removeAllViews()
        rowViews.clear()
        if (bank.songLen == 0) {
            listBox.addView(ui.text("The song is empty.", 13f, Theme.DIM))
        }
        for (k in 0 until bank.songLen) {
            val r = ui.row()
            r.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4))
            val name = ui.text("", 14f, Theme.TEXT, true)
            name.gravity = Gravity.CENTER_VERTICAL
            r.addView(name, ui.w(1f, 3))
            r.addView(ui.button("−") { bars(k, -1) }, ui.w(0f, 2))
            r.addView(ui.button("+") { bars(k, 1) }, ui.w(0f, 2))
            r.addView(ui.button("▲") { move(k, -1) }, ui.w(0f, 2))
            r.addView(ui.button("▼") { move(k, 1) }, ui.w(0f, 2))
            r.addView(ui.button("Part") { changePart(k) }, ui.w(0f, 2))
            val del = ui.button("✕") {
                bank.songRemove(k)
                rebuildList()
            }
            ui.styleButton(del, false)
            del.setTextColor(Theme.RED)
            r.addView(del, ui.w(0f, 2))
            listBox.addView(r, ui.fullWidth(3))
            rowViews.add(r)
        }
        lastKey = ""
        paintList()
    }

    private fun bars(k: Int, d: Int) {
        if (k !in 0 until bank.songLen) return
        bank.songBars[k] = (bank.songBars[k] + d).coerceIn(1, PartBank.MAX_BARS)
        lastKey = ""
        paintList()
    }

    private fun move(k: Int, d: Int) {
        bank.songMove(k, d)
        rebuildList()
    }

    private fun changePart(k: Int) {
        AlertDialog.Builder(a)
            .setTitle("Part for place ${k + 1}")
            .setItems(PartNames.NAMES) { _, which ->
                if (k in 0 until bank.songLen) bank.songPart[k] = which
                lastKey = ""
                paintList()
            }
            .show()
    }

    private fun paintList() {
        val playingIdx = if (e.playing && bank.songOn) bank.songIdx else -1
        for (k in rowViews.indices) {
            if (k >= bank.songLen) break
            val r = rowViews[k]
            val p = bank.songPart[k].coerceIn(0, PartBank.N - 1)
            val isNow = k == playingIdx
            r.background = ui.bg(
                if (isNow) Theme.ACCENT else Theme.PANEL, 10,
                PARTCOL[p], 2
            )
            val tv = r.getChildAt(0) as TextView
            val n = bank.songBars[k]
            var s = "${k + 1}.  ${PartNames.NAMES[p]}  ·  $n bar" + (if (n > 1) "s" else "")
            if (isNow) s += "   ▶ bar ${bank.songBar + 1}/$n"
            tv.text = s
            tv.setTextColor(if (isNow) Color.BLACK else Theme.TEXT)
        }
    }

    // ---------------- refresh ----------------

    private fun paint() {
        val playing = e.playing
        val cur = bank.cur
        val wait = bank.cueReq
        paintPads()
        val cp = bank.parts[cur]
        if (cp.hasClip()) {
            val secs = cp.clipBars * Pat.barSeconds(e.bpmF)
            recInfo.text = String.format(
                Locale.US, "%s has a recording: %d bars (%.1f s at BPM %d). It plays: %s.",
                PartNames.NAMES[cur], cp.clipBars, secs, e.bpm, PartBank.MODE_NAMES[cp.mode]
            )
        } else {
            recInfo.text = "${PartNames.NAMES[cur]} has no recording. Press ● REC PART at the top, play, press it again, then send it here."
        }
        modeBtn.text = "Plays: " + (if (cp.hasClip()) PartBank.MODE_NAMES[cp.mode] else "patterns")
        ui.styleButton(modeBtn, cp.hasClip() && cp.mode != 0, Theme.GREEN)
        jumpBtn.text = if (bank.jumpNow) "Jump: right away" else "Jump: at the next bar"
        ui.styleButton(jumpBtn, bank.jumpNow, Theme.GREEN)
        songBtn.text = if (bank.songOn) "Song mode: ON" else "Song mode: OFF"
        ui.styleButton(songBtn, bank.songOn, Theme.GREEN)
        loopBtn.text = if (bank.songLoop) "Loop: ON" else "Loop: OFF"
        ui.styleButton(loopBtn, bank.songLoop, Theme.GREEN)
        val songPlaying = playing && bank.songOn
        playSongBtn.text = if (songPlaying) "■ Stop song" else "▶ Play song"
        ui.styleButton(playSongBtn, songPlaying)

        info.text = (if (bank.loose) "On screen: a screen that is in no Q (it is kept as Last screen when a Q starts)"
            else (if (playing && e.prepare < 0) "Playing: " else "On screen: ") + qName(cur)) +
            (if (wait >= 0) "  →  next: ${qName(wait)}" else "") +
            (if (bank.songOn) "   (song mode is on, tapping a Q switches it off)" else "")

        val total = bank.songTotalBars()
        val secs = (total * Pat.barSeconds(e.bpmF)).toInt()
        songInfo.text = if (bank.songLen == 0) {
            "No parts in the song yet."
        } else {
            String.format(
                Locale.US, "%d parts · %d bars · about %d:%02d at BPM %d",
                bank.songLen, total, secs / 60, secs % 60, e.bpm
            )
        }
        val canMix = bank.songLen > 0
        if (mixSongBtn.isEnabled != canMix) {
            mixSongBtn.isEnabled = canMix
            mixSongBtn.alpha = if (canMix) 1f else 0.35f
        }
        paintList()
    }

    override fun onShow() {
        padKey.fill("")
        checkMix()
        if (rowViews.size != bank.songLen) rebuildList()
        lastKey = ""
        paint()
    }

    /** 3.36: "Whole song → MIX" shows only while 16 Track is on this device */
    private fun checkMix() {
        mixCheckMs = android.os.SystemClock.uptimeMillis()
        val v = if (MixSend.installed(a)) android.view.View.VISIBLE else android.view.View.GONE
        if (mixSongBtn.visibility != v) mixSongBtn.visibility = v
    }

    override fun onTick() {
        // (16 Track may be installed or removed while this page stays open: looked at again every few seconds)
        if (android.os.SystemClock.uptimeMillis() - mixCheckMs > 4000L) checkMix()
        val key = "${e.playing}|${bank.cur}|${bank.cueReq}|${bank.songIdx}|${bank.songBar}|${bank.songOn}|" +
            "${bank.version}|${(System.currentTimeMillis() / 250L) % 2L}|${e.bpm}|${bank.loose}|${e.prepare}|${onBeat()}"
        if (key == lastKey) return
        lastKey = key
        paint()
    }

    companion object {
        val PARTCOL = intArrayOf(
            Theme.BLUE,
            Theme.GREEN,
            Theme.YELLOW,
            Theme.ACCENT,
            Theme.PURPLE,
            Theme.RED
        )
    }
}
