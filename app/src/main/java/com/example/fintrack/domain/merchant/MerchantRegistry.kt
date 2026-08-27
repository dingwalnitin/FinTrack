package com.example.fintrack.domain.merchant

/**
 * Stage 7 P14 — domain-level merchant registry.
 *
 * A read-only view of confirmed (VPA -> merchant) bindings that the
 * categorization engine consults. The parser-level MerchantRegistry
 * remains the parser-side view; this one is the domain-side view so
 * domain services never depend on parser internals.
 */
class MerchantRegistry(
    private val confirmedVpaToMerchant: Map<String, String> = emptyMap(),
) {
    fun isKnownMerchant(normalizedVpa: String): Boolean =
        confirmedVpaToMerchant.containsKey(normalizedVpa)

    fun merchantFor(normalizedVpa: String): String? = confirmedVpaToMerchant[normalizedVpa]

    companion object {
        fun empty() = MerchantRegistry()
    }
}
