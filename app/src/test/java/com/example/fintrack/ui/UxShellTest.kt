package com.example.fintrack.ui

import com.example.fintrack.ui.common.formatMinor
import com.example.fintrack.ui.navigation.Routes
import com.example.fintrack.ui.settings.SettingsUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM tests for the UX-shell increment: typed routes, money display
 * formatting, and safe settings defaults. Compose rendering is covered by the
 * a11y smoke checks in androidTest; these verify the shell logic.
 */
class UxShellTest {

    @Test
    fun `all seven top-level routes are defined and unique`() {
        val routes = Routes.topLevel.map { it.route }
        assertEquals(listOf("home", "transactions", "accounts", "budgets", "insights", "review", "settings"), routes)
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `account detail route builds parameterized path`() {
        assertEquals("accounts/abc-123", Routes.accountDetail("abc-123"))
        assertTrue(Routes.ACCOUNT_DETAIL.startsWith("accounts/"))
    }

    @Test
    fun `deep link pattern uses scheme and host`() {
        val pattern = "${Routes.DEEP_LINK_SCHEME}://${Routes.DEEP_LINK_HOST}/accounts/{accountId}"
        assertTrue(pattern.startsWith("fintrack://app/accounts/"))
    }

    @Test
    fun `minor units format with two decimals and sign`() {
        assertEquals("12.34", formatMinor(1234L))
        assertEquals("-12.34", formatMinor(-1234L))
        assertEquals("0.05", formatMinor(5L))
        assertEquals("0.00", formatMinor(0L))
    }

    @Test
    fun `settings default to safe values - exports redacted`() {
        val defaults = SettingsUiModel()
        assertFalse(defaults.exportIncludeRawEvidence)
        assertTrue(defaults.autoCategorizationEnabled)
    }
}
