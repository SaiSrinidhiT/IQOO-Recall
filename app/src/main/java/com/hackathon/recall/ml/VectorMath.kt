package com.hackathon.recall.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (norm == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0f else (ab / (sqrt(aa) * sqrt(bb))).toFloat()
    }

    /** Mean over the tokens where [mask] is 1, for a row-major [seqLen × dim] token-embedding matrix. */
    fun meanPool(tokens: FloatArray, seqLen: Int, dim: Int, mask: IntArray): FloatArray {
        require(tokens.size == seqLen * dim) { "expected ${seqLen * dim} values, got ${tokens.size}" }
        val out = FloatArray(dim)
        var n = 0
        for (t in 0 until seqLen) {
            if (mask[t] == 0) continue
            n++
            val base = t * dim
            for (d in 0 until dim) out[d] += tokens[base + d]
        }
        if (n > 0) for (d in 0 until dim) out[d] /= n
        return out
    }

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** Jaccard similarity of lowercase word sets (near-duplicate check, brief §8). */
    fun jaccard(a: String, b: String): Double {
        val sa = words(a)
        val sb = words(b)
        if (sa.isEmpty() && sb.isEmpty()) return 1.0
        val inter = sa.count { it in sb }
        return inter.toDouble() / (sa.size + sb.size - inter)
    }

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length > 1 }.toSet()
}
