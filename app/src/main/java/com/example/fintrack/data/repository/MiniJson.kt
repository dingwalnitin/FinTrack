package com.example.fintrack.data.repository

/**
 * Tiny JSON encoder/decoder used by the dedup repositories for fields the
 * App Bible keeps as JSON-as-string. Deliberately minimal — we only need
 * to round-trip a list of strings and a flat string-to-double map. Real
 * org.json / kotlinx.serialization comes in when feature scope demands it.
 *
 * The format is intentionally simple: a leading tag ('L' for list, 'M' for
 * map) so we can parse without ambiguity, with values separated by '|'.
 */
internal object MiniJson {

    fun encodeList(items: List<String>): String =
        if (items.isEmpty()) "L|" else "L|" + items.joinToString("|") { escape(it) }

    fun decodeList(s: String): List<String> {
        if (!s.startsWith("L|")) return emptyList()
        val body = s.removePrefix("L|")
        if (body.isEmpty()) return emptyList()
        return body.split("|").map { unescape(it) }
    }

    fun encodeMap(map: Map<String, Double>): String {
        if (map.isEmpty()) return "M|"
        return "M|" + map.entries.joinToString("|") { (k, v) -> "${escape(k)}=${v}" }
    }

    fun decodeMap(s: String): Map<String, Double> {
        if (!s.startsWith("M|")) return emptyMap()
        val body = s.removePrefix("M|")
        if (body.isEmpty()) return emptyMap()
        return body.split("|").mapNotNull { kv ->
            val idx = kv.indexOf('=')
            if (idx <= 0) null else unescape(kv.substring(0, idx)) to (kv.substring(idx + 1).toDoubleOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("=", "\\=")
    private fun unescape(s: String): String = s
        .replace("\\=", "=")
        .replace("\\|", "|")
        .replace("\\\\", "\\")
}
