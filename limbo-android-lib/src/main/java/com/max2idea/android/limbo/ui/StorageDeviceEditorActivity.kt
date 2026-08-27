package com.max2idea.android.limbo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.limbo.emu.lib.R
import com.max2idea.android.limbo.files.FileUtils
import com.max2idea.android.limbo.machine.Machine
import com.max2idea.android.limbo.machine.MachineFilePaths
import com.max2idea.android.limbo.main.Config
import com.max2idea.android.limbo.main.LimboFileManager
import com.max2idea.android.limbo.main.LimboSettingsManager
import com.max2idea.android.limbo.ui.components.LimboDropdown
import com.max2idea.android.limbo.ui.theme.LimboTheme
import com.max2idea.android.limbo.toast.ToastUtils

/**
 * Single storage device detail page.
 * Launched from LimboActivity ("Add/Change" or tapping one of the storage summary rows).
 * Edits one device's detailed settings and returns the result for LimboActivity to apply.
 */
class StorageDeviceEditorActivity : ComponentActivity() {

    companion object {
        // request extras
        const val EXTRA_MODE = "mode"
        const val EXTRA_EDIT_TAG = "edit_tag"
        const val EXTRA_TYPE_LABELS = "type_labels"
        const val EXTRA_TYPE_FILE_TYPES = "type_file_types"
        const val EXTRA_TYPE_CREATE_IMAGE = "type_create_image"
        const val EXTRA_TYPE_CAN_IF = "type_can_if"
        const val EXTRA_INIT_TYPE_SEL = "init_type_sel"
        const val EXTRA_INIT_SIZE = "init_size"
        const val EXTRA_INIT_SIZE_UNIT_SEL = "init_size_unit_sel"
        const val EXTRA_INIT_IMAGE = "init_image"
        const val EXTRA_IF_LABELS = "if_labels"
        const val EXTRA_IF_VALUES = "if_values"
        const val EXTRA_FORMAT_LABELS = "format_labels"
        const val EXTRA_FORMAT_VALUES = "format_values"
        const val EXTRA_INIT_IF_SEL = "init_if_sel"
        const val EXTRA_INIT_FORMAT_SEL = "init_format_sel"

        // result extras
        const val EXTRA_REMOVE = "remove"
        const val EXTRA_TYPE_SEL = "type_sel"
        const val EXTRA_FILE_TYPE = "file_type"
        const val EXTRA_CREATE_IMAGE = "create_image"
        const val EXTRA_SIZE = "size"
        const val EXTRA_SIZE_UNIT_SEL = "size_unit_sel"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_NEW_IMAGE_NAME = "new_image_name"
        const val EXTRA_NEW_IMAGE_SIZE_BYTES = "new_image_size_bytes"
        const val EXTRA_IF = "if"
        const val EXTRA_FORMAT = "format"

        const val MODE_NEW = "new"
        const val MODE_EDIT = "edit"

        @JvmField
        val REQUEST_CODE = 4001

        /** Folder-pick request code used when confirming a new disk image (choose save location). */
        const val SAVE_DIR_REQUEST_CODE = 4002
    }

    private lateinit var typeLabels: Array<String>
    private lateinit var typeFileTypes: Array<String>
    private lateinit var typeCreateImage: BooleanArray
    private lateinit var typeCanIf: BooleanArray
    private lateinit var ifLabels: Array<String>
    private lateinit var ifValues: Array<String>
    private lateinit var formatLabels: Array<String>
    private lateinit var formatValues: Array<String>
    private var editTag = -1
    private var isNew = true

