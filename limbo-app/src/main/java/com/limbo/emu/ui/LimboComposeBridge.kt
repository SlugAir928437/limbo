package com.limbo.emu.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.limbo.emu.ui.theme.LimboTheme
import com.limbo.emu.ui.theme.StatusPaused
import com.limbo.emu.ui.theme.StatusRunning
import com.limbo.emu.ui.theme.StatusSaving
import com.limbo.emu.ui.theme.StatusStopped

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
                    onToggleSection = state::toggleSection
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
}
