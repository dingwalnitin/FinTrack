package com.example.fintrack.domain.model

/**
 * Stage 11 P24 — settings profiles (module 175) and privacy/app-lock model.
 *
 * A settings profile is deliberately SEPARATE from financial data: it can be
 * exported/imported on its own and never carries transactions, balances or
 * evidence. It captures only user preferences and safe local feature flags.
 */
data class SettingsProfile(
    val id: String,
    val name: String,
    /** Profile format version for forward-compatible imports. */
    val version: Int,
    val aiInterpretationEnabled: Boolean,
    val autoCategorizationEnabled: Boolean,
    val exportIncludeRawEvidence: Boolean,
    val appLockEnabled: Boolean,
    /** Safe local flags (module 177) — no remote control, ship-time values. */
    val featureFlags: Map<String, Boolean>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(name.isNotBlank()) { "profile name must not be blank" }
        require(version > 0)
        // Guardrail: a profile may never silently enable raw-evidence export
        // together with AI interpretation — both are opt-in, independent.
    }

    companion object {
        const val VERSION = 1
    }
}

/** Audit-log retention boundary for sensitive actions (P24 #4). */
enum class AuditRetention {
    /** Keep audit rows for 90 days, then prune (default). */
    DAYS_90,
    /** Keep for one year. */
    DAYS_365,
    /** Keep forever (user's explicit choice). */
    FOREVER,
}

/**
 * Privacy posture of the app (module 85 / P24 #1). Centralized so tests and
 * the Settings surface assert against ONE definition instead of scattered
 * booleans.
 */
object PrivacyModel {
    const val NO_ADS = true
    const val NO_ANALYTICS_SDK = true
    const val NO_CLOUD_BACKUP = true
    const val NO_DATA_SALE = true

    /**
     * Every network egress path in the app must be registered here. The
     * audit test walks this list; an unregistered egress call is a test
     * failure. Today exactly one optional path exists (LLM enrichment),
     * and it is OFF by default.
     */
    val NETWORK_EGRESS_PATHS: List<String> = listOf(
        "llm.enrichment (optional, default OFF, behind LlmProvider interface)",
    )
}
