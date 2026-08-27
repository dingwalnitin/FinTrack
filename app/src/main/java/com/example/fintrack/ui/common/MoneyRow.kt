package com.example.fintrack.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.ui.theme.LocalMoneySemantics
import com.example.fintrack.R

/**
 * Dense finance row primitive: compact height, title left, right-aligned
 * amount with explicit currency, and semantic debit/credit styling that is
 * never color-only (sign glyph + spoken label).
 */
data class MoneyRowData(
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val isDebit: Boolean,
)

@Composable
fun MoneyRow(data: MoneyRowData) {
    val money = LocalMoneySemantics.current
    val directionLabel = stringResource(if (data.isDebit) R.string.debit_label else R.string.credit_label)
    val sign = stringResource(if (data.isDebit) R.string.amount_debit_prefix else R.string.amount_credit_prefix)
    val formatted = formatMinor(kotlin.math.abs(data.amountMinor))
    val amountText = "$sign$formatted ${data.currencyCode}"
    // Screen-reader meaningful label: direction + amount + title, not just visuals.
    val a11y = "$directionLabel $amountText. ${data.title}"

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(data.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                directionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = amountText,
            style = MaterialTheme.typography.labelLarge,
            color = if (data.isDebit) money.debit else money.credit,
        )
    }
}

/** Integer minor-units display formatting; persistence never uses float. */
fun formatMinor(minor: Long): String {
    val negative = minor < 0
    val abs = kotlin.math.abs(minor)
    return "${if (negative) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
