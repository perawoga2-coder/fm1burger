package com.example.fm1burger

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.Locale

class EditScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val store = a.store
    override val root = ScrollView(a)
    private val content = ui.col()

    private lateinit var spinner: Spinner
    private lateinit var wave: WaveformView
    private lateinit var info: TextView
    private var files: List<File> = emptyList()
    private var current: File? = null
    private var buf = FloatArray(0)
    private var rate = Cfg.SR
    private val undo = ArrayList<FloatArray>()
    var pendingOpen: File? = null

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(32))
        root.addView(content)
        content.addView(ui.title("Sample editor"))

        spinner = ui.spinner(listOf("(no samples)")) { idx -> choose(idx) }
        content.addView(spinner, ui.fullWidth(6))

        wave = WaveformView(a)
        wave.editable = true
        wave.onSelect = { _, _ -> updateInfo() }
        content.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(200)).also {
            it.topMargin = ui.dp(6)
        })
        info = ui.text("", 12f, Theme.DIM)
        content.addView(info, ui.fullWidth(4))
        content.addView(ui.text("Drag on the waveform to select a part. Without a selection, the whole sample is used.", 12f, Theme.DIM))

        row(
            "● Record new sample (any input)" to { a.flow.recordForEdit() }
        )
        row(
            "Play" to { playSel() },
            "Stop" to { a.engine.previewStop() },
            "Select all" to { wave.setSelection(0f, 0f); updateInfo() }
        )
        row(
            "Trim to selection" to { trim() },
            "Cut selection" to { cut() },
            "Silence" to { silence() }
        )
        row(
            "Normalize" to { normalize() },
            "Fade in" to { fade(true) },
            "Fade out" to { fade(false) }
        )
        row(
            "Reverse" to { reverse() },
            "Louder" to { gain(1.25f) },
            "Quieter" to { gain(0.8f) }
        )
        row(
            "Undo" to { undoOp() },
            "Revert" to { revert() }
        )
        row(
            "Save (overwrite)" to { save(true) },
            "Save as copy" to { save(false) }
        )
    }

    private fun row(vararg items: Pair<String, () -> Unit>) {
        val r = ui.row()
        for (it in items) r.addView(ui.button(it.first) { it.second() }, ui.w(1f, 3))
        content.addView(r, ui.fullWidth(6))
    }

    // ---------------- loading ----------------

    override fun onShow() {
        files = store.list()
        val names = files.map { it.name }.ifEmpty { listOf("(no samples)") }
        val want = pendingOpen?.name ?: current?.name
        pendingOpen = null
        ui.fill(spinner, names)
        val idx = if (want != null) files.indexOfFirst { it.name == want } else -1
        if (idx >= 0) {
            spinner.setSelection(idx)
            if (current?.name != want) choose(idx)
        } else if (files.isNotEmpty() && current == null) {
            choose(0)
        }
    }

    private fun choose(idx: Int) {
        val f = files.getOrNull(idx) ?: return
        if (current?.name == f.name) return
        load(f)
    }

    private fun load(f: File) {
        val p = Wav.read(f)
        if (p == null) {
            ui.toast("Cannot read this file")
            return
        }
        current = f
        buf = p.data
        rate = p.rate
        undo.clear()
        wave.setData(buf)
        wave.setSelection(0f, 0f)
        updateInfo()
    }

    private fun revert() {
        val f = current ?: return
        val p = Wav.read(f) ?: return
        buf = p.data
        rate = p.rate
        undo.clear()
        wave.setData(buf)
        wave.setSelection(0f, 0f)
        updateInfo()
    }

    private fun updateInfo() {
        if (current == null) {
            info.text = "Pick a sample from the library."
            return
        }
        val total = buf.size.toDouble() / rate
        var s = String.format(Locale.US, "%s · %.2f s · %d Hz", current?.name, total, rate)
        if (wave.hasSelection()) {
            val (i0, i1) = range()
            s += String.format(Locale.US, " · selected %.2f s", (i1 - i0).toDouble() / rate)
        }
        info.text = s
    }

    // ---------------- helpers ----------------

    private fun range(): Pair<Int, Int> {
        if (!wave.hasSelection()) return Pair(0, buf.size)
        val i0 = (wave.selStart() * buf.size).toInt().coerceIn(0, buf.size)
        val i1 = (wave.selEnd() * buf.size).toInt().coerceIn(i0, buf.size)
        return Pair(i0, i1)
    }

    private fun pushUndo() {
        undo.add(buf.copyOf())
        if (undo.size > 10) undo.removeAt(0)
    }

    private fun changed(keepSel: Boolean = true) {
        wave.setData(buf)
        if (!keepSel) wave.setSelection(0f, 0f)
        updateInfo()
    }

    private fun ready(): Boolean {
        if (current == null || buf.isEmpty()) {
            ui.toast("Pick a sample first")
            return false
        }
        return true
    }

    // ---------------- operations ----------------

    private fun playSel() {
        if (!ready()) return
        val (i0, i1) = range()
        if (i1 - i0 < 2) return
        val seg = buf.copyOfRange(i0, i1)
        a.engine.previewPlay(Wav.resample(seg, rate, Cfg.SR))
    }

    private fun trim() {
        if (!ready()) return
        if (!wave.hasSelection()) {
            ui.toast("Select a part first")
            return
        }
        val (i0, i1) = range()
        if (i1 - i0 < 2) return
        pushUndo()
        buf = buf.copyOfRange(i0, i1)
        changed(false)
    }

    private fun cut() {
        if (!ready()) return
        if (!wave.hasSelection()) {
            ui.toast("Select a part first")
            return
        }
        val (i0, i1) = range()
        if (i1 <= i0 || i1 - i0 >= buf.size) return
        pushUndo()
        val out = FloatArray(buf.size - (i1 - i0))
        System.arraycopy(buf, 0, out, 0, i0)
        System.arraycopy(buf, i1, out, i0, buf.size - i1)
        buf = out
        changed(false)
    }

    private fun silence() {
        if (!ready()) return
        val (i0, i1) = range()
        pushUndo()
        for (i in i0 until i1) buf[i] = 0f
        changed()
    }

    private fun normalize() {
        if (!ready()) return
        val (i0, i1) = range()
        val p = Wav.peak(buf, i0, i1)
        if (p < 0.0001f) return
        pushUndo()
        val g = 0.9f / p
        for (i in i0 until i1) buf[i] = (buf[i] * g).coerceIn(-1f, 1f)
        changed()
    }

    private fun fade(inFade: Boolean) {
        if (!ready()) return
        val (i0, i1) = range()
        val n = i1 - i0
        if (n < 2) return
        pushUndo()
        for (i in 0 until n) {
            val g = if (inFade) i.toFloat() / n else 1f - i.toFloat() / n
            buf[i0 + i] = buf[i0 + i] * g
        }
        changed()
    }

    private fun reverse() {
        if (!ready()) return
        val (i0, i1) = range()
        if (i1 - i0 < 2) return
        pushUndo()
        var l = i0
        var r = i1 - 1
        while (l < r) {
            val t = buf[l]
            buf[l] = buf[r]
            buf[r] = t
            l++
            r--
        }
        changed()
    }

    private fun gain(g: Float) {
        if (!ready()) return
        val (i0, i1) = range()
        pushUndo()
        for (i in i0 until i1) buf[i] = (buf[i] * g).coerceIn(-1f, 1f)
        changed()
    }

    private fun undoOp() {
        if (undo.isEmpty()) {
            ui.toast("Nothing to undo")
            return
        }
        buf = undo.removeAt(undo.size - 1)
        changed(false)
    }

    private fun save(overwrite: Boolean) {
        val f = current
        if (f == null || buf.isEmpty()) {
            ui.toast("Pick a sample first")
            return
        }
        if (overwrite) {
            store.overwrite(f, buf, rate)
            a.reloadByName(f.name)
            ui.toast("Saved ${f.name}")
        } else {
            val nf = store.saveNew(f.nameWithoutExtension + "_edit", buf, rate)
            ui.toast("Saved a copy: ${nf.name}")
        }
    }
}
