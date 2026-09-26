package com.hackathon.recall.ml

/**
 * Splits OCR text into chunks of at most [maxTokens] WordPiece tokens, with about [overlapTokens]
 * tokens of overlap between neighbours (brief §3.2: ≤ 110 tokens, 20-token overlap). Chunk text keeps
 * the original words so it can also feed full-text search and be shown to the user.
 */
class TextChunker(
    private val tokenizer: WordPieceTokenizer,
    val maxTokens: Int = 110,
    val overlapTokens: Int = 20,
) {
    data class Chunk(val text: String, val tokens: Int)

    fun chunk(text: String): List<Chunk> {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val counts = IntArray(words.size) { tokenizer.countPieces(words[it]) }
        val chunks = ArrayList<Chunk>()
        var start = 0
        while (start < words.size) {
            var end = start
            var total = 0
            while (end < words.size && total + counts[end] <= maxTokens) {
                total += counts[end]
                end++
            }
            if (end == start) { // a single word over the budget; cannot happen for WordPiece (≤ 100 pieces)
                total = counts[start]
                end = start + 1
            }
            if (total > 0) chunks += Chunk(words.subList(start, end).joinToString(" "), total)
            if (end >= words.size) break
            var back = end
            var overlap = 0
            while (back > start + 1 && overlap + counts[back - 1] <= overlapTokens) {
                back--
                overlap += counts[back]
            }
            start = back
        }
        return chunks
    }

    /** Longest prefix of [text], by whole words, that fits in [budget] WordPiece tokens. */
    fun fit(text: String, budget: Int): String {
        val out = StringBuilder()
        var used = 0
        for (w in text.split(WHITESPACE)) {
            if (w.isEmpty()) continue
            val n = tokenizer.countPieces(w)
            if (used + n > budget) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(w)
            used += n
        }
        return out.toString()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
