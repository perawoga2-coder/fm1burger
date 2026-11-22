package com.example.fm1burger

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A page's ScrollView that a [StepDock] can hold still: while a dock has EDIT locked the page does not scroll. */
class LockableScrollView(ctx: Context) : ScrollView(ctx) {
    /** the dock that has EDIT locked on this page (null: the page scrolls) */
    var locker: StepDock? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        if (locker != null) false else super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        if (locker != null) true else super.onTouchEvent(ev)

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean =
        if (locker != null) false else super.onGenericMotionEvent(ev)
}

/**
 * 3.37: the dock above a step grid: 🔒 EDIT, a big ▶ PLAY / ■ STOP and P (PREPARE: only this page's instrument).
 *
 * EDIT unlocked (the start; its light blinks): the page scrolls and the grid only shows: a touch on it does nothing
 * and is left to the page, so it scrolls.
 * EDIT locked (the light stays lit): the grid pops up: the page moves so the dock is at its top, the grid grows to
 * the height that is free under it and the page is held still (LockableScrollView); the grid takes taps and drags
 * as it always did. A grid much taller than the screen keeps its size and gets ▲ ▼ to page through it.
 *
 * The dock takes the [grid] into its own [view]: the page adds [view] where the grid was.
 * [gridH] = the grid's own height in px (or WRAP_CONTENT); [prep] = the page's PrepareBar (P and Play are its).
 */
