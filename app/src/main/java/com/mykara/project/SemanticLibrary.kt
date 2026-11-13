package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Thư viện từ khóa → hình + hoạt ảnh (data/semantic_library.json). */
class SemanticLibrary(json: JSONObject) {
    class Visual(val id: String, val label: String, val emoji: String, val kind: String = "vector", val variant: String = "")
    class ChainPhase(val at: String, val preset: String)
    class Chain(val id: String, val label: String, val phases: List<ChainPhase>)
    class Entry(
        val tokens: List<String>,
        val visual: String,
        val chain: String,
        val textMode: String,
        val ambiguous: Boolean,
        val confidence: Double,
        val explicitPhases: List<Pair<Int, String>>?,
    )

    val visuals: List<Visual>
    val chains: List<Chain>
    val entries: List<Entry>

    init {
        val v = json.getJSONObject("visuals")
        visuals = v.keys().asSequence().toList().map {
            Visual(
                it, v.getJSONObject(it).getString("label"), v.getJSONObject(it).optString("emoji", ""),
                v.getJSONObject(it).optString("kind", "vector"), v.getJSONObject(it).optString("variant", ""),
            )
        }
        chains = objects(json.getJSONArray("chains")).map { c ->
            Chain(
                c.getString("id"), c.getString("label"),
                objects(c.getJSONArray("phases")).map { ChainPhase(it.getString("at"), it.getString("preset")) },
            )
        }
        entries = objects(json.getJSONArray("entries")).map { e ->
            val amb = e.optBoolean("ambiguous", false)
            val explicit = e.optJSONArray("phases")?.let { arr ->
                objects(arr).map { Pair(it.getInt("atToken"), it.getString("preset")) }
            }
            Entry(
                tokens = e.getString("match").split(Regex("\\s+")).map { normalize(it) },
                visual = e.getString("visual"),
                chain = e.optString("chain", ""),
                textMode = e.optString("textMode", "replace"),
                ambiguous = amb,
                confidence = e.optDouble("confidence", if (amb) 0.4 else 0.8),
                explicitPhases = explicit,
            )
        }.sortedByDescending { it.tokens.size } // cụm dài trước
    }

    fun visual(id: String): Visual? = visuals.firstOrNull { it.id == id }
    fun chain(id: String): Chain? = chains.firstOrNull { it.id == id }

    /** Phase (atToken, preset) cho một mục đã khớp, trải trên span từ. */
    fun phasesFor(entry: Entry, span: Int): JSONArray {
        val out = JSONArray()
        val ex = entry.explicitPhases
        if (ex != null) {
            for ((at, preset) in ex) out.put(JSONObject().put("atToken", at.coerceIn(0, span - 1)).put("preset", preset))
            return out
        }
        val ch = chain(entry.chain)
        return if (ch != null) chainPhases(ch, span) else out
    }

    fun chainPhases(chain: Chain, span: Int): JSONArray {
        val out = JSONArray()
        for (p in chain.phases) {
            out.put(JSONObject().put("atToken", if (p.at == "last") span - 1 else 0).put("preset", p.preset))
        }
        return out
    }

    private fun objects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).map { arr.getJSONObject(it) }

    companion object {
        /** Chuẩn hóa một từ để so khớp: NFC, bỏ dấu câu ở hai đầu, chữ thường. */
        fun normalize(t: String): String =
            Normalizer.normalize(t, Normalizer.Form.NFC).trim { !it.isLetterOrDigit() }.lowercase()
    }
}
