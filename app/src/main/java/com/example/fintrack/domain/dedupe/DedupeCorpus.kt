package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.model.DedupeVerdict

/**
 * P09 #6: false-merge / false-split evaluation corpora.
 *
 * Each case is a pair of [Candidate]s plus the expected verdict. The
 * diagnostic [notes] field documents *why* a pair landed in its band so
 * regressions are quick to triage.
 *
 * Conventions:
 *  - amountMinor is the absolute value in paise (INR) or cents (USD).
 *  - occurredAtEpochMs is millis since epoch.
 *  - rail matches parser Rail names.
 *  - "ambiguous same-value purchases" deliberately go to REVIEW; they
 *    must NOT auto-merge (P09 #4).
 */
object DedupeCorpus {

    const val VERSION = "dedup-corpus-v1"

    data class Case(
        val id: String,
        val description: String,
        val a: Candidate,
        val b: Candidate,
        val expected: DedupeVerdict,
        val notes: String,
    )

    val ALL: List<Case> = listOf(
        // ---- AUTO_MERGE: strong reference id + same amount + same rail + same account ----
        Case(
            id = "am-upi-ref-equal",
            description = "Two SMS about the same UPI txn, same ref id.",
            a = Candidate(
                eventId = "eA", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = "418293746512",
                counterpartyNormalized = "swiggy",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = "418293746512",
                counterpartyNormalized = "swiggy",
                cardMask = null, occurredAtEpochMs = 1_700_000_060_000L,
            ),
            expected = DedupeVerdict.AUTO_MERGE,
            notes = "Same UPI ref id; rail + amount + account + merchant all match.",
        ),
        // ---- REVIEW: same-amount purchases close in time, no ref id (the canonical false-merge trap) ----
        Case(
            id = "rv-same-amount-coffee",
            description = "Two INR 99 coffee purchases 4 minutes apart, no ref id.",
            a = Candidate(
                eventId = "eA", amountMinor = 9_900L, currencyCode = "INR",
                direction = "DEBIT", rail = "CARD_POS", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "blue tokai coffee",
                cardMask = "1234", occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 9_900L, currencyCode = "INR",
                direction = "DEBIT", rail = "CARD_POS", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "blue tokai coffee",
                cardMask = "1234", occurredAtEpochMs = 1_700_000_240_000L,
            ),
            expected = DedupeVerdict.REVIEW,
            notes = "Same amount/card/merchant, but no ref id — user must decide.",
        ),
        // ---- REVIEW: different merchants same amount, no ref id ----
        Case(
            id = "rv-different-merchant-same-amount",
            description = "INR 250 to two different merchants same hour.",
            a = Candidate(
                eventId = "eA", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "merchant-a",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "merchant-b",
                cardMask = null, occurredAtEpochMs = 1_700_000_300_000L,
            ),
            expected = DedupeVerdict.REVIEW,
            notes = "Same amount/rail/account, no ref, different merchants — ambiguous.",
        ),
        // ---- REJECT: different accounts ----
        Case(
            id = "rj-different-account",
            description = "Two events with same ref id but different accounts.",
            a = Candidate(
                eventId = "eA", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = "418293746512",
                counterpartyNormalized = "swiggy",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc2",
                refId = "418293746512",
                counterpartyNormalized = "swiggy",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            expected = DedupeVerdict.REJECT,
            notes = "Same ref id but different accounts — never auto-merge across accounts.",
        ),
        // ---- REJECT: different currencies ----
        Case(
            id = "rj-currency-mismatch",
            description = "INR vs USD on the same ref id.",
            a = Candidate(
                eventId = "eA", amountMinor = 25_000L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = "418293746512",
                counterpartyNormalized = "merchant",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 25_000L, currencyCode = "USD",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = "418293746512",
                counterpartyNormalized = "merchant",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            expected = DedupeVerdict.REJECT,
            notes = "Same numeric amount in different currencies — different events.",
        ),
        // ---- AUTO_MERGE: bank rail ref + same account + same amount + same direction ----
        Case(
            id = "am-neft-ref",
            description = "Two NEFT credits with the same UTR.",
            a = Candidate(
                eventId = "eA", amountMinor = 150_000L, currencyCode = "INR",
                direction = "CREDIT", rail = "NEFT", accountId = "acc1",
                refId = "UTR778899112233",
                counterpartyNormalized = "acme pvt ltd",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 150_000L, currencyCode = "INR",
                direction = "CREDIT", rail = "NEFT", accountId = "acc1",
                refId = "UTR778899112233",
                counterpartyNormalized = "acme pvt ltd",
                cardMask = null, occurredAtEpochMs = 1_700_000_120_000L,
            ),
            expected = DedupeVerdict.AUTO_MERGE,
            notes = "NEFT UTR is the durable cross-rail key for the same transfer.",
        ),
        // ---- REVIEW: same merchant same amount, 1 day apart, no ref ----
        Case(
            id = "rv-same-merchant-day-apart",
            description = "Two INR 99 subscriptions a day apart with no ref.",
            a = Candidate(
                eventId = "eA", amountMinor = 9_900L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "netflix",
                cardMask = null, occurredAtEpochMs = 1_700_000_000_000L,
            ),
            b = Candidate(
                eventId = "eB", amountMinor = 9_900L, currencyCode = "INR",
                direction = "DEBIT", rail = "UPI", accountId = "acc1",
                refId = null,
                counterpartyNormalized = "netflix",
                cardMask = null, occurredAtEpochMs = 1_700_086_400_000L,
            ),
            expected = DedupeVerdict.REVIEW,
            notes = "Same merchant + amount + rail + account, but no ref id and a day apart.",
        ),
    )
}
