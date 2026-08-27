package com.example.fintrack.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.Category

/**
 * Stage 7 P14 #6 (absorbing module 141): parent/child category picker.
 *
 * Navigation model: the picker shows the children of the currently
 * selected parent (or top-level roots when no parent is selected).
 * A breadcrumb row lets the user walk back up. Selecting a leaf fires
 * [onSelected]; selecting a parent drills in.
 *
 * The uncategorized root is always offered as an explicit choice so
 * "unknown" stays a first-class, honest state.
 */
@Composable
fun CategoryPicker(
    allCategories: List<Category>,
    selectedCategoryId: String?,
    onSelected: (Category) -> Unit,
) {
    var currentParentId by remember { mutableStateOf<String?>(null) }

    val roots = allCategories.filter { it.parentId == null && !it.isUncategorizedRoot }
    val uncategorized = allCategories.filter { it.isUncategorizedRoot }
    val children = allCategories.filter { it.parentId?.value == currentParentId }
    val visible = if (currentParentId == null) roots + uncategorized else children
    val currentParent = allCategories.firstOrNull { it.id.value == currentParentId }

    Column(Modifier.fillMaxWidth()) {
        // Breadcrumb navigation.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            FilterChip(
                selected = currentParentId == null,
                onClick = { currentParentId = null },
                label = { Text("Top") },
            )
            if (currentParent != null) {
                Text(
                    "› ${currentParent.name}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visible, key = { it.id.value }) { category ->
                val hasChildren = allCategories.any { it.parentId == category.id }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedCategoryId == category.id.value,
                        onClick = {
                            if (hasChildren) currentParentId = category.id.value else onSelected(category)
                        },
                        label = {
                            Text(if (hasChildren) "${category.name} ›" else category.name)
                        },
                    )
                }
            }
        }
    }
}
