package com.example.fintrack.ui.common

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fintrack.ui.theme.LocalMoneySemantics
import com.example.fintrack.ui.theme.categoryColor
import com.example.fintrack.ui.theme.categoryIcon
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
    /** Small line under the title, e.g. category or time — purely cosmetic. */
    val subtitle: String? = null,
    /** Drives the leading icon/color hash; falls back to [title] when null. */
    val categoryLabel: String? = null,
)

@Composable
fun MoneyRow(data: MoneyRowData, onClick: (() -> Unit)? = null) {
    val money = LocalMoneySemantics.current
    val directionLabel = stringResource(if (data.isDebit) R.string.debit_label else R.string.credit_label)
    val sign = stringResource(if (data.isDebit) R.string.amount_debit_prefix else R.string.amount_credit_prefix)
    val formatted = formatMinor(kotlin.math.abs(data.amountMinor))
    val amountText = "$sign$formatted ${data.currencyCode}"
    // Screen-reader meaningful label: direction + amount + title, not just visuals.
    val a11y = "$directionLabel $amountText. ${data.title}"
    val hashKey = data.categoryLabel ?: data.title
    val tint = categoryColor(hashKey)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = categoryIcon(hashKey),
            containerColor = tint.copy(alpha = 0.18f),
            tint = tint,
            size = 44.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                data.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                data.subtitle ?: directionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
