package com.photonlab.ui.editor

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photonlab.domain.model.EditState
import com.photonlab.domain.model.LutFile
import com.photonlab.ui.components.AdjustmentSlider
import com.photonlab.ui.theme.PhotonLabTheme
import kotlin.math.abs
import kotlin.math.ln

@Composable
fun EditorScreen(
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current as? Activity

    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Measured heights of the floating panels (px) used to center the image in the visible area
    var topBarHeightPx     by remember { mutableIntStateOf(0) }
    var bottomPanelHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(uiState.sourceBitmap) { zoom = 1f; zoomOffset = Offset.Zero }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
        val maxX = (boxSize.width  * (zoom - 1)) / 2f
        val maxY = (boxSize.height * (zoom - 1)) / 2f
        zoomOffset = Offset(
            (zoomOffset.x + panChange.x * zoom).coerceIn(-maxX, maxX),
            (zoomOffset.y + panChange.y * zoom).coerceIn(-maxY, maxY),
        )
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) viewModel.loadImages(uris) }

    val lutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importLut(it) } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) imagePicker.launch("image/*") }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Intercept back press when exports are pending
    BackHandler(enabled = uiState.pendingExportCount > 0) {
        viewModel.requestExit()
    }

    // Auto-finish when background exports complete
    LaunchedEffect(uiState.finishActivity) {
        if (uiState.finishActivity) {
            viewModel.finishActivityHandled()
            activity?.finish()
        }
    }

    fun openImages() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            imagePicker.launch("image/*")
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    Scaffold(
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Full-screen image area ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(
                        top    = with(density) { topBarHeightPx.toDp() },
                        bottom = with(density) { bottomPanelHeightPx.toDp() },
                    )
                    .onSizeChanged { boxSize = it },
                contentAlignment = Alignment.Center,
            ) {
                val displayBitmap = when {
                    uiState.showOriginal -> uiState.originalGeomBitmap ?: uiState.sourceBitmap
                    else                 -> uiState.previewBitmap
                }

                if (displayBitmap != null) {
                    AnimatedContent(
                        targetState    = uiState.batchNavKey to uiState.batchIndex,
                        transitionSpec = {
                            if (initialState.first == targetState.first) {
                                // No navigation — instant swap (e.g. showOriginal toggle or edit render)
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            } else {
                                val fwd = targetState.second > initialState.second
                                slideInHorizontally(tween(240)) { if (fwd) it else -it } + fadeIn(tween(180)) togetherWith
                                slideOutHorizontally(tween(240)) { if (fwd) -it else it } + fadeOut(tween(180))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        label    = "photo_swipe",
                    ) { _ ->
                    Image(
                        painter            = BitmapPainter(displayBitmap.asImageBitmap()),
                        contentDescription = null,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier
                            .fillMaxSize()
                            .pointerInput(uiState.batchCount) {
                                if (uiState.batchCount <= 1) return@pointerInput
                                val threshold = 80.dp.toPx()
                                awaitEachGesture {
                                    val firstEv = awaitPointerEvent()
                                    val firstCh = firstEv.changes.firstOrNull() ?: return@awaitEachGesture
                                    val startX  = firstCh.position.x
                                    val startY  = firstCh.position.y
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) {
                                            val dx = ch.position.x - startX
                                            val dy = ch.position.y - startY
                                            if (zoom == 1f && abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) {
                                                if (dx < 0) viewModel.nextImage() else viewModel.prevImage()
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                            .graphicsLayer(
                                scaleX       = zoom,
                                scaleY       = zoom,
                                translationX = zoomOffset.x,
                                translationY = zoomOffset.y,
                            )
                            .transformable(transformableState)
                            .pointerInput(Unit) {
                                // Custom gesture: show-original on press, double-tap to reset zoom.
                                // A press held longer than 300 ms is treated as "viewing original"
                                // and does NOT count as a tap for double-tap purposes, so looking
                                // at the original twice never accidentally resets the zoom.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val pressStart = System.currentTimeMillis()
                                    viewModel.setShowOriginal(true)

                                    waitForUpOrCancellation()
                                    val pressDuration = System.currentTimeMillis() - pressStart
                                    viewModel.setShowOriginal(false)

                                    // Long press → just viewing original, skip double-tap check
                                    if (pressDuration >= 300L) return@awaitEachGesture

                                    // Wait for a second tap within 300 ms
                                    val secondDown = withTimeoutOrNull(300L) {
                                        awaitFirstDown(requireUnconsumed = false)
                                    } ?: return@awaitEachGesture

                                    // Double tap: reset zoom
                                    zoom = 1f
                                    zoomOffset = Offset.Zero
                                    waitForUpOrCancellation()
                                }
                            },
                    )
                    } // end AnimatedContent
                    if (uiState.showOriginal) {
                        Text(
                            text     = "ORIGINAL",
                            style    = MaterialTheme.typography.labelMedium,
                            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center).padding(top = 8.dp),
                        )
                    }
                    if (uiState.isProcessing) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(32.dp).align(Alignment.BottomEnd).padding(8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    // Grid overlay
                    if (uiState.gridMode != GridMode.NONE) {
                        GridOverlay(
                            gridMode = uiState.gridMode,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Top-right overlay buttons: grid + histogram
                    Row(
                        modifier          = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick  = viewModel::cycleGridMode,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.GridOn,
                                contentDescription = "Toggle grid",
                                tint               = if (uiState.gridMode != GridMode.NONE)
                                                         MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        IconButton(
                            onClick  = viewModel::toggleHistogram,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.BarChart,
                                contentDescription = "Toggle histogram",
                                tint               = if (uiState.showHistogram) MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                    // Histogram overlay (bottom-left)
                    if (uiState.showHistogram) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .size(width = 160.dp, height = 80.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.small,
                                )
                                .clip(MaterialTheme.shapes.small),
                        ) {
                            HistogramOverlay(
                                bitmap   = uiState.histogramBitmap ?: uiState.previewBitmap ?: displayBitmap,
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                            )
                        }
                    }
                } else {
                    EmptyState(onOpen = ::openImages)
                }
            }

            // ── Top bar overlay ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged { topBarHeightPx = it.height },
            ) {
                Column {
                    TopBar(
                        canUndo      = uiState.canUndo,
                        canRedo      = uiState.canRedo,
                        hasImage     = uiState.sourceBitmap != null,
                        canHistory   = uiState.historyEntries.size > 1,
                        isProcessing = uiState.isProcessing,
                        batchCount   = uiState.batchCount,
                        batchIndex   = uiState.batchIndex,
                        onOpen       = ::openImages,
                        onUndo       = viewModel::undo,
                        onRedo       = viewModel::redo,
                        onReset      = viewModel::resetAll,
                        onExport     = viewModel::export,
                        onSettings   = viewModel::openSettings,
                        onSkip       = viewModel::skipImage,
                        onHistory    = viewModel::openHistory,
                    )
                    HorizontalDivider()
                }
            }

            // ── Bottom edit panel overlay ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged { bottomPanelHeightPx = it.height },
            ) {
                Column {
                    HorizontalDivider()
                    ToolControls(
                        state       = uiState,
                        viewModel   = viewModel,
                        onLutPick   = { lutPicker.launch("*/*") },
                        onStartCrop = viewModel::startCrop,
                    )
                }
            }

            // ── Full-screen overlays ───────────────────────────────────────────

            if (uiState.showCropScreen) {
                val bmp = uiState.cropSourceBitmap ?: uiState.sourceBitmap
                if (bmp != null) {
                    CropScreen(
                        bitmap      = bmp,
                        initialCrop = uiState.editState.cropRect,
                        onConfirm   = viewModel::confirmCrop,
                        onCancel    = viewModel::cancelCrop,
                    )
                }
            }

            if (uiState.showSettings) {
                SettingsDialog(
                    quality        = uiState.jpegQuality,
                    onQuality      = viewModel::setJpegQuality,
                    lutFolder      = uiState.lutFolder,
                    onLutFolder    = viewModel::setLutFolder,
                    presets        = uiState.savedPresets,
                    hasImage       = uiState.sourceBitmap != null,
                    editState      = uiState.editState,
                    currentLut     = uiState.currentLut,
                    onApplyPreset  = { preset -> viewModel.applyPresetNow(preset); viewModel.closeSettings() },
                    onSavePreset   = viewModel::savePreset,
                    onRenamePreset = viewModel::renamePreset,
                    onDeletePreset = viewModel::deletePreset,
                    onDismiss      = viewModel::closeSettings,
                )
            }

            if (uiState.showHistoryDialog) {
                HistoryDialog(
                    entries       = uiState.historyEntries,
                    currentIndex  = uiState.historyCursor,
                    onJumpTo      = viewModel::jumpToHistory,
                    onDismiss     = viewModel::closeHistory,
                )
            }

            uiState.applyBatchPreset?.let { preset ->
                ApplyBatchDialog(
                    presetName = preset.name,
                    onConfirm  = { viewModel.confirmApplyBatch(preset) },
                    onSkip     = viewModel::skipApplyBatch,
                )
            }

            if (uiState.showExitConfirmDialog) {
                ExitWithExportsDialog(
                    pendingCount = uiState.pendingExportCount,
                    onBackground = {
                        viewModel.runExportsInBackground()
                        activity?.moveTaskToBack(true)
                    },
                    onClose  = {
                        viewModel.dismissExitDialog()
                        activity?.finish()
                    },
                    onWait   = viewModel::dismissExitDialog,
                )
            }
        }
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    hasImage: Boolean,
    canHistory: Boolean,
    isProcessing: Boolean,
    batchCount: Int,
    batchIndex: Int,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onSkip: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpen) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Open image(s)")
        }
        IconButton(onClick = onSkip, enabled = hasImage) {
            Icon(Icons.Default.SkipNext, contentDescription = "Skip image")
        }
        if (batchCount > 1) {
            Text(
                text  = "${batchIndex + 1} / $batchCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onHistory, enabled = canHistory) {
            Icon(Icons.Default.History, contentDescription = "Edit history")
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        IconButton(onClick = onReset, enabled = hasImage) {
            Icon(Icons.Default.DeleteSweep, contentDescription = "Reset all")
        }
        FilledTonalIconButton(onClick = onExport, enabled = hasImage && !isProcessing) {
            Icon(Icons.Default.Save, contentDescription = "Export")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

// ── History dialog ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryDialog(
    entries: List<EditState>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit History") },
        text  = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        "No history yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Most recent first
                    entries.indices.reversed().forEach { idx ->
                        val state     = entries[idx]
                        val prevState = if (idx > 0) entries[idx - 1] else null
                        val isCurrent = idx == currentIndex
                        val isFuture  = idx > currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { onJumpTo(idx) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text  = "${idx + 1}. ${historyLabel(idx, state, prevState)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isFuture  -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else      -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun historyLabel(index: Int, state: EditState, prev: EditState?): String {
    if (index == 0 || prev == null) return "Initial"
    val parts = buildList {
        if (state.exposure    != prev.exposure)    add("Exp ${"%.1f".format(state.exposure)}")
        if (state.luminosity  != prev.luminosity)  add("Lum ${"%.0f".format(state.luminosity)}")
        if (state.contrast    != prev.contrast)    add("Con ${"%.0f".format(state.contrast)}")
        if (state.highlights  != prev.highlights)  add("Hi ${"%.0f".format(state.highlights)}")
        if (state.shadows     != prev.shadows)     add("Sha ${"%.0f".format(state.shadows)}")
        if (state.saturation  != prev.saturation)  add("Sat ${"%.0f".format(state.saturation)}")
        if (state.vibrance    != prev.vibrance)    add("Vib ${"%.0f".format(state.vibrance)}")
        if (state.temperature != prev.temperature) add("Tmp ${"%.0f".format(state.temperature)}")
        if (state.tint        != prev.tint)        add("Tnt ${"%.0f".format(state.tint)}")
        if (state.sharpening  != prev.sharpening)  add("Sharp ${"%.0f".format(state.sharpening)}")
        if (state.noise       != prev.noise)       add("Noise ${"%.0f".format(state.noise)}")
        if (state.rotation    != prev.rotation || state.fineRotation != prev.fineRotation)
            add("Rot ${"%.1f".format(state.rotation.toFloat() + state.fineRotation)}°")
        if (state.cropRect    != prev.cropRect)    add(if (state.cropRect != null) "Cropped" else "Crop removed")
        if (state.frameEnabled != prev.frameEnabled || state.frameSizePct != prev.frameSizePct
                || state.frameColor != prev.frameColor || state.frameRatio != prev.frameRatio)
            add(if (state.frameEnabled) "Frame" else "Frame off")
        if (state.lutUri      != prev.lutUri)      add(if (state.lutUri != null) "LUT" else "LUT removed")
    }
    return if (parts.isEmpty()) "No changes" else parts.joinToString(", ")
}

// ── Settings dialog (includes preset management) ───────────────────────────────

@Composable
private fun SettingsDialog(
    quality: Int,
    onQuality: (Int) -> Unit,
    lutFolder: String,
    onLutFolder: (String) -> Unit,
    presets: List<Preset>,
    hasImage: Boolean,
    editState: EditState,
    currentLut: LutFile?,
    onApplyPreset: (Preset) -> Unit,
    onSavePreset: (String, Set<PresetParam>) -> Unit,
    onRenamePreset: (Preset, String) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local sub-dialog state
    var showCreate       by remember { mutableStateOf(false) }
    var renamingPreset by remember { mutableStateOf<Preset?>(null) }
    var deletingPreset by remember { mutableStateOf<Preset?>(null) }
    var lutFolderText  by remember(lutFolder) { mutableStateOf(lutFolder) }

    val lutFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { lutFolderText = DocumentsContract.getTreeDocumentId(it).substringAfter(':') }
    }

    when {
        showCreate -> {
            CreatePresetDialog(
                editState  = editState,
                currentLut = currentLut,
                onSave     = { name, params ->
                    onSavePreset(name, params)
                    showCreate = false
                    onDismiss()
                },
                onDismiss  = { showCreate = false },
            )
        }
        renamingPreset != null -> {
            RenamePresetDialog(
                preset    = renamingPreset!!,
                onRename  = { newName -> onRenamePreset(renamingPreset!!, newName); renamingPreset = null },
                onDismiss = { renamingPreset = null },
            )
        }
        deletingPreset != null -> {
            ConfirmDeleteDialog(
                presetName = deletingPreset!!.name,
                onConfirm  = { onDeletePreset(deletingPreset!!); deletingPreset = null },
                onDismiss  = { deletingPreset = null },
            )
        }
        else -> {
            var sliderVal by remember(quality) { mutableFloatStateOf(quality.toFloat()) }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Settings") },
                text  = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // ── Quality ──────────────────────────────────────────
                        Text("Export", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("JPEG Quality", style = MaterialTheme.typography.bodyMedium)
                            Text("${sliderVal.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value         = sliderVal,
                            onValueChange = { sliderVal = it },
                            valueRange    = 50f..100f,
                            steps         = 49,
                        )
                        OutlinedTextField(
                            value         = lutFolderText,
                            onValueChange = { lutFolderText = it },
                            label         = { Text("Default LUT folder") },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            trailingIcon  = {
                                IconButton(onClick = { lutFolderPicker.launch(null) }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse")
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))

                        // ── Presets ───────────────────────────────────────────
                        Text("Presets", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()

                        if (presets.isEmpty()) {
                            Text(
                                "No presets saved yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        } else {
                            presets.forEach { preset ->
                                Row(
                                    modifier          = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (hasImage) {
                                        TextButton(
                                            onClick        = { onApplyPreset(preset) },
                                            modifier       = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Text(
                                                preset.name,
                                                textAlign = TextAlign.Start,
                                                maxLines  = 1,
                                                overflow  = TextOverflow.Ellipsis,
                                                modifier  = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    } else {
                                        Text(
                                            preset.name,
                                            style    = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick  = { renamingPreset = preset },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename",
                                            modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick  = { deletingPreset = preset },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                                            modifier = Modifier.size(18.dp),
                                            tint     = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick  = { showCreate = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Create preset") }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onQuality(sliderVal.toInt())
                        onLutFolder(lutFolderText.trim())
                        onDismiss()
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                },
            )
        }
    }
}

// ── Preset sub-dialogs ─────────────────────────────────────────────────────────

@Composable
private fun CreatePresetDialog(
    editState: EditState,
    currentLut: LutFile?,
    onSave: (String, Set<PresetParam>) -> Unit,
    onDismiss: () -> Unit,
) {
    val modifiedParams = remember(editState, currentLut) {
        PresetParam.entries.filter { it.isNonDefault(editState, currentLut != null) }.toSet()
    }
    var name     by remember { mutableStateOf("") }
    var selected by remember(modifiedParams) { mutableStateOf(modifiedParams) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Preset") },
        text  = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                if (modifiedParams.isEmpty()) {
                    Text(
                        "No adjustments have been made. Modify some parameters first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Parameters to include:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    modifiedParams.forEach { param ->
                        Row(
                            modifier          = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked         = param in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + param else selected - param
                                },
                            )
                            Text(
                                "${param.label}: ${param.displayValue(editState, currentLut?.let { "applied" })}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { onSave(name, selected) },
                enabled  = name.isNotBlank() && selected.isNotEmpty() && modifiedParams.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenamePresetDialog(
    preset: Preset,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(preset) { mutableStateOf(preset.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Preset") },
        text  = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Name") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onRename(name) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    presetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Preset") },
        text  = { Text("Delete \"$presetName\"? This cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ApplyBatchDialog(
    presetName: String,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Apply to remaining images?") },
        text  = {
            Text(
                "Apply preset \"$presetName\" to all remaining images in the batch?",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Yes, apply to all") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("No, just this one") } },
    )
}

// ── PresetParam display helpers ────────────────────────────────────────────────

private fun PresetParam.displayValue(state: EditState, currentLutName: String?): String = when (this) {
    PresetParam.EXPOSURE    -> "%.1f".format(state.exposure)
    PresetParam.LUMINOSITY  -> "%.0f".format(state.luminosity)
    PresetParam.CONTRAST    -> "%.0f".format(state.contrast)
    PresetParam.HIGHLIGHTS  -> "%.0f".format(state.highlights)
    PresetParam.SHADOWS     -> "%.0f".format(state.shadows)
    PresetParam.SATURATION  -> "%.0f".format(state.saturation)
    PresetParam.VIBRANCE    -> "%.0f".format(state.vibrance)
    PresetParam.TEMPERATURE -> "%.0f".format(state.temperature)
    PresetParam.TINT        -> "%.0f".format(state.tint)
    PresetParam.SHARPENING  -> "%.0f".format(state.sharpening)
    PresetParam.NOISE       -> "%.0f".format(state.noise)
    PresetParam.ROTATION    -> "${"%.1f".format(state.rotation.toFloat() + state.fineRotation)}°"
    PresetParam.LUT         -> currentLutName ?: "applied"
    PresetParam.FRAME       -> "${state.frameSizePct.toInt()}%"
}

private fun PresetParam.isNonDefault(state: EditState, hasLut: Boolean): Boolean = when (this) {
    PresetParam.EXPOSURE    -> state.exposure    != 0f
    PresetParam.LUMINOSITY  -> state.luminosity  != 0f
    PresetParam.CONTRAST    -> state.contrast    != 0f
    PresetParam.HIGHLIGHTS  -> state.highlights  != 0f
    PresetParam.SHADOWS     -> state.shadows     != 0f
    PresetParam.SATURATION  -> state.saturation  != 0f
    PresetParam.VIBRANCE    -> state.vibrance    != 0f
    PresetParam.TEMPERATURE -> state.temperature != 0f
    PresetParam.TINT        -> state.tint        != 0f
    PresetParam.SHARPENING  -> state.sharpening  != 0f
    PresetParam.NOISE       -> state.noise       != 0f
    PresetParam.ROTATION    -> state.rotation    != 0 || state.fineRotation != 0f
    PresetParam.LUT         -> hasLut
    PresetParam.FRAME       -> state.frameEnabled
}

// ── Tool controls ──────────────────────────────────────────────────────────────

@Composable
private fun ToolControls(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onLutPick: () -> Unit,
    onStartCrop: () -> Unit,
) {
    val e = state.editState
    Column(modifier = Modifier.fillMaxWidth().height(340.dp)) {
        CategoryTabRow(
            selected = state.selectedCategory,
            onSelect = viewModel::selectCategory,
        )
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))
            when (state.selectedCategory) {
                ToolCategory.TONE -> {
                    AdjustmentSlider("Exposure",   e.exposure,   -5f..5f,     viewModel::setExposure,   step = 0.1f)
                    AdjustmentSlider("Luminosity", e.luminosity, -100f..100f, viewModel::setLuminosity)
                    AdjustmentSlider("Contrast",   e.contrast,   -100f..100f, viewModel::setContrast)
                    AdjustmentSlider("Highlights", e.highlights, -100f..100f, viewModel::setHighlights)
                    AdjustmentSlider("Shadows",    e.shadows,    -100f..100f, viewModel::setShadows)
                    AdjustmentSlider("Sharpening", e.sharpening, 0f..100f,    viewModel::setSharpening)
                    AdjustmentSlider("Noise",      e.noise,      -100f..100f, viewModel::setNoise)
                }
                ToolCategory.COLOR -> {
                    AdjustmentSlider("Saturation",  e.saturation,  -100f..100f, viewModel::setSaturation)
                    AdjustmentSlider("Vibrance",    e.vibrance,    -100f..100f, viewModel::setVibrance)
                    AdjustmentSlider("Temperature", e.temperature, -100f..100f, viewModel::setTemperature)
                    AdjustmentSlider("Tint",        e.tint,        -100f..100f, viewModel::setTint)
                }
                ToolCategory.LUT -> {
                    LutPanel(
                        currentLutName = state.currentLut?.name,
                        onImport       = onLutPick,
                        onRemove       = viewModel::removeLut,
                    )
                }
                ToolCategory.TRANSFORM -> {
                    TransformPanel(
                        rotation       = e.rotation,
                        fineRotation   = e.fineRotation,
                        hasCrop        = e.cropRect != null,
                        onRotateCw     = viewModel::rotateClockwise,
                        onRotateCcw    = viewModel::rotateCounterClockwise,
                        onFineRotation = viewModel::setFineRotation,
                        onStartCrop    = onStartCrop,
                    )
                    SectionHeader("Frame")
                    FramePanel(
                        state      = e,
                        onEnabled  = viewModel::setFrameEnabled,
                        onColor    = viewModel::setFrameColor,
                        onRatio    = viewModel::setFrameRatio,
                        onSize     = viewModel::setFrameSizePct,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryTabRow(
    selected: ToolCategory,
    onSelect: (ToolCategory) -> Unit,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            ToolCategory.TONE      to "Tone",
            ToolCategory.COLOR     to "Color",
            ToolCategory.LUT       to "LUT",
            ToolCategory.TRANSFORM to "Transform",
        ).forEach { (cat, label) ->
            FilterChip(
                selected = selected == cat,
                onClick  = { onSelect(cat) },
                label    = { Text(label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

// ── LUT panel ─────────────────────────────────────────────────────────────────

@Composable
private fun LutPanel(currentLutName: String?, onImport: () -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (currentLutName != null) {
            Text("Active: $currentLutName", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        } else {
            Text("No LUT applied", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Import LUT")
            }
            if (currentLutName != null) {
                FilledTonalButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove") }
            }
        }
        Text("Supports .cube (3DLUT) and HaldCLUT PNG files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Transform panel ───────────────────────────────────────────────────────────

@Composable
private fun TransformPanel(
    rotation: Int,
    fineRotation: Float,
    hasCrop: Boolean,
    onRotateCw: () -> Unit,
    onRotateCcw: () -> Unit,
    onFineRotation: (Float) -> Unit,
    onStartCrop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Rotation: ${"%.1f".format(rotation.toFloat() + fineRotation)}°",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRotateCcw, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("CCW")
            }
            FilledTonalButton(onClick = onRotateCw, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("CW")
            }
        }
        AdjustmentSlider("Fine Rotation", fineRotation, -45f..45f, onFineRotation, step = 0.1f)
        FilledTonalButton(onClick = onStartCrop, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(if (hasCrop) "Edit Crop" else "Crop Image")
        }
    }
}

// ── Frame panel ───────────────────────────────────────────────────────────────

private val frameColorSwatches = listOf(
    Color(1f, 1f, 1f)                  to 0xFFFFFFFF.toInt(),  // White
    Color(0f, 0f, 0f)                  to 0xFF000000.toInt(),  // Black
    Color(0.8f, 0.8f, 0.8f)           to 0xFFCCCCCC.toInt(),  // Light gray
    Color(0.267f, 0.267f, 0.267f)     to 0xFF444444.toInt(),  // Dark gray
)

private val frameRatioOptions = listOf(
    "Original" to 0f,
    "1:1"      to 1f,
    "4:3"      to 4f / 3f,
    "3:2"      to 3f / 2f,
    "9:16"     to 9f / 16f,
)

@Composable
private fun FramePanel(
    state: EditState,
    onEnabled: (Boolean) -> Unit,
    onColor: (Int) -> Unit,
    onRatio: (Float) -> Unit,
    onSize: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable frame", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Switch(checked = state.frameEnabled, onCheckedChange = onEnabled)
        }

        if (state.frameEnabled) {
            // Color swatches
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                frameColorSwatches.forEach { (color, argb) ->
                    val isSelected = state.frameColor == argb
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { onColor(argb) },
                    )
                }
            }

            // Ratio chips
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                frameRatioOptions.forEach { (label, ratio) ->
                    FilterChip(
                        selected = abs(state.frameRatio - ratio) < 0.01f,
                        onClick  = { onRatio(ratio) },
                        label    = { Text(label) },
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }

            // Size slider
            AdjustmentSlider("Size", state.frameSizePct, 0f..30f, onSize, step = 0.5f)
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier            = Modifier.padding(32.dp),
    ) {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null,
            modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            text      = "Open one or more images to start editing",
            style     = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onOpen) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp)); Text("Open Image(s)")
        }
    }
}

// ── Histogram ──────────────────────────────────────────────────────────────────

/**
 * Compute R/G/B histograms from [bitmap].
 * Caller is responsible for passing a frame-free bitmap so that white/black/grey
 * border pixels don't contaminate the histogram.
 */
private fun computeHistogram(bitmap: Bitmap): Triple<FloatArray, FloatArray, FloatArray> {
    val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
    val pixels = bitmap.width * bitmap.height
    val step = maxOf(1, pixels / 8000)
    var i = 0
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (i % step == 0) {
                val px = bitmap.getPixel(x, y)
                r[(px shr 16) and 0xFF]++
                g[(px shr 8)  and 0xFF]++
                b[px          and 0xFF]++
            }
            i++
        }
    }
    val maxVal = maxOf(r.max(), g.max(), b.max()).toFloat().coerceAtLeast(1f)
    val logMax = ln(maxVal + 1f)
    return Triple(
        r.map { ln(it.toFloat() + 1f) / logMax }.toFloatArray(),
        g.map { ln(it.toFloat() + 1f) / logMax }.toFloatArray(),
        b.map { ln(it.toFloat() + 1f) / logMax }.toFloatArray(),
    )
}

@Composable
private fun HistogramOverlay(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val (rBins, gBins, bBins) = remember(bitmap) { computeHistogram(bitmap) }
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height; val binW = w / 256f
        listOf(
            rBins to Color(0xFF00E5FF),
            gBins to Color(0xFF00ACC1),
            bBins to Color(0xFF4DD0E1),
        ).forEach { (bins, color) ->
            for (i in 0 until 256) {
                val barH = bins[i] * h
                drawRect(
                    color   = color.copy(alpha = 0.6f),
                    topLeft = Offset(i * binW, h - barH),
                    size    = Size(binW + 0.5f, barH),
                )
            }
        }
    }
}

// ── Grid overlay ───────────────────────────────────────────────────────────────

@Composable
private fun GridOverlay(gridMode: GridMode, modifier: Modifier = Modifier) {
    val lineColor = Color.White.copy(alpha = 0.45f)
    Canvas(modifier = modifier) {
        when (gridMode) {
            GridMode.THIRDS -> drawGrid(3, lineColor)
            GridMode.NINTHS -> drawGrid(9, lineColor)
            GridMode.GOLDEN -> {
                val phi = 0.6180339887f
                val a   = 1f - phi   // ≈ 0.382
                listOf(a, phi).forEach { t ->
                    drawLine(lineColor, Offset(size.width * t, 0f),
                        Offset(size.width * t, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(lineColor, Offset(0f, size.height * t),
                        Offset(size.width, size.height * t), strokeWidth = 1.dp.toPx())
                }
            }
            GridMode.NONE -> Unit
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    divisions: Int,
    color: Color,
) {
    val stroke = 1.dp.toPx()
    for (i in 1 until divisions) {
        val x = size.width  * i / divisions
        val y = size.height * i / divisions
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        drawLine(color, Offset(0f, y), Offset(size.width, y),  strokeWidth = stroke)
    }
}

// ── Exit-with-pending-exports dialog ──────────────────────────────────────────

@Composable
private fun ExitWithExportsDialog(
    pendingCount: Int,
    onBackground: () -> Unit,
    onClose: () -> Unit,
    onWait: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text("Exports in progress") },
        text  = {
            Text(
                "$pendingCount export${if (pendingCount > 1) "s" else ""} still running.\n\n" +
                "Run in background — the app will close itself once finished.\n" +
                "Or wait here until they complete.",
            )
        },
        confirmButton = {
            Button(onClick = onBackground) { Text("Run in background") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Close anyway") }
                TextButton(onClick = onWait)  { Text("Wait") }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun EditorScreenPreview() {
    PhotonLabTheme { EditorScreen() }
}
