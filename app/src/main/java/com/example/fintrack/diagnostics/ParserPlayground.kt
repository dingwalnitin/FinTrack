package com.example.fintrack.diagnostics

import com.example.fintrack.parser.FinTrackParser
import com.example.fintrack.parser.FinancialClass
import com.example.fintrack.parser.ParseCandidate
import com.example.fintrack.parser.classify.DeterministicSmsClassifier
import com.example.fintrack.parser.fixture.FixtureCorpus
import java.time.ZoneId

/**
 * Stage 12 P25 #2 — parser playground.
 *
 * Runs raw synthetic SMS against the full pipeline (classify → normalize →
 * rail-adapter extraction) without ever touching the production ledger.
 * Every result carries the fixture id (or "synthetic"), the stage-by-stage
 * trace and the extracted candidate (or null when no deterministic rule
 * matched). The playground never writes to Room; it is purely diagnostic.
 *
 * A fixture can be "imported" into the production ledger only by an explicit
 * user action elsewhere (e.g. a manual-entry flow); the playground itself
 * never triggers that path.
 */
class ParserPlayground(
    private val parser: FinTrackParser = FinTrackParser(ZoneId.of("Asia/Kolkata")),
) {

    /** One stage of the pipeline, for diagnostics display. */
    data class StageTrace(
        val stage: String,           // CLASSIFY | NORMALIZE | EXTRACT
        val input: String,
        val output: String,
        val success: Boolean,
        val detail: String?,
    )

    /** Result of running one raw SMS through the full pipeline. */
    data class PlaygroundResult(
        val fixtureId: String?,      // null for ad-hoc synthetic input
        val raw: String,
        val classification: FinancialClass,
        val borderlineReason: String?,
        val normalized: String,
        val candidate: ParseCandidate?,
        val stages: List<StageTrace>,
        val provenance: Map<String, String>, // field -> ruleId
    )

    /**
     * Run one raw SMS through the full pipeline. Never touches Room.
     * Returns the full stage-by-stage trace for diagnostics.
     */
    fun run(raw: String, fixtureId: String? = null): PlaygroundResult {
        val stages = mutableListOf<StageTrace>()

        // 1. classify
        val cls = parser.classify(raw)
        stages += StageTrace(
            stage = "CLASSIFY",
            input = raw,
            output = cls.financialClass.name,
            success = true,
            detail = cls.matchedSignals.joinToString(","),
        )

        // 2. normalize
        val normalized = com.example.fintrack.parser.normalize.TextNormalizer.normalize(raw)
        stages += StageTrace(
            stage = "NORMALIZE",
            input = raw,
            output = normalized,
            success = true,
            detail = "length=${raw.length}→${normalized.length}",
        )

        // 3. extract (only when FINANCIAL)
        val candidate = if (cls.financialClass == FinancialClass.FINANCIAL) {
            parser.parse(raw)
        } else null
        stages += StageTrace(
            stage = "EXTRACT",
            input = normalized,
            output = candidate?.let { "amount=${it.amountMinor} direction=${it.direction} rail=${it.rail}" }
                ?: "null (no deterministic rule matched)",
            success = candidate != null,
            detail = candidate?.let { "confidence=${it.classificationConfidence}" },
        )

        return PlaygroundResult(
            fixtureId = fixtureId,
            raw = raw,
            classification = cls.financialClass,
            borderlineReason = DeterministicSmsClassifier.borderlineReason(cls)?.name,
            normalized = normalized,
            candidate = candidate,
            stages = stages,
            provenance = candidate?.fieldProvenance?.mapValues { it.value.ruleId } ?: emptyMap(),
        )
    }

    /**
     * Run the full fixture corpus and return per-fixture results plus
     * aggregate precision/recall. This is the regression gate for parser
     * changes: any prompt/rule change that alters a fixture's expected
     * output is flagged here.
     */
    fun runCorpus(): CorpusResult {
        val results = FixtureCorpus.ALL.map { f ->
            run(f.raw, fixtureId = f.id) to f
        }
        var tp = 0; var fp = 0; var fn = 0
        var extractionMatches = 0
        var extractionMismatches = 0
        val mismatches = mutableListOf<String>()
        for ((result, fixture) in results) {
            val predictedPos = result.classification == FinancialClass.FINANCIAL
            val actualPos = fixture.expectedClass == FinancialClass.FINANCIAL
            when {
                predictedPos && actualPos -> tp++
                predictedPos && !actualPos -> fp++
                !predictedPos && actualPos -> fn++
            }
            if (fixture.expectParsed != null) {
                val parsed = result.candidate != null
                if (parsed == fixture.expectParsed) extractionMatches++
                else {
                    extractionMismatches++
                    mismatches += "${fixture.id}: expectedParsed=${fixture.expectParsed} got=$parsed"
                }
            }
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        return CorpusResult(
            fixtureVersion = FixtureCorpus.VERSION,
            total = results.size,
            precision = precision,
            recall = recall,
            extractionMatches = extractionMatches,
            extractionMismatches = extractionMismatches,
            mismatchDetails = mismatches,
            results = results.map { it.first },
        )
    }

    data class CorpusResult(
        val fixtureVersion: String,
        val total: Int,
        val precision: Double,
        val recall: Double,
        val extractionMatches: Int,
        val extractionMismatches: Int,
        val mismatchDetails: List<String>,
        val results: List<PlaygroundResult>,
    ) {
        val regressionFree: Boolean
            get() = extractionMismatches == 0 && precision >= 0.9 && recall >= 0.9
    }
}
