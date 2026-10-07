package com.limbo.emu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.limbo.emu.R
import com.limbo.emu.ui.components.LabeledTextFieldRow
import com.limbo.emu.ui.components.LimboDropdown
import com.limbo.emu.ui.components.SectionCard
import com.limbo.emu.ui.components.StatusDot
import com.limbo.emu.ui.components.SwitchRow
import com.limbo.emu.ui.theme.StatusStopped

/**
 * Callbacks invoked by the Compose UI. Implemented by LimboActivity.
 */
interface LimboUiCallbacks {
    fun onMachineSelected(index: Int)
    fun onStartVm()
    fun onPauseVm()
    fun onStopVm()
    fun onRestartVm()
    fun onAddStorageDevice()
    fun onStorageDeviceClicked(deviceTag: Int)
    fun onOpenMenu()

    // user interface section
    fun onUiSelected(index: Int)
    fun onKeyboardSelected(index: Int)
    fun onMouseSelected(index: Int)

    // board section
    fun onMachineTypeSelected(index: Int)
    fun onCpuSelected(index: Int)
    fun onCpuNumChanged(value: String)
    fun onRamChanged(value: String)
    fun onDisableI8042Changed(checked: Boolean)
    fun onEnableNvramChanged(checked: Boolean)
    fun onNvramSelected(index: Int)
    fun onAccelSelected(index: Int)
    fun onEnableMTTCGChanged(checked: Boolean)
    fun onDisableHPETChanged(checked: Boolean)
    fun onDisableTSCChanged(checked: Boolean)
    fun onDisableACPIChanged(checked: Boolean)

    // boot section
    fun onBootSelected(index: Int)
    fun onBiosSelected(index: Int)
    fun onKernelSelected(index: Int)
    fun onInitrdSelected(index: Int)
    fun onAppendChanged(value: String)

    // graphics
    fun onVgaSelected(index: Int)

    // audio
    fun onSoundSelected(index: Int)

    // network
    fun onNetSelected(index: Int)
    fun onNicSelected(index: Int)
    fun onDnsChanged(value: String)
    fun onHostFwdChanged(value: String)

    // advanced
    fun onExtraParamsChanged(value: String)
    fun onUsbControllerSelected(index: Int)
}

/**
 * Main configuration screen for Limbo, rendered with Miuix (Xiaomi HyperOS design system).
 */
