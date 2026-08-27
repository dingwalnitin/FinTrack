package com.example.fintrack.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * P07: strict typed decoder + validator for model output.
 *
 * Validation happens BEFORE any persistence. Rejections are classified:
 *  - BAD_JSON: not parseable JSON at all (bounded retry allowed)
 *  - SCHEMA_VALIDATION_FAILED: wrong types / unknown enums / unsupported fields
 *  - INVALID_CONTENT: missing critical values, impossible dates/amounts
 *  - HALLUCINATION_REJECTED: identifiers not present in the supplied evidence
 *
 * The decoder never guesses: absent fields stay null; unknown enum values and
 * unsupported extra fields are hard failures, not silent drops.
 */
object LlmResponseDecoder {

    /** Tokens from the evidence the model is allowed to echo back. */
    data class EvidenceBounds(
        /** Amount tokens seen in the message, e.g. "250.00" minor-unit candidates. */
        val knownAmountsMinor: Set<Long> = emptySet(),
        /** Account/card mask suffixes present in the message. */
        val knownAccountTokens: Set<String> = emptySet(),
        /** Counterparty names appearing in the message. */
        val knownCounterparties: Set<String> = emptySet(),
        /** Rail keywords present in the message. */
        val knownRails: Set<String> = emptySet(),
        /** Earliest plausible event time (message received time). */
        val receivedAtEpochMs: Long = Long.MAX_VALUE,
    )

    sealed interface ValidationResult {
        data class Valid(val response: RawParsed) : ValidationResult
        data class Invalid(val errorClass: LlmErrorClass, val reason: String) : ValidationResult
    }

    /** Parsed-but-unpersisted shape: exactly what [ParseResponse] needs minus metadata. */
    data class RawParsed(
        val interpretation: Interpretation,
        val overallConfidence: Double?,
    )

    private val SUPPORTED_FIELDS = setOf(
        "amountMinor", "currencyCode", "direction", "accountToken", "rail",
        "counterpartyRaw", "counterpartyNormalized", "categorySuggestion",
        "transferTargetToken", "recurring", "emiDetail", "occurredAtEpochMs",
        "confidence", "overallConfidence",
    )

    fun decode(rawJson: String, bounds: EvidenceBounds): ValidationResult {
        val root = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            return ValidationResult.Invalid(LlmErrorClass.BAD_JSON, "not a JSON object")
        }

        // Unsupported fields are rejected — the model must not invent structure.
        val unknown = root.keys().asSequence().filter { it !in SUPPORTED_FIELDS }.toList()
        if (unknown.isNotEmpty()) {
            return ValidationResult.Invalid(
                LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                "unsupported fields: $unknown",
            )
        }

        // ---- critical values ----
        val amountMinor = optLong(root, "amountMinor")
            ?: return ValidationResult.Invalid(LlmErrorClass.INVALID_CONTENT, "missing amountMinor")
        if (amountMinor <= 0 || amountMinor > 10_000_000_000L) {
            return ValidationResult.Invalid(LlmErrorClass.INVALID_CONTENT, "impossible amount: $amountMinor")
        }
        if (bounds.knownAmountsMinor.isNotEmpty() && amountMinor !in bounds.knownAmountsMinor) {
            return ValidationResult.Invalid(
                LlmErrorClass.HALLUCINATION_REJECTED,
                "amount $amountMinor not in evidence",
            )
        }

