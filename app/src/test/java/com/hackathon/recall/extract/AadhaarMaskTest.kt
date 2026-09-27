package com.hackathon.recall.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/** The reported case: a chat answer printed a full Aadhaar number that the rest of the app masks. */
class AadhaarMaskTest {
    private val valid = "234123412346" // passes Verhoeff
    private val spaced = "2341 2341 2346"

    @Test
    fun `a valid Aadhaar number in an answer is masked, spaced or not`() {
        assertEquals("Srikar's Aadhaar number is XXXX XXXX 2346", EntityExtractor.maskAadhaarIn("Srikar's Aadhaar number is $valid"))
        assertEquals("It is XXXX XXXX 2346.", EntityExtractor.maskAadhaarIn("It is $spaced."))
    }

    @Test
    fun `other 12-digit numbers that fail the checksum are left alone`() {
        val account = "234123412345" // same shape, fails Verhoeff
        assertEquals("Account $account", EntityExtractor.maskAadhaarIn("Account $account"))
    }

    @Test
    fun `text without numbers is unchanged`() {
        assertEquals("Your policy expires on 3 March 2027.", EntityExtractor.maskAadhaarIn("Your policy expires on 3 March 2027."))
    }
}
