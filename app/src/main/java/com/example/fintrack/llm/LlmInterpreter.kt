package com.example.fintrack.llm

import com.example.fintrack.domain.model.Message
import com.example.fintrack.domain.model.Transaction

/**
 * LLM boundary. Clients propose interpretations only; they can never mutate
 * financial records — proposals pass through deterministic validation in the
 * application layer before any write.
 */
interface LlmInterpreter {
    suspend fun proposeInterpretation(evidence: Message): Transaction?
}
