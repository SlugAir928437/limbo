package com.max2idea.android.limbo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose UI state for a storage device row.
 * Mirrors the old StorageDeviceEntry view fields as observable state.
 */
class StorageDeviceUiState {
    var typeOptions by mutableStateOf(listOf<String>())
    var typeSel by mutableStateOf(0)
    var sizeValue by mutableStateOf("4")
    var sizeUnitOptions by mutableStateOf(listOf("GB", "MB", "TB"))
    var sizeUnitSel by mutableStateOf(0)
    var imageOptions by mutableStateOf(listOf("None"))
    var imageSel by mutableStateOf(0)
    var showSize by mutableStateOf(false)
    var enabled by mutableStateOf(true)

    // backing fields copied from the old DeviceType
    var removable by mutableStateOf(false)
    var createImage by mutableStateOf(false)
    var sharedFolder by mutableStateOf(false)
    var hardDiskSlot by mutableStateOf(-1)

    // transient identity used by the activity to map back to its entry
    var tag by mutableStateOf(0)
}

/**
 * Identifies a collapsible section of the main configuration screen.
 * Using an enum instead of string literals gives compile-time safety:
 * adding a new section forces the [LimboUiState.toggleSection] `when`
 * (and any other exhaustive handling) to be updated.
 */
enum class Section {
    UI,
    BOARD,
    STORAGE,
    BOOT,
    GRAPHICS,
    AUDIO,
    NETWORK,
    ADVANCED
}

/**
 * Central observable UI state for LimboMainScreen.
 * Every field is backed by Compose snapshot state so the UI recomposes
 * whenever business logic updates it.
 */
class LimboUiState {
    // machine
    var machines by mutableStateOf(listOf<String>())
    var machineSel by mutableStateOf(0)
    var machineEnabled by mutableStateOf(true)

    // status
    var statusText by mutableStateOf("")
    var statusRunning by mutableStateOf(false)
    // 0 = stopped, 1 = running, 2 = paused, 3 = saving (used to pick the status color)
    var statusKind by mutableStateOf(0)

    // user interface section
    var uiOptions by mutableStateOf(listOf<String>())
    var uiSel by mutableStateOf(0)
    var uiEnabled by mutableStateOf(true)
    var keyboardOptions by mutableStateOf(listOf<String>())
    var keyboardSel by mutableStateOf(0)
    var keyboardEnabled by mutableStateOf(true)
    var mouseOptions by mutableStateOf(listOf<String>())
    var mouseSel by mutableStateOf(0)
    var mouseEnabled by mutableStateOf(true)

    // board section
    var machineTypeOptions by mutableStateOf(listOf<String>())
    var machineTypeSel by mutableStateOf(0)
    var machineTypeEnabled by mutableStateOf(true)
    var cpuOptions by mutableStateOf(listOf<String>())
    var cpuSel by mutableStateOf(0)
    var cpuEnabled by mutableStateOf(true)
    // CPU core count is a free-form numeric input
    var cpuNumValue by mutableStateOf("1")
    var cpuNumEnabled by mutableStateOf(true)
    // RAM size (MB) is a free-form numeric input
    var ramValue by mutableStateOf("512")
    var ramEnabled by mutableStateOf(true)
    var enableKVM by mutableStateOf(false)
    var enableKVMEnabled by mutableStateOf(true)
    var disableI8042 by mutableStateOf(true)
    var disableI8042Enabled by mutableStateOf(false)
    var enableNvram by mutableStateOf(true)
    var enableNvramEnabled by mutableStateOf(false)
    var nvramOptions by mutableStateOf(listOf<String>())
    var nvramSel by mutableStateOf(0)
    var nvramEnabled by mutableStateOf(false)
    var enableMTTCG by mutableStateOf(false)
    var enableMTTCGEnabled by mutableStateOf(true)
    var disableACPI by mutableStateOf(false)
    var disableACPIEnabled by mutableStateOf(true)
    var disableHPET by mutableStateOf(false)
    var disableHPETEnabled by mutableStateOf(true)
    var disableTSC by mutableStateOf(false)
    var disableTSCEnabled by mutableStateOf(true)

    // storage devices
    var storageDevices by mutableStateOf(listOf<StorageDeviceUiState>())
    var addDeviceEnabled by mutableStateOf(true)

    // boot section
    var bootOptions by mutableStateOf(listOf<String>())
    var bootSel by mutableStateOf(0)
    var bootEnabled by mutableStateOf(true)
    var biosOptions by mutableStateOf(listOf<String>())
    var biosSel by mutableStateOf(0)
    var biosEnabled by mutableStateOf(true)
    var kernelOptions by mutableStateOf(listOf<String>())
    var kernelSel by mutableStateOf(0)
    var kernelEnabled by mutableStateOf(true)
    var initrdOptions by mutableStateOf(listOf<String>())
    var initrdSel by mutableStateOf(0)
    var initrdEnabled by mutableStateOf(true)
    var append by mutableStateOf("")
    var appendEnabled by mutableStateOf(true)

    // graphics section
    var vgaOptions by mutableStateOf(listOf<String>())
    var vgaSel by mutableStateOf(0)
    var vgaEnabled by mutableStateOf(true)

    // audio section
    var soundOptions by mutableStateOf(listOf<String>())
    var soundSel by mutableStateOf(0)
    var soundEnabled by mutableStateOf(true)

    // network section
    var netOptions by mutableStateOf(listOf<String>())
    var netSel by mutableStateOf(0)
    var netEnabled by mutableStateOf(true)
    var nicOptions by mutableStateOf(listOf<String>())
    var nicSel by mutableStateOf(0)
    var nicEnabled by mutableStateOf(true)
    var dns by mutableStateOf("")
    var dnsEnabled by mutableStateOf(true)
    var hostFwd by mutableStateOf("")
    var hostFwdEnabled by mutableStateOf(true)

    // advanced section
    var extraParams by mutableStateOf("")
    var extraParamsEnabled by mutableStateOf(true)

    // section collapse state
    var uiCollapsed by mutableStateOf(true)
    var boardCollapsed by mutableStateOf(true)
    var storageCollapsed by mutableStateOf(true)
    var bootCollapsed by mutableStateOf(true)
    var graphicsCollapsed by mutableStateOf(false)
    var audioCollapsed by mutableStateOf(true)
    var networkCollapsed by mutableStateOf(true)
    var advancedCollapsed by mutableStateOf(true)

    // section summaries
    var uiSummary by mutableStateOf("")
    var boardSummary by mutableStateOf("")
    var storageSummary by mutableStateOf("")
    var bootSummary by mutableStateOf("")
    var graphicsSummary by mutableStateOf("")
    var audioSummary by mutableStateOf("")
    var networkSummary by mutableStateOf("")
    var advancedSummary by mutableStateOf("")

    /**
     * Toggles the collapsed state of the given [section].
     * The `when` is exhaustive over [Section], so adding a new section
     * produces a compile error here instead of silently doing nothing.
     */
    fun toggleSection(section: Section) {
        when (section) {
            Section.UI -> uiCollapsed = !uiCollapsed
            Section.BOARD -> boardCollapsed = !boardCollapsed
            Section.STORAGE -> storageCollapsed = !storageCollapsed
            Section.BOOT -> bootCollapsed = !bootCollapsed
            Section.GRAPHICS -> graphicsCollapsed = !graphicsCollapsed
            Section.AUDIO -> audioCollapsed = !audioCollapsed
            Section.NETWORK -> networkCollapsed = !networkCollapsed
            Section.ADVANCED -> advancedCollapsed = !advancedCollapsed
        }
    }
}
