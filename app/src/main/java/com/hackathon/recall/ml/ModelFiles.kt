package com.hackathon.recall.ml

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where model files live on the phone: `/sdcard/Android/data/<appId>/files/models/` (brief §3.3).
 * `scripts/push_models.sh` pushes them and writes `manifest.json` with each file's expected size, so a
 * truncated push is reported as corrupt instead of crashing the interpreter.
 */
class ModelFiles(context: Context) {
    val dir: File = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    val siglip = File(dir, SIGLIP)
    val nomic = File(dir, NOMIC)
    /** Extracted Qualcomm AI Hub Genie bundle for Snapdragon 8 Elite Gen 5 (metadata.json + .bin shards). */
    val qwen = File(dir, QWEN_DIR)
    /** Fallback (brief §3.3): a Qwen3-1.7B folder the same SDK can import (AI Hub bundle or GGUF). */
    val qwenFallback = File(dir, QWEN_FALLBACK_DIR)

    @Serializable
    data class Manifest(val files: Map<String, Long> = emptyMap())

    data class Status(val name: String, val path: String, val present: Boolean, val bytes: Long, val problem: String?) {
        val ok: Boolean get() = present && problem == null
    }

    private fun manifest(): Manifest? {
        val f = File(dir, "manifest.json")
        if (!f.exists()) return null
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), f.readText()) }.getOrNull()
    }

    fun statuses(): List<Status> {
        val m = manifest()
        fun check(name: String, f: File): Status {
            if (!f.exists()) return Status(name, f.absolutePath, false, 0, "missing")
            val bytes = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            val expected = m?.files?.get(f.name)
            val problem = when {
                bytes == 0L -> "empty"
                expected != null && expected != bytes -> "size $bytes, expected $expected (corrupt or partial push)"
                f.isDirectory && !File(f, "metadata.json").exists() && f.listFiles().orEmpty().none { it.name.endsWith(".gguf") } ->
                    "no metadata.json or .gguf inside"
                else -> null
            }
            return Status(name, f.absolutePath, true, bytes, problem)
        }
        return listOf(
            check("SigLIP2 vision", siglip),
            check("Nomic Embed Text v1.5", nomic),
            check("Qwen3-4B-Instruct-2507", qwen),
        ) + if (qwenFallback.exists()) listOf(check("Qwen3-1.7B (fallback)", qwenFallback)) else emptyList()
    }

    companion object {
        const val SIGLIP = "siglip2_vision.tflite"
        const val NOMIC = "nomic_embed_text.tflite"
        const val QWEN_DIR = "qwen3_4b_instruct_2507"
        const val QWEN_FALLBACK_DIR = "qwen3_1_7b"
    }
}
