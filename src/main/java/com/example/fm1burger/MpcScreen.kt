package com.example.fm1burger

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * MPC (3.16): 16 big pads, < > for the 2 banks (A and B, 32 sounds). A pad plays the moment it is touched.
 * Long press a pad to choose its sound from anywhere: the Maker Bank, this device's files, a sampler track,
 * the built-in kits. REC TAPS writes what you play into the drum sequencer while it plays.
 * The pads are the same 32 sounds as the drum sequencer's rows.
 */
class MpcScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val e = a.engine
    override val root = ScrollView(a)
    private val content = ui.col()
    private val pads = ArrayList<Button>()
    private val flash = LongArray(Cfg.PAD_BANK)
    private var bank = 0
    private lateinit var bankText: Button
    private lateinit var recBtn: Button
    private var recTaps = false
    private val main = Handler(Looper.getMainLooper())
    private val prep = PrepareBar(a, false) { Engine.PREP_PADS }

    // pad colours: one per row of four, like the coloured pads of a hardware sampler
    private val rowColors = intArrayOf(Theme.RED, Theme.ACCENT, Theme.YELLOW, Theme.TEAL)

    private fun pi(i: Int) = bank * Cfg.PAD_BANK + i

    init {
        content.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(88))
        root.addView(content)

        val top = ui.row()
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(ui.title("MPC"), ui.w(0f, 0))
        val prev = ui.button("  <  ") { setBank(bank - 1) }
        bankText = ui.button("Bank A") { setBank(bank + 1) }
        val next = ui.button("  >  ") { setBank(bank + 1) }
        bankText.textSize = 16f
        bankText.typeface = Ui.BOLD
        top.addView(android.view.View(a), ui.w(0.3f, 0))
        top.addView(prev, ui.w(0f, 3))
        top.addView(bankText, ui.w(1f, 3))
        top.addView(next, ui.w(0f, 3))
        recBtn = ui.button("● REC taps") { toggleRec() }
        top.addView(recBtn, ui.w(0.8f, 3))
        top.addView(ui.button("Drum steps →") { a.showTab(MainActivity.TAB_PADS) }, ui.w(0.8f, 3))
        content.addView(top)
        content.addView(prep.view, ui.fullWidth(4))
        content.addView(
            ui.text("Tap a pad to play it · long press to choose its sound from anywhere · " +
                "REC taps: what you play while the sequencer runs goes into the drum steps.", 12f, Theme.DIM),
            ui.fullWidth(4)
        )

        // 4 x 4, pad 1 at the bottom left as on hardware samplers
        for (rowIdx in 3 downTo 0) {
            val row = ui.row()
            for (c in 0 until 4) {
                val i = rowIdx * 4 + c
                val b = makePad(i)
                pads.add(b)
                val lp = LinearLayout.LayoutParams(0, ui.dp(116), 1f)
                lp.setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
                row.addView(b, lp)
            }
            content.addView(row, ui.fullWidth(0))
        }
        // (pads[] is filled top row first; keep a lookup from pad number to button)
        val ordered = arrayOfNulls<Button>(Cfg.PAD_BANK)
        var k = 0
        for (rowIdx in 3 downTo 0) for (c in 0 until 4) { ordered[rowIdx * 4 + c] = pads[k]; k++ }
        pads.clear()
        for (b in ordered) pads.add(b!!)

        val bottom = ui.row()
        bottom.addView(ui.button("Rock Heavy kit → bank") { kit(true) }, ui.w(1f, 3))
        bottom.addView(ui.button("Electronic kit → bank") { kit(false) }, ui.w(1f, 3))
        bottom.addView(ui.button("Maker Bank →") { a.showTab(MainActivity.TAB_LIBRARY) }, ui.w(1f, 3))
        content.addView(bottom, ui.fullWidth(10))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makePad(i: Int): Button {
        val b = Button(a)
        b.isAllCaps = false
        b.textSize = 14f
        b.gravity = Gravity.CENTER
        b.maxLines = 3
        b.ellipsize = TextUtils.TruncateAt.END
        var longRun: Runnable? = null
        var longDone = false
        b.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longDone = false
                    hit(i)
                    val r = Runnable {
                        longDone = true
                        choose(i)
                    }
                    longRun = r
                    main.postDelayed(r, 650)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longRun?.let { main.removeCallbacks(it) }
                    longRun = null
                }
            }
            true
        }
        return b
    }

    private fun hit(i: Int) {
        val q = pi(i)
        e.padHit(q, 1f)
        flash[i] = System.currentTimeMillis()
        paint(i)
        if (recTaps && e.playing && !e.paused) {
            // to the nearest step: a tap a little before the step lands on it
            val s = e.curStep
            if (s in 0 until Cfg.STEPS) {
                val p = e.pads[q]
                p.on[s] = true
                p.lvl[s] = 1f
            }
        }
    }

    private fun choose(i: Int) {
        val q = pi(i)
        a.pickSound("Sound for pad ${Cfg.padName(q)}") { f ->
            a.assignPad(q, f)
            main.postDelayed({ onShow() }, 300)
        }
    }

    private fun toggleRec() {
        recTaps = !recTaps
        ui.styleButton(recBtn, recTaps, Theme.RED)
        if (recTaps && !e.playing) ui.toast("Press ▶ to play: the taps go into the drum steps while it plays")
    }

    private fun setBank(k: Int) {
        val n = Cfg.PADS / Cfg.PAD_BANK
        val nb = ((k % n) + n) % n
        if (nb > 0 && !Features.gate(a, Features.PAD_BANK_B)) return
        bank = nb
        onShow()
    }

    private fun kit(rock: Boolean) {
        ui.toast("Loading the kit...")
        Thread {
            val ok = try {
                if (rock) RockKit.install(a, a.store) else DrumKit.ensureAll(a.store)
                true
            } catch (ex: Exception) {
                false
            }
            a.runOnUiThread {
                if (!ok) {
                    ui.toast("Could not load the kit")
                    return@runOnUiThread
                }
                if (rock) {
                    for (j in RockKit.pads.indices) a.assignPad(pi(j), a.store.file(RockKit.fileName(RockKit.pads[j])))
                    for (j in RockKit.extras.indices) a.assignPad(pi(RockKit.pads.size + j), a.store.file(RockKit.fileName(RockKit.extras[j])))
                } else {
                    for (j in DrumKit.padKit.indices) a.assignPad(pi(j), a.store.file(DrumKit.fileName(DrumKit.padKit[j])))
                }
                main.postDelayed({ onShow() }, 400)
                ui.toast("Kit on bank ${'A' + bank}")
            }
        }.start()
    }

    private fun paint(i: Int) {
        val q = pi(i)
        val p = e.pads[q]
        val col = rowColors[i / 4]
        val lit = System.currentTimeMillis() - flash[i] < 140
        val empty = p.name.isEmpty()
        b(i).background = when {
            lit -> ui.bg(col, 16, Theme.TEXT, 2)
            empty -> ui.bg(Theme.PANEL, 16, Theme.LINE, 1)
            else -> ui.bg(Theme.alpha(col, 60), 16, col, 2)
        }
        b(i).setTextColor(if (lit) Theme.onColor(col) else if (empty) Theme.FAINT else Theme.TEXT)
    }

    private fun b(i: Int) = pads[i]

    override fun onShow() {
        bankText.text = "Bank ${'A' + bank}"
        for (i in 0 until Cfg.PAD_BANK) {
            val q = pi(i)
            val n = e.pads[q].name.removeSuffix(".wav").removePrefix("Drum_").replace('_', ' ')
            b(i).text = "${i + 1}\n" + (if (n.isEmpty()) "(hold to choose)" else n)
            paint(i)
        }
        ui.styleButton(recBtn, recTaps, Theme.RED)
    }

    override fun onTick() {
        prep.refresh()
        val now = System.currentTimeMillis()
        for (i in 0 until Cfg.PAD_BANK) if (flash[i] != 0L && now - flash[i] < 400) {
            paint(i)
            if (now - flash[i] >= 140) flash[i] = 0L
        }
    }
}
