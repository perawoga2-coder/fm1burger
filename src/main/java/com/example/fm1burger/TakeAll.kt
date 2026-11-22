package com.example.fm1burger

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 3.34 TAKE ALL: the panel of the queue slots Q1..Q6 (they are the 6 parts of the Song page, so the song plays them).
 *
 * SEND ->   everything that is in use on Home (every lane that is not muted: patterns, sounds, volumes) is copied
 *           into the slot. The screen and what plays stay exactly as they are.
 * <- RECALL the slot replaces everything on screen, to make or change a part from it. The part that plays does not
 *           change, and after it the screen belongs to no slot: nothing changes a slot until it is sent.
 */
class TakeAllPanel(private val a: MainActivity) {

    private val ui = a.ui
    private val e = a.engine
    private val bank = e.bank
    private val rows = ui.col()
    private var dlg: AlertDialog? = null

    private fun qName(i: Int): String = "Q${i + 1} ${PartNames.NAMES[i]}"

    fun show() {
        val box = ui.col()
        box.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
        box.addView(ui.title("TAKE ALL"))
        val hint = ui.text(
            "SEND →  copies every lane that is not muted (patterns, sounds, volumes) into a slot. The screen stays " +
                "as it is, so you can go on to the next part.\n← RECALL  puts a slot back on screen.\n" +
                "The Song page plays Q1 to Q6 in your order.", 12f, Theme.DIM
        )
        box.addView(hint, ui.fullWidth(4))
        box.addView(rows, ui.fullWidth(8))
        val sc = ScrollView(a)
        sc.addView(box)
        build()
        dlg = AlertDialog.Builder(a).setView(sc).setNegativeButton("Close", null).show()
    }

    private fun build() {
        rows.removeAllViews()
        for (i in 0 until PartBank.N) rows.addView(slotRow(i), ui.fullWidth(4))
        if (bank.last.used) rows.addView(lastRow(), ui.fullWidth(10))
    }

    /** the screen is this part itself (the old rule: what you edit belongs to the part that plays) */
    private fun isScreen(i: Int): Boolean = i == bank.cur && !bank.loose

    private fun slotRow(i: Int): View {
        val p = bank.parts[i]
        val lit = p.used
        val row = ui.row()
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ui.bg(if (lit) Theme.alpha(Theme.ACCENT, 40) else Theme.PANEL2, 12, if (lit) Theme.ACCENT else Theme.LINE, if (lit) 2 else 1)
        row.setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
        val dot = View(a)
        dot.background = ui.bg(if (lit) Theme.ACCENT else Theme.FAINT, 6)
        row.addView(dot, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).also { it.marginEnd = ui.dp(10) })
        val txt = ui.col()
        txt.addView(ui.text(qName(i), 15f, if (lit) Theme.TEXT else Theme.DIM, true))
        var sub = when {
            !p.used -> "empty"
            isScreen(i) && !p.full -> "the screen itself, not sent yet"
            p.full && p.lanes >= 0 -> "holds ${p.lanes} lane" + (if (p.lanes == 1) "" else "s")
            else -> "holds ${p.count()} steps"
        }
        if (p.hasClip()) sub += " · rec ${p.clipBars} bars"
        if (i == bank.cur) sub += if (bank.loose) " · plays in the song / cues" else if (p.full) " · on screen now" else ""
        txt.addView(ui.text(sub, 11f, Theme.DIM))
        row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val send = ui.button("SEND →") { askSend(i) }
        ui.styleButton(send, true, Theme.ACCENT)
        row.addView(send, ui.w(0f, 3))
        val rec = ui.button("← RECALL") { askRecall(p, qName(i), isScreen(i)) }
        rec.isEnabled = p.used
        rec.alpha = if (p.used) 1f else 0.35f
        row.addView(rec, ui.w(0f, 3))
        return row
    }

    private fun lastRow(): View {
        val row = ui.row()
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ui.bg(Theme.PANEL2, 12, Theme.LINE, 1)
        row.setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
        val txt = ui.col()
        txt.addView(ui.text("Last screen", 14f, Theme.TEXT, true))
        txt.addView(ui.text("what was on screen before a recall or a part change replaced it", 11f, Theme.DIM))
        row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.button("← RECALL") { askRecall(bank.last, "the last screen", false) }, ui.w(0f, 3))
        return row
    }

    private fun askSend(i: Int) {
        val p = bank.parts[i]
        // a slot that is only the screen itself (never sent) holds nothing to lose
        if (!p.used || (isScreen(i) && !p.full)) {
            send(i)
            return
        }
        val what = if (p.full && p.lanes >= 0) "${p.lanes} lane" + (if (p.lanes == 1) "" else "s") else "${p.count()} steps"
        AlertDialog.Builder(a)
            .setTitle("Replace ${qName(i)}?")
            .setMessage("${qName(i)} already holds $what. They are replaced by everything that is in use on screen now." +
                (if (p.hasClip()) " Its recording stays." else ""))
            .setPositiveButton("Replace") { _, _ -> send(i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun send(i: Int) {
        val n = a.homeScreen.lanesInUse()
        bank.send(i, n)
        a.preloadBank()
        build()
        ui.toast("Sent to ${qName(i)}: $n lane" + (if (n == 1) "" else "s") + ". The screen stays as it is.")
    }

    private fun askRecall(src: PartSnap, name: String, same: Boolean) {
        if (!src.used) return
        if (same) {
            ui.toast("$name is what is on screen now")
            return
        }
        AlertDialog.Builder(a)
            .setTitle("Recall $name?")
            .setMessage(
                "Everything on screen (patterns, sounds, volumes, mutes) is replaced by $name. " +
                    "What is on screen now is kept as \"Last screen\", but it is in no slot unless you sent it."
            )
            .setPositiveButton("Recall") { _, _ ->
                bank.recall(src)
                a.preloadBank()
                a.refreshCurrent()
                dlg?.dismiss()
                ui.toast("$name is on screen. Change it, then TAKE ALL → SEND it to a slot.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
