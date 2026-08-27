package com.example.fintrack.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.fintrack.R
import com.example.fintrack.ui.settings.SettingsScreen
import com.example.fintrack.ui.settings.SettingsUiModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.fintrack.ui.accounts.AccountsScreen
import com.example.fintrack.ui.accounts.ReconcileScreen
import com.example.fintrack.ui.accounts.ReconcileViewModel
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.transactions.TransactionsRoute
import androidx.compose.ui.unit.dp

/**
 * Stable app shell: bottom navigation for the 7 top-level destinations plus a
 * parameterized account-detail route with deep-link support.
 *
 * Back-stack behavior: top-level destinations use saveState/restoreState so
 * filter/sort state survives navigating away and back. All offline.
 */
@Composable
fun FinTrackAppShell(
    navController: NavHostController = rememberNavController(),
    transactionsRoute: @Composable () -> Unit,
    accountsViewModel: com.example.fintrack.ui.accounts.AccountsViewModel,
    reconcileViewModel: ReconcileViewModel,
    smsConsentState: com.example.fintrack.ui.sms.SmsConsentState,
    onRequestSmsPermission: () -> Unit,
    onStartSmsBackfill: () -> Unit,
    onPauseSmsBackfill: () -> Unit,
    onSmsRevokeHandled: () -> Unit,
    budgetsSink: com.example.fintrack.domain.service.BudgetSink? = null,
    recurringSink: com.example.fintrack.domain.service.RecurringSink? = null,
    recurringService: com.example.fintrack.domain.service.RecurringService? = null,
    homeViewModel: com.example.fintrack.application.insights.HomeViewModel? = null,
    insightsViewModel: com.example.fintrack.application.insights.InsightsViewModel? = null,
    searchViewModel: com.example.fintrack.application.search.SearchViewModel? = null,
    reviewQueueViewModel: com.example.fintrack.application.review.ReviewQueueViewModel? = null,
    aiQueryViewModel: com.example.fintrack.application.ai.AiQueryViewModel? = null,
    backupViewModel: com.example.fintrack.application.backup.BackupViewModel? = null,
    appLockService: com.example.fintrack.domain.service.AppLockService? = null,
    diagnosticsViewModel: com.example.fintrack.application.diagnostics.DiagnosticsViewModel? = null,
    llmConfigStore: com.example.fintrack.llm.LlmConfigStore? = null,
    llmProcessingViewModel: com.example.fintrack.application.enrichment.LlmProcessingViewModel? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.semantics {
                    contentDescription = "Main navigation"
                }
            ) {
                Routes.topLevel.forEach { dest ->
                    val selected = currentRoute == dest.route
                    val label = stringResource(dest.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = {
                            Text(
                                label,
                                maxLines = 1,
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        if (selected) "$label, selected" else label
                                },
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.HOME) {
                val vm = homeViewModel
                if (vm != null) {
                    com.example.fintrack.ui.home.HomeScreen(vm)
                } else {
                    PlaceholderScreen(stringResource(R.string.nav_home))
                }
            }
            composable(Routes.TRANSACTIONS) { transactionsRoute() }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    viewModel = accountsViewModel,
                    onEditBalance = { id, amount ->
                        accountsViewModel.recordActualBalance(id, "INR", amount)
                    },
                )
            }
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "${Routes.DEEP_LINK_SCHEME}://${Routes.DEEP_LINK_HOST}/accounts/{accountId}" }
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                ReconcileScreen(reconcileViewModel, accountId = accountId, currencyCode = "INR")
            }
            composable(Routes.BUDGETS) {
                val bSink = budgetsSink
                val rSink = recurringSink
                val rSvc = recurringService
                if (bSink != null && rSink != null && rSvc != null) {
                    com.example.fintrack.ui.budgets.BudgetsScreen(
                        com.example.fintrack.ui.budgets.BudgetsViewModel(bSink, rSink, rSvc)
                    )
                } else {
                    PlaceholderScreen(stringResource(R.string.nav_budgets))
                }
            }
            composable(Routes.INSIGHTS) {
                val vm = insightsViewModel
                if (vm != null) {
                    com.example.fintrack.ui.insights.InsightsScreen(vm)
                } else {
                    PlaceholderScreen(stringResource(R.string.nav_insights))
                }
            }
            composable(Routes.AI_QUERY) {
                val vm = aiQueryViewModel
                if (vm != null) {
                    com.example.fintrack.ui.ai.AiQueryScreen(vm)
                } else {
                    PlaceholderScreen("AI query")
                }
            }
            composable(Routes.REVIEW) {
                val rvm = reviewQueueViewModel
                if (rvm != null) {
                    com.example.fintrack.ui.review.ReviewQueueScreen(
                        viewModel = rvm,
                        onOpenTransaction = { /* detail route handled by transactions tab */ },
                    )
                } else {
                    PlaceholderScreen(stringResource(R.string.nav_review))
                }
            }
            composable(Routes.SETTINGS) {
                val store = llmConfigStore
                val initialModel = if (store != null) {
                    SettingsUiModel(llmConfig = store.load())
                } else {
                    SettingsUiModel()
                }
                SettingsScreen(
                    model = initialModel,
                    onChanged = { ui ->
                        store?.save(ui.llmConfig)
                    },
                    onNavigateToDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onNavigateToSmsConsent = { navController.navigate(Routes.SMS_CONSENT) },
                    onRequestSmsPermission = onRequestSmsPermission,
                    llmProcessingViewModel = llmProcessingViewModel,
                )
            }
            composable(Routes.BACKUP_RESTORE) {
                val bvm = backupViewModel
                if (bvm != null) {
                    com.example.fintrack.ui.backup.BackupRestoreScreen(bvm)
                } else {
                    PlaceholderScreen("Backup & restore")
                }
            }
            composable(Routes.SMS_CONSENT) {
                com.example.fintrack.ui.sms.SmsConsentScreen(
                    state = smsConsentState,
                    onRequestPermission = onRequestSmsPermission,
                    onStartBackfill = { onStartSmsBackfill() },
                    onPauseBackfill = { onPauseSmsBackfill() },
                    onRevokeHandled = onSmsRevokeHandled,
                )
            }
            composable(Routes.DIAGNOSTICS) {
                val dvm = diagnosticsViewModel
                if (dvm != null) {
                    com.example.fintrack.ui.diagnostics.DiagnosticsScreen(dvm)
                } else {
                    PlaceholderScreen("Developer diagnostics")
                }
            }
        }
    }
}

/** Placeholder for features delivered by later increments — explicit, not blank. */
@Composable
fun PlaceholderScreen(name: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("$name — coming in a later increment", style = MaterialTheme.typography.bodyLarge)
    }
}
