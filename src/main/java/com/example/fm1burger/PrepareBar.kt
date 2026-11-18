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
        hint = ui.text("", 11f, Theme.YELLOW)
        hint.visibility = View.GONE
        view.addView(hint, ui.fullWidth(2))
        refresh()
    }

    private fun toggle() {
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

    fun refresh() {
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
