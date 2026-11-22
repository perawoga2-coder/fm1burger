package com.example.fm1burger

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 3.27: a Home lane's steps, the FM-1 way. A tap on the lane's picture on Home opens it over the page: the 16 steps
 * of a bar as two rows of 8 big keys (as the FM-1's keys; 3.28: as many as the meter's bar), the bar (1..4), the step picked and what it plays:
 * NOTE (a Sampler lane: -24..+24 semitones from the sample) and LEVEL; PLAY / STOP, CLEAR BAR, COPY BAR, HOME.
 * A tap on a key turns the step on (and picks it), a tap on a lit key that is picked turns it off.
 * It edits the same steps as the Steps and Drum sequencer pages: they are one pattern.
 */
class StepEditor(private val a: MainActivity, private val lane: Int) {
    private val ui = a.ui
    private val e = a.engine
    private val drum = a.lanes.type[lane] == Inst.DRUM
    private val k = a.lanes.idx[lane]
    private val keys = ArrayList<Button>()
    private val keyBox = ui.col()
    private var keyMeter = -1
    private lateinit var meterBtn: Button
    private val barBtns = ArrayList<Button>()
    private var picked = 0                          // the step picked in the bar shown (0..15)
    private val stepText = ui.text("", 15f, Theme.TEXT, true)
    private lateinit var noteS: SliderRow
    private lateinit var levelS: SliderRow
    private lateinit var playBtn: Button
    private val h = Handler(Looper.getMainLooper())
    private var lastPlay = -2
    private val color = if (drum) Theme.RED else Theme.ACCENT

    private fun on(s: Int): Boolean = if (drum) e.pads[k].on[s] else e.tracks[k].steps[s].on
    private fun level(s: Int): Float = if (drum) e.pads[k].lvl[s] else e.tracks[k].steps[s].level
    private fun pitch(s: Int): Int = if (drum) 0 else e.tracks[k].steps[s].pitch

    private fun setOn(s: Int, v: Boolean) {
        if (drum) e.pads[k].on[s] = v else e.tracks[k].steps[s].on = v
    }

    private fun setLevel(s: Int, v: Float) {
        if (drum) e.pads[k].lvl[s] = v else e.tracks[k].steps[s].level = v
    }

    private fun noteName(p: Int): String {
        val n = 60 + p
        return arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[Math.floorMod(n, 12)] + (Math.floorDiv(n, 12) - 1) +
            "  (" + (if (p > 0) "+$p" else "$p") + ")"
    }

    fun show() {
        val box = ui.col()
        box.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        val sound = (if (drum) e.pads[k].name else e.tracks[k].sampleName).removeSuffix(".wav").removePrefix("Drum_").ifEmpty { "(no sound)" }
        box.addView(ui.text(sound, 13f, Theme.DIM))

        // the bar (and 3.28: the meter)
        val br = ui.row()
        meterBtn = ui.button(Pat.METERS[Pat.meter]) {
            val items = Array(Pat.METERS.size) { "${Pat.METERS[it]}   (${Pat.spbOf(it)} steps a bar)" }
            AlertDialog.Builder(a).setTitle("Meter").setItems(items) { _, w ->
                a.setMeter(w)
                if (picked >= Pat.spb()) picked = 0
                paint()
            }.show()
        }
        br.addView(meterBtn, ui.w(0.9f, 3))
        br.addView(ui.text("BAR", 12f, Theme.DIM), ui.w(0f, 6))
        for (b in 0 until 4) {
            val bb = ui.button("${b + 1}") {
                if (b >= Pat.bars()) ui.toast("The pattern is ${Pat.bars()} bar. Set its length on the Steps page.")
                else { Pat.follow = false; Pat.view = b; paint() }
            }
            barBtns.add(bb)
            br.addView(bb, ui.w(1f, 3))
        }
        box.addView(br, ui.fullWidth(4))

        // the keys (as many as the meter's bar has: 16 for 4/4, two rows of 8 as the FM-1's keys)
        box.addView(keyBox, ui.fullWidth(6))
        for (s in 0 until 24) {
            run {
                val b = Button(a)
                b.isAllCaps = false
                b.text = "${s + 1}"
                b.textSize = 16f
                b.typeface = Typeface.DEFAULT_BOLD
                b.gravity = Gravity.CENTER
                b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
                b.setPadding(0, 0, 0, 0)
                b.stateListAnimator = null
                b.setOnClickListener { tap(s) }
                b.setOnLongClickListener { picked = s; paint(); true }
                keys.add(b)
            }
        }

        // the step picked
        box.addView(stepText, ui.fullWidth(12))
        if (!drum) {
            noteS = ui.slider("NOTE", 48, 24, { noteName(it - 24) }) { v ->
                val s = Pat.base() + picked
                e.tracks[k].steps[s].pitch = v - 24
                if (!on(s)) setOn(s, true)
                paint()
            }
            box.addView(noteS.view, ui.fullWidth(2))
        }
        levelS = ui.slider("LEVEL", 100, 100, { "$it %" }) { v ->
            val s = Pat.base() + picked
            setLevel(s, (v / 100f).coerceAtLeast(0.05f))
            if (!on(s)) setOn(s, true)
            paint()
        }
        box.addView(levelS.view, ui.fullWidth(2))

        // the buttons
        val r1 = ui.row()
        playBtn = ui.button("▶ PLAY") { if (e.playing) e.stop() else e.play() }
        r1.addView(playBtn, ui.w(1.2f))
        r1.addView(ui.button("CLEAR BAR") {
            for (i in 0 until Pat.spb()) { val s = Pat.base() + i; setOn(s, false); setLevel(s, 1f); if (!drum) e.tracks[k].steps[s].pitch = 0 }
            paint()
        }, ui.w(1f))
        r1.addView(ui.button("COPY BAR →") {
            val from = Pat.base()
            val to = ((Pat.viewBar() + 1) % Pat.bars()) * Pat.slot()
            if (to == from) { ui.toast("One bar only: set a longer pattern on the Steps page."); return@button }
            for (i in 0 until Pat.spb()) {
                setOn(to + i, on(from + i)); setLevel(to + i, level(from + i))
                if (!drum) e.tracks[k].steps[to + i].pitch = pitch(from + i)
            }
            Pat.follow = false
            Pat.view = to / Pat.slot()
            paint()
            ui.toast("Bar ${from / Pat.slot() + 1} copied to bar ${to / Pat.slot() + 1}")
        }, ui.w(1f))
        box.addView(r1, ui.fullWidth(12))
        box.addView(ui.text("Tap a key: the step on (and picked). Tap the picked step again: off. Long press: pick it " +
            "without changing it. These are the same steps as the Steps / Drum sequencer pages.", 11f, Theme.FAINT), ui.fullWidth(8))

        val sv = ScrollView(a)
        sv.addView(box)
        val dlg = AlertDialog.Builder(a).setTitle("LANE ${lane + 1}  ·  ${a.lanes.label(lane)}  ·  STEPS")
            .setView(sv)
            .setPositiveButton("◀ HOME", null)
            .create()
        dlg.setOnDismissListener { h.removeCallbacksAndMessages(null); a.refreshCurrent() }
        dlg.show()
        paint()
        h.post(tick)
    }

