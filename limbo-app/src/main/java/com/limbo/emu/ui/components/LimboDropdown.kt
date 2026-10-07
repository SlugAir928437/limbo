package com.limbo.emu.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * Miuix（HyperOS）风格的下拉选择项：整行可点，右侧显示当前值，点击后弹出选项列表。
 *
 * 替代原来的 Material 3 `ExposedDropdownMenuBox` / `android.widget.Spinner`。
 *
 * 注意：弹层由 Miuix `Scaffold` 提供的 `MiuixPopupHost` 承载，
 * 因此本组件必须放在 Miuix `Scaffold` 内部使用。
 */
@Composable
fun LimboDropdown(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    summary: String? = null,
    displayTransform: (String) -> String = { it },
    onSelected: (Int) -> Unit
) {
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        items = options.map(displayTransform),
        selectedIndex = selectedIndex,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        startAction = { if (iconRes != null) DropdownStartIcon(iconRes) },
        onSelectedIndexChange = onSelected
    )
}

/** 下拉项左侧的图标（保持 PNG 原色，不做 tint）。 */
@Composable
private fun DropdownStartIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier
            .padding(end = 12.dp)
            .size(24.dp),
        tint = Color.Unspecified
    )
}
