package com.example.vegetablescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

data class Match(val name: String, val similarity: Float)

/**
 * Everything the app has been taught: vegetable name -> list of example "fingerprints".
 * Saved in the app's private storage, so it survives closing the app.
 */
class KnowledgeStore(context: Context) {

    private val file = File(context.filesDir, "taught.json")
    private val samples = LinkedHashMap<String, MutableList<FloatArray>>()

    init {
        load()
    }

    @Synchronized
    fun add(name: String, embedding: FloatArray) {
        val list = samples.getOrPut(name) { mutableListOf() }
        list.add(embedding)
        if (list.size > MAX_SAMPLES_PER_VEGETABLE) list.removeAt(0)
    }

    @Synchronized
    fun commit() = save()

    @Synchronized
    fun remove(name: String) {
        samples.remove(name)
        save()
    }

    @Synchronized
    fun clear() {
        samples.clear()
        save()
    }

    @Synchronized
    fun names(): List<String> = samples.keys.toList()

    @Synchronized
    fun count(name: String): Int = samples[name]?.size ?: 0

    @Synchronized
    fun isEmpty(): Boolean = samples.isEmpty()

    /** Best matching taught vegetable for this fingerprint, or null if nothing is taught. */
    @Synchronized
    fun bestMatch(embedding: FloatArray): Match? {
        var best: Match? = null
        for ((name, list) in samples) {
            // Average of the top 3 most similar examples is steadier than a single best one
            val top = list.map { cosine(embedding, it) }.sortedDescending().take(3)
            val score = top.average().toFloat()
            if (best == null || score > best.similarity) best = Match(name, score)
        }
        return best
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    private fun save() {
        try {
            val root = JSONObject()
            for ((name, list) in samples) {
                val arr = JSONArray()
                for (v in list) {
                    val inner = JSONArray()
                    for (x in v) inner.put(x.toDouble())
                    arr.put(inner)
                }
                root.put(name, arr)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            for (name in root.keys()) {
                val arr = root.getJSONArray(name)
                val list = mutableListOf<FloatArray>()
                for (i in 0 until arr.length()) {
                    val inner = arr.getJSONArray(i)
                    list.add(FloatArray(inner.length()) { inner.getDouble(it).toFloat() })
                }
                samples[name] = list
            }
        } catch (_: Exception) {
            samples.clear()
        }
    }

    companion object {
        const val MAX_SAMPLES_PER_VEGETABLE = 40
    }
}
