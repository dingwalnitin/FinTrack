package com.example.fintrack.domain.merchant

/**
 * Stage 7 P14 — domain-level merchant normalization helpers.
 *
 * Independent of the parser package so domain services can normalize
 * merchant strings without depending on parser internals. The rules
 * here mirror the parser-level TextNormalizer (lowercase, collapse
 * whitespace, drop common bank-suffix noise) but are deliberately
 * narrower: parser-level normalization also has to preserve amount
 * tokens and rail hints, while merchant normalization only cares
 * about the human surface form.
 */
object MerchantNormalization {

    private val NOISE_TOKENS = setOf(
        "ltd", "limited", "pvt", "private", "india", "ind", "in",
        "co", "company", "corp", "inc",
    )

    /**
     * Normalize a merchant surface form to a stable, comparable key.
     *  - lowercases
     *  - strips common corporate suffixes (Ltd, Pvt, India, …)
     *  - collapses whitespace
     *  - drops most punctuation
     *
     * Empty / null input -> empty string (callers must not invent
     * merchants; uncategorized is the fallback).
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lowered = raw.lowercase()
        // Split on non-alphanumeric boundaries but keep digits.
        val tokens = lowered
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        val pruned = tokens.filterNot { it in NOISE_TOKENS }
        // Always at least one token; if we stripped everything, fall back to
        // the full list to avoid producing an empty key.
        val finalTokens = if (pruned.isEmpty()) tokens else pruned
        return finalTokens.joinToString(" ")
    }
}
