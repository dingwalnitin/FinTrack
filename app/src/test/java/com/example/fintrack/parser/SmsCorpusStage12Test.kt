package com.example.fintrack.parser

import com.example.fintrack.parser.fixture.FixtureCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Stage 12 P26 #2 — SMS corpus tests with precision/recall targets.
 *
 * Regression gate over the expanded fixture corpus (v2). Every fixture must
 * classify and extract exactly as expected; any drift is a deterministic
 * failure in CI.
 */
class SmsCorpusStage12Test {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val parser = FinTrackParser(zone)

    @Test
    fun `corpus v2 classification precision and recall meet targets`() {
        var tp = 0; var fp = 0; var fn = 0
        for (f in FixtureCorpus.ALL) {
            val result = parser.classify(f.raw)
            val predictedPos = result.financialClass == FinancialClass.FINANCIAL
            val actualPos = f.expectedClass == FinancialClass.FINANCIAL
            when {
                predictedPos && actualPos -> tp++
                predictedPos && !actualPos -> fp++
                !predictedPos && actualPos -> fn++
            }
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        assertTrue("precision $precision below 0.9", precision >= 0.9)
        assertTrue("recall $recall below 0.9", recall >= 0.9)
    }

    @Test
    fun `corpus v2 extraction matches expectations`() {
        for (f in FixtureCorpus.ALL) {
            val candidate = parser.parse(f.raw)
            when (f.expectParsed) {
                true -> assertNotNull("fixture ${f.id} should parse", candidate)
                false -> assertNull("fixture ${f.id} must NOT parse deterministically", candidate)
                null -> {}
            }
            if (candidate == null) continue
            f.amountMinor?.let { assertEquals("fixture ${f.id} amount", it, candidate.amountMinor) }
            f.direction?.let { assertEquals("fixture ${f.id} direction", it, candidate.direction) }
            f.rail?.let { assertEquals("fixture ${f.id} rail", it, candidate.rail) }
            f.upiVpa?.let { assertEquals("fixture ${f.id} vpa", it, candidate.upiVpa) }
            f.bankReference?.let { assertEquals("fixture ${f.id} ref", it, candidate.bankReference) }
            f.cardMask?.let { assertEquals("fixture ${f.id} cardMask", it, candidate.cardMask) }
            f.creditKind?.let { assertEquals("fixture ${f.id} creditKind", it, candidate.creditKind) }
        }
    }

    @Test
    fun `corpus v2 covers all required rails and credit kinds`() {
        val rails = FixtureCorpus.ALL.mapNotNull { it.rail }.toSet()
        assertTrue("UPI missing", rails.contains(Rail.UPI))
        assertTrue("IMPS missing", rails.contains(Rail.IMPS))
        assertTrue("NEFT missing", rails.contains(Rail.NEFT))
        assertTrue("RTGS missing", rails.contains(Rail.RTGS))
        assertTrue("CARD_POS missing", rails.contains(Rail.CARD_POS))
        assertTrue("CARD_ONLINE missing", rails.contains(Rail.CARD_ONLINE))
        assertTrue("ATM missing", rails.contains(Rail.ATM))

        val creditKinds = FixtureCorpus.ALL.mapNotNull { it.creditKind }.toSet()
        assertTrue("SALARY missing", creditKinds.contains(CreditKind.SALARY))
        assertTrue("INTEREST_CREDIT missing", creditKinds.contains(CreditKind.INTEREST_CREDIT))
        assertTrue("CASHBACK missing", creditKinds.contains(CreditKind.CASHBACK))
        assertTrue("REFUND missing", creditKinds.contains(CreditKind.REFUND))
        assertTrue("P2P_RECEIVE missing", creditKinds.contains(CreditKind.P2P_RECEIVE))
        assertTrue("TRANSFER_IN missing", creditKinds.contains(CreditKind.TRANSFER_IN))
    }

    @Test
    fun `corpus v2 includes malformed and ambiguous fixtures that stay unresolved`() {
        val borderline = FixtureCorpus.ALL.filter { it.expectedClass == FinancialClass.BORDERLINE }
        assertTrue("need borderline fixtures", borderline.size >= 5)
        val malformed = FixtureCorpus.ALL.filter { f ->
            f.raw.contains("Rs.,,,") || f.raw.contains("foo@@bar") || f.expectParsed == false
        }
        assertTrue("need malformed fixtures", malformed.size >= 3)
    }

    @Test
    fun `corpus v2 includes Indian financial message variants`() {
        val indianVariants = FixtureCorpus.ALL.filter { f ->
            f.raw.contains("Rs.") || f.raw.contains("INR") || f.raw.contains("A/c") ||
                f.raw.contains("UPI") || f.raw.contains("VPA") || f.raw.contains("IMPS") ||
                f.raw.contains("NEFT") || f.raw.contains("RTGS")
        }
        assertTrue("need Indian variants", indianVariants.size >= 15)
    }
}