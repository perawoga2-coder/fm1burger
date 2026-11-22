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
 *  - 6 cue parts like a DJ: Intro, Verse, Pre-Chorus, Hook, Break, Outro. Tap one to jump to it.
 *    The jump happens at the start of the next bar, so it always lands in time.
 *  - Song builder: put parts in order with a number of bars each, then play the whole song.
 */
class SongScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    private val bank = e.bank
    override val root = ScrollView(a)
    private val content = ui.col()

    private val cueBtns = ArrayList<Button>()
    private lateinit var jumpBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var recInfo: TextView
    private lateinit var songBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var playSongBtn: Button
    private lateinit var info: TextView
    private lateinit var songInfo: TextView
    private val listBox = ui.col()
    private val rowViews = ArrayList<LinearLayout>()
    private var lastKey = ""

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("Song parts (cues)"))
        content.addView(
            ui.text(
                "Each part keeps its own patterns: track steps, drums, bassline, synth steps and the mute buttons. " +
                    "Samples, sounds and effects are the same in every part.\n" +
                    "Tap a part to jump to it, like the cue buttons of a DJ. While playing, the jump waits for the next bar. " +
                    "Whatever you edit on the other pages goes into the part that is playing now. " +
                    "An empty part starts as a copy of the part you came from, so you can change it from there.\n" +
                    "Live recording: press ● REC PART at the top while the music plays. It starts at bar 1, " +
                    "press it again and it stops at the end of the bar. Then choose the part to send it to (Intro, Verse...). " +
                    "The 6 cue buttons at the top are cue 1 to 6: long press one to choose which part it plays.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        for (rowNo in 0 until 2) {
            val r = ui.row()
            for (c in 0 until 3) {
                val i = rowNo * 3 + c
                val b = Button(a)
                b.isAllCaps = false
                b.textSize = 13f
                b.setOnClickListener { a.cuePart(i) }
                cueBtns.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(76), 1f)
                lp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                r.addView(b, lp)
            }
            content.addView(r, ui.fullWidth(4))
        }

        info = ui.text("", 13f, Theme.TEXT)
        content.addView(info, ui.fullWidth(6))

        val r1 = ui.row()
        jumpBtn = ui.button("") {
            bank.jumpNow = !bank.jumpNow
            lastKey = ""
        }
        r1.addView(jumpBtn, ui.w(1f, 3))
        r1.addView(ui.button("Copy this part to...") { copyDialog() }, ui.w(1f, 3))
        r1.addView(ui.button("Clear this part") { clearDialog() }, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(4))

        content.addView(ui.text("Recording of this part", 14f, Theme.ACCENT, true), ui.fullWidth(10))
        recInfo = ui.text("", 13f, Theme.TEXT)
        content.addView(recInfo, ui.fullWidth(2))
        val r2 = ui.row()
        modeBtn = ui.button("") { cycleMode() }
        r2.addView(modeBtn, ui.w(1.4f, 3))
        r2.addView(ui.button("Save to device") { exportClip() }, ui.w(1f, 3))
        r2.addView(ui.button("Delete recording") { deleteClip() }, ui.w(1f, 3))
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

    // ---------------- recording of the current part ----------------

    private fun cycleMode() {
        val p = bank.parts[bank.cur]
        if (!p.hasClip()) {
            ui.toast("This part has no recording yet. Press ● REC PART at the top to record one.")
            return
        }
        p.mode = (p.mode + 1) % 3
        lastKey = ""
        ui.toast("${PartNames.NAMES[bank.cur]} plays: ${PartBank.MODE_NAMES[p.mode]} (from the next bar)")
    }

    private fun exportClip() {
        val c = bank.cur
        val f = bank.clipFile(a.filesDir, c)
        if (!bank.parts[c].hasClip() || !f.exists()) {
            ui.toast("This part has no recording yet")
            return
        }
        a.saveFile(f)
    }

    private fun deleteClip() {
        val c = bank.cur
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

    private fun copyDialog() {
        val from = bank.cur
        val targets = (0 until PartBank.N).filter { it != from }
        val items = Array(targets.size) {
            val k = targets[it]
            PartNames.NAMES[k] + (if (bank.parts[k].used) "  (will be replaced)" else "  (empty)")
        }
        AlertDialog.Builder(a)
            .setTitle("Copy ${PartNames.NAMES[from]} to")
            .setItems(items) { _, which ->
                val k = targets[which]
                bank.parts[from].capture(e)
                bank.parts[k].copyFrom(bank.parts[from])
                lastKey = ""
                ui.toast("${PartNames.NAMES[from]} copied to ${PartNames.NAMES[k]}")
            }
            .show()
    }

    private fun clearDialog() {
        val c = bank.cur
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
        val blink = (System.currentTimeMillis() / 250L) % 2L == 0L
        for (i in 0 until PartBank.N) {
            val b = cueBtns[i]
            val p = bank.parts[i]
            val n = if (i == cur) -1 else p.count()
            val sub = when {
                i == cur -> if (playing) "▶ playing" else "selected"
                i == wait -> "waiting for the bar..."
                !p.used -> "empty"
                else -> "$n steps"
            }
            val rec = if (p.hasClip()) "\n● rec ${p.clipBars} bars" else ""
            b.text = PartNames.NAMES[i] + "\n" + sub + rec
            val col = when {
                i == cur -> PARTCOL[i]
                i == wait && blink -> Theme.TEXT
                else -> Theme.PANEL2
            }
            b.background = ui.bg(col, 12, PARTCOL[i], if (i == cur) 0 else 2)
            b.setTextColor(if (i == cur || (i == wait && blink)) Color.BLACK else if (p.used) Theme.TEXT else Theme.DIM)
        }
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

        info.text = "Now: ${PartNames.NAMES[cur]}" +
            (if (wait >= 0) "  →  next: ${PartNames.NAMES[wait]}" else "") +
            (if (bank.songOn) "   (song mode is on, tapping a part switches it off)" else "")

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
        paintList()
    }

    override fun onShow() {
        if (rowViews.size != bank.songLen) rebuildList()
        lastKey = ""
        paint()
    }

    override fun onTick() {
        val key = "${e.playing}|${bank.cur}|${bank.cueReq}|${bank.songIdx}|${bank.songBar}|${bank.songOn}|" +
            "${bank.version}|${(System.currentTimeMillis() / 250L) % 2L}|${e.bpm}"
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