    private fun tap(i: Int) {
        val s = Pat.base() + i
        if (on(s) && picked == i) setOn(s, false)
        else {
            if (!on(s)) setOn(s, true)
            picked = i
            if (drum) e.padHit(k, level(s)) else e.audition(k)
        }
        paint()
    }

    private val tick = object : Runnable {
        override fun run() {
            val cur = if (e.playing) e.curStep else -1
            if (cur != lastPlay) {
                lastPlay = cur
                if (Pat.follow && cur >= 0) Pat.view = cur / Pat.slot()
                paint()
            }
            h.postDelayed(this, 40)
        }
    }

    /** the keys in rows for the meter: 4/4 two rows of 8, 3/4 and 6/8 two of 6, 2/4 one of 8, 5/4 two of 10, 7/8 two of 7, 12/8 three of 8 */
    private fun layoutKeys() {
        if (keyMeter == Pat.meter) return
        keyMeter = Pat.meter
        val n = Pat.spb()
        val rows = if (n <= 8) 1 else if (n <= 20) 2 else 3
        val per = (n + rows - 1) / rows
        keyBox.removeAllViews()
        for (k in keys) (k.parent as? LinearLayout)?.removeView(k)
        for (r in 0 until rows) {
            val row = ui.row()
            for (c in 0 until per) {
                val i = r * per + c
                if (i >= n) break
                val gapL = if (i > 0 && Pat.accent(i) && c > 0) ui.dp(9) else ui.dp(3)
                row.addView(keys[i], LinearLayout.LayoutParams(0, ui.dp(58), 1f).also { it.marginStart = gapL; it.marginEnd = ui.dp(3) })
            }
            keyBox.addView(row, ui.fullWidth(if (r == 0) 4 else 6))
        }
    }

    private fun paint() {
        layoutKeys()
        val base = Pat.base()
        val cur = if (e.playing) e.curStep else -1
        if (picked >= Pat.spb()) picked = 0
        for (i in 0 until Pat.spb()) {
            val s = base + i
            val b = keys[i]
            val lit = on(s)
            val now = s == cur
            val bg = when {
                now -> Theme.TEXT
                lit -> Theme.alpha(color, (90 + 165 * level(s)).toInt().coerceIn(90, 255))
                else -> Theme.PANEL2
            }
            b.background = ui.bg(bg, 10, if (i == picked) Theme.YELLOW else if (Pat.accent(i)) Theme.STEEL else Theme.LINE,
                if (i == picked) 3 else if (Pat.accent(i)) 2 else 1)
            b.setTextColor(if (now) Theme.BG else if (lit) Theme.BG else Theme.DIM)
            b.text = if (lit && !drum && pitch(s) != 0) "${i + 1}\n${if (pitch(s) > 0) "+" else ""}${pitch(s)}" else "${i + 1}"
        }
        for (b in 0 until 4) {
            barBtns[b].visibility = if (b < Pat.bars()) android.view.View.VISIBLE else android.view.View.INVISIBLE
            ui.styleButton(barBtns[b], b == Pat.viewBar(), color)
        }
        meterBtn.text = Pat.METERS[Pat.meter]
        ui.styleButton(meterBtn, Pat.meter != 0, Theme.TEAL)
        val s = base + picked
        stepText.text = "STEP ${picked + 1}  of bar ${Pat.viewBar() + 1}" + if (on(s)) "" else "   (off)"
        if (!drum) noteS.set(pitch(s) + 24)
        levelS.set((level(s) * 100).toInt())
        playBtn.text = if (e.playing) "■ STOP" else "▶ PLAY"
        ui.styleButton(playBtn, true, if (e.playing) Theme.RED else Theme.GREEN)
    }
}