class StepDock(
    private val a: MainActivity, private val prep: PrepareBar?, private val grid: View,
    private val gridH: Int = ViewGroup.LayoutParams.WRAP_CONTENT, showP: Boolean = true, private val bottomDp: Int = 72
) {
    private val ui = a.ui
    private val e = a.engine
    val view: LinearLayout = ui.col()
    private val bar = ui.row()
    private val holder = Holder(a)
    private val editBtn: Button
    private val playBtn: Button
    private val pBtn: Button
    private val upBtn: Button
    private val downBtn: Button
    var locked = false
        private set
    private var lockedSv: LockableScrollView? = null
    private var natural = 0
    private var over = 0
    private var lastKey = ""
    private val pass = ArrayList<View>()
    private val saved = HashMap<View, FloatArray>()      // a stretched row / cell: its own height and weight
    private val loc = IntArray(2)
    private val rect = Rect()

    /** The grid's frame: view-only while EDIT is unlocked. */
    private inner class Holder(ctx: Context) : FrameLayout(ctx) {
        private var through = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!locked) {
                // not a step (a row's name, mute, volume): works as ever; a step: nothing, and the page scrolls
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) through = hitsPass(ev)
                return if (through) super.dispatchTouchEvent(ev) else false
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(ev)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            view.postDelayed(this, 70)
        }
    }

    init {
        editBtn = ui.button("🔒 EDIT") { if (locked) unlock() else lock() }
        editBtn.textSize = 15f
        editBtn.typeface = Ui.BOLD
        playBtn = ui.button("▶ PLAY") {
            if (e.playing && !e.paused) e.stop()
            else if (prep != null) prep.play()
            else e.play()
        }
        playBtn.textSize = 20f
        playBtn.typeface = Ui.BOLD
        pBtn = ui.button("P") { prep?.toggle() }
        pBtn.textSize = 17f
        pBtn.typeface = Ui.BOLD
        upBtn = ui.button("▲") { page(-1) }
        downBtn = ui.button("▼") { page(1) }
        val hgt = ui.dp(56)
        val m = ui.dp(3)
        bar.addView(editBtn, LinearLayout.LayoutParams(0, hgt, 1.2f).also { it.setMargins(m, m, m, m) })
        bar.addView(playBtn, LinearLayout.LayoutParams(0, hgt, 2f).also { it.setMargins(m, m, m, m) })
        bar.addView(pBtn, LinearLayout.LayoutParams(ui.dp(60), hgt).also { it.setMargins(m, m, m, m) })
        bar.addView(upBtn, LinearLayout.LayoutParams(ui.dp(52), hgt).also { it.setMargins(m, m, m, m) })
        bar.addView(downBtn, LinearLayout.LayoutParams(ui.dp(52), hgt).also { it.setMargins(m, m, m, m) })
        if (!showP || prep == null) pBtn.visibility = View.GONE
        upBtn.visibility = View.GONE
        downBtn.visibility = View.GONE
        ui.styleButton(editBtn, true, Theme.YELLOW)
        view.addView(bar)
        (grid.parent as? ViewGroup)?.removeView(grid)
        holder.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, gridH))
        view.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                view.removeCallbacks(tick)
                view.post(tick)
            }

            override fun onViewDetachedFromWindow(v: View) {
                view.removeCallbacks(tick)
                if (locked) unlock()
            }
        })
    }

    /** Parts of the grid that are not steps (a row's name, its mute, its volume): they work with EDIT unlocked too. */
    fun passThrough(vs: List<View>) {
        pass.addAll(vs)
    }

    private fun hitsPass(ev: MotionEvent): Boolean {
        val x = ev.rawX
        val y = ev.rawY
        for (v in pass) {
            if (!v.isShown) continue
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height) return true
        }
        return false
    }

    private fun scroll(): LockableScrollView? {
        var p = view.parent
        while (p != null) {
            if (p is LockableScrollView) return p
            p = p.parent
        }
        return null
    }

    private fun lock() {
        if (locked) return
        val sv = scroll()
        sv?.locker?.let { if (it !== this) it.unlock() }      // one grid up at a time
        natural = grid.height
        locked = true
        lockedSv = sv
        if (sv != null) {
            sv.locker = this
            sv.fling(0)                                        // a fling still running ends here
        }
        hold()
        view.post { if (locked) hold() }
        refresh()
    }

    private fun unlock() {
        if (!locked) return
        locked = false
        lockedSv?.let { if (it.locker === this) it.locker = null }
        lockedSv = null
        over = 0
        holder.scrollTo(0, 0)
        for ((v, s) in saved) {
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.height = s[0].toInt()
            lp.weight = s[1]
            v.layoutParams = lp
        }
        saved.clear()
        holder.layoutParams?.let { it.height = ViewGroup.LayoutParams.WRAP_CONTENT; holder.layoutParams = it }
        grid.layoutParams?.let { it.height = gridH; grid.layoutParams = it }
        upBtn.visibility = View.GONE
        downBtn.visibility = View.GONE
        refresh()
    }

    /** Locked: the dock at the top of the page, the grid as high as the room under it. Called again and again: it only changes what is off. */
    private fun hold() {
        val sv = lockedSv
        var h = natural
        var avail = natural
        if (sv != null && sv.height > 0) {
            avail = max(sv.height - sv.paddingTop - sv.paddingBottom - bar.height - ui.dp(bottomDp), ui.dp(120))
            // the whole grid in the room there is; a grid far taller than it keeps its size (▲ ▼ page through it)
            h = if (avail >= natural * 0.7f) avail else natural
        }
        if (h <= 0) return
        val frame = min(h, avail)
        over = h - frame
        val hl = holder.layoutParams
        if (hl != null && hl.height != frame) {
            hl.height = frame
            holder.layoutParams = hl
        }
        val gl = grid.layoutParams
        if (gl != null && gl.height != h) {
            gl.height = h
            grid.layoutParams = gl
        }
        stretch()
        val vis = if (over > 0) View.VISIBLE else View.GONE
        if (upBtn.visibility != vis) {
            upBtn.visibility = vis
            downBtn.visibility = vis
        }
        if (holder.scrollY > over) holder.scrollTo(0, over)
        if (sv != null) {
            view.getDrawingRect(rect)
            sv.offsetDescendantRectToMyCoords(view, rect)
            val y = max(0, rect.top - ui.dp(2))
            if (sv.scrollY != y) sv.scrollTo(0, y)
        }
    }

    /** A grid made of rows of cells (not one drawn view): its rows share the height, the cells fill their row. */
    private fun stretch() {
        val g = grid as? LinearLayout ?: return
        if (g.orientation != LinearLayout.VERTICAL) return
        for (i in 0 until g.childCount) {
            val row = g.getChildAt(i)
            val lp = row.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.height != 0 || lp.weight != 1f) {
                saved[row] = floatArrayOf(lp.height.toFloat(), lp.weight)
                lp.height = 0
                lp.weight = 1f
                row.layoutParams = lp
            }
            if (row !is LinearLayout || row.orientation != LinearLayout.HORIZONTAL) continue
            for (k in 0 until row.childCount) {
                val c = row.getChildAt(k)
                val cl = c.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (cl.height != LinearLayout.LayoutParams.MATCH_PARENT) {
                    saved[c] = floatArrayOf(cl.height.toFloat(), cl.weight)
                    cl.height = LinearLayout.LayoutParams.MATCH_PARENT
                    c.layoutParams = cl
                }
            }
        }
    }

    private fun page(dir: Int) {
        val step = (holder.height * 0.8f).toInt()
        holder.scrollTo(0, (holder.scrollY + dir * step).coerceIn(0, max(0, over)))
    }

    /** Runs by itself while the dock is on screen: play / P / EDIT lights, the blink, and the hold while locked. */
    private fun refresh() {
        if (!view.isShown) {
            if (locked) unlock()                               // the page was left: unlocked when it is shown again
            return
        }
        val playing = e.playing && !e.paused
        val pOn = prep?.on == true
        val key = "$playing|$pOn|$locked"
        if (key != lastKey) {
            lastKey = key
            playBtn.text = if (playing) "■ STOP" else "▶ PLAY"
            ui.styleButton(playBtn, true, if (playing) Theme.RED else Theme.GREEN)
            ui.styleButton(pBtn, pOn, Theme.YELLOW)
        }
        if (locked) {
            editBtn.alpha = 1f
            hold()
        } else {
            val t = (SystemClock.uptimeMillis() % 900L) / 900f
            editBtn.alpha = 0.3f + 0.7f * abs(2f * t - 1f)
        }
    }
}
