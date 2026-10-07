package com.limbo.emu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Limbo 应用主题，基于 Miuix（小米 HyperOS / MIUI 设计系统）。
 *
 * - [dynamicColor] 为 true 时使用 Monet 动态取色（跟随系统壁纸，HyperOS 原生观感）；
 *   为 false 时退化为 Miuix 内置的默认浅色 / 深色配色方案。
 * - [darkTheme] 显式指定深浅色；由调用方传入（默认跟随系统），保持与原 Material 3 主题一致的行为。
 *
 * 主题值通过 [MiuixTheme.colorScheme] 与 [MiuixTheme.textStyles] 读取。
 */
@Composable
fun LimboTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色（HyperOS / Material You）：Android 12+ 读取壁纸种子色
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val mode = if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    val controller = remember(mode, darkTheme) {
        ThemeController(
            colorSchemeMode = mode,
            isDark = darkTheme
        )
    }
    MiuixTheme(
        controller = controller,
        content = content
    )
}
