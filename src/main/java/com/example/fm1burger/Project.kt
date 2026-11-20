package com.example.fm1burger

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Saves and restores the whole session (tracks, steps, effects, pads) as one small JSON file. */
class Project(private val a: MainActivity) {

    private val file = File(a.filesDir, "project.json")

    /** True when the saved project had sounds on the pads (so a first-start kit must not replace them). */
    var hadPads = false

    fun save() {
        try {
            val e = a.engine
            val o = JSONObject()
            o.put("bpm", e.bpm)
            o.put("master", e.masterVol.toDouble())
            o.put("padVol", e.padVol.toDouble())
            val ts = JSONArray()
            for (t in e.tracks) {
                val j = JSONObject()
                j.put("sample", t.sampleName)
                j.put("vol", t.volume.toDouble())
                j.put("pan", t.pan.toDouble())
                j.put("mute", t.mute)
                j.put("loop", t.loop)
                j.put("pitch", t.pitch)
                j.put("start", t.start.toDouble())
                j.put("end", t.end.toDouble())
                j.put("reverse", t.reverse)
                j.put("boostDb", t.boostDb.toDouble())
                j.put("pingPong", t.pingPong)
                j.put("loopA", t.loopA.toDouble())
                j.put("loopB", t.loopB.toDouble())
                j.put("xfadeMs", t.xfadeMs.toDouble())
                j.put("fitBars", t.fitBars)
                j.put("keepPitch", t.keepPitch)
                j.put("slices", t.slices)
                val slo = JSONArray()
                val slr = JSONArray()
                for (k in 0 until Pat.BAR) {
                    slo.put(t.sliceOf[k])
                    slr.put(t.sliceRev[k])
                }
                j.put("sliceOf", slo)
                j.put("sliceRev", slr)
                val so = JSONArray()
                val sp = JSONArray()
                val sl = JSONArray()
                for (s in t.steps) {
                    so.put(s.on)
                    sp.put(s.pitch)
                    sl.put(s.level.toDouble())
                }
                j.put("stepOn", so)
                j.put("stepPitch", sp)
                j.put("stepLevel", sl)
                val fo = JSONArray()
                val fa = JSONArray()
                val fb = JSONArray()
                val ord = JSONArray()
                val order = t.fx.order
                for (i in 0 until FxChain.N) {
                    fo.put(t.fx.on[i])
                    fa.put(t.fx.a[i].toDouble())
                    fb.put(t.fx.b[i].toDouble())
                    ord.put(order[i])
                }
                j.put("fxOn", fo)
                j.put("fxA", fa)
                j.put("fxB", fb)
                j.put("fxOrder", ord)
                j.put("fxIntensity", t.fx.intensity.toDouble())
                ts.put(j)
            }
            o.put("tracks", ts)
            val ps = JSONArray()
            for (p in e.pads) ps.put(p.name)
            o.put("pads", ps)
            val po = JSONArray()
            val pl = JSONArray()
            for (p in e.pads) {
                val ao = JSONArray()
                val al = JSONArray()
                for (k in 0 until Cfg.STEPS) {
                    ao.put(p.on[k])
                    al.put(p.lvl[k].toDouble())
                }
                po.put(ao)
                pl.put(al)
            }
            o.put("padOn", po)
            o.put("padLvl", pl)
            val pvv = JSONArray()
            val pmm = JSONArray()
            for (p in e.pads) {
                pvv.put(p.volume.toDouble())
                pmm.put(p.mute)
            }
            o.put("padRowVol", pvv)
            o.put("padRowMute", pmm)
            val dfo = JSONArray()
            val dfa = JSONArray()
            val dfb = JSONArray()
            for (i in 0 until FxChain.N) {
                dfo.put(e.drumFx.on[i])
                dfa.put(e.drumFx.a[i].toDouble())
                dfb.put(e.drumFx.b[i].toDouble())
            }
            o.put("drumFxOn", dfo)
            o.put("drumFxA", dfa)
            o.put("drumFxB", dfb)
            o.put("lanes", a.lanes.toJson())
            o.put("mixBits", e.mixRec.bits)
            o.put("clockIdle", e.clockIdle)
            o.put("patLen", Pat.len)
            o.put("latencyMode", e.latencyMode)
            o.put("stemsOn", e.stemsOn)
            o.put("auto", e.auto.toJson())
            SafeIo.writeText(file, o.toString())
        } catch (ex: Exception) {
        }
    }

