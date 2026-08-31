package com.example.fintrack

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Empty activity for Compose UI tests, declared in the DEBUG source set so it
 * ships inside the target app APK (process `com.example.fintrack`).
 *
 * Tests use `createAndroidComposeRule<TestActivity>()` instead of the headless
 * `createComposeRule()` because the headless rule does not surface its Compose
 * hierarchy reliably on Android 16 (API 36) with Compose UI 1.7.x.
 *
 * This activity deliberately does NOT call `setContent`.  Each test provides
 * its own Compose content via `composeTestRule.setContent { ... }`.
 */
class TestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}