@Composable
fun LimboMainScreen(
    state: LimboUiState,
    callbacks: LimboUiCallbacks,
    statusColor: Color = StatusStopped,
    onToggleSection: (Section) -> Unit
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.app_name),
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.limbo),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color.Unspecified
                    )
                },
                actions = {
                    IconButton(onClick = { callbacks.onOpenMenu() }) {
                        Text("⋮", style = MiuixTheme.textStyles.title3)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item { MachineCard(state = state, callbacks = callbacks) }
            item { StatusCard(state = state, statusColor = statusColor) }
            item { ControlButtons(state = state, callbacks = callbacks) }

            // User Interface section
            item {
                SectionCard(
                    title = stringResource(R.string.title_user_interface),
                    iconRes = R.drawable.ui,
                    summary = state.uiSummary,
                    collapsed = state.uiCollapsed,
                    onToggle = { onToggleSection(Section.UI) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_display),
                        iconRes = R.drawable.ui,
                        options = state.uiOptions,
                        selectedIndex = state.uiSel,
                        enabled = state.uiEnabled,
                        onSelected = { callbacks.onUiSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.title_keyboard),
                        iconRes = R.drawable.keyboard,
                        options = state.keyboardOptions,
                        selectedIndex = state.keyboardSel,
                        enabled = state.keyboardEnabled,
                        onSelected = { callbacks.onKeyboardSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.title_mouse),
                        iconRes = R.drawable.mouse,
                        options = state.mouseOptions,
                        selectedIndex = state.mouseSel,
                        enabled = state.mouseEnabled,
                        onSelected = { callbacks.onMouseSelected(it) }
                    )
                }
            }

            // Board section
            item {
                SectionCard(
                    title = stringResource(R.string.title_board),
                    iconRes = R.drawable.machinetype,
                    summary = state.boardSummary,
                    collapsed = state.boardCollapsed,
                    onToggle = { onToggleSection(Section.BOARD) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_machine_type),
                        iconRes = R.drawable.machinetype,
                        options = state.machineTypeOptions,
                        selectedIndex = state.machineTypeSel,
                        enabled = state.machineTypeEnabled,
                        onSelected = { callbacks.onMachineTypeSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_cpu_model),
                        iconRes = R.drawable.cpu,
                        options = state.cpuOptions,
                        selectedIndex = state.cpuSel,
                        enabled = state.cpuEnabled,
                        onSelected = { callbacks.onCpuSelected(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_cpu_cores),
                        value = state.cpuNumValue,
                        enabled = state.cpuNumEnabled,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { callbacks.onCpuNumChanged(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_ram_memory_mb),
                        value = state.ramValue,
                        enabled = state.ramEnabled,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { callbacks.onRamChanged(it) }
                    )
                    SwitchRow(
                        label = stringResource(R.string.label_disable_i8042),
                        checked = state.disableI8042,
                        enabled = state.disableI8042Enabled,
                        onCheckedChange = { callbacks.onDisableI8042Changed(it) }
                    )
                    SwitchRow(
                        label = stringResource(R.string.label_enable_nvram),
                        checked = state.enableNvram,
                        enabled = state.enableNvramEnabled,
                        onCheckedChange = { callbacks.onEnableNvramChanged(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_nvram_file),
                        iconRes = R.drawable.sysfile,
                        options = state.nvramOptions,
                        selectedIndex = state.nvramSel,
                        enabled = state.enableNvram && state.nvramEnabled,
                        onSelected = { callbacks.onNvramSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_accel),
                        iconRes = R.drawable.cpu,
                        options = state.accelOptions,
                        selectedIndex = state.accelSel,
                        enabled = state.accelEnabled,
                        onSelected = { callbacks.onAccelSelected(it) }
                    )
                    if (state.showMTTCGSwitch) {
                        SwitchRow(
                            label = stringResource(R.string.label_enable_mttcg),
                            checked = state.enableMTTCG,
                            enabled = state.enableMTTCGEnabled,
                            onCheckedChange = { callbacks.onEnableMTTCGChanged(it) }
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.label_disable_hpet),
                        checked = state.disableHPET,
                        enabled = state.disableHPETEnabled,
                        onCheckedChange = { callbacks.onDisableHPETChanged(it) }
                    )
                    SwitchRow(
                        label = stringResource(R.string.label_disable_tsc),
                        checked = state.disableTSC,
                        enabled = state.disableTSCEnabled,
                        onCheckedChange = { callbacks.onDisableTSCChanged(it) }
                    )
                    SwitchRow(
                        label = stringResource(R.string.label_disable_acpi),
                        checked = state.disableACPI,
                        enabled = state.disableACPIEnabled,
                        onCheckedChange = { callbacks.onDisableACPIChanged(it) }
                    )
                }
            }

            // Storage devices section
            item {
                SectionCard(
                    title = stringResource(R.string.title_storage_devices),
                    iconRes = R.drawable.harddisk,
                    summary = state.storageSummary,
                    collapsed = state.storageCollapsed,
                    onToggle = { onToggleSection(Section.STORAGE) }
                ) {
                    state.storageDevices.forEach { device ->
                        StorageDeviceSummaryRow(
                            device = device,
                            onClick = { callbacks.onStorageDeviceClicked(device.tag) }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Button(
                            onClick = { callbacks.onAddStorageDevice() },
                            enabled = state.addDeviceEnabled,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.add_storage_device))
                        }
                    }
                }
            }

            // Boot section
            item {
                SectionCard(
                    title = stringResource(R.string.title_boot),
                    iconRes = R.drawable.drives,
                    summary = state.bootSummary,
                    collapsed = state.bootCollapsed,
                    onToggle = { onToggleSection(Section.BOOT) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_boot_from_device),
                        iconRes = R.drawable.drives,
                        options = state.bootOptions,
                        selectedIndex = state.bootSel,
                        enabled = state.bootEnabled,
                        onSelected = { callbacks.onBootSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_bios),
                        iconRes = R.drawable.sysfile,
                        options = state.biosOptions,
                        selectedIndex = state.biosSel,
                        enabled = state.biosEnabled,
                        onSelected = { callbacks.onBiosSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_kernel),
                        iconRes = R.drawable.sysfile,
                        options = state.kernelOptions,
                        selectedIndex = state.kernelSel,
                        enabled = state.kernelEnabled,
                        onSelected = { callbacks.onKernelSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_initrd),
                        iconRes = R.drawable.sysfile,
                        options = state.initrdOptions,
                        selectedIndex = state.initrdSel,
                        enabled = state.initrdEnabled,
                        onSelected = { callbacks.onInitrdSelected(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_append),
                        value = state.append,
                        enabled = state.appendEnabled,
                        placeholder = "root=/dev/sda1",
                        onValueChange = { callbacks.onAppendChanged(it) }
                    )
                }
            }

            // Graphics section
            item {
                SectionCard(
                    title = stringResource(R.string.title_graphics),
                    iconRes = R.drawable.screen,
                    summary = state.graphicsSummary,
                    collapsed = state.graphicsCollapsed,
                    onToggle = { onToggleSection(Section.GRAPHICS) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_video_display),
                        iconRes = R.drawable.screen,
                        options = state.vgaOptions,
                        selectedIndex = state.vgaSel,
                        enabled = state.vgaEnabled,
                        onSelected = { callbacks.onVgaSelected(it) }
                    )
                }
            }

            // Audio section
            item {
                SectionCard(
                    title = stringResource(R.string.title_audio),
                    iconRes = R.drawable.audiocard,
                    summary = state.audioSummary,
                    collapsed = state.audioCollapsed,
                    onToggle = { onToggleSection(Section.AUDIO) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_sound_card),
                        iconRes = R.drawable.audiocard,
                        options = state.soundOptions,
                        selectedIndex = state.soundSel,
                        enabled = state.soundEnabled,
                        onSelected = { callbacks.onSoundSelected(it) }
                    )
                }
            }

            // Network section
            item {
                SectionCard(
                    title = stringResource(R.string.label_network),
                    iconRes = R.drawable.network,
                    summary = state.networkSummary,
                    collapsed = state.networkCollapsed,
                    onToggle = { onToggleSection(Section.NETWORK) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_network),
                        iconRes = R.drawable.network,
                        options = state.netOptions,
                        selectedIndex = state.netSel,
                        enabled = state.netEnabled,
                        onSelected = { callbacks.onNetSelected(it) }
                    )
                    LimboDropdown(
                        title = stringResource(R.string.label_network_card),
                        iconRes = R.drawable.networkcard,
                        options = state.nicOptions,
                        selectedIndex = state.nicSel,
                        enabled = state.nicEnabled,
                        onSelected = { callbacks.onNicSelected(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_dns_server),
                        value = state.dns,
                        enabled = state.dnsEnabled,
                        placeholder = "8.8.8.8",
                        onValueChange = { callbacks.onDnsChanged(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_host_forward),
                        value = state.hostFwd,
                        enabled = state.hostFwdEnabled,
                        placeholder = "tcp:2222:22",
                        onValueChange = { callbacks.onHostFwdChanged(it) }
                    )
                }
            }

            // Advanced section
            item {
                SectionCard(
                    title = stringResource(R.string.title_advanced),
                    iconRes = R.drawable.advanced,
                    summary = state.advancedSummary,
                    collapsed = state.advancedCollapsed,
                    onToggle = { onToggleSection(Section.ADVANCED) }
                ) {
                    LimboDropdown(
                        title = stringResource(R.string.label_usb_controller),
                        iconRes = R.drawable.advanced,
                        options = state.usbControllerOptions,
                        selectedIndex = state.usbControllerSel,
                        enabled = state.usbControllerEnabled,
                        onSelected = { callbacks.onUsbControllerSelected(it) }
                    )
                    LabeledTextFieldRow(
                        label = stringResource(R.string.label_extra_qemu_params),
                        value = state.extraParams,
                        enabled = state.extraParamsEnabled,
                        onValueChange = { callbacks.onExtraParamsChanged(it) }
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MachineCard(state: LimboUiState, callbacks: LimboUiCallbacks) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        cornerRadius = 16.dp
    ) {
        LimboDropdown(
            title = stringResource(R.string.machineHeader),
            summary = state.archLabel.ifEmpty { null },
            iconRes = R.drawable.limbo,
            options = state.machines,
            selectedIndex = state.machineSel,
            enabled = state.machineEnabled,
            onSelected = { callbacks.onMachineSelected(it) }
        )
    }
}

@Composable
private fun StatusCard(
    state: LimboUiState,
    statusColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(color = statusColor)
            Spacer(Modifier.width(12.dp))
            Text(
                text = state.statusText.ifEmpty { stringResource(R.string.Stopped) },
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ControlButtons(state: LimboUiState, callbacks: LimboUiCallbacks) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        IconButton(
            onClick = { callbacks.onStartVm() },
            modifier = Modifier.size(64.dp),
            backgroundColor = MiuixTheme.colorScheme.primaryContainer,
            cornerRadius = 32.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.play),
                contentDescription = stringResource(R.string.button_start),
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified
            )
        }
        IconButton(
            onClick = { callbacks.onPauseVm() },
            modifier = Modifier.size(64.dp),
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            cornerRadius = 32.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.pause),
                contentDescription = stringResource(R.string.button_pause),
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified
            )
        }
        IconButton(
            onClick = { callbacks.onStopVm() },
            modifier = Modifier.size(64.dp),
            backgroundColor = MiuixTheme.colorScheme.errorContainer,
            cornerRadius = 32.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.stop),
                contentDescription = stringResource(R.string.button_stop),
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified
            )
        }
        IconButton(
            onClick = { callbacks.onRestartVm() },
            modifier = Modifier.size(64.dp),
            backgroundColor = MiuixTheme.colorScheme.tertiaryContainer,
            cornerRadius = 32.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.reset),
                contentDescription = stringResource(R.string.button_restart),
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified
            )
        }
    }
}

@Composable
private fun StorageDeviceSummaryRow(
    device: StorageDeviceUiState,
    onClick: () -> Unit
) {
    val typeLabel = device.typeOptions.getOrNull(device.typeSel) ?: "?"
    val imageValue = device.imageOptions.getOrNull(device.imageSel) ?: "None"
    val value = if (imageValue.isNullOrEmpty() || imageValue.equals("None", ignoreCase = true)) "" else imageValue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.harddisk),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color.Unspecified
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = typeLabel,
                style = MiuixTheme.textStyles.main,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "›",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
