package com.example.fintrack.diagnostics

import com.example.fintrack.parser.FinTrackParser
import com.example.fintrack.parser.fixture.FixtureCorpus
import java.time.ZoneId

/**
 * Stage 12 P25 #4 — fixture diff tooling.
 *
 * Compares the current parser output against a recorded baseline (the
 * "golden" expected outputs in [FixtureCorpus]). Any change in a parser
 * rule, prompt version or normalization that alters a fixture's expected
 * output is flagged as a regression. The diff is deterministic and can be
 * run in CI without a device.
 *
 * The baseline is derived from the fixture expectations themselves; the
 * diff therefore detects *drift* between code and fixture, not between
 * two arbitrary runs.
 */
class FixtureDiff(
    private val parser: FinTrackParser = FinTrackParser(ZoneId.of("Asia/Kolkata")),
) {

    /** One fixture whose actual output differs from its expected baseline. */
    data class DiffEntry(
        val fixtureId: String,
        val field: String,           // classification | parsed | amount | direction | rail | vpa | ref | cardMask | creditKind
        val expected: String,
        val actual: String,
    )

    data class DiffResult(
        val fixtureVersion: String,
        val totalFixtures: Int,
        val diffs: List<DiffEntry>,
    ) {
        val regressionCount: Int get() = diffs.size
        val isClean: Boolean get() = diffs.isEmpty()

        /** Human-readable summary for CI logs. */
        fun summary(): String = buildString {
            appendLine("FixtureDiff $fixtureVersion: $totalFixtures fixtures, $regressionCount regressions")
            if (diffs.isNotEmpty()) {
                diffs.forEach { d ->
                    appendLine("  ${d.fixtureId}.${d.field}: expected=${d.expected} actual=${d.actual}")
                }
            }
        }
    }

    /**
     * Run the diff. Returns a [DiffResult] with one entry per field that
     * differs from the fixture baseline.
     */
    fun diff(): DiffResult {
        val diffs = mutableListOf<DiffEntry>()
        for (f in FixtureCorpus.ALL) {
            val cls = parser.classify(f.raw)
            if (cls.financialClass != f.expectedClass) {
                diffs += DiffEntry(
                    fixtureId = f.id,
                    field = "classification",
                    expected = f.expectedClass.name,
                    actual = cls.financialClass.name,
                )
            }
            val candidate = parser.parse(f.raw)
            val parsed = candidate != null
            if (f.expectParsed != null && parsed != f.expectParsed) {
                diffs += DiffEntry(
                    fixtureId = f.id,
                    field = "parsed",
                    expected = f.expectParsed.toString(),
                    actual = parsed.toString(),
                )
            }
            if (candidate != null) {
                f.amountMinor?.let { exp ->
                    if (candidate.amountMinor != exp) {
                        diffs += DiffEntry(f.id, "amount", exp.toString(), candidate.amountMinor.toString())
                    }
                }
                f.direction?.let { exp ->
                    if (candidate.direction != exp) {
                        diffs += DiffEntry(f.id, "direction", exp.name, candidate.direction?.name ?: "null")
                    }
                }
                f.rail?.let { exp ->
                    if (candidate.rail != exp) {
                        diffs += DiffEntry(f.id, "rail", exp.name, candidate.rail.name)
                    }
                }
                f.upiVpa?.let { exp ->
                    if (candidate.upiVpa != exp) {
                        diffs += DiffEntry(f.id, "vpa", exp, candidate.upiVpa ?: "null")
                    }
                }
                f.bankReference?.let { exp ->
                    if (candidate.bankReference != exp) {
                        diffs += DiffEntry(f.id, "ref", exp, candidate.bankReference ?: "null")
                    }
                }
                f.cardMask?.let { exp ->
                    if (candidate.cardMask != exp) {
                        diffs += DiffEntry(f.id, "cardMask", exp, candidate.cardMask ?: "null")
                    }
                }
                f.creditKind?.let { exp ->
                    if (candidate.creditKind != exp) {
                        diffs += DiffEntry(f.id, "creditKind", exp.name, candidate.creditKind?.name ?: "null")
                    }
                }
            }
        }
        return DiffResult(
            fixtureVersion = FixtureCorpus.VERSION,
            totalFixtures = FixtureCorpus.ALL.size,
            diffs = diffs,
        )
    }
}
