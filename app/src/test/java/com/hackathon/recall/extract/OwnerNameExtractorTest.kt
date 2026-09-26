package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerNameExtractorTest {
    private fun extract(text: String, type: DocType) = OwnerNameExtractor.extract(text.trimIndent().lines(), type)

    @Test
    fun `reads the name above the date of birth on an Aadhaar front`() {
        val front = """
            भारत सरकार
            Government of India
            వెంకట రావు
            Venkata Rao
            DOB: 01/01/1985
            Male
            1234 5678 9012
        """
        assertEquals("Venkata Rao", extract(front, DocType.AADHAAR))
    }

    /** The bug: a town on the address side matched "a line of letters above the DOB" just as well as a name. */
    @Test
    fun `ignores a town sitting in the address block`() {
        val back = """
            Address:
            S/O Ramesh Babu, 2-45
            Main Road
            Ongole
            Prakasam, Andhra Pradesh
            Pin code: 523001
            DOB: 01/01/1985
        """
        assertNull(extract(back, DocType.AADHAAR))
    }

    @Test
    fun `ignores a town beside Aadhaar's own back-of-card field labels`() {
        val back = """
            VTC: Ongole
            PO: Ongole
            District: Prakasam
            State: Andhra Pradesh
            DOB: 01/01/1985
        """
        assertNull(extract(back, DocType.AADHAAR))
    }

    @Test
    fun `ignores a place on a line labelled Name inside an address`() {
        val text = """
            Permanent Address
            Name
            Ongole
            Andhra Pradesh
        """
        assertNull(extract(text, DocType.AADHAAR))
    }

    @Test
    fun `still reads a labelled name away from the address`() {
        val slip = """
            ACME Industries Pvt Ltd
            Employee Name: Ravi Kumar
            Designation: Engineer
            Net Pay: 45000
        """
        assertEquals("Ravi Kumar", extract(slip, DocType.SALARY_SLIP))
    }

    /** A legitimate name must survive an address elsewhere on the same document. */
    @Test
    fun `reads a name when the address is further down the page`() {
        val policy = """
            Policy Holder Name: Lakshmi Devi
            Policy No: 12345
            Sum Insured: 500000
            Communication Address:
            5-12 Gandhi Street
            Guntur, Andhra Pradesh
        """
        assertEquals("Lakshmi Devi", extract(policy, DocType.HEALTH_INSURANCE))
    }

    @Test
    fun `single-word names are still allowed outside an address`() {
        val card = """
            Government of India
            Kalaivani
            DOB: 12/05/1990
            Female
        """
        assertEquals("Kalaivani", extract(card, DocType.AADHAAR))
    }

    /** Receipts print "Name:" too; owners only make sense on identity-bearing documents. */
    @Test
    fun `does not read an owner off a receipt`() {
        val receipt = """
            Byte Biryani
            Customer Name: David Robinson
            Total: 450
        """
        assertNull(extract(receipt, DocType.RECEIPT_INVOICE))
    }
}
