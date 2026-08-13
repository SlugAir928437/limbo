package com.max2idea.android.limbo.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Material 3 dropdown field that replaces the legacy android.widget.Spinner.
 * Shows the selected option and opens a menu on click.
 *
 * NOTE: the menu items must be rendered directly (not inside a LazyColumn):
 * ExposedDropdownMenu applies Modifier.exposedDropdownSize() which queries
 * intrinsic measurements, and lazy lists (SubcomposeLayout) do not support
 * intrinsic measurement and crash. Option lists are bounded anyway
 * (CPU/RAM are free-form inputs now), so direct rendering stays smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimboDropdown(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    displayTransform: (String) -> String = { it },
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = if (selectedIndex in options.indices) selectedIndex else 0
    val selected = options.getOrNull(safeIndex) ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayTransform(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayTransform(option),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}
