package com.hackathon.recall.ingest

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Groups geotagged photos into trips, fully offline (no reverse geocoding). Home is the ~5 km grid
 * cell the user took photos in on the most different days; a photo is "away" when it is more than
 * [AWAY_KM] from home, and away photos with no gap longer than [GAP_MS] between them form one trip.
 */
object TripClusterer {
    data class Point(val uri: String, val takenAt: Long, val lat: Double, val lon: Double)

    const val AWAY_KM = 40.0
    const val GAP_MS = 36L * 60 * 60 * 1000
    const val MIN_PHOTOS = 3
    private const val CELL_DEG = 0.05
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** uri → trip id (the trip's first photo time, so ids stay stable when later photos are added). */
    fun cluster(points: List<Point>): Map<String, Long> {
        val located = points.filter { it.lat != 0.0 || it.lon != 0.0 }
        if (located.size < MIN_PHOTOS) return emptyMap()
        val home = home(located)
        val away = located.filter { distanceKm(it.lat, it.lon, home.first, home.second) > AWAY_KM }.sortedBy { it.takenAt }
        val trips = mutableListOf<MutableList<Point>>()
        for (p in away) {
            val last = trips.lastOrNull()?.last()
            if (last != null && p.takenAt - last.takenAt <= GAP_MS) trips.last().add(p) else trips.add(mutableListOf(p))
        }
        return trips.filter { it.size >= MIN_PHOTOS }
            .flatMap { trip -> trip.map { it.uri to trip.first().takenAt } }
            .toMap()
    }

    /** Centre of the cell with photos on the most distinct days (one busy day out doesn't make a home). */
    fun home(points: List<Point>): Pair<Double, Double> {
        val byCell = points.groupBy { floor(it.lat / CELL_DEG).toLong() to floor(it.lon / CELL_DEG).toLong() }
        val best = byCell.maxBy { (_, ps) -> ps.map { it.takenAt / DAY_MS }.distinct().size * 10_000 + ps.size }.value
        return best.map { it.lat }.average() to best.map { it.lon }.average()
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }
}
