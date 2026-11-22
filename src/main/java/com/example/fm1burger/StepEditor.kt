package com.example.fm1burger

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

    // the piano keys: two octaves from the C of octave [oct] (C4 = the sample's own pitch, as the NOTE slider shows it)
    private var oct = 3
    private var stepInput = false
    private var rec = false
    private val piano = PianoKeysView(a)
    private val octText = ui.text("", 12f, Theme.DIM)
    private lateinit var stepInBtn: Button
    private lateinit var recBtn: Button

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

        // the piano keys: hear the lane at a note, STEP INPUT writes it in the picked step, REC in the step playing
        val pr = ui.row()
        pr.gravity = Gravity.CENTER_VERTICAL
        pr.addView(ui.button("OCT −") { if (oct > OCT_MIN) { oct--; paint() } }, ui.w(1f, 3))
        octText.gravity = Gravity.CENTER
        pr.addView(octText, ui.w(1f, 3))
        pr.addView(ui.button("OCT +") { if (oct < OCT_MAX) { oct++; paint() } }, ui.w(1f, 3))
        stepInBtn = ui.button("STEP INPUT") {
            stepInput = !stepInput
            if (stepInput) rec = false
            paint()
        }
        pr.addView(stepInBtn, ui.w(1.5f, 3))
        recBtn = ui.button("● REC") {
            rec = !rec
            if (rec) stepInput = false
            paint()
        }
        pr.addView(recBtn, ui.w(1f, 3))
        // 3.32: the same keys, full-screen (touch velocity, chords); STEP INPUT / REC go on writing from there
        pr.addView(ui.button("⤢") { bigKeys(sound) }, ui.w(0.6f, 3))
        box.addView(pr, ui.fullWidth(10))
        piano.limit = !drum
        piano.onKey = { key, vel -> pianoKey(key, vel) }
        box.addView(piano, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(104)).also { it.topMargin = ui.dp(4) })

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
            "without changing it. These are the same steps as the Steps / Drum sequencer pages. " +
            (if (drum) "The piano keys play the pad (low on a key = louder)" else "The piano keys play the sample at that note") +
            "; STEP INPUT writes it in the picked step and moves on, REC writes it in the step that is playing. " +
            "⤢: the keys full-screen (hit harder = louder).", 11f, Theme.FAINT), ui.fullWidth(8))

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

    /** a piano key (0..23 from the lower C) was touched: hear it, and write it (STEP INPUT: the picked step, REC: the step playing) */
    private fun pianoKey(key: Int, vel: Float) {
        val p = (oct - 4) * 12 + key
        if (!drum && (p < -24 || p > 24)) return
        val v = vel.coerceIn(0.3f, 1f)
        if (drum) e.padHit(k, v) else e.auditionNote(k, p)
        writeKey(p, v)
    }

    /** a key's note (STEP INPUT: in the picked step, which moves on; REC: in the step playing) */
    private fun writeKey(p: Int, v: Float) {
        if (stepInput) {
            if (picked >= Pat.spb()) picked = 0
            writeNote(Pat.base() + picked, p, v)
            picked = (picked + 1) % Pat.spb()
        } else if (rec && e.playing) {
            writeNote(e.curStep, p, v)
        }
        paint()
    }

    /** 3.32: the full-screen keyboard on this lane; its keys write in the steps as the keys here do */
    private fun bigKeys(sound: String) {
        BigKeyboard(a, object : KeyLane(sound) {
            override val isSample = true
            override fun sample(): FloatArray? = if (drum) e.pads[k].data else e.tracks[k].data
            override fun semis(): Int = if (drum) 0 else e.tracks[k].pitch
            override fun from(n: Int): Int = if (drum) 0 else (e.tracks[k].start * n).toInt()
            override fun to(n: Int): Int = if (drum) n else (e.tracks[k].end * n).toInt()
            override fun keyDown(note: Int, vel: Int) {
                val p = note - 60
                if (!drum && (p < -24 || p > 24)) return
                writeKey(p, (vel / 127f).coerceIn(0.05f, 1f))
            }
            override fun info(): String = when {
                stepInput -> "STEP INPUT: step ${picked + 1}"
                rec -> if (e.playing) "REC" else "REC (press PLAY)"
                else -> ""
            }
        }, oct).show { paint() }
    }

    private fun writeNote(s: Int, p: Int, v: Float) {
        if (s < 0 || s >= Cfg.STEPS) return
        setOn(s, true)
        if (drum) setLevel(s, v) else e.tracks[k].steps[s].pitch = p
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
        piano.octave = oct
        piano.low = (oct - 4) * 12
        octText.text = "C$oct – B${oct + 1}"
        ui.styleButton(stepInBtn, stepInput, Theme.YELLOW)
        ui.styleButton(recBtn, rec, Theme.RED)
        playBtn.text = if (e.playing) "■ STOP" else "▶ PLAY"
        ui.styleButton(playBtn, true, if (e.playing) Theme.RED else Theme.GREEN)
    }

    private companion object {
        // the lower C of the keys: C2 (pitch -24) .. C5 (pitch +12, so the keys reach +24)
        const val OCT_MIN = 2
        const val OCT_MAX = 5
    }
}

