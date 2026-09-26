package com.hackathon.recall.ml

import java.io.InputStream
import java.text.Normalizer

/**
 * BERT uncased WordPiece, matching Hugging Face `BertTokenizerFast` as configured for
 * nomic-ai/nomic-embed-text-v1.5 (BertNormalizer with lowercase + strip accents, BertPreTokenizer,
 * WordPiece with "##" continuation and 100 max chars per word, [CLS] … [SEP] template).
 * Parity with Python is checked by `tools/tokenizer_parity.py` and `WordPieceTokenizerTest`.
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>, val maxLength: Int = 128) {
    val clsId: Int = vocab.getValue("[CLS]")
    val sepId: Int = vocab.getValue("[SEP]")
    val padId: Int = vocab.getValue("[PAD]")
    val unkId: Int = vocab.getValue("[UNK]")

    class Encoding(val ids: IntArray, val mask: IntArray, val length: Int)

    /** Token ids padded to [maxLength]; long input is truncated from the end, like `truncation=True`. */
    fun encode(text: String): Encoding {
        val pieces = wordPieces(text)
        val kept = minOf(pieces.size, maxLength - 2)
        val ids = IntArray(maxLength) { padId }
        val mask = IntArray(maxLength)
        ids[0] = clsId
        for (i in 0 until kept) ids[i + 1] = pieces[i]
        ids[kept + 1] = sepId
        for (i in 0..kept + 1) mask[i] = 1
        return Encoding(ids, mask, kept + 2)
    }

    fun wordPieces(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in basicTokens(text)) out.addAll(wordPiece(word))
        return out
    }

    fun countPieces(text: String): Int = wordPieces(text).size

    /** Normalized words after whitespace/punctuation splitting (the pre-tokenizer output). */
    fun basicTokens(text: String): List<String> {
        val normalized = normalize(text)
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isWhitespace(cp) -> flush(current, tokens)
                isPunctuation(cp) -> {
                    flush(current, tokens)
                    tokens.add(String(Character.toChars(cp)))
                }
                else -> current.appendCodePoint(cp)
            }
        }
        flush(current, tokens)
        return tokens
    }

    private fun flush(sb: StringBuilder, into: MutableList<String>) {
        if (sb.isNotEmpty()) {
            into.add(sb.toString())
            sb.setLength(0)
        }
    }

    private fun wordPiece(word: String): List<Int> {
        val cps = word.codePoints().toArray()
        if (cps.size > MAX_CHARS_PER_WORD) return listOf(unkId)
        val out = ArrayList<Int>(4)
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found = -1
            while (start < end) {
                val sub = String(cps, start, end - start)
                val id = vocab[if (start > 0) "##$sub" else sub]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) return listOf(unkId)
            out.add(found)
            start = end
        }
        return out
    }

    companion object {
        const val MAX_CHARS_PER_WORD = 100

        fun fromVocab(stream: InputStream, maxLength: Int = 128): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(40_000)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { i, token -> vocab[token] = i }
            }
            return WordPieceTokenizer(vocab, maxLength)
        }

        /** Clean text → CJK spacing → NFD + drop Mn (strip accents) → per-char lowercase. */
        fun normalize(text: String): String {
            val cleaned = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp == 0 || cp == 0xFFFD || isControl(cp) -> {}
                    isWhitespace(cp) -> cleaned.append(' ')
                    isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                    else -> cleaned.appendCodePoint(cp)
                }
            }
            val nfd = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            val out = StringBuilder(nfd.length)
            i = 0
            while (i < nfd.length) {
                val cp = nfd.codePointAt(i)
                i += Character.charCount(cp)
                if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
                out.appendCodePoint(Character.toLowerCase(cp))
            }
            return out.toString()
        }

        /** Unicode White_Space, as used by the Rust tokenizers crate (`char::is_whitespace`). */
        fun isWhitespace(cp: Int): Boolean = when (cp) {
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            in 0x2000..0x200A -> true
            else -> false
        }

        /** Categories Cc, Cf, Cs, Co, Cn, except tab/newline/carriage return. */
        fun isControl(cp: Int): Boolean {
            if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return false
            return when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
                else -> false
            }
        }

        fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        fun isCjk(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F || cp in 0x2B920..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