        val currencyCode = optString(root, "currencyCode")
            ?: return ValidationResult.Invalid(LlmErrorClass.INVALID_CONTENT, "missing currencyCode")
        if (!Regex("^[A-Z]{3}$").matches(currencyCode)) {
            return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, "bad currencyCode")
        }

        val directionName = optString(root, "direction")
            ?: return ValidationResult.Invalid(LlmErrorClass.INVALID_CONTENT, "missing direction")
        val direction = try {
            Interpretation.Direction.valueOf(directionName)
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, "unknown direction '$directionName'")
        }

        // ---- optional typed fields ----
        val railName = optString(root, "rail")
        val rail = if (railName == null) null else try {
            Interpretation.Rail.valueOf(railName)
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, "unknown rail '$railName'")
        }
        if (rail != null && bounds.knownRails.isNotEmpty() && rail.name !in bounds.knownRails &&
            !(rail == Interpretation.Rail.UNKNOWN)
        ) {
            return ValidationResult.Invalid(LlmErrorClass.HALLUCINATION_REJECTED, "rail ${rail.name} not in evidence")
        }

        val accountToken = optString(root, "accountToken")
        if (accountToken != null && bounds.knownAccountTokens.isNotEmpty() &&
            accountToken !in bounds.knownAccountTokens
        ) {
            return ValidationResult.Invalid(
                LlmErrorClass.HALLUCINATION_REJECTED,
                "account token not in evidence",
            )
        }

        val counterpartyRaw = optString(root, "counterpartyRaw")
        if (counterpartyRaw != null && bounds.knownCounterparties.isNotEmpty() &&
            bounds.knownCounterparties.none { it.equals(counterpartyRaw, ignoreCase = true) }
        ) {
            return ValidationResult.Invalid(
                LlmErrorClass.HALLUCINATION_REJECTED,
                "counterparty not in evidence",
            )
        }

        val occurredAt = optLong(root, "occurredAtEpochMs")
        if (occurredAt != null && (occurredAt <= 0 || occurredAt > bounds.receivedAtEpochMs + DAY_MS)) {
            return ValidationResult.Invalid(
                LlmErrorClass.INVALID_CONTENT,
                "impossible date",
            )
        }

        val recurring = if (root.has("recurring") && !root.isNull("recurring")) {
            if (root.get("recurring") !is Boolean) {
                return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, "recurring must be boolean")
            }
            root.getBoolean("recurring")
        } else null

        val emiDetail = optString(root, "emiDetail")

        // ---- confidence block ----
        val conf = root.optJSONObject("confidence")
        fun fieldConf(key: String): FieldConfidence? {
            if (conf == null) return null
            val entry = conf.optJSONObject(key) ?: return null
            val v = entry.optDouble("value", Double.NaN)
            if (v.isNaN() || v < 0.0 || v > 1.0) {
                throw SchemaException("confidence.$key.value out of range")
            }
            return FieldConfidence(value = v, explanation = entry.optString("explanation", ""))
        }

        val interpretation = try {
            Interpretation(
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                direction = direction,
                accountToken = accountToken,
                rail = rail,
                counterpartyRaw = counterpartyRaw,
                counterpartyNormalized = optString(root, "counterpartyNormalized")
                    ?: counterpartyRaw?.lowercase()?.trim(),
                categorySuggestion = optString(root, "categorySuggestion"),
                transferTargetToken = optString(root, "transferTargetToken"),
                recurring = recurring,
                emiDetail = emiDetail,
                occurredAtEpochMs = occurredAt,
                confidenceAmount = fieldConf("amount"),
                confidenceDirection = fieldConf("direction"),
                confidenceAccount = fieldConf("account"),
                confidenceRail = fieldConf("rail"),
                confidenceCounterparty = fieldConf("counterparty"),
                confidenceCategory = fieldConf("category"),
                confidenceTransferTarget = fieldConf("transferTarget"),
                confidenceRecurring = fieldConf("recurring"),
                confidenceEmi = fieldConf("emi"),
            )
        } catch (e: SchemaException) {
            return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, e.message ?: "schema error")
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, e.message ?: "schema error")
        }

        val overall = if (root.has("overallConfidence") && !root.isNull("overallConfidence")) {
            val v = root.getDouble("overallConfidence")
            if (v < 0.0 || v > 1.0) {
                return ValidationResult.Invalid(LlmErrorClass.SCHEMA_VALIDATION_FAILED, "overallConfidence out of range")
            }
            v
        } else null

        return ValidationResult.Valid(RawParsed(interpretation, overall))
    }

    class SchemaException(message: String) : Exception(message)

    private fun optString(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.getString(key) else null

    private fun optLong(o: JSONObject, key: String): Long? =
        if (o.has(key) && !o.isNull(key)) o.getLong(key) else null

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
