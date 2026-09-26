package com.hackathon.recall.extract

/** Verhoeff checksum (dihedral group D5), as used by Aadhaar numbers. */
object Verhoeff {
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val inv = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    fun isValid(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return false
        var c = 0
        for (i in digits.indices) {
            c = d[c][p[i % 8][digits[digits.length - 1 - i] - '0']]
        }
        return c == 0
    }

    /** Check digit to append to [digits] so that the result passes [isValid]. */
    fun checkDigit(digits: String): Int {
        require(digits.all { it in '0'..'9' })
        var c = 0
        for (i in digits.indices) {
            c = d[c][p[(i + 1) % 8][digits[digits.length - 1 - i] - '0']]
        }
        return inv[c]
    }

    /** Aadhaar: 12 digits, first digit 2-9 (UIDAI never issues 0/1 prefixes), Verhoeff-valid. */
    fun isValidAadhaar(digits: String): Boolean =
        digits.length == 12 && digits[0] in '2'..'9' && isValid(digits)
}
