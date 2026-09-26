package com.hackathon.recall.ml

/** Compute unit that actually executed the last inference (not the one requested). */
enum class Backend { NPU, GPU, CPU, UNAVAILABLE }

/** Every on-device embedder sits behind this; implementations load once, stay warm, and never run on the main thread. */
interface EmbeddingModel<in I> {
    suspend fun embed(input: I): FloatArray
    val backend: Backend
    val lastLatencyMs: Long
}
