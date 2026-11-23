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
 * big and easy to tap. 3.28: the list has the page; when a name is picked a slim bar comes up under the list with
 * the functions for that name (the rest of them behind "⋯").
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
    private val selBar = ui.col()
    private val fnBtns = ArrayList<android.widget.Button>()
    private var cat = "All"
    // 3.26 BOXES: the box open (and its sub-box), the rows that show them
    private var box: String? = null
    private var sub: String? = null
    private val boxRow = ui.row()
    private val subRow = ui.row()
    private val subScroll = android.widget.HorizontalScrollView(a)
    private var sel: File? = null
    private val rowOf = HashMap<String, android.widget.Button>()

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)
        val head = ui.col()
        head.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(2))
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
            b.minHeight = ui.dp(34)
            b.minimumHeight = ui.dp(34)
            b.setPadding(ui.dp(14), 0, ui.dp(14), 0)
            catBtns.add(b)
            catRow.addView(b, ui.w(0f, 2))
        }
        hs.addView(catRow)
        head.addView(hs, ui.fullWidth(4))

        // 3.26: the boxes (a second row opens with a box's sub-boxes)
        val bs = android.widget.HorizontalScrollView(a)
        bs.isHorizontalScrollBarEnabled = false
        bs.addView(boxRow)
        head.addView(bs, ui.fullWidth(2))
        subScroll.isHorizontalScrollBarEnabled = false
        subScroll.addView(subRow)
        head.addView(subScroll, ui.fullWidth(0))

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
        head.addView(search, ui.fullWidth(2))

        listBox.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(88))
        listScroll.addView(listBox)
        root.addView(listScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 3.28: the functions for the name picked: a slim bar under the list, there only while a name is picked
        selBar.setBackgroundColor(Theme.PANEL)
        val barLine = View(a)
        barLine.setBackgroundColor(Theme.LINE)
        selBar.addView(barLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)))
        val nameRow = ui.row()
        nameRow.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0)
        selText.maxLines = 1
        selText.ellipsize = android.text.TextUtils.TruncateAt.END
        selInfo.maxLines = 1
        selInfo.ellipsize = android.text.TextUtils.TruncateAt.END
        selInfo.setPadding(ui.dp(10), 0, 0, 0)
        nameRow.addView(selText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        nameRow.addView(selInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selBar.addView(nameRow)
        val r1 = ui.row()
        r1.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4))
        // the widths follow the labels, so that all seven fit a narrow phone
        fn(r1, "▶", Theme.GREEN, 1f) { preview(it) }
        fn(r1, "→ Home lane", Theme.ACCENT, 3f) { homeDialog(it) }
        fn(r1, "→ Kit", Theme.ACCENT, 1.6f) { kitDialog(it) }
        fn(r1, "→ Track", Theme.ACCENT, 2f) { chooseTrack(it) }
        fn(r1, "Edit", Theme.BLUE, 1.4f) { a.openEdit(it) }
        fn(r1, "⋯", Theme.PURPLE, 1f) { moreMenu(it) }
        val close = ui.button("✕") { unpick() }
        close.setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
        r1.addView(close, ui.w(1f, 2))
        selBar.addView(r1)
        root.addView(selBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        showSel()
    }

    private fun fn(r: LinearLayout, label: String, color: Int, weight: Float, act: (File) -> Unit) {
        val b = ui.button(label) {
            val f = sel
            if (f == null || !f.exists()) ui.toast("Tap a name in the list first") else act(f)
        }
        b.setTag(color)
        b.setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
        fnBtns.add(b)
        r.addView(b, ui.w(weight, 2))
    }

    /** "⋯": the functions that are not on the bar. */
    private fun moreMenu(f: File) {
        AlertDialog.Builder(a)
            .setTitle(f.nameWithoutExtension)
            .setItems(arrayOf("Category", "Tags", "Rename", "→ Box", "→ MPC pad", "Export", "Devices…", "Delete", "■ Stop")) { _, w ->
                if (w == 8) a.engine.previewStop()
                else if (!f.exists()) ui.toast("Tap a name in the list first")
                else when (w) {
                    0 -> catDialog(f)
                    1 -> tagsDialog(f)
                    2 -> renameDialog(f)
                    3 -> boxDialog(f)
                    4 -> choosePad(f)
                    5 -> exportPlain(f)
                    6 -> deviceMenu(f)
                    else -> deleteDialog(f)
                }
            }
            .show()
    }

    /** "✕": no name picked, the bar goes away. */
    private fun unpick() {
        sel = null
        paintRows()
        showSel()
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
        val tree = Boxes.tree(a, store)
        paintBoxes(tree, all)
        val top = tree.firstOrNull { it.title == box }
        val open = top?.children?.firstOrNull { it.title == sub } ?: top
        if (top == null) { box = null; sub = null }
        val files = store.listCat(cat, search.text.toString()).filter { open == null || open.has(it.name) }
        if (sel?.exists() != true) sel = null
        if (files.isEmpty()) {
            listBox.addView(ui.text(
                if (all.isEmpty()) "No sounds yet. Record, sample an instrument, import a WAV, or Add sounds… above."
                else if (open != null) "Nothing here yet. Pick a sound anywhere and press → Box (or → Kit)."
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
        selBar.visibility = if (f == null) View.GONE else View.VISIBLE
        if (f == null) {
            selText.text = ""
            selInfo.text = ""
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
            .setItems(Array(Cfg.TRACKS) { "Track ${it + 1}" }) { _, which -> a.assignTrack(which, f, fresh = true) }
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

    // ---------------- 3.26 BOXES, → Home lane, → Kit ----------------

    private fun boxButton(row: LinearLayout, label: String, on: Boolean, color: Int, act: () -> Unit): android.widget.Button {
        val b = ui.button(label) { act() }
        b.minHeight = ui.dp(32)
        b.minimumHeight = ui.dp(32)
        b.textSize = 13f
        b.setPadding(ui.dp(12), 0, ui.dp(12), 0)
        ui.styleButton(b, on, color, 18)
        row.addView(b, ui.w(0f, 2))
        return b
    }

    private fun paintBoxes(tree: List<Box>, all: List<File>) {
        boxRow.removeAllViews()
        subRow.removeAllViews()
        boxButton(boxRow, "▣ All boxes", box == null, Theme.TEAL) { box = null; sub = null; refresh() }
        for (b in tree) {
            val n = all.count { b.has(it.name) }
            val btn = boxButton(boxRow, "▣ ${b.title}  $n", b.title == box, if (b.user) Theme.YELLOW else Theme.TEAL) {
                box = if (box == b.title) null else b.title
                sub = null
                refresh()
                listScroll.scrollTo(0, 0)
            }
            if (b.user) btn.setOnLongClickListener { userBoxMenu(b.title); true }
        }
        boxButton(boxRow, "+ NEW BOX", false, Theme.YELLOW) { newBoxDialog() }
        val top = tree.firstOrNull { it.title == box }
        if (top != null && top.children.isNotEmpty()) {
            subScroll.visibility = View.VISIBLE
            boxButton(subRow, "All in ${top.title}", sub == null, Theme.TEAL) { sub = null; refresh() }
            for (c in top.children) {
                val n = all.count { c.has(it.name) }
                val btn = boxButton(subRow, "${c.title}  $n", c.title == sub, Theme.TEAL) { sub = c.title; refresh(); listScroll.scrollTo(0, 0) }
                if (top.title == "Drum Kits" && Boxes.kit(c.title) != null) btn.setOnLongClickListener { a.showTab(MainActivity.TAB_PADS); true }
            }
        } else subScroll.visibility = View.GONE
    }

    private fun newBoxDialog(then: ((String) -> Unit)? = null) {
        val edit = EditText(a)
        edit.hint = "Song 1, best kicks, ..."
        AlertDialog.Builder(a).setTitle("New box").setView(edit)
            .setPositiveButton("Make") { _, _ ->
                val n = edit.text.toString().trim()
                if (!Boxes.newBox(a, n)) ui.toast("That name is empty, already a box, or the name of a ready-made box")
                else { then?.invoke(n); refresh() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun userBoxMenu(name: String) {
        AlertDialog.Builder(a).setTitle("Box: $name")
            .setItems(arrayOf("Rename", "Delete the box (its sounds stay in the Maker Bank)")) { _, w ->
                if (w == 0) {
                    val edit = EditText(a)
                    edit.setText(name)
                    AlertDialog.Builder(a).setTitle("Rename the box").setView(edit)
                        .setPositiveButton("OK") { _, _ ->
                            if (!Boxes.renameBox(a, name, edit.text.toString())) ui.toast("That name is empty, already a box, or the name of a ready-made box")
                            else { if (box == name) box = edit.text.toString().trim(); refresh() }
                        }
                        .setNegativeButton("Cancel", null).show()
                } else {
                    Boxes.deleteBox(a, name)
                    if (box == name) box = null
                    refresh()
                }
            }.show()
    }

    /** → Box: the user's boxes, ticked where the sound is; a new box from here too */
    private fun boxDialog(f: File) {
        val names = Boxes.userBoxes()
        if (names.isEmpty()) {
            newBoxDialog { n -> Boxes.setIn(a, n, f.name, true); ui.toast("${f.nameWithoutExtension} is in the box $n") }
            return
        }
        val checked = BooleanArray(names.size) { Boxes.inBox(names[it], f.name) }
        AlertDialog.Builder(a).setTitle("Boxes for ${f.nameWithoutExtension}")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, w, on -> checked[w] = on }
            .setPositiveButton("OK") { _, _ ->
                for (i in names.indices) Boxes.setIn(a, names[i], f.name, checked[i])
                refresh()
            }
            .setNeutralButton("+ New box") { _, _ -> newBoxDialog { n -> Boxes.setIn(a, n, f.name, true); refresh() } }
            .setNegativeButton("Cancel", null).show()
    }

    /** → Home lane: straight onto a Home lane that plays sounds (a sampler track, or a drum pad) */
    private fun homeDialog(f: File) {
        val l = a.lanes
        val lanes = (0 until LaneSetup.N).filter { l.type[it] == Inst.SAMPLE || l.type[it] == Inst.DRUM }
        if (lanes.isEmpty()) { ui.toast("No Sampler or Drum lane on Home. Tap a lane number on Home to choose one."); return }
        val items = lanes.map { i ->
            val k = l.idx[i]
            val now = if (l.type[i] == Inst.DRUM) a.engine.pads[k].name else a.engine.tracks[k].sampleName
            "${i + 1}.  ${l.label(i)}   " + now.removeSuffix(".wav").ifEmpty { "(empty)" }
        }.toTypedArray()
        AlertDialog.Builder(a).setTitle("Put ${f.nameWithoutExtension} on which Home lane?")
            .setItems(items) { _, w ->
                val i = lanes[w]
                val k = l.idx[i]
                if (l.type[i] == Inst.DRUM) a.assignPad(k, f) else a.assignTrack(k, f, fresh = true)
                ui.toast("Lane ${i + 1} (${l.label(i)}): ${f.nameWithoutExtension}")
            }.show()
    }

    /** → Kit: into a slot of the pads now (bank A / B) or of one of your kits */
    private fun kitDialog(f: File) {
        Boxes.load(a)
        val targets = listOf("Pads now, bank A", "Pads now, bank B") + Boxes.kits.map { "Kit: ${it.name}" }
        AlertDialog.Builder(a).setTitle("Put ${f.nameWithoutExtension} into which kit?")
            .setItems(targets.toTypedArray()) { _, w ->
                val kit = if (w >= 2) Boxes.kits[w - 2] else null
                val slots = Array(Boxes.SLOTS) { s ->
                    val now = if (kit != null) kit.slots[s] else a.engine.pads[w * Cfg.PAD_BANK + s].name
                    "${s + 1}.  " + now.removeSuffix(".wav").removePrefix("Drum_").ifEmpty { "(empty)" }
                }
                AlertDialog.Builder(a).setTitle("Which slot? (${targets[w]})")
                    .setItems(slots) { _, s ->
                        if (kit != null) {
                            kit.slots[s] = f.name
                            Boxes.saveKits(a)
                            ui.toast("${kit.name}, slot ${s + 1}: ${f.nameWithoutExtension}")
                            refresh()
                        } else {
                            a.assignPad(w * Cfg.PAD_BANK + s, f)
                            ui.toast("Pad ${Cfg.padName(w * Cfg.PAD_BANK + s)}: ${f.nameWithoutExtension}")
                        }
                    }.show()
            }
            .setNeutralButton("Make a kit in the drum sequencer") { _, _ -> a.showTab(MainActivity.TAB_PADS) }
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
                    Boxes.rename(a, f.name, nf.name)
                    DrumEdit.renameSound(a.filesDir, f.name, nf.name)
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
                DrumEdit.deleteNote(a.filesDir, f.name)
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
