package com.example.fintrack.parser

import com.example.fintrack.domain.model.Message

/** Parser boundary: raw evidence in, structured candidates out. No writes. */
interface EvidenceParser {
    fun parse(evidence: Message): ParsedCandidate
}

data class ParsedCandidate(
    val amountMajor: Double?,
    val currencyCode: String?,
    val counterparty: String?,
)
