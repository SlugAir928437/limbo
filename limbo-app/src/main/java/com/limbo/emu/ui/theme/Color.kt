package com.limbo.emu.ui.theme

import androidx.compose.ui.graphics.Color

// 状态指示色与 Miuix（HyperOS）动态主题解耦：虚拟机运行状态语义固定，
// 不随壁纸取色 / 深浅色模式变化，因此这里保留固定色值。
val StatusRunning = Color(0xFF4CAF50)
val StatusStopped = Color(0xFF9E9E9E)
val StatusPaused = Color(0xFFFFC107)
val StatusSaving = Color(0xFF2196F3)
