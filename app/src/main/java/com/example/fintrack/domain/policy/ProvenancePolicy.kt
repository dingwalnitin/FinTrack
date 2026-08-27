package com.example.fintrack.domain.policy

/**
 * Provenance hierarchy (domain-owned; no data-layer dependency).
 * Higher rank outranks lower when merging interpretations:
 *   USER_CONFIRMED > RAW_EVIDENCE(SMS) > IMPORT_FILE > HEURISTIC > MODEL_SUGGESTION
 */
enum class SourceRank(val rank: Int) {
    MODEL_SUGGESTION(1),
    HEURISTIC(2),
    IMPORT_FILE(3),
    RAW_EVIDENCE(4),
    USER_CONFIRMED(5);

    fun outranks(other: SourceRank) = rank >= other.rank
}

/**
 * Provenance resolution policy. Raw SMS evidence outranks inferred values;
 * user-confirmed values outrank heuristic/model suggestions.
 * User corrections survive automated reprocessing unless the user overrides.
 */
object ProvenancePolicy {

    fun rankOf(sourceKind: String): SourceRank =
        when (sourceKind.uppercase()) {
            "SMS", "RAW_EVIDENCE" -> SourceRank.RAW_EVIDENCE
            "MANUAL_ENTRY", "USER_CORRECTION", "USER_CONFIRMED" -> SourceRank.USER_CONFIRMED
            "IMPORT_FILE" -> SourceRank.IMPORT_FILE
            "HEURISTIC" -> SourceRank.HEURISTIC
            "LLM_INTERPRETATION", "MODEL_SUGGESTION" -> SourceRank.MODEL_SUGGESTION
            else -> SourceRank.HEURISTIC // unknown treated as weakest-but-parseable
        }

    /**
     * May an automated reprocess overwrite the stored interpretation?
     * False whenever a user correction exists — corrections are first-class.
     */
    fun mayAutomatedOverwrite(
        storedSourceKind: String,
        hasUserCorrection: Boolean,
        incomingSourceKind: String,
    ): Boolean {
        if (hasUserCorrection) return false
        return rankOf(incomingSourceKind).rank > rankOf(storedSourceKind).rank
    }
}
