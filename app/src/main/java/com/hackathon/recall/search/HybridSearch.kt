package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.toSummary
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.ml.ModelManager
import com.hackathon.recall.ml.NomicEmbedder

/**
 * Hybrid retrieval (brief §6.2): FTS over chunks and titles, Nomic cosine over chunk vectors, and a
 * doc-type ranking, fused with RRF (k = 60). Date filters apply afterwards; returns documents.
 */
class HybridSearch(private val repo: DocumentRepository, private val models: ModelManager) {
    data class Hit(val doc: DocumentEntity, val score: Double, val cosine: Float?, val keyword: Boolean)

    suspend fun search(intent: QueryIntent, rawQuery: String, limit: Int = 10, ownerFilter: String? = null, excludeIds: Set<Long> = emptySet()): List<Hit> {
        val start = System.nanoTime()
        val keyword = repo.ftsSearch("$rawQuery ${intent.queryEn}", 50)
        val semantic: List<Pair<Long, Float>> = models.nomic?.let { n ->
            repo.vectorSearch(n.embed(NomicEmbedder.QUERY_PREFIX + intent.queryEn), 50)
        } ?: emptyList()
        val all = repo.all()
        val byType = if (intent.docTypes.isEmpty()) emptyList() else all
            .filter { it.effectiveType() in intent.docTypes }
            .sortedByDescending { it.toSummary().effectiveDate }
            .map { it.id }
        val fused = Rrf.fuse(listOf(keyword, semantic.map { it.first }, byType))

        val cosine = semantic.toMap()
        val keywordSet = keyword.toSet()
        val typeSet = byType.toSet()
        val minCos = models.config.search.minCosine
        val docsById = all.associateBy { it.id }
        val hits = fused.mapNotNull { (id, score) ->
            val doc = docsById[id] ?: return@mapNotNull null
            // The user already said this one is wrong, or has already seen it in the previous reply.
            if (id in excludeIds) return@mapNotNull null
            // Asked for a type: only confident documents of that type. Keyword or meaning matches of other
            // types (a salary slip mentioning "PAN") and unsure guesses were what put wrong cards in answers.
            if (intent.docTypes.isNotEmpty() && doc.effectiveType() !in intent.docTypes) return@mapNotNull null
            // Otherwise a document must match by keyword, or by meaning strongly enough to stand on its own.
            val relevant = id in keywordSet || id in typeSet || (cosine[id] ?: 0f) >= maxOf(minCos, SEMANTIC_ONLY_MIN)
            if (!relevant) return@mapNotNull null
            val eff = doc.toSummary().effectiveDate
            if (intent.dateFrom != null && eff.isBefore(intent.dateFrom)) return@mapNotNull null
            if (intent.dateTo != null && eff.isAfter(intent.dateTo)) return@mapNotNull null
            if (!ownerFilter.isNullOrBlank() && doc.ownerName?.contains(ownerFilter, ignoreCase = true) != true) return@mapNotNull null
            Hit(doc, score, cosine[id], id in keywordSet)
        }.take(limit)
        Metrics.record("query.search", (System.nanoTime() - start) / 1e6)
        return hits
    }

    private companion object {
        /**
         * Nomic cosine a meaning-only match must reach. The config's 0.35 is near the level unrelated
         * texts reach, which let random documents into answers; above this, the match stands on its own.
         */
        const val SEMANTIC_ONLY_MIN = 0.5f
    }
}
