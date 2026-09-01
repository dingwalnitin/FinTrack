package com.example.fintrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.fintrack.ui.accounts.AccountsViewModel
import com.example.fintrack.ui.accounts.ReconcileViewModel
import com.example.fintrack.ui.navigation.FinTrackAppShell
import com.example.fintrack.ui.sms.SmsConsentViewModel
import com.example.fintrack.ui.theme.FinTrackTheme
import com.example.fintrack.ui.transactions.TransactionsRoute
import com.example.fintrack.ui.TransactionsViewModel

class MainActivity : ComponentActivity() {

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Forward the outcome to the view-model. The repository has already
        // observed it via the source.hasPermission() check.
        smsConsentViewModel?.onPermissionResult(
            granted = result.values.any { it }
        )
    }

    private var smsConsentViewModel: SmsConsentViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FinTrackApplication
        val viewModel = TransactionsViewModel(app.legacyRepository)
        val accountsViewModel = AccountsViewModel(app.financeRepository)
        val reconcileViewModel = ReconcileViewModel(app.financeRepository)
        val smsViewModel = SmsConsentViewModel(app, app.smsRepository, app.smsSource)
        smsConsentViewModel = smsViewModel

        // Stage 9 (P19 + P20): dashboard, insights, search/diagnostics.
        val homeVM = com.example.fintrack.application.insights.HomeViewModel(
            repository = app.insightsRepository,
            budgetSink = app.stage8Repository,
            reviewQueueService = app.reviewQueueService,
            engine = app.insightsEngine,
        )
        val insightsVM = com.example.fintrack.application.insights.InsightsViewModel(
            repository = app.insightsRepository,
            engine = app.insightsEngine,
        )
        val searchVM = com.example.fintrack.application.search.SearchViewModel(
            repository = app.insightsRepository,
            reconciliationService = app.reconciliationService,
        )
        val reviewQueueVM = com.example.fintrack.application.review.ReviewQueueViewModel(app.reviewQueueService)

        // Stage 10 (P21): AI natural-language query.
        val aiQueryVM = com.example.fintrack.application.ai.AiQueryViewModel(
            repository = app.aiQueryRepository,
            parser = app.aiQueryParser,
        )

        // Stage 11 (P23 + P24): backup/restore + app lock.
        val backupVM = com.example.fintrack.application.backup.BackupViewModel(
            backupService = app.backupService,
            backupRepository = app.backupRepository,
            auditService = app.auditService,
        )

        // Stage 12 (P25): developer diagnostics.
        val diagnosticsVM = com.example.fintrack.application.diagnostics.DiagnosticsViewModel(
            diagnosticsService = app.diagnosticsService,
            playground = app.parserPlayground,
            diff = app.fixtureDiff,
        )

        // LLM config store (Chat Completions API settings persisted locally).
        val llmConfigStore = com.example.fintrack.llm.LlmConfigStore(this)

        // LLM processing: scan ALL SMS through the LLM from Settings.
        val llmProcessingVM = com.example.fintrack.application.enrichment.LlmProcessingViewModel(
            service = app.llmProcessingService,
        )

        // Dedicated LLM settings screen: config + "Test connection" probe.
        val llmSettingsVM = com.example.fintrack.ui.settings.LlmSettingsViewModel(
            store = llmConfigStore,
        )

        // P11 #3/#6: manual entry + transaction detail (previously unreachable — now wired into the shell).
        val manualEntryVM = com.example.fintrack.application.transactions.ManualEntryViewModel(
            service = app.manualEntryService,
        )
        val transactionDetailVM = com.example.fintrack.application.transactions.TransactionDetailViewModel(
            dao = app.database.financeDaoV3(),
            daoV4 = app.database.financeDaoV4(),
            daoV2 = app.database.financeDaoV2(),
        )

        // Stage 13 (F): SMS review — passed/failed/pending list + manual re-run.
        val smsReviewVM = com.example.fintrack.application.review.SmsReviewViewModel(
            smsDao = app.database.smsDao(),
            llmDao = app.database.llmDao(),
            reviewService = app.smsReviewService,
        )

        setContent {
            FinTrackTheme {
                Surface {
                    val state by viewModel.state.collectAsState()
                    val smsState by smsViewModel.state.collectAsState()
                    FinTrackAppShell(
                        transactionsRoute = { onOpenTransaction -> TransactionsRoute(state, onOpenTransaction) },
                        accountsViewModel = accountsViewModel,
                        reconcileViewModel = reconcileViewModel,
                        smsConsentState = smsState,
                        onRequestSmsPermission = { requestSmsPermissions() },
                        onStartSmsBackfill = { smsViewModel.onStartBackfill(applicationContext) },
                        onPauseSmsBackfill = { smsViewModel.onPauseBackfill(applicationContext) },
                        onSmsRevokeHandled = { smsViewModel.onRevokeHandled() },
                        budgetsSink = app.stage8Repository,
                        recurringSink = app.stage8Repository,
                        recurringService = app.recurringService,
                        homeViewModel = homeVM,
                        insightsViewModel = insightsVM,
                        searchViewModel = searchVM,
                        reviewQueueViewModel = reviewQueueVM,
                        aiQueryViewModel = aiQueryVM,
                        backupViewModel = backupVM,
                        appLockService = app.appLockService,
                        diagnosticsViewModel = diagnosticsVM,
                        llmConfigStore = llmConfigStore,
                        llmProcessingViewModel = llmProcessingVM,
                        llmSettingsViewModel = llmSettingsVM,
                        manualEntryViewModel = manualEntryVM,
                        transactionDetailViewModel = transactionDetailVM,
                        smsReviewViewModel = smsReviewVM,
                    )
                }
            }
        }
    }

    private fun requestSmsPermissions() {
        // Request BOTH permissions on every Android version. READ_SMS is
        // hard-restricted on Android 13+ (only default SMS apps / allowlisted
        // installers can hold it), but declaring + requesting it still lets
        // the system surface the dialog where supported. RECEIVE_SMS is what
        // powers live capture of new SMS; it is grantable on all versions.
        val toRequest = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (toRequest.isEmpty()) {
            smsConsentViewModel?.onPermissionResult(true)
        } else {
            smsPermissionLauncher.launch(toRequest)
        }
    }
}
