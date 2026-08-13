package com.max2idea.android.limbo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.limbo.emu.lib.R
import com.max2idea.android.limbo.ui.components.LimboDropdown
import com.max2idea.android.limbo.ui.components.SectionCard
import com.max2idea.android.limbo.ui.components.SettingRow
import com.max2idea.android.limbo.ui.components.StatusDot
import com.max2idea.android.limbo.ui.components.SwitchRow
import com.max2idea.android.limbo.ui.components.TextFieldRow
import com.max2idea.android.limbo.ui.theme.StatusStopped

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
    fun onStorageDeviceTypeChanged(typeIndex: Int, deviceTag: Int)
    fun onStorageDeviceImageChanged(imageIndex: Int, deviceTag: Int)
    fun onStorageDeviceRemove(deviceTag: Int)
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
    fun onEnableMTTCGChanged(checked: Boolean)
    fun onEnableKVMChanged(checked: Boolean)
    fun onDisableHPETChanged(checked: Boolean)
    fun onDisableTSCChanged(checked: Boolean)
    fun onDisableACPIChanged(checked: Boolean)
    // NVRAM (IA-64 only)
    fun onNvramChanged(value: String)
    fun onNvramBrowse()

    // boot section
    fun onBootSelected(index: Int)
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
}

/**
 * Main configuration screen for Limbo, rendered with Jetpack Compose + Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimboMainScreen(
    state: LimboUiState,
    callbacks: LimboUiCallbacks,
    statusColor: Color = StatusStopped,
    onToggleSection: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.limbo),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                actions = {
                    IconButton(onClick = { callbacks.onOpenMenu() }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            MachineCard(state = state, callbacks = callbacks)
            StatusCard(state = state, statusColor = statusColor)
            ControlButtons(state = state, callbacks = callbacks)

            // User Interface section
            SectionCard(
                title = stringResource(R.string.title_user_interface),
                iconRes = R.drawable.ui,
                summary = state.uiSummary,
                collapsed = state.uiCollapsed,
                onToggle = { onToggleSection("ui") }
            ) {
                SettingRow(label = stringResource(R.string.label_display), iconRes = R.drawable.ui) {
                    LimboDropdown(
                        options = state.uiOptions,
                        selectedIndex = state.uiSel,
                        enabled = state.uiEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onUiSelected(it) }
                    )
                }
                SettingRow(label = stringResource(R.string.title_keyboard), iconRes = R.drawable.keyboard) {
                    LimboDropdown(
                        options = state.keyboardOptions,
                        selectedIndex = state.keyboardSel,
                        enabled = state.keyboardEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onKeyboardSelected(it) }
                    )
                }
                SettingRow(label = stringResource(R.string.title_mouse), iconRes = R.drawable.mouse) {
                    LimboDropdown(
                        options = state.mouseOptions,
                        selectedIndex = state.mouseSel,
                        enabled = state.mouseEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onMouseSelected(it) }
                    )
                }
            }

            // Board section
            SectionCard(
                title = stringResource(R.string.title_board),
                iconRes = R.drawable.machinetype,
                summary = state.boardSummary,
                collapsed = state.boardCollapsed,
                onToggle = { onToggleSection("board") }
            ) {
                SettingRow(label = stringResource(R.string.label_machine_type), iconRes = R.drawable.machinetype) {
                    LimboDropdown(
                        options = state.machineTypeOptions,
                        selectedIndex = state.machineTypeSel,
                        enabled = state.machineTypeEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onMachineTypeSelected(it) }
                    )
                }
                // NVRAM is an IA-64 only motherboard option
                if (state.nvramVisible) {
                    SettingRow(label = stringResource(R.string.label_nvram), iconRes = R.drawable.machinetype) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.nvramValue,
                                onValueChange = { callbacks.onNvramChanged(it) },
                                enabled = state.nvramEnabled,
                                placeholder = { Text(stringResource(R.string.hint_nvram), maxLines = 1) },
                                singleLine = true,
                                modifier = Modifier.width(170.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { callbacks.onNvramBrowse() },
                                enabled = state.nvramEnabled
                            ) {
                                Text("…")
                            }
                        }
                    }
                }
                SettingRow(label = stringResource(R.string.label_cpu_model), iconRes = R.drawable.cpu) {
                    LimboDropdown(
                        options = state.cpuOptions,
                        selectedIndex = state.cpuSel,
                        enabled = state.cpuEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onCpuSelected(it) }
                    )
                }
                TextFieldRow(
                    label = stringResource(R.string.label_cpu_cores),
                    value = state.cpuNumValue,
                    enabled = state.cpuNumEnabled,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { callbacks.onCpuNumChanged(it) }
                )
                TextFieldRow(
                    label = stringResource(R.string.label_ram_memory_mb),
                    value = state.ramValue,
                    enabled = state.ramEnabled,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { callbacks.onRamChanged(it) }
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    label = stringResource(R.string.label_enable_mttcg),
                    checked = state.enableMTTCG,
                    enabled = state.enableMTTCGEnabled,
                    onCheckedChange = { callbacks.onEnableMTTCGChanged(it) }
                )
                SwitchRow(
                    label = stringResource(R.string.label_enable_kvm),
                    checked = state.enableKVM,
                    enabled = state.enableKVMEnabled,
                    onCheckedChange = { callbacks.onEnableKVMChanged(it) }
                )
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

            // Storage devices section
            SectionCard(
                title = stringResource(R.string.title_storage_devices),
                iconRes = R.drawable.harddisk,
                summary = state.storageSummary,
                collapsed = state.storageCollapsed,
                onToggle = { onToggleSection("storage") }
            ) {
                state.storageDevices.forEach { device ->
                    StorageDeviceRow(
                        device = device,
                        onTypeChanged = { callbacks.onStorageDeviceTypeChanged(it, device.tag) },
                        onImageChanged = { callbacks.onStorageDeviceImageChanged(it, device.tag) },
                        onRemove = { callbacks.onStorageDeviceRemove(device.tag) }
                    )
                }
                Button(
                    onClick = { callbacks.onAddStorageDevice() },
                    enabled = state.addDeviceEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.add_storage_device))
                }
            }

            // Boot section
            SectionCard(
                title = stringResource(R.string.title_boot),
                iconRes = R.drawable.drives,
                summary = state.bootSummary,
                collapsed = state.bootCollapsed,
                onToggle = { onToggleSection("boot") }
            ) {
                SettingRow(label = stringResource(R.string.label_boot_from_device), iconRes = R.drawable.drives) {
                    LimboDropdown(
                        options = state.bootOptions,
                        selectedIndex = state.bootSel,
                        enabled = state.bootEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onBootSelected(it) }
                    )
                }
                SettingRow(label = stringResource(R.string.label_kernel), iconRes = R.drawable.sysfile) {
                    LimboDropdown(
                        options = state.kernelOptions,
                        selectedIndex = state.kernelSel,
                        enabled = state.kernelEnabled,
                        modifier = Modifier.width(180.dp),
                        onSelected = { callbacks.onKernelSelected(it) }
                    )
                }
                SettingRow(label = stringResource(R.string.label_initrd), iconRes = R.drawable.sysfile) {
                    LimboDropdown(
                        options = state.initrdOptions,
                        selectedIndex = state.initrdSel,
                        enabled = state.initrdEnabled,
                        modifier = Modifier.width(180.dp),
                        onSelected = { callbacks.onInitrdSelected(it) }
                    )
                }
                TextFieldRow(
                    label = stringResource(R.string.label_append),
                    value = state.append,
                    enabled = state.appendEnabled,
                    placeholder = "root=/dev/sda1",
                    onValueChange = { callbacks.onAppendChanged(it) }
                )
            }

            // Graphics section
            SectionCard(
                title = stringResource(R.string.title_graphics),
                iconRes = R.drawable.screen,
                summary = state.graphicsSummary,
                collapsed = state.graphicsCollapsed,
                onToggle = { onToggleSection("graphics") }
            ) {
                SettingRow(label = stringResource(R.string.label_video_display), iconRes = R.drawable.screen) {
                    LimboDropdown(
                        options = state.vgaOptions,
                        selectedIndex = state.vgaSel,
                        enabled = state.vgaEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onVgaSelected(it) }
                    )
                }
            }

            // Audio section
            SectionCard(
                title = stringResource(R.string.title_audio),
                iconRes = R.drawable.audiocard,
                summary = state.audioSummary,
                collapsed = state.audioCollapsed,
                onToggle = { onToggleSection("audio") }
            ) {
                SettingRow(label = stringResource(R.string.label_sound_card), iconRes = R.drawable.audiocard) {
                    LimboDropdown(
                        options = state.soundOptions,
                        selectedIndex = state.soundSel,
                        enabled = state.soundEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onSoundSelected(it) }
                    )
                }
            }

            // Network section
            SectionCard(
                title = stringResource(R.string.label_network),
                iconRes = R.drawable.network,
                summary = state.networkSummary,
                collapsed = state.networkCollapsed,
                onToggle = { onToggleSection("network") }
            ) {
                SettingRow(label = stringResource(R.string.label_network), iconRes = R.drawable.network) {
                    LimboDropdown(
                        options = state.netOptions,
                        selectedIndex = state.netSel,
                        enabled = state.netEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onNetSelected(it) }
                    )
                }
                SettingRow(label = stringResource(R.string.label_network_card), iconRes = R.drawable.networkcard) {
                    LimboDropdown(
                        options = state.nicOptions,
                        selectedIndex = state.nicSel,
                        enabled = state.nicEnabled,
                        modifier = Modifier.width(150.dp),
                        onSelected = { callbacks.onNicSelected(it) }
                    )
                }
                TextFieldRow(
                    label = stringResource(R.string.label_dns_server),
                    value = state.dns,
                    enabled = state.dnsEnabled,
                    placeholder = "8.8.8.8",
                    onValueChange = { callbacks.onDnsChanged(it) }
                )
                TextFieldRow(
                    label = stringResource(R.string.label_host_forward),
                    value = state.hostFwd,
                    enabled = state.hostFwdEnabled,
                    placeholder = "tcp:2222:22",
                    onValueChange = { callbacks.onHostFwdChanged(it) }
                )
            }

            // Advanced section
            SectionCard(
                title = stringResource(R.string.title_advanced),
                iconRes = R.drawable.advanced,
                summary = state.advancedSummary,
                collapsed = state.advancedCollapsed,
                onToggle = { onToggleSection("advanced") }
            ) {
                TextFieldRow(
                    label = stringResource(R.string.label_extra_qemu_params),
                    value = state.extraParams,
                    enabled = state.extraParamsEnabled,
                    onValueChange = { callbacks.onExtraParamsChanged(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MachineCard(state: LimboUiState, callbacks: LimboUiCallbacks) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.limbo),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.machineHeader),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            LimboDropdown(
                options = state.machines,
                selectedIndex = state.machineSel,
                enabled = state.machineEnabled,
                modifier = Modifier.width(160.dp),
                onSelected = { callbacks.onMachineSelected(it) }
            )
        }
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
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
                style = MaterialTheme.typography.titleLarge,
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
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        IconButton(
            onClick = { callbacks.onStartVm() },
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.play),
                contentDescription = stringResource(R.string.button_start),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        IconButton(
            onClick = { callbacks.onPauseVm() },
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.pause),
                contentDescription = stringResource(R.string.button_pause),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        IconButton(
            onClick = { callbacks.onStopVm() },
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.stop),
                contentDescription = stringResource(R.string.button_stop),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        IconButton(
            onClick = { callbacks.onRestartVm() },
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.reset),
                contentDescription = stringResource(R.string.button_restart),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun StorageDeviceRow(
    device: StorageDeviceUiState,
    onTypeChanged: (Int) -> Unit,
    onImageChanged: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LimboDropdown(
                    options = device.typeOptions,
                    selectedIndex = device.typeSel,
                    enabled = device.enabled,
                    modifier = Modifier.weight(1.4f),
                    onSelected = onTypeChanged
                )
                Spacer(Modifier.width(8.dp))
                if (device.showSize) {
                    androidx.compose.material3.OutlinedTextField(
                        value = device.sizeValue,
                        onValueChange = { device.sizeValue = it },
                        enabled = device.enabled,
                        singleLine = true,
                        modifier = Modifier.weight(0.7f),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    LimboDropdown(
                        options = device.sizeUnitOptions,
                        selectedIndex = device.sizeUnitSel,
                        enabled = device.enabled,
                        modifier = Modifier.weight(0.7f),
                        onSelected = { device.sizeUnitSel = it }
                    )
                }
                IconButton(
                    onClick = onRemove,
                    enabled = device.enabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.delete),
                        contentDescription = stringResource(R.string.remove_storage_device),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LimboDropdown(
                options = device.imageOptions,
                selectedIndex = device.imageSel,
                enabled = device.enabled,
                modifier = Modifier.fillMaxWidth(),
                onSelected = onImageChanged
            )
        }
    }
}
