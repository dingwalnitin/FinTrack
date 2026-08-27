package com.example.fintrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.R

/**
 * Cross-cutting UI state components used by every feature.
 * Bounded, screen-reader-labelled states — never an infinite spinner for AI work:
 * processing/pending always shows what is pending and offers dismissal/retry.
 */

/** Full-screen state container with a semantic label for TalkBack. */
@Composable
fun StateContainer(
    stateLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = stateLabel },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun EmptyState(message: String = stringResource(R.string.state_empty)) {
    StateContainer(stateLabel = message) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Skeleton loading: bounded shimmer-free placeholders. Deterministic and
 * test-friendly; announced as "loading" to screen readers.
 */
@Composable
fun LoadingSkeleton(rows: Int = 6) {
    val label = stringResource(R.string.state_loading)
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows.coerceAtMost(20)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(40.dp).background(
                        MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)
                    )
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth(0.55f).height(14.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)
                        )
                    )
                    Box(
                        Modifier.fillMaxWidth(0.35f).height(12.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)
                        )
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Actionable error: message + retry/dismiss. Never a dead end.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    StateContainer(stateLabel = message) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.state_error_title), style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                if (onDismiss != null) OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

/**
 * Processing/pending state for AI/background work. Always bounded: shows the
 * pending description and a cancel affordance instead of an infinite spinner.
 */
@Composable
fun ProcessingState(description: String, onCancel: (() -> Unit)? = null) {
    val label = stringResource(R.string.state_processing)
    StateContainer(stateLabel = "$label: $description") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
            Text("$label — $description", style = MaterialTheme.typography.bodyMedium)
            if (onCancel != null) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

/**
 * Review/uncertainty state: surfaces items needing user confirmation with an
 * explicit reason chip (non-color-only cue).
 */
@Composable
fun ReviewBanner(reason: String, onReview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${stringResource(R.string.state_review)}: $reason",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onReview) { Text(stringResource(R.string.action_review)) }
    }
}
