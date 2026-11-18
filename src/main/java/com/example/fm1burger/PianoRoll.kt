package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** What a piano roll edits: one note per step (the bassline and the analog synth sequencer are both like this). */
interface RollModel {
    fun isOn(s: Int): Boolean
    /** MIDI note of step s. */
    fun note(s: Int): Int
    /** Steps the note of step s holds (1 = one 16th). */
    fun length(s: Int): Int
    fun put(s: Int, n: Int)
    fun off(s: Int)
    val canLength: Boolean
    fun setLength(s: Int, l: Int)
    /** bit 0 = accent, bit 1 = slide (bassline only). */
    fun flags(s: Int): Int
    val hasFlags: Boolean
    fun cycleFlags(s: Int)
    val lowest: Int
    val highest: Int
    fun audition(n: Int)
    fun changed()
}

/**
 * A piano roll of one bar (the bar the bar buttons show): pitch rows against 16 steps.
 * Tap an empty cell: a note (it replaces the note of that step). Tap a note: it goes away.
 * Drag a note sideways: its length (analog synth). Hold a note: accent / slide (bassline).
 */
class PianoRollView(ctx: Context, private val model: RollModel) : View(ctx) {

    companion object {
        const val ROWS = 25
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(n: Int): String = NAMES[((n % 12) + 12) % 12] + (n / 12 - 1)
    }

