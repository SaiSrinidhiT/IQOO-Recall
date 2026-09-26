package com.hackathon.recall.search

/** Reciprocal rank fusion: score(d) = Σ 1 / (k + rank), rank starting at 1 (brief §6.2, k = 60). */
object Rrf {
    const val K = 60

    fun fuse(rankings: List<List<Long>>, k: Int = K): List<Pair<Long, Double>> {
        val scores = LinkedHashMap<Long, Double>()
        for (ranking in rankings) {
            ranking.distinct().forEachIndexed { i, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + i + 1) }
        }
        // Stable sort: ties keep first-appearance order, so results are deterministic.
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
