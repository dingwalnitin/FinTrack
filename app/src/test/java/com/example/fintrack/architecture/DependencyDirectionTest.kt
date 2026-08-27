package com.example.fintrack.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture tests: enforce dependency direction
 * ui -> application -> domain -> data, with llm/parser/importexport as boundaries.
 *
 * These are source-scanning tests (no external framework dependency) so they run
 * anywhere Gradle runs. They fail if a layer imports a forbidden package.
 */
class DependencyDirectionTest {

    private fun sources(vararg parts: String): List<File> =
        File("src/main/java/com/example/fintrack").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> parts.all { p -> f.path.replace('\\', '/').contains(p) } }
            .toList()

    private fun violations(files: List<File>, forbidden: String): List<String> =
        files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.trimStart().startsWith("import") && line.contains(forbidden))
                    "${f.name}:${i + 1} imports $forbidden"
                else null
            }
        }

    private fun assertNoViolations(desc: String, files: List<File>, forbidden: List<String>) {
        val all = forbidden.flatMap { violations(files, it) }
        assertTrue("$desc violated:\n${all.joinToString("\n")}", all.isEmpty())
    }

    @Test
    fun `domain depends on nothing above it`() {
        assertNoViolations(
            "domain",
            sources("/domain/"),
            listOf(".ui.", ".application.", ".data.", ".llm.", ".parser.", ".importexport.")
        )
    }

    @Test
    fun `data does not depend on ui`() {
        assertNoViolations("data", sources("/data/"), listOf(".ui."))
    }

    @Test
    fun `ui never touches Room directly`() {
        assertNoViolations(
            "ui",
            sources("/ui/"),
            listOf("androidx.room", ".data.db.")
        )
    }

    @Test
    fun `llm boundary never references data db writes`() {
        assertNoViolations("llm", sources("/llm/"), listOf(".data.db."))
    }

    @Test
    fun `domain policy layer does not depend on data db`() {
        assertNoViolations(
            "policy",
            sources("/domain/policy/"),
            listOf("androidx.room", ".data.repository.")
        )
    }

    @Test
    fun `ui never references repository implementations`() {
        assertNoViolations(
            "ui",
            sources("/ui/"),
            listOf(".data.repository.", "RoomFinanceRepository")
        )
    }

    @Test
    fun `non-goals registry lists forbidden capabilities`() {
        val registry = com.example.fintrack.domain.NonGoals.FORBIDDEN_CAPABILITIES
        listOf("bank", "investment", "transfer execution", "cloud", "push notification", "SMS deletion")
            .forEach { keyword ->
                assertTrue(
                    "NonGoals registry missing capability containing '$keyword'",
                    registry.any { it.contains(keyword, ignoreCase = true) }
                )
            }
    }

    @Test
    fun `sms policy lives in domain and forbids deletion`() {
        // The ingestion policy is in domain/sms and is the only authoritative
        // definition of source-kind / status codes. UI/feature code must not
        // re-define these.
        val policyFile = sources("/domain/sms/").firstOrNull { it.name == "SmsIngestionPolicy.kt" }
        assertTrue("expected SmsIngestionPolicy.kt in domain", policyFile != null)
        val text = policyFile!!.readText()
        assertTrue(
            "policy must never declare a DELETE source kind",
            !text.contains("DELETE")
        )
    }

    @Test
    fun `sms ui never touches Room directly`() {
        assertNoViolations(
            "sms ui",
            sources("/ui/sms/"),
            listOf("androidx.room", ".data.db.")
        )
    }

    @Test
    fun `sms source abstraction does not depend on data`() {
        assertNoViolations(
            "sms source",
            sources("/sms/"),
            // The SmsSource interface and ContentResolverSmsSource may use
            // platform android.* APIs and domain contracts, but must never
            // touch Room or repository implementations directly.
            listOf(".data.db.", ".data.repository.")
        )
    }

    @Test
    fun `parser framework lives in main and is testable without Android`() {
        // The pure parser modules (classifier, normalizer, candidate schema)
        // must not import platform android.* APIs so they stay JVM-testable.
        val parserSources = sources("/parser/")
        val pure = listOf(
            "DeterministicSmsClassifier.kt", "TextNormalizer.kt", "ParseCandidate.kt",
        )
        val violations = parserSources.filter { f -> f.name in pure }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (line.trimStart().startsWith("import") && "android." in line)
                        "${f.name}:${i + 1} imports android.*"
                    else null
                }
            }
        assertTrue(
            "pure parser modules must not import android.*:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
