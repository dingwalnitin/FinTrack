package com.example.fintrack.application.config

/**
 * Local-only configuration with safe defaults. No remote arbitrary configuration:
 * values ship with the app and can only change via app updates.
 */
data class AppConfig(
    val enrichmentEnabled: Boolean = false, // LLM path off by default
    val maxRetryAttempts: Int = 3,
    val defaultCurrencyCode: String = "USD",
) {
    init {
        require(maxRetryAttempts in 1..10)
    }

    companion object {
        fun safeDefaults() = AppConfig()
    }
}
