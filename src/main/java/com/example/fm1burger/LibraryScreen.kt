package com.example.fm1burger

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.util.Locale

class LibraryScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val store = a.store
    override val root = ScrollView(a)
    private val content = ui.col()
    private val listBox = ui.col()
    private val search = EditText(a)

    init {
        content.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(88))
        root.addView(content)

        content.addView(ui.title("Sample library"))
        val top = ui.row()
        top.addView(ui.button("Import WAV") { a.importFiles() }, ui.w(1f, 3))
        top.addView(ui.button("Stop preview") { a.engine.previewStop() }, ui.w(1f, 3))
        content.addView(top, ui.fullWidth(6))

        // FM sound banks
        val fm = ui.row()
        fm.addView(ui.button("MakerHip FM bank (32)") {
            ui.toast("Adding the MakerHip FM bank...")
            Thread {
                val n = try { FmBank.install(a, store) } catch (ex: Exception) { -1 }
                a.runOnUiThread {
                    refresh()
                    ui.toast(when {
                        n < 0 -> "Could not add the FM bank"
                        n == 0 -> "The 32 MakerHip FM sounds are already in the Library (search \"FM\")"
                        else -> "Added $n MakerHip FM sounds (search \"FM\")"
                    })
                }
            }.start()
        }, ui.w(1f, 3))
        fm.addView(ui.button("Import FM bank (.syx)") { a.importSyx() }, ui.w(1f, 3))
        content.addView(fm, ui.fullWidth(4))
        content.addView(
            ui.text("FM banks: any DX7-style .syx bank (32 voices) can be turned into samples here. " +
                "The MakerHip FM bank is our own.", 11f, Theme.DIM), ui.fullWidth(2)
        )

        search.hint = "Search name or tag..."
        search.setSingleLine(true)
        search.setTextColor(Theme.TEXT)
        search.setHintTextColor(Theme.DIM)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        content.addView(search, ui.fullWidth(6))
        content.addView(listBox, ui.fullWidth(4))
    }

    override fun onShow() {
        refresh()
    }

    fun refresh() {
        listBox.removeAllViews()
        val files = store.list(search.text.toString())
        if (files.isEmpty()) {
            listBox.addView(ui.text("No samples yet. Record, sample an instrument, or import a WAV file.", 14f, Theme.DIM))
            return
        }
        for (f in files) listBox.addView(rowFor(f), ui.fullWidth(8))
    }

    private fun rowFor(f: File): View {
        val p = ui.panel()
        p.addView(ui.text(f.name, 16f, Theme.TEXT, true))
        val tg = store.getTags(f.name)
        if (tg.isNotEmpty()) p.addView(ui.text("# $tg", 12f, Theme.ACCENT))
        p.addView(ui.text(store.describe(f), 12f, Theme.DIM))

        val r1 = ui.row()
        r1.addView(ui.button("Play") { preview(f) }, ui.w(1f, 2))
        r1.addView(ui.button("To track") { chooseTrack(f) }, ui.w(1f, 2))
        r1.addView(ui.button("To pad") { choosePad(f) }, ui.w(1f, 2))
        r1.addView(ui.button("Edit") { a.openEdit(f) }, ui.w(1f, 2))
        p.addView(r1, ui.fullWidth(6))

        val r2 = ui.row()
        r2.addView(ui.button("Tags") { tagsDialog(f) }, ui.w(1f, 2))
        r2.addView(ui.button("Rename") { renameDialog(f) }, ui.w(1f, 2))
        r2.addView(ui.button("Export") { exportPlain(f) }, ui.w(1f, 2))
        r2.addView(ui.button("Devices…") { deviceMenu(f) }, ui.w(1f, 2))
        r2.addView(ui.button("Delete") { deleteDialog(f) }, ui.w(1f, 2))
        p.addView(r2)
        return p
    }

    /** Things for connected gear (the FM-1 for now), kept out of the way of the main buttons. */
    private fun deviceMenu(f: File) {
        AlertDialog.Builder(a)
            .setTitle(f.name)
            .setItems(arrayOf(
                "Send into the sample slots of " + (a.hub.slotDevice ?: "a connected device") + " (USB)",
                "Export a WAV that fits a 496 KB sample memory (M-VAVE FM-1)"
            )) { _, w ->
                if (w == 0) {
                    Thread {
                        val d = store.loadEngine(f.name)
                        a.runOnUiThread { if (d == null) ui.toast("Cannot read this file") else a.sendToFm1(f.name, d) }
                    }.start()
                } else {
                    fm1Dialog(f)
                }
            }
            .show()
    }

    private fun preview(f: File) {
        Thread {
            val d = store.loadEngine(f.name)
            a.runOnUiThread {
                if (d == null) ui.toast("Cannot play this file") else a.engine.previewPlay(d)
            }
        }.start()
    }

    private fun chooseTrack(f: File) {
        AlertDialog.Builder(a)
            .setTitle("Load into which track?")
            .setItems(Array(Cfg.TRACKS) { "Track ${it + 1}" }) { _, which -> a.assignTrack(which, f) }
            .show()
    }

    private fun choosePad(f: File) {
        AlertDialog.Builder(a)
            .setTitle("Put on which pad?")
            .setItems(Array(Cfg.PADS) { "Pad ${Cfg.padName(it)}" }) { _, which ->
                a.assignPad(which, f)
                ui.toast("Pad ${Cfg.padName(which)}: ${f.name}")
            }
            .show()
    }

    private fun tagsDialog(f: File) {
        val edit = EditText(a)
        edit.setText(store.getTags(f.name))
        edit.hint = "kick, 808, dark"
        AlertDialog.Builder(a)
            .setTitle("Tags (comma separated)")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                store.setTags(f.name, edit.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameDialog(f: File) {
        val edit = EditText(a)
        edit.setText(f.nameWithoutExtension)
        AlertDialog.Builder(a)
            .setTitle("Rename")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val nf = store.rename(f, edit.text.toString())
                if (nf == null) {
                    ui.toast("That name is empty or already used")
                } else {
                    a.renameRefs(f.name, nf.name)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDialog(f: File) {
        AlertDialog.Builder(a)
            .setTitle("Delete")
            .setMessage("Delete ${f.name}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(f)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPlain(f: File) {
        try {
            a.saveBytes(f.name, f.readBytes())
        } catch (e: Exception) {
            ui.toast("Export failed: ${e.message}")
        }
    }

    private fun fm1Dialog(f: File) {
        val p = store.loadRaw(f.name)
        if (p == null) {
            ui.toast("Cannot read this file")
            return
        }
        val rates = intArrayOf(11025, 16000, 22050, 32000, 44100)
        val box = ui.col()
        box.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(4))
        box.addView(ui.text("Makes a mono 16 bit WAV that fits the FM-1 sample memory (about 496 KB).", 13f, Theme.DIM))
        val sizeTv = ui.text("", 14f, Theme.TEXT)
        val rateSp = ui.spinner(rates.map { "$it Hz" }) { idx ->
            val size = store.fm1Size(p, rates[idx])
            val kb = size / 1024
            val maxSec = (Cfg.FM1_BUDGET - 44) / 2.0 / rates[idx]
            sizeTv.text = if (size <= Cfg.FM1_BUDGET) {
                "Size: $kb KB. Fits in 496 KB."
            } else {
                String.format(
                    Locale.US,
                    "Size: %d KB. Too big for 496 KB. At this rate the maximum length is %.1f s.",
                    kb, maxSec
                )
            }
        }
        rateSp.setSelection(2)
        box.addView(rateSp)
        box.addView(sizeTv)
        val trim = CheckBox(a)
        trim.text = "Cut the end so it fits"
        trim.isChecked = true
        box.addView(trim)

        AlertDialog.Builder(a)
            .setTitle("Export for FM-1")
            .setView(ScrollView(a).also { it.addView(box) })
            .setPositiveButton("Export") { _, _ ->
                val rate = rates[rateSp.selectedItemPosition]
                val res = store.fm1Bytes(p, rate, trim.isChecked)
                val name = f.nameWithoutExtension + "_fm1_" + (rate / 1000) + "k.wav"
                a.saveBytes(name, res.first)
                if (res.second) ui.toast("The end was cut to fit the FM-1 budget")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
