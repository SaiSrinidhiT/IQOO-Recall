package com.hackathon.recall.search

/** Finds a town the user has photos from in a message, tolerating one typo in longer names ("ongol"). */
object PlaceMatcher {
    fun find(query: String, places: List<String>): String? {
        if (places.isEmpty()) return null
        val q = QueryText.normalize(query)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        // Longest names first, so "Navi Mumbai" wins over "Mumbai".
        for (place in places.sortedByDescending { it.length }) {
            val p = QueryText.normalize(place)
            if (p.length < MIN_LENGTH) continue
            if (Regex("(?<![\\p{L}\\p{N}])${Regex.escape(p)}(?![\\p{L}\\p{N}])").containsMatchIn(q)) return place
            if (' ' !in p && p.length >= FUZZY_LENGTH && tokens.any { QueryText.editDistance(it.removeSuffix("s"), p, 1) <= 1 }) return place
        }
        return null
    }

    /** Shorter names ("Un", "Ron") collide with ordinary words. */
    private const val MIN_LENGTH = 4
    private const val FUZZY_LENGTH = 6
}
