package com.example.fm1burger

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

class StepsScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val trackBtns = ArrayList<Button>()
    private lateinit var grid: StepGridView
    private lateinit var info: TextView

    init {
        content.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(24))
        root.addView(content)

        val selRow = ui.row()
        for (i in 0 until Cfg.TRACKS) {
            val b = ui.button("Track ${i + 1}") { a.selectTrack(i) }
            trackBtns.add(b)
            selRow.addView(b, ui.w(1f, 3))
        }
        content.addView(selRow)

        info = ui.text("", 12f, Theme.DIM)
        content.addView(info, ui.fullWidth(6))

        grid = StepGridView(a)
        grid.onEdit = { }
        content.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(330)).also {
                it.topMargin = ui.dp(6)
            }
        )
        content.addView(
            ui.text(
                "Top grid: tap a cell to set the pitch of a step (middle row = original pitch, " +
                    "each row = 1 semitone). Tap the same cell again to clear it. " +
                    "Bottom bars: drag to set the level (hit strength) of each step.",
                12f, Theme.DIM
            ), ui.fullWidth(6)
        )

        val r1 = ui.row()
        r1.addView(ui.button("Clear") { sel().clearSteps(); grid.invalidate() }, ui.w(1f, 3))
        r1.addView(ui.button("Every 4") { fill(4) }, ui.w(1f, 3))
        r1.addView(ui.button("Every 2") { fill(2) }, ui.w(1f, 3))
        r1.addView(ui.button("Random") { randomize() }, ui.w(1f, 3))
        content.addView(r1, ui.fullWidth(10))

        val r2 = ui.row()
        r2.addView(ui.button("◀ Shift") { shift(-1) }, ui.w(1f, 3))
        r2.addView(ui.button("Shift ▶") { shift(1) }, ui.w(1f, 3))
        r2.addView(ui.button("All pitch 0") { flatten() }, ui.w(1f, 3))
        r2.addView(ui.button("All level 100%") { fullLevel() }, ui.w(1f, 3))
        content.addView(r2, ui.fullWidth(2))
    }

    private fun sel(): Track = e.tracks[a.sel]

    private fun fill(every: Int) {
        val t = sel()
        for (i in 0 until Cfg.STEPS) {
            t.steps[i].on = i % every == 0
            t.steps[i].pitch = 0
            t.steps[i].level = 1f
        }
        grid.invalidate()
    }

    private fun randomize() {
        val t = sel()
        for (i in 0 until Cfg.STEPS) {
            val s = t.steps[i]
            s.on = Random.nextInt(100) < 35
            s.pitch = 0
            s.level = 0.5f + Random.nextFloat() * 0.5f
        }
        grid.invalidate()
    }

    private fun shift(dir: Int) {
        val t = sel()
        val on = BooleanArray(Cfg.STEPS) { t.steps[it].on }
        val pi = IntArray(Cfg.STEPS) { t.steps[it].pitch }
        val lv = FloatArray(Cfg.STEPS) { t.steps[it].level }
        for (i in 0 until Cfg.STEPS) {
            val j = (i + dir + Cfg.STEPS) % Cfg.STEPS
            t.steps[j].on = on[i]
            t.steps[j].pitch = pi[i]
            t.steps[j].level = lv[i]
        }
        grid.invalidate()
    }

    private fun flatten() {
        for (s in sel().steps) s.pitch = 0
        grid.invalidate()
    }

    private fun fullLevel() {
        for (s in sel().steps) s.level = 1f
        grid.invalidate()
    }

    override fun onShow() {
        val t = sel()
        grid.track = t
        for (i in 0 until Cfg.TRACKS) ui.styleButton(trackBtns[i], i == a.sel, Theme.TRACK_COLORS[i])
        info.text = if (t.loop) {
            "Track ${a.sel + 1} is in LOOP mode, so its steps are ignored. Switch it to STEP on the Tracks tab."
        } else {
            "Editing track ${a.sel + 1}" + (if (t.sampleName.isEmpty()) " (no sample loaded yet)" else ": ${t.sampleName}")
        }
        grid.invalidate()
    }

    override fun onTick() {
        val s = if (e.playing) e.curStep else -1
        if (grid.playStep != s) {
            grid.playStep = s
            grid.invalidate()
        }
    }
}
