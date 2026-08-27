package com.example.fintrack.domain.service

/**
 * Stage 11 P24 #3 — LLM data-minimization payload transform.
 *
 * Only the minimum transaction-bearing fields are ever handed to a provider:
 * amount, currency, direction hint, rail and the masked account suffix.
 * Everything else is stripped or redacted BEFORE the prompt is built:
 *  - full phone numbers → removed;
 *  - full account numbers → reduced to their last-4 mask;
 *  - unrelated SMS text / sender identifiers → never included;
 *  - OTPs → removed;
 *  - VPA handles are kept ONLY in masked local-part form because they are
 *    transaction-bearing (needed to identify the counterparty), e.g.
 *    `r*********5@ypl`.
 *
 * The transform is deterministic and covered by golden fixtures so the
 * privacy contract cannot drift silently.
 */
object LlmMinimization {

    const val VERSION = "llm-min-v1"

    private val PHONE = Regex("""\b(?:\+91[- ]?)?[6-9][0-9]{9}\b""")
    private val OTP = Regex("""(?i)\bOTP\b[^0-9]{0,12}[0-9]{4,8}""")
    private val ACCOUNT_RUN = Regex("""\b([0-9Xx*]{5,})\b""")
    private val VPA = Regex("""\b([a-zA-Z0-9._-]+)@([a-zA-Z]{2,})\b""")

    /** The exact field set allowed to leave the device. */
    data class MinimizedPayload(
        val amountMinor: Long?,
        val currencyCode: String?,
        val directionHint: String?,      // DEBIT | CREDIT | null (unknown)
        val rail: String?,
        val maskedAccountSuffix: String?, // last 4 digits only
        val maskedVpa: String?,           // r***5@ypl form or null
        val occurredAtEpochMs: Long?,
    ) {
        /**
         * Canonical text actually embedded into the prompt. Nothing outside
         * these fields can appear because this is the only serializer.
         */
        fun toPromptFragment(): String = buildString {
            append("txn{")
            amountMinor?.let { append("amount=$it;") }
            currencyCode?.let { append("cc=$it;") }
            directionHint?.let { append("dir=$it;") }
            rail?.let { append("rail=$it;") }
            maskedAccountSuffix?.let { append("acct=****$it;") }
            maskedVpa?.let { append("vpa=$it;") }
            occurredAtEpochMs?.let { append("at=$it;") }
            append("}")
        }
    }

    /**
     * Build the minimized payload from raw evidence text + already-parsed
     * structured fields. Raw text is used ONLY to extract the masked
     * identifier fragments; it is never itself included in the payload.
     */
    fun minimize(
        rawEvidenceText: String,
        amountMinor: Long?,
        currencyCode: String?,
        directionHint: String?,
        rail: String?,
        occurredAtEpochMs: Long?,
    ): MinimizedPayload {
        val cleaned = OTP.replace(rawEvidenceText, "")
        val phoneFree = PHONE.replace(cleaned, "")

        val maskedSuffix = ACCOUNT_RUN.findAll(phoneFree)
            .map { it.groupValues[1] }
            .filter { it.any { ch -> ch.isDigit() } }
            .map { digits -> digits.filter { ch -> ch.isDigit() } }
            .filter { it.length >= 4 }
            .firstOrNull()
            ?.takeLast(4)

        val maskedVpa = VPA.find(phoneFree)?.let { m ->
            val local = m.groupValues[1]
            val domain = m.groupValues[2]
            if (local.length <= 2) {
                "${local.first()}***@$domain"
            } else {
                "${local.first()}*******${local.last()}@$domain"
            }
        }

        return MinimizedPayload(
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            directionHint = directionHint?.takeIf { it == "DEBIT" || it == "CREDIT" },
            rail = rail,
            maskedAccountSuffix = maskedSuffix,
            maskedVpa = maskedVpa,
            occurredAtEpochMs = occurredAtEpochMs,
        )
    }
}

/**
 * Golden fixtures for the minimization transform (P24 acceptance).
 */
object LlmMinimizationFixtures {

    data class Fixture(
        val name: String,
        val rawText: String,
        val mustNotContain: List<String>,
        val mustContain: List<String>,
    )

    val ALL: List<Fixture> = listOf(
        Fixture(
            "upi debit keeps masked vpa, drops phone",
            "Rs.250 debited to rameshkumar95@ypl on 12/06. Call 9876543210. OTP 483921",
            listOf("9876543210", "483921", "rameshkumar95@ypl"),
            listOf("amount=25000", "vpa=r*******5@ypl"),
        ),
        Fixture(
            "full account number reduced to last4",
            "A/c 1234567890 debited Rs.1,200 IMPS",
            listOf("1234567890", "A/c"),
            listOf("acct=****7890", "rail=IMPS"),
        ),
        Fixture(
            "unrelated sms text never enters payload",
            "Your electricity bill reminder for June",
            listOf("electricity", "reminder"),
            emptyList(),
        ),
    )
}
