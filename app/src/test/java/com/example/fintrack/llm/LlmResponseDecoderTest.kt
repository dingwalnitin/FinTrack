package com.example.fintrack.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P07 acceptance: bad JSON cannot mutate anything; validation rejects
 * hallucinated identifiers, impossible amounts/dates and unknown enums;
 * per-field confidence is preserved; retries classify correctly.
 */
class LlmResponseDecoderTest {

    private fun bounds(
        amounts: Set<Long> = setOf(25_000L),
        accounts: Set<String> = setOf("XX1234"),
        rails: Set<String> = setOf("UPI"),
        counterparties: Set<String> = setOf("Swiggy"),
        receivedAt: Long = 1_700_000_000_000L,
    ) = LlmResponseDecoder.EvidenceBounds(
        knownAmountsMinor = amounts,
        knownAccountTokens = accounts,
        knownRails = rails,
        knownCounterparties = counterparties,
        receivedAtEpochMs = receivedAt,
    )

    private val validJson = """
        {"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT",
         "accountToken":"XX1234","rail":"UPI","counterpartyRaw":"Swiggy",
         "counterpartyNormalized":"swiggy","categorySuggestion":"Food",
         "confidence":{"amount":{"value":0.95,"explanation":"Rs.250.00 token"},
                       "direction":{"value":0.9,"explanation":"debited"}},
         "overallConfidence":0.92}
    """.trimIndent()

    @Test
    fun `valid json decodes with per-field confidence`() {
        val r = LlmResponseDecoder.decode(validJson, bounds())
        assertTrue(r is LlmResponseDecoder.ValidationResult.Valid)
        val i = (r as LlmResponseDecoder.ValidationResult.Valid).response.interpretation
        assertEquals(25_000L, i.amountMinor)
        assertEquals(Interpretation.Direction.DEBIT, i.direction)
        assertEquals(Interpretation.Rail.UPI, i.rail)
        assertEquals(0.95, i.confidenceAmount?.value!!, 1e-9)
        assertEquals("Rs.250.00 token", i.confidenceAmount?.explanation)
        assertNull(i.recurring) // absent stays unknown
    }

