package com.example.fm1burger

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The P (PREPARE) switch of a step sequencer page.
 * P on: this page's Play plays only this page's part. Nothing else is triggered, no MIDI Start goes
 * out and nothing is recorded, so the part can be prepared before it goes into the master.
 * P off: Play works as before (everything plays).
 *
 * withTransport = true adds its own Play and Stop buttons (for pages that have none);
 * otherwise the page keeps its own buttons and calls play() from its Play button.
 */
class PrepareBar(private val a: MainActivity, withTransport: Boolean, private val part: () -> Int) {

    private val ui = a.ui
    private val e = a.engine
    val view: LinearLayout = ui.col()
    /** The P button alone, to put into a page's own transport row. */
    val pButton: Button
    private var playBtn: Button? = null
    private var stopBtn: Button? = null
    private val hint: TextView
    var on = false
        private set
    private var lastKey = ""

    // pattern length and the bar shown (one setting for every sequencer page)
    private val lenBtn: Button
    private val meterBtn: Button
    private val barBtns = ArrayList<Button>()
    private val followBtn: Button
    private var pageKey = ""
    /** Called when the bar shown or the pattern length changed: the page redraws its steps. */
    var onPage: (() -> Unit)? = null

    init {
        pButton = ui.button("P") { toggle() }
        pButton.textSize = 15f
        pButton.typeface = Ui.BOLD
        if (withTransport) {
            val r = ui.row()
            r.addView(pButton, ui.w(0f, 3))
            val pb = ui.button("▶ Play") { play() }
            val sb = ui.button("■ Stop") { e.stop() }
            playBtn = pb
            stopBtn = sb
            r.addView(pb, ui.w(1f, 3))
            r.addView(sb, ui.w(1f, 3))
            view.addView(r)
        }
        val pr = ui.row()
        lenBtn = ui.button("1 bar") { a.patternLengthDialog() }
        lenBtn.textSize = 12f
        pr.addView(lenBtn, ui.w(1.2f, 2))
        meterBtn = ui.button("4/4") { a.meterDialog() }
        meterBtn.textSize = 12f
        pr.addView(meterBtn, ui.w(0.9f, 2))
        for (k in 0 until Pat.MAX_BARS) {
            val b = ui.button("${k + 1}") {
                Pat.view = k
                Pat.follow = false
                refreshPage()
            }
            b.textSize = 12f
            barBtns.add(b)
            pr.addView(b, ui.w(0.45f, 2))
        }
        followBtn = ui.button("Follow") {
            Pat.follow = !Pat.follow
            refreshPage()
        }
        followBtn.textSize = 12f
        pr.addView(followBtn, ui.w(1.1f, 2))
        view.addView(pr, ui.fullWidth(4))
        hint = ui.text("", 11f, Theme.YELLOW)
        hint.visibility = View.GONE
        view.addView(hint, ui.fullWidth(2))
        pageKey = "-"
        refresh()
    }

    /** P on / off (also from the step dock's P: one state). */
    fun toggle() {
        on = !on
        // leaving PREPARE while this part is playing alone: stop it
        if (!on && e.playing && e.prepare == part()) e.stop()
        refresh()
        ui.toast(
            if (on) "PREPARE on: Play here plays only ${Engine.prepName(part())}"
            else "PREPARE off: Play plays everything again"
        )
    }

    /** The page's Play: alone in PREPARE, or the normal play / resume. */
    fun play() {
        if (on) {
            e.playPrepare(part())
        } else if (e.playing && e.paused) {
            e.pause()
        } else if (!e.playing || e.prepare >= 0) {
            e.play()
        }
    }

    /** Bar buttons, length and Follow. While playing with Follow on, the page shows the bar that plays. */
    fun refreshPage() {
        if (Pat.follow && e.playing && !e.paused && e.curStep >= 0) {
            val vb = e.curStep / Pat.slot()
            if (vb != Pat.view) Pat.view = vb
        }
        val bars = Pat.bars()
        val vb = Pat.viewBar()
        val key = "${Pat.len}|$vb|${Pat.follow}|${Pat.meter}"
        if (key == pageKey) return
        pageKey = key
        lenBtn.text = if (bars == 1) "1 bar" else "$bars bars"
        meterBtn.text = Pat.METERS[Pat.meter]
        ui.styleButton(meterBtn, Pat.meter != 0, Theme.TEAL)
        ui.styleButton(lenBtn, bars > 1, Theme.TEAL)
        for (k in barBtns.indices) {
            barBtns[k].visibility = if (k < bars) View.VISIBLE else View.INVISIBLE
            ui.styleButton(barBtns[k], k == vb && bars > 1, Theme.TEAL)
        }
        followBtn.visibility = if (bars > 1) View.VISIBLE else View.INVISIBLE
        ui.styleButton(followBtn, Pat.follow, Theme.TEAL)
        onPage?.invoke()
    }

    fun refresh() {
        refreshPage()
        val p = part()
        val alone = e.playing && e.prepare == p
        val key = "$on|$alone|${e.playing}|$p"
        if (key == lastKey) return
        lastKey = key
        ui.styleButton(pButton, on, Theme.YELLOW)
        playBtn?.let { ui.styleButton(it, e.playing && (alone || (!on && e.prepare < 0)), if (alone) Theme.YELLOW else Theme.ACCENT) }
        stopBtn?.let { ui.styleButton(it, false) }
        hint.visibility = if (on) View.VISIBLE else View.GONE
        hint.text = "PREPARE: ▶ plays only ${Engine.prepName(p)}. Nothing else plays, no MIDI start, not recorded."
    }
}