    private var typeSel by mutableIntStateOf(0)
    private var sizeValue by mutableStateOf("4")
    private var sizeUnitSel by mutableIntStateOf(0)
    private var newImageName by mutableStateOf("")
    private var imageOptions by mutableStateOf(listOf<String>())
    private var imageSel by mutableIntStateOf(0)
    private var ifSel by mutableIntStateOf(0)
    private var formatSel by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = intent
        isNew = i.getStringExtra(EXTRA_MODE) != MODE_EDIT
        editTag = i.getIntExtra(EXTRA_EDIT_TAG, -1)
        typeLabels = i.getStringArrayExtra(EXTRA_TYPE_LABELS) ?: arrayOf()
        typeFileTypes = i.getStringArrayExtra(EXTRA_TYPE_FILE_TYPES) ?: arrayOf()
        typeCreateImage = i.getBooleanArrayExtra(EXTRA_TYPE_CREATE_IMAGE) ?: BooleanArray(0)
        typeCanIf = i.getBooleanArrayExtra(EXTRA_TYPE_CAN_IF) ?: BooleanArray(0)
        ifLabels = i.getStringArrayExtra(EXTRA_IF_LABELS) ?: arrayOf(getString(R.string.label_default))
        ifValues = i.getStringArrayExtra(EXTRA_IF_VALUES) ?: arrayOf("")
        formatLabels = i.getStringArrayExtra(EXTRA_FORMAT_LABELS) ?: arrayOf(getString(R.string.label_auto))
        formatValues = i.getStringArrayExtra(EXTRA_FORMAT_VALUES) ?: arrayOf("")
        typeSel = i.getIntExtra(EXTRA_INIT_TYPE_SEL, 0)
        sizeValue = i.getStringExtra(EXTRA_INIT_SIZE) ?: "4"
        sizeUnitSel = i.getIntExtra(EXTRA_INIT_SIZE_UNIT_SEL, 0)
        ifSel = i.getIntExtra(EXTRA_INIT_IF_SEL, 0)
        formatSel = i.getIntExtra(EXTRA_INIT_FORMAT_SEL, 0)

        rebuildImageOptions(i.getStringExtra(EXTRA_INIT_IMAGE))

