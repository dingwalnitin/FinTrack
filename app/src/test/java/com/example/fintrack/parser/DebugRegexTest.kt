package com.example.fintrack.parser

import org.junit.Test

class DebugRegexTest {
    @Test
    fun x() {
        val r = Regex(
            "\\b(debited|spent|withdrawn|paid|purchase|deducted|charged|transfer (?:of|from)|" +
                "imps? transfer (?:of|from))\\b",
            RegexOption.IGNORE_CASE,
        )
        val t = "imps transfer of rs.15,000.00 from a/c xx1234 to vendor llp on 02/08/26. ref no utib123456789"
        println("matches: " + r.findAll(t).toList())
    }
}
