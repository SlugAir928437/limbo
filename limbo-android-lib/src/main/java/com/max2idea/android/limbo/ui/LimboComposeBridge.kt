package com.max2idea.android.limbo.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.max2idea.android.limbo.ui.theme.LimboTheme
import com.max2idea.android.limbo.ui.theme.StatusPaused
import com.max2idea.android.limbo.ui.theme.StatusRunning
import com.max2idea.android.limbo.ui.theme.StatusSaving
import com.max2idea.android.limbo.ui.theme.StatusStopped

/**
 * Kotlin bridge used by the Java [ComponentActivity] to host the Compose UI.
 * Keeps the Java business logic layer free of Compose/kotlin lambdas.
 */
object LimboComposeBridge {

    @JvmStatic
    fun setContent(
        activity: ComponentActivity,
        state: LimboUiState,
        callbacks: LimboUiCallbacks
    ) {
        activity.setContent {
            LimboTheme {
                LimboMainScreen(
                    state = state,
                    callbacks = callbacks,
                    statusColor = statusColor(state.statusKind),
                    onToggleSection = { section -> toggleSection(state, section) }
                )
            }
        }
    }

    @Composable
    private fun statusColor(kind: Int): androidx.compose.ui.graphics.Color = when (kind) {
        1 -> StatusRunning
        2 -> StatusPaused
        3 -> StatusSaving
        else -> StatusStopped
    }

    private fun toggleSection(state: LimboUiState, section: String) {
        when (section) {
            "ui" -> state.uiCollapsed = !state.uiCollapsed
            "board" -> state.boardCollapsed = !state.boardCollapsed
            "storage" -> state.storageCollapsed = !state.storageCollapsed
            "boot" -> state.bootCollapsed = !state.bootCollapsed
            "graphics" -> state.graphicsCollapsed = !state.graphicsCollapsed
            "audio" -> state.audioCollapsed = !state.audioCollapsed
            "network" -> state.networkCollapsed = !state.networkCollapsed
            "advanced" -> state.advancedCollapsed = !state.advancedCollapsed
        }
    }
}
