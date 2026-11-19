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

/**
 * MAKER BANK (3.16): the sounds sorted into categories. Tap a category, then a name: the list is only names,
 * big and easy to tap. One set of function buttons sits above the list and works on the name picked.
 * The buttons are coloured by what they do: green hear, orange put somewhere, blue change, purple out, red delete.
 */
class LibraryScreen(private val a: MainActivity) : Screen {

    private val ui = a.ui
    private val store = a.store
    override val root = LinearLayout(a)
    private val listScroll = ScrollView(a)
    private val listBox = ui.col()
    private val catRow = ui.row()
    private val catBtns = ArrayList<android.widget.Button>()
    private val search = EditText(a)
    private val selText = ui.text("", 15f, Theme.TEXT, true)
    private val selInfo = ui.text("", 12f, Theme.DIM)
    private val fnBtns = ArrayList<android.widget.Button>()
    private var cat = "All"
    private var sel: File? = null
    private val rowOf = HashMap<String, android.widget.Button>()

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)
        val head = ui.col()
        head.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(4))
        root.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val tr = ui.row()
        tr.gravity = android.view.Gravity.CENTER_VERTICAL
        tr.addView(ui.title("Maker Bank"), ui.w(1f, 0))
        tr.addView(ui.button("Import WAV") { a.importFiles() }, ui.w(0f, 3))
        tr.addView(ui.button("Add sounds…") { addMenu() }, ui.w(0f, 3))
        head.addView(tr)

        // the categories
        val hs = android.widget.HorizontalScrollView(a)
        hs.isHorizontalScrollBarEnabled = false
        for (c in arrayOf("All") + Store.CATS) {
            val b = ui.button(c) { setCat(c) }
            b.minHeight = ui.dp(40)
            b.minimumHeight = ui.dp(40)
            b.setPadding(ui.dp(16), 0, ui.dp(16), 0)
            catBtns.add(b)
            catRow.addView(b, ui.w(0f, 3))
        }
        hs.addView(catRow)
        head.addView(hs, ui.fullWidth(8))

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
        head.addView(search, ui.fullWidth(4))

        // the one set of functions, for the name picked
        val fp = ui.panel()
        fp.addView(selText)
        fp.addView(selInfo)
        val r1 = ui.row()
        fn(r1, "▶ Play", Theme.GREEN) { preview(it) }
        fn(r1, "■ Stop", Theme.GREEN) { a.engine.previewStop() }
        fn(r1, "→ MPC pad", Theme.ACCENT) { choosePad(it) }
        fn(r1, "→ Track", Theme.ACCENT) { chooseTrack(it) }
        fn(r1, "Edit", Theme.BLUE) { a.openEdit(it) }
        fp.addView(r1, ui.fullWidth(6))
        val r2 = ui.row()
        fn(r2, "Category", Theme.BLUE) { catDialog(it) }
        fn(r2, "Tags", Theme.BLUE) { tagsDialog(it) }
        fn(r2, "Rename", Theme.BLUE) { renameDialog(it) }
        fn(r2, "Export", Theme.PURPLE) { exportPlain(it) }
        fn(r2, "Devices…", Theme.PURPLE) { deviceMenu(it) }
        fn(r2, "Delete", Theme.RED) { deleteDialog(it) }
        fp.addView(r2, ui.fullWidth(4))
        head.addView(fp, ui.fullWidth(6))

        listBox.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(88))
        listScroll.addView(listBox)
        root.addView(listScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        showSel()
    }

    private fun fn(r: LinearLayout, label: String, color: Int, act: (File) -> Unit) {
        val b = ui.button(label) {
            val f = sel
            if (f == null || !f.exists()) ui.toast("Tap a name in the list first") else act(f)
        }
        b.setTag(color)
        fnBtns.add(b)
        r.addView(b, ui.w(1f, 3))
    }

    /** Sounds that come with the app, and FM banks. */
    private fun addMenu() {
        AlertDialog.Builder(a)
            .setTitle("Add sounds to the Maker Bank")
            .setItems(arrayOf(
                "MakerHip FM bank (32 sounds)",
                "Import an FM bank (.syx, DX7 style, 32 voices)",
                "Rock Heavy drum kit (12 sounds)",
                "Electronic drum kit"
            )) { _, w ->
                when (w) {
                    0 -> Thread {
                        val n = try { FmBank.install(a, store) } catch (ex: Exception) { -1 }
                        a.runOnUiThread {
                            refresh()
                            ui.toast(when {
                                n < 0 -> "Could not add the FM bank"
                                n == 0 -> "The 32 MakerHip FM sounds are already here (FM & Synth)"
                                else -> "Added $n MakerHip FM sounds (FM & Synth)"
                            })
                        }
                    }.start()
                    1 -> a.importSyx()
                    2 -> Thread {
                        val ok = try { RockKit.install(a, store); true } catch (ex: Exception) { false }
                        a.runOnUiThread { refresh(); ui.toast(if (ok) "Rock Heavy kit added" else "Could not add the kit") }
                    }.start()
                    else -> Thread {
                        val n = try { DrumKit.ensureAll(store) } catch (ex: Exception) { -1 }
                        a.runOnUiThread { refresh(); ui.toast(if (n < 0) "Could not make the kit" else "Electronic kit ready") }
                    }.start()
                }
            }
            .show()
    }

    private fun setCat(c: String) {
        cat = c
        refresh()
        listScroll.scrollTo(0, 0)
    }

    override fun onShow() {
        refresh()
    }

    fun refresh() {
        // the category buttons show how many sounds each holds
        val all = store.list()
        val counts = HashMap<String, Int>()
        for (f in all) {
            val c = store.categoryOf(f.name)
            counts[c] = (counts[c] ?: 0) + 1
        }
        for (b in catBtns) {
            val name = b.text.toString().substringBefore("  ")
            val n = if (name == "All") all.size else counts[name] ?: 0
            b.text = "$name  $n"
            ui.styleButton(b, name == cat, Theme.ACCENT, 18)
        }
        listBox.removeAllViews()
        rowOf.clear()
        val files = store.listCat(cat, search.text.toString())
        if (sel?.exists() != true) sel = null
        if (files.isEmpty()) {
            listBox.addView(ui.text(
                if (all.isEmpty()) "No sounds yet. Record, sample an instrument, import a WAV, or Add sounds… above."
                else "Nothing in $cat yet.", 14f, Theme.DIM), ui.fullWidth(8))
        }
        // the names, two in a row: big and easy to tap
        var r: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % 2 == 0) {
                r = ui.row()
                listBox.addView(r, ui.fullWidth(2))
            }
            val b = ui.button(f.nameWithoutExtension) { pick(f) }
            b.textSize = 15f
            b.isAllCaps = false
            b.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            b.setPadding(ui.dp(16), 0, ui.dp(12), 0)
            b.maxLines = 1
            b.ellipsize = android.text.TextUtils.TruncateAt.END
            b.minHeight = ui.dp(54)
            b.minimumHeight = ui.dp(54)
            b.setOnLongClickListener { pick(f); preview(f); true }
            rowOf[f.name] = b
            r!!.addView(b, ui.w(1f, 3))
        }
        if (files.size % 2 == 1) r?.addView(View(a), ui.w(1f, 3))
        paintRows()
        showSel()
    }

    /** A tap on a name: it is picked, and plays once. */
    private fun pick(f: File) {
        sel = f
        paintRows()
        showSel()
        preview(f)
    }

    private fun paintRows() {
        for ((n, b) in rowOf) ui.styleButton(b, n == sel?.name, Theme.ACCENT, 12)
    }

    private fun showSel() {
        val f = sel
        if (f == null) {
            selText.text = "Tap a name"
            selInfo.text = "The buttons below work on the name you tap. Long press a name to hear it."
        } else {
            selText.text = f.nameWithoutExtension
            val tg = store.getTags(f.name)
            selInfo.text = store.categoryOf(f.name) + " · " + store.describe(f) + (if (tg.isNotEmpty()) " · # $tg" else "")
        }
        for (b in fnBtns) {
            val c = b.tag as Int
            if (f == null) {
                b.background = ui.touchBg(ui.bg(Theme.PANEL2, 10, Theme.alpha(c, 90), 1), 10)
                b.setTextColor(Theme.FAINT)
            } else {
                b.background = ui.touchBg(ui.bg(Theme.alpha(c, 46), 10, c, 1), 10)
                b.setTextColor(c)
            }
        }
    }

    private fun catDialog(f: File) {
        val items = arrayOf("Guess from the name") + Store.CATS
        AlertDialog.Builder(a)
            .setTitle("Category of ${f.nameWithoutExtension}")
            .setItems(items) { _, w ->
                store.setCategory(f.name, if (w == 0) null else Store.CATS[w - 1])
                refresh()
            }
            .show()
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
            .setTitle("Put on which MPC pad?")
            .setItems(Array(Cfg.PADS) { "Pad ${Cfg.padName(it)}  " + a.engine.pads[it].name.removeSuffix(".wav").ifEmpty { "(empty)" } }) { _, which ->
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
                    if (sel == f) sel = nf
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