    /** MIDI note of the bottom row. */
    var low = 48
        set(v) {
            field = v.coerceIn(model.lowest - ROWS + 1, model.highest).coerceAtLeast(0)
            invalidate()
        }
    var playStep = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var selStep = -1
    var onSelect: ((Int) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = ctx.resources.displayMetrics.density
    private val keyW = 40f * density
    private val hnd = Handler(Looper.getMainLooper())

    private var downStep = -1
    private var downCol = 0
    private var tappedExisting = false
    private var moved = false
    private var longDone = false
    private val longPress = Runnable {
        if (downStep >= 0 && !moved && model.hasFlags && model.isOn(downStep)) {
            longDone = true
            model.cycleFlags(downStep)
            model.changed()
            invalidate()
        }
    }

    private fun isBlack(n: Int): Boolean = NAMES[((n % 12) + 12) % 12].contains('#')
    private fun rowH(): Float = height.toFloat() / ROWS
    private fun colW(): Float = (width - keyW) / Pat.BAR
    private fun rowOf(n: Int): Int = ROWS - 1 - (n - low)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rh = rowH()
        val cw = colW()
        p.style = Paint.Style.FILL
        p.color = Theme.BG
        c.drawRect(0f, 0f, w, h, p)
        p.textSize = 10f * density
        for (r in 0 until ROWS) {
            val n = low + ROWS - 1 - r
            val y = r * rh
            val inRange = n in model.lowest..model.highest
            // the key
            p.color = if (isBlack(n)) Color.parseColor("#2A2A33") else Color.parseColor("#D8D8DC")
            if (!inRange) p.color = Theme.PANEL
            c.drawRect(0f, y + 0.5f, keyW - 2f, y + rh - 0.5f, p)
            if (n % 12 == 0) {
                p.color = Color.BLACK
                c.drawText(noteName(n), 4f * density, y + rh * 0.75f, p)
            }
            // the lane
            for (k in 0 until Pat.BAR) {
                p.color = when {
                    !inRange -> Theme.BG
                    isBlack(n) -> if ((k / 4) % 2 == 0) Theme.PANEL else Color.parseColor("#17171D")
                    else -> if ((k / 4) % 2 == 0) Theme.PANEL2 else Theme.PANEL
                }
                c.drawRect(keyW + k * cw + 0.75f, y + 0.75f, keyW + (k + 1) * cw - 0.75f, y + rh - 0.75f, p)
            }
            if (n % 12 == 0) {
                p.color = Theme.PANEL3
                c.drawRect(keyW, y + rh - 1.5f, w, y + rh, p)
            }
        }
        val base = Pat.base()
        // notes
        for (k in 0 until Pat.BAR) {
            val s = base + k
            if (!model.isOn(s)) continue
            val n = model.note(s)
            val len = if (model.canLength) model.length(s).coerceIn(1, Pat.BAR - k) else 1
            val f = model.flags(s)
            val col = when {
                f and 1 != 0 -> Theme.YELLOW
                else -> Theme.ACCENT
            }
            if (n < low || n >= low + ROWS) {
                // outside the view: a small mark at the top / bottom edge
                p.color = col
                val y = if (n < low) h - rh * 0.35f else 0f
                c.drawRect(keyW + k * cw + 2f, y, keyW + (k + 1) * cw - 2f, y + rh * 0.35f, p)
                continue
            }
            val y = rowOf(n) * rh
            p.color = col
            c.drawRoundRect(keyW + k * cw + 2f, y + 2f, keyW + (k + len) * cw - 2f, y + rh - 2f, 6f, 6f, p)
            if (s == selStep) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f
                p.color = Theme.TEXT
                c.drawRoundRect(keyW + k * cw + 2f, y + 2f, keyW + (k + len) * cw - 2f, y + rh - 2f, 6f, 6f, p)
                p.style = Paint.Style.FILL
            }
            if (f and 2 != 0) {
                // slide: a line into the next step
                p.color = Theme.TEAL
                c.drawRect(keyW + (k + 1) * cw - 6f, y + rh * 0.4f, keyW + (k + 1) * cw + 6f, y + rh * 0.6f, p)
            }
        }
        val ps = playStep - base
        if (ps in 0 until Pat.BAR) {
            p.color = Color.argb(55, 255, 255, 255)
            c.drawRect(keyW + ps * cw, 0f, keyW + (ps + 1) * cw, h, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cw = colW()
        val rh = rowH()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moved = false
                longDone = false
                val n = low + ROWS - 1 - (e.y / rh).toInt().coerceIn(0, ROWS - 1)
                if (e.x < keyW) {
                    if (n in model.lowest..model.highest) model.audition(n)
                    downStep = -1
                    return true
                }
                val col = ((e.x - keyW) / cw).toInt().coerceIn(0, Pat.BAR - 1)
                val s = Pat.base() + col
                downCol = col
                downStep = s
                if (n !in model.lowest..model.highest) {
                    downStep = -1
                    return true
                }
                tappedExisting = model.isOn(s) && model.note(s) == n
                if (!tappedExisting) {
                    model.put(s, n)
                    model.audition(n)
                    model.changed()
                }
                selStep = s
                onSelect?.invoke(s)
                hnd.postDelayed(longPress, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = downStep
                if (s < 0) return true
                val col = ((e.x - keyW) / cw).toInt().coerceIn(0, Pat.BAR - 1)
                if (col != downCol) {
                    moved = true
                    hnd.removeCallbacks(longPress)
                    if (model.canLength && model.isOn(s)) {
                        val l = (col - (s - Pat.base()) + 1).coerceIn(1, Pat.BAR)
                        if (l != model.length(s)) {
                            model.setLength(s, l)
                            model.changed()
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hnd.removeCallbacks(longPress)
                val s = downStep
                if (s >= 0 && e.actionMasked == MotionEvent.ACTION_UP && tappedExisting && !moved && !longDone) {
                    model.off(s)
                    model.changed()
                }
                downStep = -1
                invalidate()
            }
        }
        return true
    }
}

/** A piano roll with its octave buttons and a help line. */
class PianoRollPanel(a: MainActivity, title: String, model: RollModel, help: String, octaves: Boolean = true) {
    private val ui = a.ui
    val view: LinearLayout = ui.panel()
    val roll = PianoRollView(a, model)
    private val range: TextView

    init {
        view.addView(ui.text(title, 14f, Theme.ACCENT, true))
        view.addView(ui.text(help, 12f, Theme.DIM), ui.fullWidth(2))
        val r = ui.row()
        r.addView(ui.button("▼ Octave") { roll.low = roll.low - 12; label() }, ui.w(1f, 3))
        range = ui.text("", 13f, Theme.TEXT, true)
        range.gravity = android.view.Gravity.CENTER
        r.addView(range, ui.w(1f, 3))
        r.addView(ui.button("▲ Octave") { roll.low = roll.low + 12; label() }, ui.w(1f, 3))
        view.addView(r, ui.fullWidth(6))
        if (!octaves) r.visibility = View.GONE
        roll.low = model.lowest
        view.addView(roll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(PianoRollView.ROWS * 19)).also {
            it.topMargin = ui.dp(4)
        })
        label()
    }

    private fun label() {
        range.text = "${PianoRollView.noteName(roll.low)} – ${PianoRollView.noteName(roll.low + PianoRollView.ROWS - 1)}"
    }

    /** Show the notes of the pattern: move the view to the lowest note used. */
    fun fitTo(lowestUsed: Int) {
        roll.low = (lowestUsed / 12) * 12
        label()
    }

    fun refresh() {
        label()
        roll.invalidate()
    }
}
