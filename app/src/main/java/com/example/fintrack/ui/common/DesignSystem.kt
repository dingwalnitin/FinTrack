package com.example.fintrack.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.ui.theme.BrandGradient
import com.example.fintrack.ui.theme.Palette

/**
 * Shared "premium dark fintech" design-system primitives used by every
 * screen so the whole app renders as one consistent product, matching the
 * reference mockups (see design_screens/ in the repo root).
 */

/** Rounded card wrapper with the app's standard elevation-free surface look. */
@Composable
fun FinTrackCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Section title with an optional right-aligned text action ("See all"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Violet,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** Colored circular icon container used for categories, accounts and merchants. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    containerColor: Color = Palette.SurfaceHigh,
    tint: Color = Color.White,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Big gradient hero card — used for the Home total-balance summary. */
@Composable
fun GradientHeroCard(
    label: String,
    amountText: String,
    modifier: Modifier = Modifier,
    brush: Brush = BrandGradient,
    trailing: @Composable RowScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .padding(20.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                trailing()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                amountText,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "$label $amountText" },
            )
            Spacer(Modifier.height(16.dp))
            footer()
        }
    }
}

/** Two-option pill toggle (e.g. "Category" / "Merchant"). */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Palette.Violet else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Circular progress ring (e.g. overall budget usage), gradient-capable stroke. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
    trackColor: Color = Palette.SurfaceHigh,
    progressColor: Color = Palette.Violet,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
            )
        }
        content()
    }
}

/** One donut-chart slice: share of the whole + its display color. */
data class DonutSlice(val label: String, val value: Float, val color: Color)

/** Multi-slice donut chart used for category spend breakdowns. */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat().let { if (it <= 0f) 1f else it }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
            val inset = strokeWidth.toPx() / 2
            var startAngle = -90f
            val gapDeg = if (slices.size > 1) 3f else 0f
            slices.forEach { slice ->
                val sweep = (360f * (slice.value / total) - gapDeg).coerceAtLeast(0f)
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                )
                startAngle += 360f * (slice.value / total)
            }
        }
        content()
    }
}

/** Ranked row with a proportional bar — used for "top merchants" style lists. */
@Composable
fun RankedBarRow(
    label: String,
    amountText: String,
    ratio: Float,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) {
                    IconBadge(icon = icon, containerColor = color.copy(alpha = 0.20f), tint = color, size = 34.dp)
                }
                Column {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(amountText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.SurfaceHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
    }
}

/** Small non-color-only status pill (symbol + label), used for budgets/review. */
@Composable
fun StatusPill(status: ProgressStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status.symbol, style = MaterialTheme.typography.labelMedium, color = color)
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

fun statusColor(status: ProgressStatus): Color = when (status) {
    ProgressStatus.UNDER -> Palette.Income
    ProgressStatus.NEAR_LIMIT -> Palette.Warn
    ProgressStatus.OVER -> Palette.Danger
}

/** Brand-tinted filter chip: violet fill when selected, subtle outline otherwise. */
@Composable
fun BrandFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = Palette.Violet,
            selectedLabelColor = Color.White,
        ),
    )
}

/** Gradient floating action button — used for the "add transaction" affordance. */
@Composable
fun GradientFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescriptionText: String = "Add transaction",
) {
    Box(
        modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .semantics { contentDescription = contentDescriptionText },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
    }
}
