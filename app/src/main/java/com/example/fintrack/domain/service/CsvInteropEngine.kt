package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.TxKind

/**
 * Stage 11 P23 #6 — CSV interoperability with a column-mapping wizard.
 *
 * The wizard produces a [CsvColumnMapping]; this engine turns raw CSV rows
 * into canonical transaction drafts deterministically:
 *  - sign handling: explicit sign, or a Type/Dr-Cr column, or an invert flag;
 *  - date formats: ISO, dd/MM/yyyy, dd-MM-yyyy, MM/dd/yyyy (declared, never
 *    guessed — the mapping carries the format);
 *  - amounts: Indian digit grouping (1,23,456.78) and plain forms both parse.
 *
 * Unknown/unparseable values produce per-row errors; they never become
 * fabricated transactions.
 */
class CsvInteropEngine {

    enum class CsvColumn { DATE, AMOUNT, CURRENCY, MERCHANT, CATEGORY, ACCOUNT, TYPE, NOTE, REFERENCE }

    /** Declared date formats the wizard can map to. */
    enum class DateFormat(val pattern: String) {
        ISO("yyyy-MM-dd"),
        DD_MM_YYYY_SLASH("dd/MM/yyyy"),
        DD_MM_YYYY_DASH("dd-MM-yyyy"),
        MM_DD_YYYY_SLASH("MM/dd/yyyy"),
    }

    /** How the debit/credit direction is expressed in the file. */
    sealed interface SignConvention {
        /** Amounts are signed: negative = debit (money out). */
        data object SignedAmount : SignConvention
        /** Positive = money out (bank-statement style); requires inversion. */
        data object PositiveIsDebit : SignConvention
        /** A separate column holds DEBIT/CREDIT (or Dr/Cr / Withdrawal/Deposit). */
        data class TypeColumn(val columnName: String) : SignConvention
    }

    data class CsvColumnMapping(
        val dateFormat: DateFormat,
        val signConvention: SignConvention,
        /** csv column index → canonical field. Multiple fields may share one column? No — 1:1. */
        val columns: Map<CsvColumn, Int>,
        val hasHeaderRow: Boolean,
        val defaultCurrency: String,
        val defaultAccountName: String,
    ) {
        init {
            require(columns.containsKey(CsvColumn.DATE)) { "DATE mapping is required" }
            require(columns.containsKey(CsvColumn.AMOUNT)) { "AMOUNT mapping is required" }
        }
    }

    /** One parsed row ready for import preview. */
    data class CsvTxnDraft(
        val rowIndex: Int,
        val dateEpochDay: Long,
        val amountMinor: Long,
        val currencyCode: String,
        val merchant: String?,
        val category: String?,
        val accountName: String?,
        val kind: TxKind,
        val note: String?,
        val reference: String?,
    )

    sealed interface RowResult {
        data class Ok(val draft: CsvTxnDraft) : RowResult
        data class Error(val rowIndex: Int, val reason: String) : RowResult
    }

    /**
     * Parse CSV text into drafts using the wizard's mapping.
     * Deterministic: same input + mapping ⇒ same output.
     */
    fun parse(csv: String, mapping: CsvColumnMapping): List<RowResult> {
        val lines = csv.lines().filter { it.isNotBlank() }
        val body = if (mapping.hasHeaderRow && lines.isNotEmpty()) lines.drop(1) else lines
        return body.mapIndexedNotNull { idx, line ->
            val rowIndex = idx + if (mapping.hasHeaderRow) 1 else 0
            parseRow(line, rowIndex, mapping)
        }
    }

    private fun parseRow(line: String, rowIndex: Int, m: CsvColumnMapping): RowResult? {
        val cells = splitCsv(line)
        fun cell(c: CsvColumn): String? =
            m.columns[c]?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }

        // ---- date ----
        val epochDay = cell(CsvColumn.DATE)?.let { parseDate(it, m.dateFormat) }
            ?: return RowResult.Error(rowIndex, "unparseable date '${cell(CsvColumn.DATE)}'")

        // ---- amount + sign ----
        val rawAmount = cell(CsvColumn.AMOUNT)
            ?: return RowResult.Error(rowIndex, "missing amount")
        val parsed = parseAmount(rawAmount)
            ?: return RowResult.Error(rowIndex, "unparseable amount '$rawAmount'")
        var signedMajor = parsed
        var kind = TxKind.UNKNOWN
        when (val sc = m.signConvention) {
            is SignConvention.SignedAmount -> {
                kind = if (signedMajor < 0) TxKind.EXPENSE else TxKind.INCOME
            }
            is SignConvention.PositiveIsDebit -> {
                kind = TxKind.EXPENSE
            }
            is SignConvention.TypeColumn -> {
                val typeCell = cells.getOrNull(
                    m.columns.entries.firstOrNull { it.key == CsvColumn.TYPE }?.value ?: -1,
                )?.trim()?.uppercase() ?: ""
                val isCredit = typeCell in CREDIT_WORDS
                val isDebit = typeCell in DEBIT_WORDS
                if (!isCredit && !isDebit) {
                    return RowResult.Error(rowIndex, "unknown type value '$typeCell'")
                }
                kind = if (isCredit) TxKind.INCOME else TxKind.EXPENSE
            }
        }
        // amountMinor is ALWAYS the absolute value (codebase invariant —
        // TransactionV6 requires it); the sign is encoded in [kind].
        val amountMinor = (kotlin.math.abs(signedMajor) * 100).toLong()

        return RowResult.Ok(
            CsvTxnDraft(
                rowIndex = rowIndex,
                dateEpochDay = epochDay,
                amountMinor = amountMinor,
                currencyCode = cell(CsvColumn.CURRENCY) ?: m.defaultCurrency,
                merchant = cell(CsvColumn.MERCHANT),
                category = cell(CsvColumn.CATEGORY),
                accountName = cell(CsvColumn.ACCOUNT) ?: m.defaultAccountName,
                kind = kind,
                note = cell(CsvColumn.NOTE),
                reference = cell(CsvColumn.REFERENCE),
            ),
        )
    }

    /** RFC-4180-ish split honoring double quotes. */
    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    fun parseDate(raw: String, format: DateFormat): Long? = try {
        val p = java.time.format.DateTimeFormatter.ofPattern(format.pattern).withLocale(java.util.Locale.ROOT)
        java.time.LocalDate.parse(raw.trim(), p).toEpochDay()
    } catch (e: Exception) {
        null
    }

    /** Handles "1234.56", "1,23,456.78", "-500", "₹1,250". Returns major units as Double. */
    fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace("₹", "").replace("Rs.", "").replace("INR", "", ignoreCase = true)
            .trim().replace(",", "")
        return cleaned.toDoubleOrNull()
    }

    companion object {
        private val CREDIT_WORDS = setOf("CREDIT", "CR", "DEPOSIT", "CREDITED", "INCOME", "RECEIVED")
        private val DEBIT_WORDS = setOf("DEBIT", "DR", "WITHDRAWAL", "DEBITED", "EXPENSE", "PAID", "SPENT")
    }
}