/**
 * Two octaves of piano keys (C to B, twice), drawn. A touch on a key calls [onKey] with the key (0..23 from the lower C)
 * and how low on the key it was touched (0.3 at the top .. 1.0 at the bottom). One finger.
 */
class PianoKeysView(ctx: Context) : View(ctx) {
    /** the octave written on the lower C (the upper C shows the next one) */
    var octave = 3
        set(v) {
            if (field != v) { field = v; invalidate() }
        }
    /** the pitch of the lower C; with [limit] the keys outside -24..+24 are dim and do nothing */
    var low = -12
        set(v) {
            if (field != v) { field = v; invalidate() }
        }
    var limit = false
    var onKey: ((Int, Float) -> Unit)? = null

    private var lit = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = resources.displayMetrics.density

    private fun usable(key: Int): Boolean = !limit || (low + key) in -24..24

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val hh = height.toFloat()
        if (w <= 0f || hh <= 0f) return
        val ww = w / WHITES
        val rad = 4f * d
        // the white keys
        for (i in 0 until WHITES) {
            val key = i / 7 * 12 + WHITE_SEMI[i % 7]
            r.set(i * ww + d, 0f, (i + 1) * ww - d, hh - d)
            p.style = Paint.Style.FILL
            p.color = if (key == lit) Theme.YELLOW else if (usable(key)) Theme.TEXT else Theme.FAINT
            c.drawRoundRect(r, rad, rad, p)
            if (i % 7 == 0) {
                p.color = Theme.BG
                p.textSize = 11f * d
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText("C${octave + i / 7}", (i + 0.5f) * ww, hh - 7f * d, p)
            }
        }
        // the black keys, over them
        val bw = ww * BLACK_W
        val bh = hh * BLACK_H
        for (i in 0 until WHITES) {
            if (!HAS_BLACK[i % 7]) continue
            val key = i / 7 * 12 + WHITE_SEMI[i % 7] + 1
            val cx = (i + 1) * ww
            r.set(cx - bw / 2f, 0f, cx + bw / 2f, bh)
            p.style = Paint.Style.FILL
            p.color = if (key == lit) Theme.YELLOW else if (usable(key)) Theme.BG else Theme.PANEL3
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d
            p.color = Theme.LINE
            c.drawRoundRect(r, rad, rad, p)
        }
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width.toFloat()
                val hh = height.toFloat()
                if (w <= 0f || hh <= 0f) return true
                val ww = w / WHITES
                val x = ev.x.coerceIn(0f, w - 1f)
                val y = ev.y.coerceIn(0f, hh)
                var key = -1
                var keyH = hh
                if (y < hh * BLACK_H) {
                    // a black key sits on the line after white key i
                    val i = ((x + ww / 2f) / ww).toInt() - 1
                    if (i in 0 until WHITES && HAS_BLACK[i % 7] && Math.abs(x - (i + 1) * ww) <= ww * BLACK_W / 2f) {
                        key = i / 7 * 12 + WHITE_SEMI[i % 7] + 1
                        keyH = hh * BLACK_H
                    }
                }
                if (key < 0) {
                    val i = (x / ww).toInt().coerceIn(0, WHITES - 1)
                    key = i / 7 * 12 + WHITE_SEMI[i % 7]
                }
                if (!usable(key)) return true
                lit = key
                invalidate()
                onKey?.invoke(key, (0.3f + 0.7f * (y / keyH)).coerceIn(0.3f, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (lit != -1) { lit = -1; invalidate() }
            }
        }
        return true
    }

    private companion object {
        const val WHITES = 14
        const val BLACK_W = 0.6f
        const val BLACK_H = 0.6f
        val WHITE_SEMI = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val HAS_BLACK = booleanArrayOf(true, true, false, true, true, true, false)
    }
}
