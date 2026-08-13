package com.max2idea.android.limbo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Collapsible Material 3 section card with icon, title and summary header.
 * Replaces the old XML section cards (board/audio/network/etc).
 */
@Composable
fun SectionCard(
    title: String,
    iconRes: Int,
    summary: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (summary.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = if (collapsed) "⌄" else "⌃",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(vertical = 8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * A settings row: leading icon + label on the left, trailing composable on the right.
 */
@Composable
fun SettingRow(
    label: String,
    iconRes: Int?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/**
 * Label + switch row, Material 3 style.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Label + text input row (Material 3 OutlinedTextField).
 */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(0.5f)
                .padding(end = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Small colored status dot used by the status card.
 */
@Composable
fun StatusDot(color: Color, size: Int = 16) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}
