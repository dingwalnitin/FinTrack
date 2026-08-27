package com.example.fintrack.parser

import com.example.fintrack.parser.classify.DeterministicSmsClassifier
import com.example.fintrack.parser.fixture.FixtureCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Golden-fixture test: the whole corpus must classify and (where asserted)
 * extract deterministically. Also computes measurable precision/recall for
 * the classifier over the corpus.
 */
class ParserFixtureTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val parser = FinTrackParser(zone)

    @Test
    fun `classification matches expectations for every fixture`() {
        var tp = 0; var fp = 0; var fn = 0
        for (f in FixtureCorpus.ALL) {
            val result = parser.classify(f.raw)
            assertEquals(
                "fixture ${f.id}: expected ${f.expectedClass} got ${result.financialClass} " +
                    "(signals=${result.matchedSignals})",
                f.expectedClass, result.financialClass,
            )
            // P/R accounting: financial is the positive class.
            val predictedPositive = result.financialClass == FinancialClass.FINANCIAL
            val actualPositive = f.expectedClass == FinancialClass.FINANCIAL
            when {
                predictedPositive && actualPositive -> tp++
                predictedPositive && !actualPositive -> fp++
                !predictedPositive && actualPositive -> fn++
            }
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        assertTrue(
            "classifier precision $precision below 0.9",
            precision >= 0.9,
        )
        assertTrue(
            "classifier recall $recall below 0.9",
            recall >= 0.9,
        )
    }

    @Test
    fun `borderline fixtures report their reason`() {
        for (f in FixtureCorpus.ALL.filter { it.expectedBorderlineReason != null }) {
            val result = parser.classify(f.raw)
            assertEquals(
                "fixture ${f.id}",
                f.expectedBorderlineReason,
                DeterministicSmsClassifier.borderlineReason(result),
            )
        }
    }

    @Test
    fun `extraction matches expectations`() {
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
            f.bankReference?.let {
                assertEquals("fixture ${f.id} ref", it, candidate.bankReference)
            }
            f.cardMask?.let { assertEquals("fixture ${f.id} cardMask", it, candidate.cardMask) }
            f.creditKind?.let {
                assertEquals("fixture ${f.id} creditKind", it, candidate.creditKind)
            }
        }
    }

    @Test
    fun `every extracted field carries provenance`() {
        for (candidate in FixtureCorpus.ALL.mapNotNull { parser.parse(it.raw) }) {
            assertTrue(candidate.fieldProvenance.containsKey(ParseCandidate.P_AMOUNT))
            assertTrue(candidate.fieldProvenance.containsKey(ParseCandidate.P_DIRECTION))
            assertTrue(candidate.fieldProvenance.containsKey(ParseCandidate.P_RAIL))
            for ((key, prov) in candidate.fieldProvenance) {
                assertTrue("$key ruleId blank", prov.ruleId.isNotBlank())
                assertEquals("fixture version mismatch", "fixtures-v1", prov.fixtureVersion)
                assertTrue(prov.confidence in 0.0..1.0)
            }
        }
    }

    @Test
    fun `ambiguous economic meaning stays unresolved not fabricated`() {
        // "Transaction of Rs.500" has no direction verb: no candidate may be produced.
        assertNull(parser.parse("Transaction of Rs.500 on A/c XX1234 dated 12/08/26"))
        // Balance enquiry must not become a transaction.
        assertNull(parser.parse("Avail balance in A/c XX1234 is Rs.12,345.67 as on 09/08/26"))
    }
}