    fun load() {
        try {
            if (!file.exists()) return
            val e = a.engine
            val o = JSONObject(file.readText())
            a.lanes.fromJson(o.optJSONObject("lanes"))
            val mb = o.optInt("mixBits", MixRecorder.BITS_24)
            e.mixRec.bits = if (mb == 16 || mb == 24 || mb == 32) mb else MixRecorder.BITS_24
            e.clockIdle = o.optBoolean("clockIdle", true)
            Pat.len = o.optInt("patLen", Pat.BAR).let { if (it in Pat.LENGTHS) it else Pat.BAR }
            Pat.view = 0
            val lm = o.optInt("latencyMode", 1).coerceIn(0, 2)
            if (lm != e.latencyMode) e.latencyMode = lm
            e.stemsOn = o.optBoolean("stemsOn", false)
            e.auto.fromJson(o.optJSONObject("auto"))
            e.bpm = o.optInt("bpm", 120)
            e.masterVol = o.optDouble("master", 0.9).toFloat()
            e.padVol = o.optDouble("padVol", 0.8).toFloat()
            val ts = o.optJSONArray("tracks")
            if (ts != null) {
                for (i in 0 until minOf(ts.length(), Cfg.TRACKS)) {
                    val j = ts.getJSONObject(i)
                    val t = e.tracks[i]
                    t.volume = j.optDouble("vol", 0.8).toFloat()
                    t.pan = j.optDouble("pan", 0.0).toFloat()
                    t.mute = j.optBoolean("mute", false)
                    t.loop = j.optBoolean("loop", false)
                    t.pitch = j.optInt("pitch", 0)
                    t.start = j.optDouble("start", 0.0).toFloat()
                    t.end = j.optDouble("end", 1.0).toFloat()
                    t.reverse = j.optBoolean("reverse", false)
                    t.boostDb = j.optDouble("boostDb", 0.0).toFloat().coerceIn(0f, 12f)
                    t.pingPong = j.optBoolean("pingPong", false)
                    t.loopA = j.optDouble("loopA", 0.0).toFloat().coerceIn(0f, 1f)
                    t.loopB = j.optDouble("loopB", 1.0).toFloat().coerceIn(0f, 1f)
                    t.xfadeMs = j.optDouble("xfadeMs", 0.0).toFloat().coerceIn(0f, 100f)
                    t.fitBars = j.optInt("fitBars", 0).let { if (it in intArrayOf(0, 1, 2, 4, 8)) it else 0 }
                    t.keepPitch = j.optBoolean("keepPitch", false)
                    t.slices = j.optInt("slices", 0).let { if (it in intArrayOf(0, 4, 8, 16)) it else 0 }
                    val slo = j.optJSONArray("sliceOf")
                    val slr = j.optJSONArray("sliceRev")
                    for (k in 0 until Pat.BAR) {
                        t.sliceOf[k] = slo?.optInt(k, -1)?.coerceIn(-1, 15) ?: -1
                        t.sliceRev[k] = slr?.optBoolean(k, false) ?: false
                    }
                    val so = j.optJSONArray("stepOn")
                    val sp = j.optJSONArray("stepPitch")
                    val sl = j.optJSONArray("stepLevel")
                    for (k in 0 until Cfg.STEPS) {
                        val s = t.steps[k]
                        if (so != null && k < so.length()) s.on = so.optBoolean(k, false)
                        if (sp != null && k < sp.length()) s.pitch = sp.optInt(k, 0)
                        if (sl != null && k < sl.length()) s.level = sl.optDouble(k, 1.0).toFloat()
                    }
                    val fo = j.optJSONArray("fxOn")
                    val fa = j.optJSONArray("fxA")
                    val fb = j.optJSONArray("fxB")
                    for (k in 0 until FxChain.N) {
                        if (fo != null && k < fo.length()) t.fx.on[k] = fo.optBoolean(k, false)
                        if (fa != null && k < fa.length()) t.fx.a[k] = fa.optDouble(k, FxChain.DEF_A[k].toDouble()).toFloat()
                        if (fb != null && k < fb.length()) t.fx.b[k] = fb.optDouble(k, FxChain.DEF_B[k].toDouble()).toFloat()
                    }
                    t.fx.intensity = j.optDouble("fxIntensity", 1.0).toFloat().coerceIn(1f, 2f)
                    val ord = j.optJSONArray("fxOrder")
                    if (ord != null && ord.length() == FxChain.N - 1) {
                        // a project saved before Echo: keep its order, Echo goes last
                        val arr = IntArray(FxChain.N) { if (it < ord.length()) ord.optInt(it, it) else FxChain.N - 1 }
                        if (arr.sortedArray().contentEquals(IntArray(FxChain.N) { it })) t.fx.order = arr
                    } else if (ord != null && ord.length() == FxChain.N) {
                        val arr = IntArray(FxChain.N) { ord.optInt(it, it) }
                        if (arr.sortedArray().contentEquals(IntArray(FxChain.N) { it })) t.fx.order = arr
                    }
                    val name = j.optString("sample", "")
                    if (name.isNotEmpty() && a.store.file(name).exists()) a.assignTrack(i, a.store.file(name))
                }
            }
            val ps = o.optJSONArray("pads")
            if (ps != null) {
                for (i in 0 until minOf(ps.length(), Cfg.PADS)) {
                    val name = ps.optString(i, "")
                    if (name.isNotEmpty()) hadPads = true
                    if (name.isNotEmpty() && a.store.file(name).exists()) a.assignPad(i, a.store.file(name))
                }
            }
            val po = o.optJSONArray("padOn")
            val pl = o.optJSONArray("padLvl")
            if (po != null) {
                for (i in 0 until minOf(po.length(), Cfg.PADS)) {
                    val ao = po.optJSONArray(i) ?: continue
                    val al = pl?.optJSONArray(i)
                    for (k in 0 until minOf(ao.length(), Cfg.STEPS)) {
                        e.pads[i].on[k] = ao.optBoolean(k, false)
                        if (al != null && k < al.length()) e.pads[i].lvl[k] = al.optDouble(k, 1.0).toFloat()
                    }
                }
            }
            val pvv = o.optJSONArray("padRowVol")
            val pmm = o.optJSONArray("padRowMute")
            for (i in 0 until Cfg.PADS) {
                if (pvv != null && i < pvv.length()) e.pads[i].volume = pvv.optDouble(i, 1.0).toFloat()
                if (pmm != null && i < pmm.length()) e.pads[i].mute = pmm.optBoolean(i, false)
            }
            val dfo = o.optJSONArray("drumFxOn")
            val dfa = o.optJSONArray("drumFxA")
            val dfb = o.optJSONArray("drumFxB")
            for (k in 0 until FxChain.N) {
                if (dfo != null && k < dfo.length()) e.drumFx.on[k] = dfo.optBoolean(k, false)
                if (dfa != null && k < dfa.length()) e.drumFx.a[k] = dfa.optDouble(k, FxChain.DEF_A[k].toDouble()).toFloat()
                if (dfb != null && k < dfb.length()) e.drumFx.b[k] = dfb.optDouble(k, FxChain.DEF_B[k].toDouble()).toFloat()
            }
        } catch (ex: Exception) {
        }
    }
}