        setContent {
            LimboTheme {
                EditorScreen(
                    isNew = isNew,
                    typeLabels = typeLabels.toList(),
                    typeSel = typeSel,
                    onTypeSelected = { index ->
                        if (index != typeSel) {
                            typeSel = index
                            newImageName = ""
                            imageOptions = buildImageOptions(currentFileType(), isCreateImage())
                            imageSel = 0
                        }
                    },
                    createImage = isCreateImage(),
                    sizeValue = sizeValue,
                    onSizeChange = { sizeValue = it },
                    sizeUnitOptions = sizeUnitOptions(),
                    sizeUnitSel = sizeUnitSel,
                    onSizeUnitSelected = { sizeUnitSel = it },
                    canConfigIf = canConfigIf(),
                    ifLabels = ifLabels.toList(),
                    ifSel = ifSel,
                    onIfSelected = { ifSel = it },
                    formatLabels = formatLabels.toList(),
                    formatSel = formatSel,
                    onFormatSelected = { formatSel = it },
                    imageOptions = imageOptions,
                    imageSel = imageSel,
                    onImageSelected = ::onImageSelected,
                    newImageName = newImageName,
                    onNewImageNameChange = { newImageName = it },
                    onSave = ::saveAndReturn,
                    onRemove = { if (!isNew) removeAndReturn() }
                )
            }
        }
    }

    private fun isCreateImage(): Boolean =
        typeSel in typeCreateImage.indices && typeCreateImage[typeSel]

    private fun canConfigIf(): Boolean =
        typeSel in typeCanIf.indices && typeCanIf[typeSel]

    private fun currentFileType(): String =
        if (typeSel in typeFileTypes.indices) typeFileTypes[typeSel] else ""

    private fun sizeUnitOptions(): List<String> = listOf(
        getString(R.string.size_unit_gb),
        getString(R.string.size_unit_mb),
        getString(R.string.size_unit_tb)
    )

    private fun rebuildImageOptions(initialImage: String?) {
        val create = isCreateImage()
        val fileTypeName = currentFileType()
        val opts = buildImageOptions(fileTypeName, create)
        imageOptions = opts
        // restore previous selection / initial image when present
        val want = initialImage ?: imageOptions.getOrNull(if (imageSel in imageOptions.indices) imageSel else 0)
        imageSel = if (want != null && want != "None") {
            val idx = opts.indexOf(want)
            if (idx >= 0) idx else 0
        } else 0
        // ensure a just-browsed path is kept
        if (want != null && want.isNotEmpty() && want != "None" && opts.none { it == want }) {
            val merged = opts.toMutableList().apply { add(want) }
            imageOptions = merged
            imageSel = merged.lastIndex
        }
    }

    private fun buildImageOptions(fileTypeName: String, createImage: Boolean): List<String> {
        val opts = mutableListOf("None")
        if (createImage) opts.add(getString(R.string.new_image))
        opts.add(getString(R.string.open))
        val ft = runCatching { Machine.FileType.valueOf(fileTypeName) }.getOrNull()
        if (ft != null) {
            MachineFilePaths.getRecentFilePaths(ft)?.forEach { if (it != null) opts.add(it) }
        }
        return opts
    }

    private fun onImageSelected(index: Int) {
        val label = imageOptions.getOrNull(index)
        val create = isCreateImage()
        // "New" is index 1 when available, image options follow "None"/["New"]/"Open..."
        val openIndex = if (create) 2 else 1
        when (index) {
            openIndex -> {
                imageSel = index
                browseForImage()
            }
            // "New" is a placeholder option label, keep selected
            else -> {
                imageSel = index
                if (label != getString(R.string.new_image)) {
                    newImageName = ""
                }
            }
        }
    }

    private fun browseForImage() {
        val fileTypeName = currentFileType()
        if (fileTypeName.isEmpty()) return
        val ft = Machine.FileType.valueOf(fileTypeName)
        if (ft == Machine.FileType.SHARED_DIR) {
            LimboFileManager.browse(this, ft, Config.OPEN_SHARED_DIR_REQUEST_CODE)
        } else {
            LimboFileManager.browse(this, ft, Config.OPEN_IMAGE_FILE_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 选择创建后的保存文件夹:选定后自动使用该磁盘路径并退出;取消则停留本页
        if (requestCode == SAVE_DIR_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val dir = FileUtils.getDirPathFromIntent(this, data)
                if (!dir.isNullOrEmpty()) {
                    saveNewImageWithDir(dir)
                }
            }
            return
        }
        // a browse shows the literal "Open" label while it runs; revert to "None" if it was cancelled/failed
        if (resultCode != RESULT_OK || data == null) {
            val ft = runCatching { Machine.FileType.valueOf(currentFileType()) }.getOrNull()
            if (ft != null) imageSel = 0
            return
        }
        val ft = runCatching { Machine.FileType.valueOf(currentFileType()) }.getOrNull() ?: return
        val isShared = ft == Machine.FileType.SHARED_DIR
        val file = if (isShared) FileUtils.getDirPathFromIntent(this, data)
        else FileUtils.getFilePathFromIntent(this, data)
        if (file != null) {
            MachineFilePaths.insertRecentFilePath(ft, file)
            val opts = if (imageOptions.contains(file)) imageOptions.toList() else imageOptions + file
            imageOptions = opts
            imageSel = opts.indexOf(file)
        } else {
            imageSel = 0
        }
    }

    private fun selectedImageValue(): String =
        imageOptions.getOrNull(imageSel)?.takeIf { it != getString(R.string.new_image) } ?: "None"

    private fun saveAndReturn() {
        val create = isCreateImage()
        val selected = imageOptions.getOrNull(imageSel)
        val newImage = create && selected == getString(R.string.new_image)

        if (newImage) {
            if (newImageName.trim().isEmpty()) {
                ToastUtils.toastShort(this, getString(R.string.ImageFilenameCannotBeEmpty))
                return
            }
            // 确定创建前先选择保存文件夹,选定后使用该磁盘路径并返回
            LimboFileManager.browse(this, Machine.FileType.IMAGE_DIR, SAVE_DIR_REQUEST_CODE)
            return
        }

        val result = Intent()
        result.putExtra(EXTRA_REMOVE, false)
        result.putExtra(EXTRA_TYPE_SEL, typeSel)
        result.putExtra(EXTRA_FILE_TYPE, currentFileType())
        result.putExtra(EXTRA_CREATE_IMAGE, create)
        result.putExtra(EXTRA_IF, ifValues.getOrNull(ifSel) ?: "")
        result.putExtra(EXTRA_FORMAT, formatValues.getOrNull(formatSel) ?: "")
        if (create) {
            result.putExtra(EXTRA_SIZE, sizeValue)
            result.putExtra(EXTRA_SIZE_UNIT_SEL, sizeUnitSel)
        }
        result.putExtra(EXTRA_IMAGE, selectedImageValue())
        setResult(RESULT_OK, result)
        finish()
    }

    /** Uses the picked folder as the save location for the new disk image and returns/exit the page. */
    private fun saveNewImageWithDir(dir: String) {
        LimboSettingsManager.setImagesDir(this, dir)
        val result = Intent()
        result.putExtra(EXTRA_REMOVE, false)
        result.putExtra(EXTRA_TYPE_SEL, typeSel)
        result.putExtra(EXTRA_FILE_TYPE, currentFileType())
        result.putExtra(EXTRA_CREATE_IMAGE, isCreateImage())
        result.putExtra(EXTRA_NEW_IMAGE_NAME, newImageName.trim())
        result.putExtra(EXTRA_NEW_IMAGE_SIZE_BYTES, selectedSizeBytes())
        result.putExtra(EXTRA_IMAGE, "None")
        result.putExtra(EXTRA_IF, ifValues.getOrNull(ifSel) ?: "")
        result.putExtra(EXTRA_FORMAT, formatValues.getOrNull(formatSel) ?: "")
        setResult(RESULT_OK, result)
        finish()
    }

    private fun removeAndReturn() {
        val result = Intent()
        result.putExtra(EXTRA_REMOVE, true)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun selectedSizeBytes(): Long {
        var value = 1L
        try {
            value = sizeValue.trim().toLong()
        } catch (_: NumberFormatException) {
            value = 1
        }
        if (value < 1) value = 1
        val unit = sizeUnitOptions().getOrNull(sizeUnitSel) ?: "GB"
        val multiplier = when (unit) {
            getString(R.string.size_unit_mb) -> 1024L * 1024L
            getString(R.string.size_unit_tb) -> 1024L * 1024L * 1024L * 1024L
            else -> 1024L * 1024L * 1024L
        }
        return value * multiplier
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen(
        isNew: Boolean,
        typeLabels: List<String>,
        typeSel: Int,
        onTypeSelected: (Int) -> Unit,
        createImage: Boolean,
        sizeValue: String,
        onSizeChange: (String) -> Unit,
        sizeUnitOptions: List<String>,
        sizeUnitSel: Int,
        onSizeUnitSelected: (Int) -> Unit,
        canConfigIf: Boolean,
        ifLabels: List<String>,
        ifSel: Int,
        onIfSelected: (Int) -> Unit,
        formatLabels: List<String>,
        formatSel: Int,
        onFormatSelected: (Int) -> Unit,
        imageOptions: List<String>,
        imageSel: Int,
        onImageSelected: (Int) -> Unit,
        newImageName: String,
        onNewImageNameChange: (String) -> Unit,
        onSave: () -> Unit,
        onRemove: () -> Unit
    ) {
        val selectedImage = imageOptions.getOrNull(imageSel)
        val showNewImageField = createImage && selectedImage == stringResource(R.string.new_image)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.title_storage_device_editor), fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.Cancel)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors()
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.label_storage_type), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(0.dp))
                LimboDropdown(
                    options = typeLabels,
                    selectedIndex = typeSel,
                    modifier = Modifier.fillMaxWidth(),
                    onSelected = onTypeSelected
                )
                Spacer(Modifier.width(0.dp))

                if (canConfigIf) {
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.label_storage_interface), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(0.dp))
                    LimboDropdown(
                        options = ifLabels,
                        selectedIndex = ifSel,
                        modifier = Modifier.fillMaxWidth(),
                        onSelected = onIfSelected
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.label_storage_format), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(0.dp))
                    LimboDropdown(
                        options = formatLabels,
                        selectedIndex = formatSel,
                        modifier = Modifier.fillMaxWidth(),
                        onSelected = onFormatSelected
                    )
                }

                // 仅在选择"创建(新镜像)"时才显示大小和名称选项
                if (showNewImageField) {
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.label_storage_size), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = sizeValue,
                            onValueChange = onSizeChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        LimboDropdown(
                            options = sizeUnitOptions,
                            selectedIndex = sizeUnitSel,
                            modifier = Modifier.width(120.dp),
                            onSelected = onSizeUnitSelected
                        )
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.label_new_image_name), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = newImageName,
                        onValueChange = onNewImageNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                }

                Spacer(Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.label_storage_image), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(0.dp))
                LimboDropdown(
                    options = imageOptions,
                    selectedIndex = imageSel,
                    modifier = Modifier.fillMaxWidth(),
                    displayTransform = { it },
                    onSelected = onImageSelected
                )

                Spacer(Modifier.padding(top = 24.dp))
                if (!isNew) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.remove_storage_device))
                    }
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.Ok))
                }
            }
        }
    }
}