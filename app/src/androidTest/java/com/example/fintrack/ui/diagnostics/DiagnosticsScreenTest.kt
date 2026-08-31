package com.example.fintrack.ui.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.fintrack.TestActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fintrack.application.diagnostics.DiagnosticsViewModel
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.migration.Migrations
import com.example.fintrack.diagnostics.DiagnosticsService
import com.example.fintrack.parser.FinTrackParser
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI test for DiagnosticsScreen.
 *
 * Uses an in-memory Room database so the diagnostics service can build a
 * report without touching any real production data. Verifies the screen
 * renders the main sections and the parser playground works.
 */
class DiagnosticsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private lateinit var db: FinTrackDatabaseV2

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FinTrackDatabaseV2::class.java,
        )
            .allowMainThreadQueries()
            .addMigrations(*Migrations.ALL)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun diagnosticsScreen_rendersSections() {
        val service = DiagnosticsService(
            database = db,
            dao = db.financeDaoV8(),
            smsDao = db.smsDao(),
            llmDao = db.llmDao(),
            parser = FinTrackParser(),
        )
        val vm = DiagnosticsViewModel(service)
        composeTestRule.setContent {
            DiagnosticsScreen(vm)
        }

        Thread.sleep(500) // let the ViewModel build the report
        composeTestRule.onNodeWithText("Developer diagnostics").assertIsDisplayed()
        // Sections below the fold in the LazyColumn: scroll them into view
        // before asserting they are displayed.
        composeTestRule.onNodeWithText("Parser playground").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Fixture regression gate").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun diagnosticsScreen_playgroundRunsSyntheticSms() {
        val service = DiagnosticsService(
            database = db,
            dao = db.financeDaoV8(),
            smsDao = db.smsDao(),
            llmDao = db.llmDao(),
            parser = FinTrackParser(),
        )
        val vm = DiagnosticsViewModel(service)
        composeTestRule.setContent {
            DiagnosticsScreen(vm)
        }

        Thread.sleep(500)
        vm.runPlayground("Rs.250.00 debited from A/c XX1234 to Swiggy via UPI. Ref 123456")
        Thread.sleep(300)
        // The playground result renders the classification.
        val res = vm.state.value.playgroundResult
        assert(res != null) { "playground must produce a result" }
        composeTestRule.onNodeWithText("Classification: FINANCIAL").performScrollTo().assertIsDisplayed()
    }
}