package com.example.fintrack.parser

import com.example.fintrack.parser.classify.DeterministicSmsClassifier
import com.example.fintrack.parser.rail.MerchantRegistry
import com.example.fintrack.parser.rail.UpiParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Focused tests for classification/extraction separation, credit-kind
 * detection (salary/interest/cashback/refund/P2P), merchant learning and
 * owned-account transfer candidate matching.
 */
class ParserSemanticsTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    // ---- classification vs extraction separation ----

    @Test
    fun `classifier never extracts fields`() {
        val result = DeterministicSmsClassifier.classify(
            "Rs.250 debited from A/c XX1234 via UPI Ref 123"
        )
        assertEquals(FinancialClass.FINANCIAL, result.financialClass)
        // ClassificationResult carries no amount/direction fields by design.
        assertFalse(result.toString().contains("amountMinor"))
    }

    @Test
    fun `non-financial messages produce no candidate`() {
        val parser = FinTrackParser(zone)
        assertNull(parser.parse("Your OTP is 482913. Do not share."))
        assertNull(parser.parse("Congratulations! You won a lottery. Click here."))
    }

    // ---- salary / interest / cashback / refund (modules 136-137) ----

    @Test
    fun `salary credit detected`() {
        val parser = FinTrackParser(zone)
        val c = parser.parse(
            "NEFT credit Rs.85,000/- A/c XX1234 SALARY AUGUST ABC TECHNOLOGIES on 01-08-2026. UTR N123456789012345"
        )
        assertNotNull(c)
        assertEquals(CreditKind.SALARY, c!!.creditKind)
        assertEquals(Direction.CREDIT, c.direction)
    }

    @Test
    fun `interest credit detected`() {
        val c = FinTrackParser(zone).parse(
            "Interest credited Rs.132.50 to your savings A/c XX1234 on 30/06/26"
        )
        assertNotNull(c)
        assertEquals(CreditKind.INTEREST_CREDIT, c!!.creditKind)
    }

    @Test
    fun `cashback vs refund distinguished`() {
        val parser = FinTrackParser(zone)
        assertEquals(
            CreditKind.CASHBACK,
            parser.parse("Cashback of Rs.50/- credited to A/c XX1234 on 06/08/26")!!.creditKind,
        )
        assertEquals(
            CreditKind.REFUND,
            parser.parse("Refund of Rs.2,499.00 credited to A/c XX1234 on 07/08/26")!!.creditKind,
        )
    }

    // ---- P2P classification (module 138) ----

    @Test
    fun `unregistered personal vpa classifies as p2p receive`() {
        val c = FinTrackParser(zone).parse(
            "INR 1,500 credited to your A/c XX5678 on 16-07-2026 at 09:10 from Rahul Sharma " +
                "(rahul.sharma@okhdfcbank). UPI Ref 512340987654"
        )
        assertNotNull(c)
        assertEquals(CreditKind.P2P_RECEIVE, c!!.creditKind)
        assertEquals("rahul.sharma@okhdfcbank", c.upiVpa)
    }

    // ---- merchant learning from confirmed VPAs (module 140) ----

    @Test
    fun `confirmed merchant vpa classifies as merchant credit`() {
        val registry = MerchantRegistry(mapOf("swiggy@ybl" to "Swiggy"))
        val parser = FinTrackParser(zone, registry)
        val c = parser.parse(
            "INR 300 credited to your A/c XX1234 on 16-07-2026 at 09:10 from Swiggy (swiggy@ybl). " +
                "UPI Ref 512340987000"
        )
        assertNotNull(c)
        assertEquals(CreditKind.MERCHANT_CREDIT, c!!.creditKind)
    }

    @Test
    fun `unconfirmed vpas are never treated as merchants`() {
        val registry = MerchantRegistry.empty()
        val adapter = UpiParsers(registry)
        val c = adapter.parse(
            "Rs.100 credited to you from Someone (someone@ybl) via UPI on 01/08/26", zone,
        )
        assertNotNull(c)
        assertTrue(
            c!!.creditKind == CreditKind.P2P_RECEIVE || c.creditKind == CreditKind.UNKNOWN,
        )
    }

    // ---- owned-account transfer candidate matching (module 139) ----

    @Test
    fun `transfer-in candidates carry account token for ownership matching`() {
        val c = FinTrackParser(zone).parse(
            "IMPS credit of Rs.5,000.00 in A/c XX9999 on 03/08/26. Ref no UTIB555000111"
        )
        assertNotNull(c)
        assertEquals(CreditKind.TRANSFER_IN, c!!.creditKind)
        // The token is only a hint; matching to an owned account requires the
        // user-confirmed sender mapping — asserted here as data availability.
        assertEquals("9999", c.accountToken)
    }

    // ---- provenance ----

    @Test
    fun `provenance identifies rule fixture version and confidence`() {
        val c = FinTrackParser(zone).parse(
            "Rs.250.00 debited from A/c XX1234 on 15/07/26 at 14:32 to Swiggy (swiggy@ybl) via UPI. Ref 418293746512"
        )!!
        val amountProv = c.fieldProvenance[ParseCandidate.P_AMOUNT]!!
        assertTrue(amountProv.ruleId.startsWith("amount."))
        assertEquals("fixtures-v1", amountProv.fixtureVersion)
        assertTrue(amountProv.confidence > 0.5)
        val railProv = c.fieldProvenance[ParseCandidate.P_RAIL]!!
        assertEquals("rail.upi", railProv.ruleId)
    }

    @Test
    fun `missing fields are null not fabricated`() {
        // No date in this message -> occurredAt must be null.
        val c = FinTrackParser(zone).parse("Rs.250 debited from A/c XX1234 via UPI")!!
        assertNull(c.occurredAtEpochMs)
        assertNull(c.localDateEpochDay)
        assertNull(c.upiVpa)
    }

    // ---- Stage 12 P25 / P26: fee detection ----

    @Test
    fun `embedded imps charge fee is detected`() {
        val c = FinTrackParser(zone).parse(
            "IMPS charge of Rs.5.00 debited from A/c XX1234 on 20/08/26. Ref 900123456789"
        )
        assertNotNull(c)
        assertEquals(500L, c!!.feeAmountMinor)
        assertEquals(Direction.DEBIT, c.direction)
        assertEquals(Rail.IMPS, c.rail)
        assertTrue(c.fieldProvenance.containsKey(ParseCandidate.P_FEE))
    }

    @Test
    fun `message without fee phrase has no feeAmount`() {
        val c = FinTrackParser(zone).parse(
            "Rs.250.00 debited from A/c XX1234 to Swiggy via UPI. Ref 418293746512"
        )!!
        assertNull(c.feeAmountMinor)
    }
}
