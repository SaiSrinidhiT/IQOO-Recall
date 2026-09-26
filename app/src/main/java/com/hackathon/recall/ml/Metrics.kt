package com.hackathon.recall.ml

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Measured latencies for the Benchmark screen (brief F11, §12). Values are real timings, never estimates. */
object Metrics {
    data class Summary(val name: String, val count: Int, val lastMs: Double, val p50Ms: Double, val p95Ms: Double, val avgMs: Double)

    private class Series {
        val values = ArrayDeque<Double>()

        @Synchronized
        fun add(ms: Double) {
            values.addLast(ms)
            if (values.size > 500) values.removeFirst()
        }

        @Synchronized
        fun summary(name: String): Summary {
            val sorted = values.sorted()
            fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
            return Summary(name, values.size, values.lastOrNull() ?: 0.0, pct(0.5), pct(0.95), if (values.isEmpty()) 0.0 else values.average())
        }
    }

    private val series = ConcurrentHashMap<String, Series>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever a value is recorded, so screens can refresh. */
    val version: StateFlow<Int> = _version

    fun record(name: String, ms: Double) {
        series.getOrPut(name) { Series() }.add(ms)
        _version.value++
    }

    inline fun <T> time(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, (System.nanoTime() - start) / 1e6)
        }
    }

    fun snapshot(): List<Summary> = series.entries.map { (k, v) -> v.summary(k) }.sortedBy { it.name }
}
