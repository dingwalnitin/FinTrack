package com.example.fintrack.domain.ai

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Stage 10 / P21 — natural-language date-range parsing (module 172).
 *
 * Converts relative phrases ("last month", "this quarter", "past 3 months",
 * "yesterday", "this year") into explicit [LocalDate] ranges using the app
 * ZoneId and a caller-supplied "today" so results are deterministic and
 * testable. Wall-clock strings are never stored; only epoch-day ranges.
 *
 * The parser refuses rather than guesses: an unrecognized phrase returns
 * null and the caller must ask the user or fall back to explicit dates.
 */
object NaturalDateParser {

    data class DateRange(val fromDay: Long, val toDay: Long, val label: String) {
        init {
            require(fromDay <= toDay) { "fromDay after toDay" }
        }
    }

    /**
     * Parse a phrase against [today] in [zone]. Returns null when the phrase
     * is not recognized — unknown stays unknown.
     */
    fun parse(phrase: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DateRange? {
        val p = phrase.trim().lowercase()
        if (p.isEmpty()) return null

        // Explicit ISO / dd-mm-yyyy dates pass through unchanged.
        parseExplicit(p)?.let { return it }

        return when {
            p == "today" -> range(today, today, "today")
            p == "yesterday" -> range(today.minusDays(1), today.minusDays(1), "yesterday")
            p == "this week" || p == "current week" -> {
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                range(start, today, "this week")
            }
            p == "last week" || p == "previous week" -> {
                val thisStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
                val start = thisStart.minusDays(7)
                range(start, thisStart.minusDays(1), "last week")
            }
            p == "this month" || p == "current month" ->
                monthRange(YearMonth.from(today), "this month", today)
            p == "last month" || p == "previous month" ->
                monthRange(YearMonth.from(today).minusMonths(1), "last month", today)
            p == "this quarter" || p == "current quarter" ->
                quarterRange(today, 0, "this quarter")
            p == "last quarter" || p == "previous quarter" ->
                quarterRange(today, 1, "last quarter")
            p == "this year" || p == "current year" ->
                range(today.withDayOfYear(1), today, "this year")
            p == "last year" || p == "previous year" -> {
                val y = today.year - 1
                range(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "last year")
            }
            p == "all time" || p == "everything" ->
                range(LocalDate.of(1970, 1, 1), today, "all time")
            else -> parseRelativeNDays(p, today) ?: parseRelativeNMonths(p, today)
        }
    }

    /** Explicit single-date or dash/range forms: "2026-08-01", "01/08/2026..05/08/2026". */
    private fun parseExplicit(p: String): DateRange? {
        val iso = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(p) ?: return null
        val (y, m, d) = iso.destructured
        val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
        return DateRange(date.toEpochDay(), date.toEpochDay(), p)
    }

    private fun parseRelativeNDays(p: String, today: LocalDate): DateRange? {
        val m = Regex("^past (\\d{1,3}) days?$").find(p) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n !in 1..365) return null
        return range(today.minusDays(n.toLong() - 1), today, "past $n days")
    }

    private fun parseRelativeNMonths(p: String, today: LocalDate): DateRange? {
        val m = Regex("^past (\\d{1,2}) months?$").find(p) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n !in 1..24) return null
        val start = today.minusMonths(n.toLong() - 1).withDayOfMonth(1)
        return range(start, today, "past $n months")
    }

    private fun monthRange(ym: YearMonth, label: String, today: LocalDate): DateRange =
        range(ym.atDay(1), minOf(ym.atEndOfMonth(), today), label)

    /**
     * Indian fiscal quarters are calendar quarters here (Jan-Mar etc.) — the
     * app has no fiscal-year concept and none is invented.
     */
    private fun quarterRange(today: LocalDate, back: Int, label: String): DateRange {
        val qStartMonth = ((today.monthValue - 1) / 3) * 3 + 1
        var start = LocalDate.of(today.year, qStartMonth, 1).minusMonths((back * 3).toLong())
        val end = start.plusMonths(3).minusDays(1)
        if (back > 0 && end.isAfter(today)) {
            // A previous-quarter request can never extend past today.
            start = start
        }
        return range(start, minOf(end, today), label)
    }

    private fun range(from: LocalDate, to: LocalDate, label: String): DateRange =
        DateRange(from.toEpochDay(), to.toEpochDay(), label)
}
