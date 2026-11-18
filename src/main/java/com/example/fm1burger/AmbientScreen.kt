package com.example.fm1burger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * The New Ambient page: choose a creature, shape how it lives (density, pitch, tone, wander, space),
 * let it call by itself (FREE) or on the lit steps while the sequencer plays.
 */
class AmbientScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val amb = a.engine.ambient
    override val root = ScrollView(a)
    private val content = ui.col()
    private val kindBtns = ArrayList<Button>()
    private val stepBtns = ArrayList<Button>()
    private lateinit var about: TextView
    private lateinit var freeBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var keyText: TextView
    private lateinit var wave: AmbientWaveView
    private val sliders = ArrayList<Pair<SliderRow, () -> Int>>()
    private var playStep = -2
    private val prep = PrepareBar(a, true) { Engine.PREP_AMBIENT }
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)
        content.addView(ui.title("New Ambient"))
        content.addView(
            ui.text(
                "A living soundscape of our own creatures. Pick one, then let it call by itself (FREE) " +
                    "or on the lit steps while the sequencer plays.",
                12f, Theme.DIM
            ), ui.fullWidth(4)
        )

        // creatures, two rows
        val grid = ui.col()
        var row: LinearLayout? = null
        for (k in 0 until NewAmbient.N_KIND) {
            if (k % 4 == 0) {
                row = ui.row()
                grid.addView(row)
            }
            val b = ui.button(NewAmbient.NAMES[k]) {
                amb.kind = k
                refresh()
                amb.callReq = true
            }
            b.textSize = 12f
            kindBtns.add(b)
            row?.addView(b, ui.w(1f, 3))
        }
        // keep the last row's buttons the same width as the others
        if (NewAmbient.N_KIND % 4 != 0) {
            for (i in 0 until 4 - NewAmbient.N_KIND % 4) {
                row?.addView(View(a), ui.w(1f, 3))
            }
        }
        content.addView(grid, ui.fullWidth(10))
        about = ui.text("", 13f, Theme.TEXT)
        content.addView(about, ui.fullWidth(6))

        wave = AmbientWaveView(a, amb)
        content.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(84)).also {
            it.topMargin = ui.dp(10)
        })

        val tr = ui.row()
        freeBtn = ui.button("FREE: off") {
            amb.free = !amb.free
            refresh()
        }
        tr.addView(freeBtn, ui.w(1.2f, 3))
        tr.addView(ui.button("Call now ▶") { amb.callReq = true }, ui.w(1f, 3))
        muteBtn = ui.button("Mute") {
            amb.mute = !amb.mute
            refresh()
        }
        tr.addView(muteBtn, ui.w(0.8f, 3))
        content.addView(tr, ui.fullWidth(10))
        content.addView(prep.view, ui.fullWidth(6))
        prep.onPage = { if (stepBtns.size == Pat.BAR) paintSteps(true) }

        // how it lives
        val p = ui.panel()
        p.addView(ui.text("How it lives", 14f, Theme.ACCENT, true))
        addSlider(p, "Density", 100, { (amb.density * 100).toInt() }, { if (it < 10) "rare" else if (it > 85) "busy" else "$it%" }) {
            amb.density = it / 100f
        }
        addSlider(p, "Pitch", 24, { amb.pitch + 12 }, { val v = it - 12; if (v > 0) "+$v st" else "$v st" }) {
            amb.pitch = it - 12
        }
        addSlider(p, "Tone", 100, { (amb.tone * 100).toInt() }, { if (it < 34) "dark" else if (it > 66) "bright" else "$it%" }) {
            amb.tone = it / 100f
        }
        addSlider(p, "Wander", 100, { (amb.wander * 100).toInt() }, { if (it < 15) "calm" else if (it > 80) "wild" else "$it%" }) {
            amb.wander = it / 100f
        }
        addSlider(p, "Space", 100, { (amb.space * 100).toInt() }, { "$it%" }) {
            amb.space = it / 100f
        }
        addSlider(p, "Volume", 100, { (amb.volume * 100).toInt() }, { "$it%" }) {
            amb.volume = it / 100f
        }
        val kr = ui.row()
        kr.addView(ui.text("Key", 12f, Theme.DIM), ui.w(0f, 4))
        kr.addView(ui.button("◀") {
            amb.key = (amb.key + 11) % 12
            refresh()
        }, ui.w(0f, 3))
        keyText = ui.text("C", 16f, Theme.TEXT, true)
        keyText.gravity = android.view.Gravity.CENTER
        kr.addView(keyText, LinearLayout.LayoutParams(ui.dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        kr.addView(ui.button("▶") {
            amb.key = (amb.key + 1) % 12
            refresh()
        }, ui.w(0f, 3))
        kr.addView(ui.text("pentatonic: every note fits", 11f, Theme.FAINT), ui.w(1f, 6))
        p.addView(kr, ui.fullWidth(6))
        content.addView(p, ui.fullWidth(12))

        // steps
        val sp = ui.panel()
        sp.addView(ui.text("Steps (while the sequencer plays)", 14f, Theme.ACCENT, true))
        for (half in 0 until 2) {
            val r = ui.row()
            for (c in 0 until 8) {
                val s = half * 8 + c
                val b = ui.button("${s + 1}") {
                    val st = Pat.base() + s
                    amb.steps[st] = !amb.steps[st]
                    paintSteps(true)
                }
                b.textSize = 11f
                b.setPadding(0, 0, 0, 0)
                stepBtns.add(b)
                r.addView(b, LinearLayout.LayoutParams(0, ui.dp(40), 1f).also {
                    it.setMargins(ui.dp(2), ui.dp(3), if (c == 3) ui.dp(8) else ui.dp(2), ui.dp(3))
                })
            }
            sp.addView(r)
        }
        val sr = ui.row()
        sr.addView(ui.button("Clear") {
            for (i in 0 until Cfg.STEPS) amb.steps[i] = false
            paintSteps(true)
        }, ui.w(1f, 3))
        sr.addView(ui.button("Every bar") {
            for (i in 0 until Cfg.STEPS) amb.steps[i] = i % Pat.BAR == 0
            paintSteps(true)
        }, ui.w(1f, 3))
        sr.addView(ui.button("Every beat") {
            for (i in 0 until Cfg.STEPS) amb.steps[i] = i % 4 == 0
            paintSteps(true)
        }, ui.w(1f, 3))
        sp.addView(sr, ui.fullWidth(6))
        content.addView(sp, ui.fullWidth(12))
    }

    private fun addSlider(
        parent: LinearLayout, name: String, max: Int, get: () -> Int, fmt: (Int) -> String, set: (Int) -> Unit
    ) {
        val s = ui.slider(name, max, get(), fmt, set)
        sliders.add(Pair(s, get))
        parent.addView(s.view, ui.fullWidth(4))
    }

    private fun paintSteps(force: Boolean) {
        val e = a.engine
        val ps = if (e.playing) e.curStep else -1
        if (!force && ps == playStep) return
        playStep = ps
        val col = Inst.color(Inst.AMBIENT)
        val base = Pat.base()
        for (c in 0 until Pat.BAR) {
            val s = base + c
            val b = stepBtns[c]
            b.text = "${s + 1}"
            when {
                s == ps -> ui.styleButton(b, true, Theme.TEXT, 8)
                amb.steps[s] -> ui.styleButton(b, true, col, 8)
                else -> ui.styleButton(b, false, col, 8)
            }
        }
    }

    fun refresh() {
        val col = Inst.color(Inst.AMBIENT)
        for (k in kindBtns.indices) ui.styleButton(kindBtns[k], amb.kind == k, col)
        about.text = NewAmbient.ABOUT[amb.kind.coerceIn(0, NewAmbient.N_KIND - 1)]
        freeBtn.text = if (amb.free) "FREE: on" else "FREE: off"
        ui.styleButton(freeBtn, amb.free, Theme.GREEN)
        muteBtn.text = if (amb.mute) "Muted" else "Mute"
        ui.styleButton(muteBtn, amb.mute, Theme.RED)
        keyText.text = noteNames[amb.key.coerceIn(0, 11)]
        for ((s, get) in sliders) s.set(get())
        paintSteps(true)
    }

    override fun onShow() {
        refresh()
    }

    override fun onTick() {
        prep.refresh()
        wave.invalidate()
        paintSteps(false)
    }
}

/** The live picture of New Ambient: the last few seconds of its sound. */
class AmbientWaveView(ctx: Context, private val amb: NewAmbient) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        p.style = Paint.Style.FILL
        p.color = Theme.PANEL
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, 14 * d, 14 * d, p)
        val n = NewAmbient.WF
        val cw = w / n
        val mid = h / 2f
        val col = Inst.color(Inst.AMBIENT)
        val pos = amb.wfPos
        for (i in 0 until n) {
            val idx = (pos + i) % n
            val top = mid - (amb.wfMax[idx] * 1.6f).coerceIn(-1f, 1f) * mid * 0.9f
            val bot = mid - (amb.wfMin[idx] * 1.6f).coerceIn(-1f, 1f) * mid * 0.9f
            // older = fainter
            p.color = Theme.alpha(col, 60 + 195 * i / n)
            c.drawRect(i * cw, top, i * cw + cw + 0.5f, max(bot, top + 1f), p)
        }
    }
}
