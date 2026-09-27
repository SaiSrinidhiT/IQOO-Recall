package com.hackathon.recall.ingest

import com.hackathon.recall.ingest.TripClusterer.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripClustererTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    // Home: Hyderabad on many days. Trip 1: Goa (~560 km), 3 days. Trip 2: Vizag, later.
    private val home = (0 until 20).map { Point("home$it", it * day, 17.385 + it * 0.0001, 78.486) }
    private val goa = (0 until 6).map { Point("goa$it", 30 * day + it * 10 * hour, 15.49, 73.82) }
    private val vizag = (0 until 4).map { Point("vizag$it", 60 * day + it * hour, 17.69, 83.22) }

    @Test
    fun `away photos split into trips by time gaps`() {
        val trips = TripClusterer.cluster(home + goa + vizag)
        assertEquals(10, trips.size)
        assertEquals(setOf(30 * day), goa.map { trips.getValue(it.uri) }.toSet())
        assertEquals(setOf(60 * day), vizag.map { trips.getValue(it.uri) }.toSet())
        assertTrue(home.none { it.uri in trips })
    }

    @Test
    fun `home is where photos were taken on the most days, not the busiest single day`() {
        val wedding = (0 until 200).map { Point("w$it", 90 * day + it * 60_000, 16.5, 80.6) }
        val (lat, lon) = TripClusterer.home(home + wedding)
        assertTrue(TripClusterer.distanceKm(lat, lon, 17.385, 78.486) < 5)
    }

    @Test
    fun `too few photos, redacted locations and short outings are not trips`() {
        val twoPhotos = (0 until 2).map { Point("t$it", 40 * day + it * hour, 15.49, 73.82) }
        val redacted = (0 until 5).map { Point("r$it", 50 * day + it * hour, 0.0, 0.0) }
        val nearby = (0 until 5).map { Point("n$it", 70 * day + it * hour, 17.45, 78.55) } // ~10 km away
        assertTrue(TripClusterer.cluster(home + twoPhotos + redacted + nearby).isEmpty())
    }
}
