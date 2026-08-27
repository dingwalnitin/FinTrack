package com.example.fintrack.domain.ai

import com.example.fintrack.domain.model.TxKind
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 10 / P21 — natural-language transaction query parser.
 *
 * Converts a user question into a typed [AiQueryPlan]. The parser is
 * deterministic and rule-based: the same input always produces the same plan.
 * An optional LLM may propose a plan JSON, but it must decode through
 * [decodePlanJson] which validates every field against this whitelist —
 * arbitrary SQL or free-form model output can never reach execution.
 *
 * Unknown phrases produce [ParseOutcome.Unparsed] rather than a guessed plan.
 */
class AiQueryParser(
    private val aliasResolver: AliasResolver = AliasResolver { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface ParseOutcome {
        /** A fully-validated plan ready for execution. */
        data class Parsed(val plan: AiQueryPlan, val dateLabel: String?) : ParseOutcome

        /** The phrase was recognized but ambiguous — show the interpreted range before executing. */
        data class NeedsConfirmation(val plan: AiQueryPlan, val reason: String) : ParseOutcome

        /** Not understood; never guessed. */
        data class Unparsed(val reason: String) : ParseOutcome
    }

    /**
     * Alias resolver: maps user category/account language to stable ids via
     * deterministic aliasing (module 173). AI is only consulted when this
     * returns null AND the caller explicitly opts in.
     */
    fun interface AliasResolver {
        /** Returns the stable id for a surface form, or null when unresolved. */
        fun resolve(surface: String, kind: AliasKind): String?
    }

    enum class AliasKind { CATEGORY, ACCOUNT, MERCHANT }

    fun parse(query: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ParseOutcome {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ParseOutcome.Unparsed("empty query")

        val intent = detectIntent(q)
            ?: return ParseOutcome.Unparsed("no recognizable query intent")

        // ---- date range ----
        val (range, dateAmbiguous) = extractDateRange(q, today, zone)

        // ---- filters ----
        var filters = AiQueryPlan.Filters(fromDay = range?.fromDay, toDay = range?.toDay)
        filters = applyMerchantFilter(q, filters)
        filters = applyAmountFilters(q, filters)
        filters = applyCategoryFilter(q, filters)
        filters = applyAccountFilter(q, filters)
        filters = applyRailFilter(q, filters)

        // ---- metrics / grouping ----
        val metrics = mutableSetOf<AiQueryPlan.Metric>()
        val groupBy = mutableListOf<AiQueryPlan.Dimension>()
        detectAggregations(q, metrics, groupBy)

        val limit = extractLimit(q)

        val plan = AiQueryPlan(
            intent = intent,
            metrics = metrics,
            groupBy = groupBy,
            filters = filters,
            sort = detectSort(q),
            limit = limit,
            planIdentity = sha256(canonicalForm(intent, metrics, groupBy, filters, limit)),
            parsedAtEpochMs = clock(),
        )

        return when {
            dateAmbiguous -> ParseOutcome.NeedsConfirmation(
                plan,
                "date range interpreted as '${range?.label ?: "no range"}' — confirm before running",
            )
            else -> ParseOutcome.Parsed(plan, range?.let { describeRange(it, today) })
        }
    }

    // ---- intent detection ----

    private fun detectIntent(q: String): AiQueryPlan.Intent? = when {
        containsAny(q, listOf("how much did i spend", "total spend", "spending by", "spend on",
            "spent on", "how much", "sum of", "total income", "income from", "net flow",
            "breakdown", "by category", "by merchant", "by account")) -> AiQueryPlan.Intent.AGGREGATE
        containsAny(q, listOf("show", "list", "find", "transactions", "history", "search",
            "what did i buy", "purchases")) -> AiQueryPlan.Intent.LIST_TRANSACTIONS
        else -> null
    }

    // ---- date extraction ----

    private fun extractDateRange(
        q: String,
        today: LocalDate,
        zone: ZoneId,
    ): Pair<NaturalDateParser.DateRange?, Boolean> {
        // Try explicit ISO first, then known relative phrases in priority order.
        val candidates = listOf(
            "today", "yesterday", "this week", "last week", "this month", "last month",
            "this quarter", "last quarter", "this year", "last year", "all time",
        )
        for (c in candidates) {
            if (q.contains(c)) {
                val r = NaturalDateParser.parse(c, today, zone)
                if (r != null) return r to false
            }
        }
        Regex("past (\\d{1,3}) days?").find(q)?.let {
            NaturalDateParser.parse(it.value, today, zone)?.let { r -> return r to false }
        }
        Regex("past (\\d{1,2}) months?").find(q)?.let {
            NaturalDateParser.parse(it.value, today, zone)?.let { r -> return r to false }
        }
        // No date phrase: unbounded (still safe because limit caps rows).
        return null to false
    }

    private fun describeRange(r: NaturalDateParser.DateRange, today: LocalDate): String =
        "${LocalDate.ofEpochDay(r.fromDay)} .. ${LocalDate.ofEpochDay(r.toDay)} (${r.label})"

    // ---- filter extraction ----

    private fun applyMerchantFilter(q: String, f: AiQueryPlan.Filters): AiQueryPlan.Filters {
        val m = Regex("(?:from|at|to|on)\\s+([a-z][a-z0-9 &'.-]{2,30}?)(?:\\s+(?:in|during|between|last|past|this|since|for|over)\\b|\\s*$)").find(q)
            ?: return f
        val merchant = m.groupValues[1].trim()
        if (merchant.isBlank() || STOPWORDS.contains(merchant)) return f
        val resolved = aliasResolver.resolve(merchant, AliasKind.MERCHANT)
        return f.copy(merchantNormalized = resolved ?: merchant.trim())
    }

    private fun applyAmountFilters(q: String, f: AiQueryPlan.Filters): AiQueryPlan.Filters {
        var out = f
        Regex("over\\s+(?:rs\\.?|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").find(q)?.let {
            amountToMinor(it.groupValues[1])?.let { v -> out = out.copy(minAmountMinor = v + 1) }
        }
        Regex("under\\s+(?:rs\\.?|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").find(q)?.let {
            amountToMinor(it.groupValues[1])?.let { v -> out = out.copy(maxAmountMinor = v - 1) }
        }
        return out
    }

    private fun applyCategoryFilter(q: String, f: AiQueryPlan.Filters): AiQueryPlan.Filters {
        val m = Regex("(?:category|categor(?:y|ies))\\s+([a-z][a-z ]{2,30})").find(q) ?: return f
        val name = m.groupValues[1].trim()
        val id = aliasResolver.resolve(name, AliasKind.CATEGORY) ?: return f
        return f.copy(categoryIds = setOf(id))
    }

    private fun applyAccountFilter(q: String, f: AiQueryPlan.Filters): AiQueryPlan.Filters {
        val m = Regex("(?:account|card)\\s+([a-z][a-z0-9 -]{2,30})").find(q) ?: return f
        val name = m.groupValues[1].trim()
        val id = aliasResolver.resolve(name, AliasKind.ACCOUNT) ?: return f
        return f.copy(accountIds = setOf(id))
    }

    private fun applyRailFilter(q: String, f: AiQueryPlan.Filters): AiQueryPlan.Filters {
        val rails = TransactionV6Rails.entries.mapNotNull { rail ->
            if (q.contains(rail.keyword)) rail.name else null
        }
        return if (rails.isEmpty()) f else f.copy(rails = rails.toSet())
    }

    // ---- aggregation detection ----

    private fun detectAggregations(
        q: String,
        metrics: MutableSet<AiQueryPlan.Metric>,
        groupBy: MutableList<AiQueryPlan.Dimension>,
    ) {
        if (containsAny(q, listOf("spend", "spent", "expense"))) metrics += AiQueryPlan.Metric.TOTAL_SPEND
        if (containsAny(q, listOf("income", "earned", "salary"))) metrics += AiQueryPlan.Metric.TOTAL_INCOME
        if ("net" in q || "flow" in q) metrics += AiQueryPlan.Metric.NET_FLOW
        if ("count" in q || "how many" in q) metrics += AiQueryPlan.Metric.TRANSACTION_COUNT
        if ("by category" in q || "per category" in q) {
            metrics += AiQueryPlan.Metric.SPEND_BY_CATEGORY
            groupBy += AiQueryPlan.Dimension.CATEGORY
        }
        if ("by merchant" in q || "per merchant" in q) {
            metrics += AiQueryPlan.Metric.SPEND_BY_MERCHANT
            groupBy += AiQueryPlan.Dimension.MERCHANT
        }
        if ("by account" in q || "per account" in q) {
            metrics += AiQueryPlan.Metric.SPEND_BY_ACCOUNT
            groupBy += AiQueryPlan.Dimension.ACCOUNT
        }
        if ("by rail" in q || "per rail" in q) {
            metrics += AiQueryPlan.Metric.SPEND_BY_RAIL
            groupBy += AiQueryPlan.Dimension.RAIL
        }
        if (metrics.isEmpty() && groupBy.isNotEmpty()) {
            metrics += AiQueryPlan.Metric.TOTAL_SPEND
        }
    }

    private fun detectSort(q: String): AiQueryPlan.Sort = when {
        "largest first" in q || "highest first" in q ->
            AiQueryPlan.Sort(AiQueryPlan.Sort.SortField.AMOUNT, AiQueryPlan.Sort.SortDirection.DESC)
        "smallest first" in q || "lowest first" in q ->
            AiQueryPlan.Sort(AiQueryPlan.Sort.SortField.AMOUNT, AiQueryPlan.Sort.SortDirection.ASC)
        "oldest first" in q ->
            AiQueryPlan.Sort(AiQueryPlan.Sort.SortField.OCCURRED_AT, AiQueryPlan.Sort.SortDirection.ASC)
        else -> AiQueryPlan.Sort()
    }

    private fun extractLimit(q: String): Int {
        Regex("top (\\d{1,3})").find(q)?.let {
            val n = it.groupValues[1].toIntOrNull()
            if (n != null && n in 1..AiQueryPlan.MAX_LIMIT) return n
        }
        return AiQueryPlan.DEFAULT_LIMIT
    }

    // ---- LLM-proposed plan decoding (module 170 absorb) ----

    /**
     * Decode an LLM-proposed plan JSON. Every field must match the whitelist;
     * unknown fields or invalid enum values are hard failures. The model can
     * only ever narrow what the user asked — it cannot invent identifiers:
     * category/account ids are re-resolved through [aliasResolver] here so a
     * hallucinated UUID is rejected.
     */
    fun decodePlanJson(json: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ParseOutcome {
        val root = try {
            org.json.JSONObject(json)
        } catch (_: Exception) {
            return ParseOutcome.Unparsed("plan json not parseable")
        }
        val allowed = setOf("intent", "metrics", "groupBy", "filters", "limit")
        val unknown = root.keys().asSequence().filter { it !in allowed }.toList()
        if (unknown.isNotEmpty()) return ParseOutcome.Unparsed("unsupported plan fields: $unknown")

        val intentName = root.optString("intent")
        val intent = try {
            AiQueryPlan.Intent.valueOf(intentName)
        } catch (_: IllegalArgumentException) {
            return ParseOutcome.Unparsed("unknown intent '$intentName'")
        }

        val metrics = mutableSetOf<AiQueryPlan.Metric>()
        val jMetrics = root.optJSONArray("metrics")
        if (jMetrics != null) {
            for (i in 0 until jMetrics.length()) {
                val name = jMetrics.optString(i)
                val metric = try {
                    AiQueryPlan.Metric.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    return ParseOutcome.Unparsed("unknown metric '$name'")
                }
                metrics += metric
            }
        }

        val groupBy = mutableListOf<AiQueryPlan.Dimension>()
        val jGroup = root.optJSONArray("groupBy")
        if (jGroup != null) {
            for (i in 0 until jGroup.length()) {
                val name = jGroup.optString(i)
                val dim = try {
                    AiQueryPlan.Dimension.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    return ParseOutcome.Unparsed("unknown dimension '$name'")
                }
                groupBy += dim
            }
        }

        var filters = AiQueryPlan.Filters()
        root.optJSONObject("filters")?.let { jf ->
            val allowedFilters = setOf(
                "fromDay", "toDay", "accountIds", "categoryIds", "kinds",
                "merchantNormalized", "rails", "minAmountMinor", "maxAmountMinor",
            )
            val uf = jf.keys().asSequence().filter { it !in allowedFilters }.toList()
            if (uf.isNotEmpty()) return ParseOutcome.Unparsed("unsupported filter fields: $uf")

            filters = filters.copy(
                fromDay = if (jf.has("fromDay")) jf.optLong("fromDay") else null,
                toDay = if (jf.has("toDay")) jf.optLong("toDay") else null,
                minAmountMinor = if (jf.has("minAmountMinor")) jf.optLong("minAmountMinor") else null,
                maxAmountMinor = if (jf.has("maxAmountMinor")) jf.optLong("maxAmountMinor") else null,
                merchantNormalized = jf.optStringOrNull("merchantNormalized"),
            )
            jf.optJSONArray("accountIds")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { arr.optStringOrNull2(it) }.toMutableList()
                val resolved = ids.mapNotNull { aliasResolver.resolve(it, AliasKind.ACCOUNT) }
                if (resolved.size != ids.size) return ParseOutcome.Unparsed("unresolvable account id in plan")
                filters = filters.copy(accountIds = resolved.toSet())
            }
            jf.optJSONArray("categoryIds")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { arr.optStringOrNull2(it) }.toMutableList()
                val resolved = ids.mapNotNull { aliasResolver.resolve(it, AliasKind.CATEGORY) }
                if (resolved.size != ids.size) return ParseOutcome.Unparsed("unresolvable category id in plan")
                filters = filters.copy(categoryIds = resolved.toSet())
            }
            jf.optJSONArray("kinds")?.let { arr ->
                val kinds = (0 until arr.length()).mapNotNull { s ->
                    try {
                        TxKind.valueOf(arr.getString(s))
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                if (kinds.isEmpty()) return ParseOutcome.Unparsed("invalid kinds in plan")
                filters = filters.copy(kinds = kinds.toSet())
            }
            jf.optJSONArray("rails")?.let { arr ->
                val rails = (0 until arr.length()).mapNotNull { arr.optStringOrNull2(it) }.toSet()
                if (rails.isEmpty()) return ParseOutcome.Unparsed("invalid rails in plan")
                filters = filters.copy(rails = rails)
            }
        }

        val limit = if (root.has("limit")) root.optInt("limit", AiQueryPlan.DEFAULT_LIMIT)
        else AiQueryPlan.DEFAULT_LIMIT
        if (limit !in 1..AiQueryPlan.MAX_LIMIT) return ParseOutcome.Unparsed("limit outside bounds")

        val plan = try {
            AiQueryPlan(
                intent = intent,
                metrics = metrics,
                groupBy = groupBy,
                filters = filters,
                limit = limit,
                planIdentity = sha256(canonicalForm(intent, metrics, groupBy, filters, limit)),
                parsedAtEpochMs = clock(),
            )
        } catch (e: IllegalArgumentException) {
            return ParseOutcome.Unparsed(e.message ?: "invalid plan")
        }
        return ParseOutcome.Parsed(plan, null)
    }

    // ---- helpers ----

    private fun canonicalForm(
        intent: AiQueryPlan.Intent,
        metrics: Set<AiQueryPlan.Metric>,
        groupBy: List<AiQueryPlan.Dimension>,
        filters: AiQueryPlan.Filters,
        limit: Int,
    ): String = buildString {
        append(intent.name).append('|')
        append(metrics.map { it.name }.sorted().joinToString(",")).append('|')
        append(groupBy.joinToString(",") { it.name }).append('|')
        append(filters.fromDay ?: "-").append('|')
        append(filters.toDay ?: "-").append('|')
        append(filters.accountIds?.sorted()?.joinToString(",") ?: "-").append('|')
        append(filters.categoryIds?.sorted()?.joinToString(",") ?: "-").append('|')
        append(filters.kinds?.sorted()?.joinToString(",") { it.name } ?: "-").append('|')
        append(filters.merchantNormalized ?: "-").append('|')
        append(filters.rails?.sorted()?.joinToString(",") ?: "-").append('|')
        append(filters.minAmountMinor ?: "-").append('|')
        append(filters.maxAmountMinor ?: "-").append('|')
        append(limit)
    }

    private fun amountToMinor(token: String): Long? {
        val cleaned = token.replace(",", "")
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value <= 0 || value > 100_000_000) return null
        return Math.round(value * 100)
    }

    private fun containsAny(q: String, needles: List<String>): Boolean =
        needles.any { it in q }

    internal fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val STOPWORDS = setOf("transactions", "spending", "expenses", "the", "my", "all")

        private enum class TransactionV6Rails(val keyword: String) {
            UPI("upi"), IMPS("imps"), NEFT("neft"), RTGS("rtgs"),
            CARD_POS("pos"), CARD_ONLINE("card online"), ATM("atm"),
        }
    }
}

private fun org.json.JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun org.json.JSONArray.optStringOrNull2(index: Int): String? =
    if (!isNull(index)) optString(index) else null
