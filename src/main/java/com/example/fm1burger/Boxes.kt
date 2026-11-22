package com.example.fm1burger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 3.26: BOXES in the Maker Bank, and the user's own drum kits.
 *
 * A box only points at sounds (by file name); the sounds stay in the Maker Bank, and taking one out of a box or
 * deleting a box never deletes a sound. The ready-made boxes fill themselves from the names (Synths by engine,
 * Drum Kits, Piano / Flute / Sax, Drum Edits, My Recordings); the user's boxes and kits are kept in
 * files/boxes.json and files/kits.json.
 */
class Box(val title: String, val children: List<Box> = emptyList(), val user: Boolean = false,
          val match: (String) -> Boolean = { false }) {
    fun has(name: String): Boolean = match(name) || children.any { it.has(name) }
}

class Kit(var name: String, val slots: Array<String>, val builtIn: Boolean = false)

object Boxes {
    const val SLOTS = 16
    private var synths: Map<String, String>? = null
    private val user = LinkedHashMap<String, LinkedHashSet<String>>()     // box -> sound names
    val kits = ArrayList<Kit>()                                          // the user's kits
    private var loaded = false

    private fun boxFile(ctx: Context) = File(ctx.filesDir, "boxes.json")
    private fun kitFile(ctx: Context) = File(ctx.filesDir, "kits.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(ctx.assets.open("bank_index.json").bufferedReader().readText()).getJSONObject("synths")
            val m = HashMap<String, String>()
            for (k in o.keys()) m[k] = o.getString(k)
            synths = m
        } catch (e: Exception) { synths = emptyMap() }
        try {
            val f = boxFile(ctx)
            if (f.exists()) {
                val a = JSONObject(f.readText()).getJSONArray("boxes")
                for (i in 0 until a.length()) {
                    val b = a.getJSONObject(i)
                    val s = LinkedHashSet<String>()
                    val fs = b.getJSONArray("files")
                    for (k in 0 until fs.length()) s.add(fs.getString(k))
                    user[b.getString("name")] = s
                }
            }
        } catch (e: Exception) {}
        try {
            val f = kitFile(ctx)
            if (f.exists()) {
                val a = JSONObject(f.readText()).getJSONArray("kits")
                for (i in 0 until a.length()) {
                    val k = a.getJSONObject(i)
                    val sl = k.getJSONArray("slots")
                    kits.add(Kit(k.getString("name"), Array(SLOTS) { if (it < sl.length()) sl.getString(it) else "" }))
                }
            }
        } catch (e: Exception) {}
    }

    private fun saveBoxes(ctx: Context) {
        val a = JSONArray()
        for ((n, s) in user) a.put(JSONObject().put("name", n).put("files", JSONArray(s.toList())))
        SafeWrite.text(boxFile(ctx), JSONObject().put("boxes", a).toString())
    }

    fun saveKits(ctx: Context) {
        val a = JSONArray()
        for (k in kits) a.put(JSONObject().put("name", k.name).put("slots", JSONArray(k.slots.toList())))
        SafeWrite.text(kitFile(ctx), JSONObject().put("kits", a).toString())
    }

    // ---------------- the tree ----------------

    /** the kits that come with the app (their first 8 slots) */
    fun builtInKits(): List<Kit> {
        val l = ArrayList<Kit>()
        for (k in MakerBank.kits) l.add(Kit(k, Array(SLOTS) { if (it < 8) MakerBank.fileName(k, MakerBank.pieces[it]) else "" }, true))
        l.add(Kit("Rock Heavy", Array(SLOTS) { if (it < RockKit.pads.size) RockKit.fileName(RockKit.pads[it]) else "" }, true))
        l.add(Kit("Electronic", Array(SLOTS) { if (it < DrumKit.padKit.size) DrumKit.fileName(DrumKit.padKit[it]) else "" }, true))
        return l
    }

    fun tree(ctx: Context, store: Store): List<Box> {
        load(ctx)
        val syn = synths ?: emptyMap()
        val engines = syn.values.distinct()
        val l = ArrayList<Box>()
        l.add(Box("Synths", engines.map { en -> Box(en) { syn[it] == en } }))
        val kitBoxes = ArrayList<Box>()
        for (k in MakerBank.kits) kitBoxes.add(Box(k) { it.startsWith("$k ") })
        kitBoxes.add(Box("Rock Heavy") { it.startsWith("Rock ") })
        kitBoxes.add(Box("Electronic") { it.startsWith("Drum_") })
        for (k in kits) { val set = k.slots.filter { it.isNotEmpty() }.toSet(); kitBoxes.add(Box(k.name) { it in set }) }
        l.add(Box("Drum Kits", kitBoxes))
        l.add(Box("Piano · Flute · Sax") { it.startsWith("Grand Piano ") || it.startsWith("Flute ") || it.startsWith("Tenor Sax ") })
        val edits = File(ctx.filesDir, "drumedit")
        l.add(Box("Drum Edits") { File(edits, "$it.json").exists() })
        l.add(Box("My Recordings") { store.categoryOf(it) == "Recorded" })
        for ((n, s) in user) l.add(Box(n, user = true) { it in s })
        return l
    }

    // ---------------- the user's boxes ----------------

    fun userBoxes(): List<String> = user.keys.toList()

    fun inBox(box: String, name: String) = user[box]?.contains(name) == true

    fun newBox(ctx: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || user.containsKey(n)) return false
        user[n] = LinkedHashSet()
        saveBoxes(ctx)
        return true
    }

    fun renameBox(ctx: Context, old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty() || (n != old && user.containsKey(n))) return false
        val m = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((k, v) in user) m[if (k == old) n else k] = v
        user.clear(); user.putAll(m)
        saveBoxes(ctx)
        return true
    }

    fun deleteBox(ctx: Context, name: String) {
        user.remove(name)
        saveBoxes(ctx)
    }

    fun setIn(ctx: Context, box: String, name: String, on: Boolean) {
        val s = user[box] ?: return
        if (on) s.add(name) else s.remove(name)
        saveBoxes(ctx)
    }

    /** a sound renamed in the Maker Bank: its boxes and kits follow */
    fun rename(ctx: Context, old: String, new: String) {
        load(ctx)
        var b = false
        for (s in user.values) if (s.remove(old)) { s.add(new); b = true }
        if (b) saveBoxes(ctx)
        var k = false
        for (kit in kits) for (i in kit.slots.indices) if (kit.slots[i] == old) { kit.slots[i] = new; k = true }
        if (k) saveKits(ctx)
    }

    // ---------------- the user's kits ----------------

    fun kit(name: String): Kit? = kits.firstOrNull { it.name == name }

    fun saveKit(ctx: Context, name: String, slots: Array<String>): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val k = kit(n)
        if (k != null) for (i in 0 until SLOTS) k.slots[i] = slots.getOrElse(i) { "" }
        else kits.add(Kit(n, Array(SLOTS) { slots.getOrElse(it) { "" } }))
        saveKits(ctx)
        return true
    }

    fun deleteKit(ctx: Context, name: String) {
        kits.removeAll { it.name == name }
        saveKits(ctx)
    }
}

/** writes a small text file through a temporary one (a cut write leaves the old file) */
object SafeWrite {
    fun text(f: File, s: String) {
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeText(s)
        if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
    }
}
