package com.hackathon.recall.ingest

import kotlin.math.floor
import kotlin.math.log10

/**
 * Offline "where was this taken": the nearest town from assets/places.tsv (GeoNames, CC BY 4.0),
 * written by tools/places.py. A bigger town wins over a slightly nearer village, so a photo taken on
 * the edge of Ongole is filed under Ongole, not the hamlet next to it.
 */
class PlaceIndex(tsv: String) {
    private class Place(val name: String, val lat: Double, val lon: Double, val pop: Int)

    private val cells: Map<Long, List<Place>> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) null else Place(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toIntOrNull() ?: 0)
        }
        .groupBy { key(floor(it.lat).toInt(), floor(it.lon).toInt()) }

    fun nearest(lat: Double, lon: Double): String? {
        val la = floor(lat).toInt()
        val lo = floor(lon).toInt()
        var best: Place? = null
        var bestScore = Double.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            for (p in cells[key(la + dy, lo + dx)].orEmpty()) {
                val km = TripClusterer.distanceKm(lat, lon, p.lat, p.lon)
                if (km > MAX_KM) continue
                val score = km - SIZE_WEIGHT_KM * log10(p.pop.coerceAtLeast(1).toDouble())
                if (score < bestScore) {
                    bestScore = score
                    best = p
                }
            }
        }
        return best?.name
    }

    private fun key(lat: Int, lon: Int): Long = (lat + 90).toLong() * 1000 + (lon + 180)

    companion object {
        /** Farther than this from any listed town, a photo gets no place name (sea, forest, mountains). */
        const val MAX_KM = 30.0
        /** Each tenfold in population is worth this many km of distance. */
        const val SIZE_WEIGHT_KM = 3.0
    }
}
