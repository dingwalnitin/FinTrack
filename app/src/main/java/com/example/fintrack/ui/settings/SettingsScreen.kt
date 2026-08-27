package com.example.fintrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.fintrack.R
import com.example.fintrack.application.enrichment.LlmProcessingViewModel
import com.example.fintrack.llm.LlmConfig

/**
 * Settings shell. Sections only — no API keys, tokens or secrets are ever
 * displayed here. Data-lifecycle entry points keep exports on-device.
 *
 * Safe defaults: AI interpretation OFF until the user opts in; exports
 * redact raw evidence by default.
 */
data class SettingsUiModel(
    val aiInterpretationEnabled: Boolean = false,   // safe default: off
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
    onRequestSmsPermission: () -> Unit = {},
    llmProcessingViewModel: LlmProcessingViewModel? = null,
) {
    var ui by remember { mutableStateOf(model) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.titleLarge)
        SectionHeader(stringResource(R.string.settings_accounts_sources))
        SettingsLink(stringResource(R.string.nav_accounts))
        SettingsLink("Scan SMS messages", onClick = onNavigateToSmsConsent)
        SectionHeader(stringResource(R.string.settings_categorization))
        ToggleRow(
            label = stringResource(R.string.settings_categorization),
            checked = ui.autoCategorizationEnabled,
        ) { ui = ui.copy(autoCategorizationEnabled = it); onChanged(ui) }
        SectionHeader(stringResource(R.string.settings_llm_controls))
        ToggleRow(
            label = stringResource(R.string.settings_llm_controls),
            checked = ui.aiInterpretationEnabled,
        ) { ui = ui.copy(aiInterpretationEnabled = it); onChanged(ui) }
        Text(
            "AI interpretation calls an OpenAI-compatible Chat Completions endpoint. " +
                "Raw SMS stays on this device; only normalized text is sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LlmConfigFields(
            config = ui.llmConfig,
            onConfigChanged = { cfg -> ui = ui.copy(llmConfig = cfg); onChanged(ui) },
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
        SectionHeader(stringResource(R.string.settings_data_lifecycle))
        ToggleRow(
            label = stringResource(R.string.settings_export_data),
            checked = ui.exportIncludeRawEvidence,
        ) { ui = ui.copy(exportIncludeRawEvidence = it); onChanged(ui) }
        Text(
            stringResource(R.string.settings_export_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionHeader("Developer")
        SettingsLink("Developer diagnostics", onClick = onNavigateToDiagnostics)
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
        SectionHeader("LLM SMS processing")
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
            Text(
                "Processed ${progress.processed} / ${progress.total} SMS " +
                    "(${progress.succeeded} succeeded, ${progress.failed} failed)",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                    else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
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

/** Editable fields for the Chat Completions API configuration. */
@Composable
private fun LlmConfigFields(
    config: LlmConfig,
    onConfigChanged: (LlmConfig) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var modelId by remember { mutableStateOf(config.modelId) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saved = false },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com") },
            supportingText = { Text("e.g. https://api.openai.com or https://your-gateway.example") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showKey) "Hide" else "Show",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { showKey = !showKey }
                        .padding(8.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it; saved = false },
            label = { Text("Model ID") },
            placeholder = { Text("gpt-4o-mini") },
            supportingText = { Text("Any model id supported by your endpoint, e.g. gpt-4o-mini, llama-3.3-70b") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onConfigChanged(LlmConfig(baseUrl.trim(), apiKey.trim(), modelId.trim()))
                saved = true
            },
            enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save API configuration") }
        if (saved) {
            Text(
                "Saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    HorizontalDivider()
}

/** Row with 48dp+ touch target and switch semantics for screen readers. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics {
                role = Role.Switch
                contentDescription = "$label: ${if (checked) "on" else "off"}"
            },
        )
    }
}

@Composable
private fun SettingsLink(label: String, onClick: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label) { onClick() }
            .padding(vertical = 8.dp),
    ) {
        Text(label)
    }
}
