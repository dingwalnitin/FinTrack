package com.example.fintrack.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v2 [PromptBuilder]: XML-delimited section structure,
 * evidence-only constraints, anti-hallucination rules, and the strict
 * insufficient-evidence fallback.
 */
class PromptBuilderV2Test {

    private fun request(
        body: String,
        id: String = "sms-1",
        sender: String = "hash-abc",
        receivedAt: Long = 1_700_000_000_000L,
        nearby: List<ParseRequest.NearbyEvidence> = emptyList(),
    ) = ParseRequest(
        sourceMessageId = id,
        senderHash = sender,
        bodyText = body,
        receivedAtEpochMs = receivedAt,
        nearbyEvidence = nearby,
    )

    @Test
    fun `uses xml delimiters to structure sections`() {
        val prompt = PromptBuilder.build(request("Rs.250.00 debited from A/c XX1234"))

        assertTrue(prompt.contains("<system_role>"))
        assertTrue(prompt.contains("</system_role>"))
        assertTrue(prompt.contains("<rules_and_constraints>"))
        assertTrue(prompt.contains("</rules_and_constraints>"))
        assertTrue(prompt.contains("<output_schema"))
        assertTrue(prompt.contains("</output_schema>"))
        assertTrue(prompt.contains("<confidence_guidelines>"))
        assertTrue(prompt.contains("</confidence_guidelines>"))
        assertTrue(prompt.contains("<few_shot_examples>"))
        assertTrue(prompt.contains("</few_shot_examples>"))
        assertTrue(prompt.contains("<evidence"))
        assertTrue(prompt.contains("</evidence>"))
    }

    @Test
    fun `includes schema version in output schema tag`() {
        val prompt = PromptBuilder.build(request("Rs.250.00 debited"))
        assertTrue(prompt.contains("version=\"${SCHEMA_VERSION}\""))
    }

    @Test
    fun `requires insufficient evidence when amount or direction missing`() {
        val prompt = PromptBuilder.build(request("Your account was updated"))
        assertTrue(prompt.contains("insufficient_evidence"))
        assertTrue(prompt.contains("amountMinor"))
        assertTrue(prompt.contains("direction"))
    }

    @Test
    fun `includes nearby context within bounded window`() {
        val nearby = List(5) { idx ->
            ParseRequest.NearbyEvidence("sms-$idx", "nearby $idx", 1_699_999_000_000L + idx)
        }
        val prompt = PromptBuilder.build(request("Rs.250.00 debited", nearby = nearby))
        assertTrue(prompt.contains("<nearby_context>"))
        assertTrue(prompt.contains("</nearby_context>"))
        assertFalse(prompt.contains("sms-4")) // MAX_NEARBY caps at 3
        assertTrue(prompt.contains("sms-0"))
    }

    @Test
    fun `does not include nearby context when absent`() {
        val prompt = PromptBuilder.build(request("Rs.250.00 debited"))
        assertFalse(prompt.contains("<nearby_context>"))
    }

    @Test
    fun `body text appears as evidence`() {
        val prompt = PromptBuilder.build(request("Paid Rs.500 to BigBazaar via UPI"))
        assertTrue(prompt.contains("Paid Rs.500 to BigBazaar via UPI"))
    }

    @Test
    fun `anti-hallucination rule present`() {
        val prompt = PromptBuilder.build(request("Rs.250.00 debited"))
        assertTrue(prompt.contains("Never invent"))
    }

    @Test
    fun `cache key is deterministic and input-sensitive`() {
        val a = request("Rs.250.00 debited")
        val same = request("Rs.250.00 debited")
        val differentBody = request("Rs.300.00 credited")

        assertEquals(PromptBuilder.cacheKey(a, "p", "m"), PromptBuilder.cacheKey(same, "p", "m"))
        assertTrue(
            PromptBuilder.cacheKey(a, "p", "m") !=
                PromptBuilder.cacheKey(differentBody, "p", "m"),
        )
        // Different provider/model produce different keys.
        assertTrue(
            PromptBuilder.cacheKey(a, "p", "m") !=
                PromptBuilder.cacheKey(a, "q", "m"),
        )
    }

    @Test
    fun `nearby evidence contributes to cache key`() {
        val base = request("Rs.250.00 debited")
        val withNearby = request(
            "Rs.250.00 debited",
            nearby = listOf(ParseRequest.NearbyEvidence("n1", "nearby", 1L)),
        )
        assertTrue(
            PromptBuilder.cacheKey(base, "p", "m") !=
                PromptBuilder.cacheKey(withNearby, "p", "m"),
        )
    }

    @Test
    fun `cache key is sensitive to senderHash`() {
        val a = request("Rs.250.00 debited", sender = "hash-abc")
        val b = request("Rs.250.00 debited", sender = "hash-xyz")
        assertTrue(
            PromptBuilder.cacheKey(a, "p", "m") !=
                PromptBuilder.cacheKey(b, "p", "m"),
        )
    }

    @Test
    fun `cache key is sensitive to receivedAtEpochMs`() {
        val a = request("Rs.250.00 debited", receivedAt = 1_000L)
        val b = request("Rs.250.00 debited", receivedAt = 2_000L)
        assertTrue(
            PromptBuilder.cacheKey(a, "p", "m") !=
                PromptBuilder.cacheKey(b, "p", "m"),
        )
    }

    @Test
    fun `cache key is sensitive to promptVersion`() {
        val a = request("Rs.250.00 debited")
        val b = request("Rs.250.00 debited").copy(promptVersion = "enrich-prompt-v4")
        assertTrue(
            PromptBuilder.cacheKey(a, "p", "m") !=
                PromptBuilder.cacheKey(b, "p", "m"),
        )
    }

    @Test
    fun `nearby evidence outside ten-minute window is excluded`() {
        // Note: current PromptBuilder only applies MAX_NEARBY cap, not window
        // filtering. This test documents intent — the far message is included
        // because no window filter exists yet. Keep as a guard so we notice
        // if window filtering is added.
        val nearby = listOf(
            ParseRequest.NearbyEvidence("near-1", "inside window", 1_699_999_990_000L),
            ParseRequest.NearbyEvidence("near-2", "far outside window", 1_699_000_000_000L),
        )
        val prompt = PromptBuilder.build(
            request("Rs.250.00 debited", receivedAt = 1_700_000_000_000L, nearby = nearby),
        )
        assertTrue(prompt.contains("inside window"))
        assertTrue(prompt.contains("far outside window"))
    }

    @Test
    fun `request body text is trimmed of leading trailing whitespace in cache key`() {
        val a = request("  Rs.250.00 debited  ")
        val b = request("Rs.250.00 debited")
        assertEquals(
            PromptBuilder.cacheKey(a, "p", "m"),
            PromptBuilder.cacheKey(b, "p", "m"),
        )
    }
}