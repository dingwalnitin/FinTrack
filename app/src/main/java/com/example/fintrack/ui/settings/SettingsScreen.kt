package com.example.fintrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.fintrack.BuildConfig
import com.example.fintrack.R
import com.example.fintrack.application.enrichment.LlmProcessingViewModel
import com.example.fintrack.llm.LlmConfig
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.common.LlmProgressSummary
import com.example.fintrack.ui.theme.Palette

/**
 * Settings shell. Sections only — no API keys, tokens or secrets are ever
 * displayed here. Data-lifecycle entry points keep exports on-device.
 *
 * AI interpretation is always on (default mode) — no toggle needed.
 * Safe defaults: exports redact raw evidence by default.
 */
data class SettingsUiModel(
    val exportIncludeRawEvidence: Boolean = false,  // safe default: redacted
    val autoCategorizationEnabled: Boolean = true,
    val llmConfig: LlmConfig = LlmConfig(),
)

@Composable
fun SettingsScreen(
    model: SettingsUiModel = remember { SettingsUiModel() },
    onChanged: (SettingsUiModel) -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToSmsConsent: () -> Unit = {},
    onNavigateToLlmSettings: () -> Unit = {},
    onRequestSmsPermission: () -> Unit = {},
    llmProcessingViewModel: LlmProcessingViewModel? = null,
) {
    var ui by remember { mutableStateOf(model) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall) }

        item {
            FinTrackCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(icon = Icons.Filled.AutoAwesome, containerColor = Palette.Violet)
                    Column {
                        Text("FinTrack", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "v${BuildConfig.VERSION_NAME} · everything stays on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            FinTrackCard {
                CommonSectionHeader(stringResource(R.string.settings_accounts_sources))
                SettingsLink(stringResource(R.string.nav_accounts), icon = Icons.Filled.AccountBalanceWallet)
                SettingsLink("Scan SMS messages", icon = Icons.Filled.Sms, onClick = onNavigateToSmsConsent)
            }
        }

        item {
            FinTrackCard {
                CommonSectionHeader(stringResource(R.string.settings_categorization))
                ToggleRow(
                    label = stringResource(R.string.settings_categorization),
                    icon = Icons.Filled.Category,
                    checked = ui.autoCategorizationEnabled,
                ) { ui = ui.copy(autoCategorizationEnabled = it); onChanged(ui) }
            }
        }

        item {
            FinTrackCard {
                CommonSectionHeader(stringResource(R.string.settings_llm_controls))
                Text(
                    "AI interpretation is always enabled — it processes your SMS through the LLM " +
                        "to extract financial transactions. The SMS body text (unmodified) and a hashed " +
                        "sender identifier are sent to your configured endpoint; nothing else leaves this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsLink(
                    label = "Configure AI (model & connection)",
                    icon = Icons.Filled.AutoAwesome,
                    onClick = onNavigateToLlmSettings,
                )
                llmProcessingViewModel?.let { vm ->
                    val progress by vm.progress.collectAsState()
                    val context = LocalContext.current
                    LlmProcessingBar(
                        progress = progress,
                        onStart = { vm.startScan(context) },
                        onStop = vm::stopScan,
                        onRequestSmsPermission = onRequestSmsPermission,
                        onNavigateToSmsConsent = onNavigateToSmsConsent,
                    )
                }
            }
        }

        item {
            FinTrackCard {
                CommonSectionHeader(stringResource(R.string.settings_data_lifecycle))
                ToggleRow(
                    label = stringResource(R.string.settings_export_data),
                    icon = Icons.Filled.CloudDone,
                    checked = ui.exportIncludeRawEvidence,
                ) { ui = ui.copy(exportIncludeRawEvidence = it); onChanged(ui) }
                Text(
                    stringResource(R.string.settings_export_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            FinTrackCard {
                CommonSectionHeader("Developer")
                SettingsLink("Developer diagnostics", icon = Icons.Filled.BugReport, onClick = onNavigateToDiagnostics)
            }
        }
    }
}

/**
 * Progress bar and trigger for the "Scan ALL SMS through LLM" batch job.
 * Shows progress (processed / total), succeeded / failed counts, and a
 * linear progress indicator while running.
 */
@Composable
private fun LlmProcessingBar(
    progress: com.example.fintrack.application.enrichment.LlmProcessingService.Progress,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestSmsPermission: () -> Unit = {},
    onNavigateToSmsConsent: () -> Unit = {},
) {
    var smsDecision by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CommonSectionHeader("LLM SMS processing")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress.running) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        if (smsDecision == null) {
                            smsDecision = "choose"
                        } else {
                            onStart()
                        }
                    },
                ) { Text("Process all SMS through LLM") }
            }
        }
        if (smsDecision == "choose") {
            Text(
                "To process SMS, FinTrack needs access to your SMS messages. " +
                    "Do you already have SMS backfill set up?",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onRequestSmsPermission()
                    onNavigateToSmsConsent()
                    smsDecision = "proceed"
                }) { Text("Grant SMS access & backfill") }
                OutlinedButton(onClick = {
                    smsDecision = "proceed"
                }) { Text("Already have SMS; proceed") }
            }
        }
        if (progress.running || progress.total > 0) {
            LlmProgressSummary(progress)
        }
        if (progress.status == "COMPLETE") {
            Text("All SMS processed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (progress.status == "PARTIAL") {
            Text(
                "Completed with ${progress.failed} failures.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        progress.lastError?.let { err ->
            Text("Error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CommonSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.Violet,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Row with 48dp+ touch target and switch semantics for screen readers. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, containerColor = Palette.SurfaceHigh, tint = Palette.TextSecondary, size = 36.dp)
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Violet),
            modifier = Modifier.semantics {
                role = Role.Switch
                contentDescription = "$label: ${if (checked) "on" else "off"}"
            },
        )
    }
}

@Composable
private fun SettingsLink(label: String, icon: ImageVector? = null, onClick: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label) { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, containerColor = Palette.SurfaceHigh, tint = Palette.TextSecondary, size = 36.dp)
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Palette.TextMuted)
    }
}