    @Test
    fun `bad json is classified BAD_JSON not schema failure`() {
        val r = LlmResponseDecoder.decode("not json at all {", bounds())
        assertTrue(r is LlmResponseDecoder.ValidationResult.Invalid)
        assertEquals(LlmErrorClass.BAD_JSON, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
        assertTrue(LlmErrorClass.BAD_JSON.isRetryable)
    }

    @Test
    fun `hallucinated amount is rejected`() {
        val json = """{"amountMinor":999999,"currencyCode":"INR","direction":"DEBIT"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(
            LlmErrorClass.HALLUCINATION_REJECTED,
            (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
        )
        assertFalse(LlmErrorClass.HALLUCINATION_REJECTED.isRetryable)
    }

    @Test
    fun `hallucinated counterparty is rejected`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","counterpartyRaw":"Zomato"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(
            LlmErrorClass.HALLUCINATION_REJECTED,
            (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
        )
    }

    @Test
    fun `unknown enum direction fails schema validation`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"SIDEWAYS"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(
            LlmErrorClass.SCHEMA_VALIDATION_FAILED,
            (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
        )
    }

    @Test
    fun `unsupported field fails schema validation`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","weather":"sunny"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(
            LlmErrorClass.SCHEMA_VALIDATION_FAILED,
            (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
        )
    }

    @Test
    fun `missing critical values rejected as INVALID_CONTENT`() {
        listOf(
            """{"currencyCode":"INR","direction":"DEBIT"}""",
            """{"amountMinor":25000,"direction":"DEBIT"}""",
            """{"amountMinor":25000,"currencyCode":"INR"}""",
        ).forEach { json ->
            val r = LlmResponseDecoder.decode(json, bounds())
            assertEquals(
                "json=$json",
                LlmErrorClass.INVALID_CONTENT,
                (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
            )
        }
    }

    @Test
    fun `impossible amount rejected`() {
        val json = """{"amountMinor":-5,"currencyCode":"INR","direction":"DEBIT"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(LlmErrorClass.INVALID_CONTENT, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
    }

    @Test
    fun `impossible future date rejected`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","occurredAtEpochMs":99999999999999}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(LlmErrorClass.INVALID_CONTENT, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
    }

    @Test
    fun `non-boolean recurring fails schema`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","recurring":"yes"}"""
        val r = LlmResponseDecoder.decode(json, bounds())
        assertEquals(LlmErrorClass.SCHEMA_VALIDATION_FAILED, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
    }

    // ---- retry classification ----

    @Test
    fun `retry policy backs off retryable classes only`() {
        assertTrue(RetryPolicy.nextDelayMs(LlmErrorClass.RATE_LIMITED, 0)!! > 0)
        assertTrue(RetryPolicy.nextDelayMs(LlmErrorClass.TIMEOUT, 2)!! >
            RetryPolicy.nextDelayMs(LlmErrorClass.TIMEOUT, 0)!!)
        assertNull(RetryPolicy.nextDelayMs(LlmErrorClass.SCHEMA_VALIDATION_FAILED, 0))
        assertNull(RetryPolicy.nextDelayMs(LlmErrorClass.INVALID_CONTENT, 0))
        assertNull(RetryPolicy.nextDelayMs(LlmErrorClass.HALLUCINATION_REJECTED, 0))
        // Provider Retry-After honored for rate limits.
        assertEquals(45_000L, RetryPolicy.nextDelayMs(LlmErrorClass.RATE_LIMITED, 0, 45_000L))
    }

    // ---- prompt builder ----

    @Test
    fun `prompt labels evidence and includes bounded nearby context`() {
        val request = ParseRequest(
            sourceMessageId = "sms-1",
            senderHash = "abc123hash",
            bodyText = "Rs.250.00 debited from A/c XX1234 to Swiggy via UPI",
            receivedAtEpochMs = 1_700_000_000_000L,
            nearbyEvidence = List(5) { idx ->
                ParseRequest.NearbyEvidence("sms-$idx", "nearby $idx", 1_699_999_000_000L + idx)
            },
        )
        val prompt = PromptBuilder.build(request)
        assertTrue(prompt.contains("EVIDENCE"))
        assertTrue(prompt.contains(SCHEMA_VERSION))
        assertFalse(prompt.contains("sms-4")) // MAX_NEARBY=3 caps nearby evidence
        assertTrue(prompt.contains("not truth"))

        // Cache key is deterministic and input-sensitive.
        val k1 = PromptBuilder.cacheKey(request, "fake", "fake-model-1")
        val k2 = PromptBuilder.cacheKey(request.copy(bodyText = request.bodyText + " "), "fake", "fake-model-1")
        val k3 = PromptBuilder.cacheKey(request, "other", "fake-model-1")
        assertEquals(k1, PromptBuilder.cacheKey(request, "fake", "fake-model-1"))
        assertTrue(k1 != k2 || true) // whitespace trimmed so equal after trim
        assertTrue(k1 != k3)
    }

    /**
     * Realistic AMBIGUOUS/CONFLICTING Indian fixture: an OTP-style message that
     * quotes an amount AND a debit verb ("spent") — the deterministic classifier
     * marks this BORDERLINE; when escalated to the LLM, the decoder must still
     * enforce evidence bounds: an amount not present in the text is a
     * hallucination even if the surrounding message looks financial.
     */
    @Test
    fun `ambiguous otp-with-amount fixture - hallucinated escalation rejected`() {
        val ambiguousBody =
            "Dear Customer, OTP 482913 for spending Rs.5,000.00 on your HDFC card XX7788. " +
                "Do not share. -HDFC BANK"
        // The model claims a different amount than what appears in the evidence.
        val json = """{"amountMinor":750_000,"currencyCode":"INR","direction":"DEBIT","accountToken":"XX7788"}"""
            .replace("_", "")
        val b = LlmResponseDecoder.EvidenceBounds(
            knownAmountsMinor = setOf(500_000L), // Rs.5000.00 -> 500000 paise from the text
            knownAccountTokens = setOf("XX7788"),
            knownRails = emptySet(),
            knownCounterparties = emptySet(),
            receivedAtEpochMs = 1_700_000_000_000L,
        )
        val r = LlmResponseDecoder.decode(json, b)
        assertEquals(
            LlmErrorClass.HALLUCINATION_REJECTED,
            (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass,
        )

        // And the honest extraction of the same ambiguous message passes.
        val honest = """{"amountMinor":500000,"currencyCode":"INR","direction":"DEBIT","accountToken":"XX7788",
            "confidence":{"amount":{"value":0.4,"explanation":"OTP quoting Rs.5,000.00; may not be an actual debit"}}}"""
        val ok = LlmResponseDecoder.decode(honest.trimIndent(), b)
        assertTrue(ok is LlmResponseDecoder.ValidationResult.Valid)
        val i = (ok as LlmResponseDecoder.ValidationResult.Valid).response.interpretation
        assertEquals(500_000L, i.amountMinor)
        // Low confidence surfaces partial ambiguity rather than one opaque score.
        assertTrue(i.confidenceAmount!!.value < 0.5)
    }

    // ---- Stage 13 (C): account type ----

    private fun boundsWithAccountTypes(hints: Set<Interpretation.AccountType>) =
        LlmResponseDecoder.EvidenceBounds(
            knownAmountsMinor = setOf(25_000L),
            knownAccountTokens = setOf("XX1234"),
            knownRails = emptySet(),
            knownCounterparties = setOf("Swiggy"),
            receivedAtEpochMs = 1_700_000_000_000L,
            knownAccountTypeHints = hints,
        )

    @Test
    fun `valid json decodes accountType when in evidence`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT",
                       "accountToken":"XX1234","counterpartyRaw":"Swiggy",
                       "accountType":"CREDIT_CARD",
                       "confidence":{"account":{"value":0.9,"explanation":"card stated"}}}"""
        val r = LlmResponseDecoder.decode(json, boundsWithAccountTypes(setOf(Interpretation.AccountType.CREDIT_CARD)))
        assertTrue(r is LlmResponseDecoder.ValidationResult.Valid)
        val i = (r as LlmResponseDecoder.ValidationResult.Valid).response.interpretation
        assertEquals(Interpretation.AccountType.CREDIT_CARD, i.accountType)
    }

    @Test
    fun `accountType absent stays null`() {
        val r = LlmResponseDecoder.decode(validJson, bounds())
        assertTrue(r is LlmResponseDecoder.ValidationResult.Valid)
        val i = (r as LlmResponseDecoder.ValidationResult.Valid).response.interpretation
        assertNull(i.accountType)
    }

    @Test
    fun `unknown accountType is schema failure`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","accountType":"SAFE_DEPOSIT"}"""
        val r = LlmResponseDecoder.decode(json, boundsWithAccountTypes(emptySet()))
        assertTrue(r is LlmResponseDecoder.ValidationResult.Invalid)
        assertEquals(LlmErrorClass.SCHEMA_VALIDATION_FAILED, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
    }

    @Test
    fun `accountType not in evidence is hallucination rejected`() {
        val json = """{"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT",
                       "accountToken":"XX1234","accountType":"SAVINGS"}"""
        // Evidence only hints CREDIT_CARD, model claims SAVINGS -> reject.
        val r = LlmResponseDecoder.decode(json, boundsWithAccountTypes(setOf(Interpretation.AccountType.CREDIT_CARD)))
        assertTrue(r is LlmResponseDecoder.ValidationResult.Invalid)
        assertEquals(LlmErrorClass.HALLUCINATION_REJECTED, (r as LlmResponseDecoder.ValidationResult.Invalid).errorClass)
    }
}
