package com.photonlab.ui.editor

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.photonlab.domain.model.DateImprintColor
import com.photonlab.domain.model.DateImprintFont
import com.photonlab.domain.model.DateImprintPosition
import com.photonlab.domain.model.DateImprintSettings
import com.photonlab.domain.model.DateImprintStyle
import com.photonlab.domain.model.EditState
import com.photonlab.domain.model.LutFile
import com.photonlab.platform.DesktopBitmap
import com.photonlab.platform.DesktopFilePicker
import com.photonlab.ui.components.AdjustmentSlider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ln

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onCloseWindow: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var zoom      by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var boxSize    by remember { mutableStateOf(IntSize.Zero) }

    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var panelHeightDp  by remember { mutableStateOf(340.dp) }
    val minPanelHeightDp = 48.dp
    val maxPanelHeightDp = 600.dp
    val dragHandleHeightDp = 8.dp
    val density = LocalDensity.current
    val bottomPanelHeightPx = with(density) { (panelHeightDp + dragHandleHeightDp).roundToPx() }

    var sidePanelWidthDp    by remember { mutableStateOf(320.dp) }
    val minSidePanelWidthDp = 240.dp
    val maxSidePanelWidthDp = 520.dp

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(boxSize) {
        if (boxSize != IntSize.Zero) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(uiState.layoutMode) { zoom = 1f; zoomOffset = Offset.Zero }

    fun zoomBy(factor: Float, anchor: Offset? = null) {
        val newZoom = (zoom * factor).coerceIn(1f, 8f)
        if (newZoom == zoom) return
        val cx = boxSize.width / 2f
        val cy = boxSize.height / 2f
        val ax = anchor?.x ?: cx
        val ay = anchor?.y ?: cy
        val newOffX = (ax - cx) * (zoom - newZoom) + zoomOffset.x
        val newOffY = (ay - cy) * (zoom - newZoom) + zoomOffset.y
        val maxX = (boxSize.width  * (newZoom - 1)) / 2f
        val maxY = (boxSize.height * (newZoom - 1)) / 2f
        zoom = newZoom
        zoomOffset = Offset(newOffX.coerceIn(-maxX, maxX), newOffY.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(uiState.sourceBitmap) { zoom = 1f; zoomOffset = Offset.Zero }
    LaunchedEffect(zoom) { viewModel.onZoomChanged(zoom) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
        val maxX = (boxSize.width  * (zoom - 1)) / 2f
        val maxY = (boxSize.height * (zoom - 1)) / 2f
        zoomOffset = Offset(
            (zoomOffset.x + panChange.x * zoom).coerceIn(-maxX, maxX),
            (zoomOffset.y + panChange.y * zoom).coerceIn(-maxY, maxY),
        )
    }


    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Plus, Key.Equals, Key.NumPadAdd      -> { zoomBy(1.2f);        true }
                        Key.Minus, Key.NumPadSubtract            -> { zoomBy(1f / 1.2f);   true }
                        else -> false
                    }
                } else false
            }) {

            // ── Shared image content lambda ────────────────────────────────
            val imageContent: @Composable BoxScope.() -> Unit = {
                val displayBitmap = when {
                    uiState.showOriginal -> uiState.originalGeomBitmap ?: uiState.sourceBitmap
                    else                 -> uiState.previewBitmap
                }
                if (displayBitmap != null) {
                    AnimatedContent(
                        targetState = uiState.batchNavKey to uiState.batchIndex,
                        transitionSpec = {
                            if (initialState.first == targetState.first) {
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
                                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                                        val startX    = firstDown.position.x
                                        val startY    = firstDown.position.y
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
                                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = zoomOffset.x, translationY = zoomOffset.y)
                                .transformable(transformableState)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        val pressStart = System.currentTimeMillis()
                                        viewModel.setShowOriginal(true)
                                        waitForUpOrCancellation()
                                        val pressDuration = System.currentTimeMillis() - pressStart
                                        viewModel.setShowOriginal(false)
                                        if (pressDuration >= 300L) return@awaitEachGesture
                                        val secondDown = withTimeoutOrNull(300L) { awaitFirstDown(requireUnconsumed = false) }
                                            ?: return@awaitEachGesture
                                        zoom = 1f; zoomOffset = Offset.Zero
                                        waitForUpOrCancellation()
                                    }
                                },
                        )
                    }
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
                    if (uiState.gridMode != GridMode.NONE) {
                        val imgW = displayBitmap.width.toFloat()
                        val imgH = displayBitmap.height.toFloat()
                        val boxW = boxSize.width.toFloat()
                        val boxH = boxSize.height.toFloat()
                        if (imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0) {
                            val scale = minOf(boxW / imgW, boxH / imgH)
                            GridOverlay(
                                gridMode = uiState.gridMode,
                                modifier = Modifier
                                    .size(with(density) { (imgW * scale).toDp() }, with(density) { (imgH * scale).toDp() })
                                    .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = zoomOffset.x, translationY = zoomOffset.y),
                            )
                        }
                    }
                    Row(
                        modifier          = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel::cycleGridMode, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.GridOn, contentDescription = "Toggle grid",
                                tint = if (uiState.gridMode != GridMode.NONE) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        IconButton(onClick = viewModel::toggleHistogram, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.BarChart, contentDescription = "Toggle histogram",
                                tint = if (uiState.showHistogram) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    if (uiState.showHistogram) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .size(width = 160.dp, height = 80.dp)
                                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                                .clip(MaterialTheme.shapes.small),
                        ) {
                            HistogramOverlay(
                                bitmap   = uiState.histogramBitmap ?: uiState.previewBitmap ?: displayBitmap,
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                            )
                        }
                    }
                } else {
                    EmptyState(onOpen = { scope.launch { val files = DesktopFilePicker.pickImageFiles(); if (files.isNotEmpty()) viewModel.loadImages(files) } })
                }
            }

            // Shared scroll-zoom modifier for the image area Box
            fun Modifier.imageAreaScrollZoom() = pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val scrollY = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                            if (scrollY != 0f) {
                                val factor = if (scrollY < 0) 1.1f else 1f / 1.1f
                                zoomBy(factor, event.changes.firstOrNull()?.position)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }

            if (uiState.layoutMode == LayoutMode.SIDE) {
                // ── Side layout: top bar + (image | panel) ────────────────
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
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
                                onOpen       = { scope.launch { val files = DesktopFilePicker.pickImageFiles(); if (files.isNotEmpty()) viewModel.loadImages(files) } },
                                onUndo       = viewModel::undo,
                                onRedo       = viewModel::redo,
                                onReset      = viewModel::resetAll,
                                onExport     = viewModel::export,
                                onExportAll  = viewModel::exportAll,
                                onSettings   = viewModel::openSettings,
                                onSkip       = viewModel::skipImage,
                                onHistory    = viewModel::openHistory,
                            )
                            HorizontalDivider()
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Image area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.surface)
                                .onSizeChanged { boxSize = it }
                                .imageAreaScrollZoom(),
                            contentAlignment = Alignment.Center,
                        ) { imageContent() }
                        // Vertical drag handle
                        Box(
                            modifier = Modifier
                                .width(dragHandleHeightDp)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmountPx ->
                                        val delta = with(density) { dragAmountPx.toDp() }
                                        sidePanelWidthDp = (sidePanelWidthDp - delta)
                                            .coerceIn(minSidePanelWidthDp, maxSidePanelWidthDp)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape),
                            )
                        }
                        VerticalDivider()
                        // Editor panel
                        Box(
                            modifier = Modifier
                                .width(sidePanelWidthDp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            ToolControls(
                                state       = uiState,
                                viewModel   = viewModel,
                                panelHeight = null,
                                onLutPick   = { scope.launch { DesktopFilePicker.pickLutFile(uiState.lutFolder)?.let { viewModel.importLut(it) } } },
                                onStartCrop = viewModel::startCrop,
                            )
                        }
                    }
                }
            } else {
                // ── Bottom layout (default) ────────────────────────────────
                // Full-screen image area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(
                            top    = with(density) { topBarHeightPx.toDp() },
                            bottom = with(density) { bottomPanelHeightPx.toDp() },
                        )
                        .onSizeChanged { boxSize = it }
                        .imageAreaScrollZoom(),
                    contentAlignment = Alignment.Center,
                ) { imageContent() }

                // Top bar overlay
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
                            onOpen       = { scope.launch { val files = DesktopFilePicker.pickImageFiles(); if (files.isNotEmpty()) viewModel.loadImages(files) } },
                            onUndo       = viewModel::undo,
                            onRedo       = viewModel::redo,
                            onReset      = viewModel::resetAll,
                            onExport     = viewModel::export,
                            onExportAll  = viewModel::exportAll,
                            onSettings   = viewModel::openSettings,
                            onSkip       = viewModel::skipImage,
                            onHistory    = viewModel::openHistory,
                        )
                        HorizontalDivider()
                    }
                }

                // Bottom edit panel overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Column {
                        // Drag handle — drag up to expand, down to shrink
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dragHandleHeightDp)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { _, dragAmountPx ->
                                        val delta = with(density) { dragAmountPx.toDp() }
                                        panelHeightDp = (panelHeightDp - delta)
                                            .coerceIn(minPanelHeightDp, maxPanelHeightDp)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(3.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape),
                            )
                        }
                        HorizontalDivider()
                        ToolControls(
                            state       = uiState,
                            viewModel   = viewModel,
                            panelHeight = panelHeightDp,
                            onLutPick   = { scope.launch { DesktopFilePicker.pickLutFile(uiState.lutFolder)?.let { viewModel.importLut(it) } } },
                            onStartCrop = viewModel::startCrop,
                        )
                    }
                }
            }

            // ── Full-screen overlays ───────────────────────────────────────

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
                    layoutMode     = uiState.layoutMode,
                    onLayoutMode   = viewModel::setLayoutMode,
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
                    entries      = uiState.historyEntries,
                    currentIndex = uiState.historyCursor,
                    onJumpTo     = viewModel::jumpToHistory,
                    onDismiss    = viewModel::closeHistory,
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
                    pendingCount   = uiState.pendingExportCount,
                    onBackground   = { viewModel.runExportsInBackground() },
                    onClose        = { viewModel.dismissExitDialog(); onCloseWindow() },
                    onWait         = viewModel::dismissExitDialog,
                )
            }
        }
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    canUndo: Boolean, canRedo: Boolean, hasImage: Boolean,
    canHistory: Boolean, isProcessing: Boolean,
    batchCount: Int, batchIndex: Int,
    onOpen: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit,
    onReset: () -> Unit, onExport: () -> Unit, onExportAll: () -> Unit, onSettings: () -> Unit,
    onSkip: () -> Unit, onHistory: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, contentDescription = "Open image(s)") }
        IconButton(onClick = onSkip, enabled = hasImage) { Icon(Icons.Default.SkipNext, contentDescription = "Skip image") }
        if (batchCount > 1) {
            Text(text = "${batchIndex + 1} / $batchCount", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onHistory, enabled = canHistory) { Icon(Icons.Default.History, contentDescription = "Edit history") }
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
        IconButton(onClick = onReset, enabled = hasImage) { Icon(Icons.Default.DeleteSweep, contentDescription = "Reset all") }
        FilledTonalIconButton(onClick = onExport, enabled = hasImage && !isProcessing) { Icon(Icons.Default.Save, contentDescription = "Export") }
        FilledTonalIconButton(onClick = onExportAll, enabled = hasImage && !isProcessing) { Icon(Icons.Default.SaveAlt, contentDescription = "Save all") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
    }
}

// ── History dialog ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryDialog(entries: List<EditState>, currentIndex: Int, onJumpTo: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit History") },
        text  = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text("No history yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    entries.indices.reversed().forEach { idx ->
                        val state = entries[idx]; val prevState = if (idx > 0) entries[idx - 1] else null
                        val isCurrent = idx == currentIndex; val isFuture = idx > currentIndex
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onJumpTo(idx) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text  = "${idx + 1}. ${historyLabel(idx, state, prevState)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when { isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer; isFuture -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface },
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
        if (state.lutPath != prev.lutPath) add(if (state.lutPath.isNotEmpty()) "LUT" else "LUT removed")
    }
    return if (parts.isEmpty()) "No changes" else parts.joinToString(", ")
}

// ── Settings dialog ────────────────────────────────────────────────────────────

@Composable
private fun SettingsDialog(
    quality: Int, onQuality: (Int) -> Unit,
    lutFolder: String, onLutFolder: (String) -> Unit,
    layoutMode: LayoutMode, onLayoutMode: (LayoutMode) -> Unit,
    presets: List<Preset>, hasImage: Boolean,
    editState: EditState, currentLut: LutFile?,
    onApplyPreset: (Preset) -> Unit, onSavePreset: (String, Set<PresetParam>) -> Unit,
    onRenamePreset: (Preset, String) -> Unit, onDeletePreset: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCreate     by remember { mutableStateOf(false) }
    var renamingPreset by remember { mutableStateOf<Preset?>(null) }
    var deletingPreset by remember { mutableStateOf<Preset?>(null) }
    var lutFolderText  by remember(lutFolder) { mutableStateOf(lutFolder) }
    val scope = rememberCoroutineScope()

    when {
        showCreate -> CreatePresetDialog(editState = editState, currentLut = currentLut,
            onSave = { name, params -> onSavePreset(name, params); showCreate = false; onDismiss() },
            onDismiss = { showCreate = false })
        renamingPreset != null -> RenamePresetDialog(preset = renamingPreset!!,
            onRename = { newName -> onRenamePreset(renamingPreset!!, newName); renamingPreset = null },
            onDismiss = { renamingPreset = null })
        deletingPreset != null -> ConfirmDeleteDialog(presetName = deletingPreset!!.name,
            onConfirm = { onDeletePreset(deletingPreset!!); deletingPreset = null },
            onDismiss = { deletingPreset = null })
        else -> {
            var sliderVal by remember(quality) { mutableFloatStateOf(quality.toFloat()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Settings") },
                text  = {
                    Column(
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Layout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = layoutMode == LayoutMode.BOTTOM,
                                onClick  = { onLayoutMode(LayoutMode.BOTTOM) },
                                label    = { Text("Bottom panel") },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = layoutMode == LayoutMode.SIDE,
                                onClick  = { onLayoutMode(LayoutMode.SIDE) },
                                label    = { Text("Side panel") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Export", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("JPEG Quality", style = MaterialTheme.typography.bodyMedium)
                            Text("${sliderVal.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(value = sliderVal, onValueChange = { sliderVal = it }, valueRange = 50f..100f, steps = 49)
                        OutlinedTextField(
                            value = lutFolderText, onValueChange = { lutFolderText = it },
                            label = { Text("Default LUT folder") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    scope.launch {
                                        val dir = DesktopFilePicker.pickDirectory()
                                        dir?.let { lutFolderText = it.absolutePath }
                                    }
                                }) { Icon(Icons.Default.FolderOpen, contentDescription = "Browse") }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Presets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        if (presets.isEmpty()) {
                            Text("No presets saved yet.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                        } else {
                            presets.forEach { preset ->
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (hasImage) {
                                        TextButton(onClick = { onApplyPreset(preset) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                            Text(preset.name, textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                        }
                                    } else {
                                        Text(preset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                                    }
                                    IconButton(onClick = { renamingPreset = preset }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { deletingPreset = preset }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) { Text("Create preset") }
                    }
                },
                confirmButton = {
                    Button(onClick = { onQuality(sliderVal.toInt()); onLutFolder(lutFolderText.trim()); onDismiss() }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
        }
    }
}

// ── Preset sub-dialogs ─────────────────────────────────────────────────────────

@Composable
private fun CreatePresetDialog(editState: EditState, currentLut: LutFile?, onSave: (String, Set<PresetParam>) -> Unit, onDismiss: () -> Unit) {
    val modifiedParams = remember(editState, currentLut) {
        PresetParam.entries.filter { it.isNonDefault(editState, currentLut != null) }.toSet()
    }
    var name     by remember { mutableStateOf("") }
    var selected by remember(modifiedParams) { mutableStateOf(modifiedParams) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Preset") },
        text  = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                if (modifiedParams.isEmpty()) {
                    Text("No adjustments have been made. Modify some parameters first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Parameters to include:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    modifiedParams.forEach { param ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = param in selected, onCheckedChange = { checked -> selected = if (checked) selected + param else selected - param })
                            Text("${param.label}: ${param.displayValue(editState, currentLut?.let { "applied" })}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name, selected) }, enabled = name.isNotBlank() && selected.isNotEmpty() && modifiedParams.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenamePresetDialog(preset: Preset, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(preset) { mutableStateOf(preset.name) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename Preset") },
        text  = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(presetName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Delete Preset") },
        text  = { Text("Delete \"$presetName\"? This cannot be undone.") },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ApplyBatchDialog(presetName: String, onConfirm: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip, title = { Text("Apply to remaining images?") },
        text  = { Text("Apply preset \"$presetName\" to all remaining images in the batch?", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Button(onClick = onConfirm) { Text("Yes, apply to all") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("No, just this one") } },
    )
}

// ── PresetParam helpers ────────────────────────────────────────────────────────

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
    PresetParam.DATE_IMPRINT -> if (state.dateImprint.enabled) state.dateImprint.style.label else "Off"
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
    PresetParam.DATE_IMPRINT -> state.dateImprint.enabled
}

// ── Tool controls ──────────────────────────────────────────────────────────────

@Composable
private fun ToolControls(state: EditorUiState, viewModel: EditorViewModel, panelHeight: Dp?, onLutPick: () -> Unit, onStartCrop: () -> Unit) {
    val e = state.editState
    Column(modifier = Modifier.fillMaxWidth().let { if (panelHeight != null) it.height(panelHeight) else it.fillMaxHeight() }) {
        CategoryTabRow(selected = state.selectedCategory, onSelect = viewModel::selectCategory)
        HorizontalDivider()
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
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
                ToolCategory.LUT -> LutPanel(currentLutName = state.currentLut?.name, onImport = onLutPick, onRemove = viewModel::removeLut)
                ToolCategory.TRANSFORM -> {
                    TransformPanel(rotation = e.rotation, fineRotation = e.fineRotation, hasCrop = e.cropRect != null,
                        onRotateCw = viewModel::rotateClockwise, onRotateCcw = viewModel::rotateCounterClockwise,
                        onFineRotation = viewModel::setFineRotation, onStartCrop = onStartCrop)
                    SectionHeader("Frame")
                    FramePanel(state = e, onEnabled = viewModel::setFrameEnabled, onColor = viewModel::setFrameColor,
                        onRatio = viewModel::setFrameRatio, onSize = viewModel::setFrameSizePct)
                    SectionHeader("Date Imprint")
                    DateImprintPanel(
                        settings       = e.dateImprint,
                        photoDate      = state.photoDate,
                        onEnabled      = viewModel::setDateImprintEnabled,
                        onStyle        = viewModel::setDateImprintStyle,
                        onColor        = viewModel::setDateImprintColor,
                        onFont         = viewModel::setDateImprintFont,
                        onSize         = viewModel::setDateImprintSize,
                        onPosition     = viewModel::setDateImprintPosition,
                        onGlow         = viewModel::setDateImprintGlow,
                        onBlur         = viewModel::setDateImprintBlur,
                        onOpacity      = viewModel::setDateImprintOpacity,
                        onBlurRepeat   = viewModel::setDateImprintBlurRepeat,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryTabRow(selected: ToolCategory, onSelect: (ToolCategory) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ToolCategory.TONE to "Tone", ToolCategory.COLOR to "Color", ToolCategory.LUT to "LUT", ToolCategory.TRANSFORM to "Transform").forEach { (cat, label) ->
            FilterChip(selected = selected == cat, onClick = { onSelect(cat) }, label = { Text(label) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun LutPanel(currentLutName: String?, onImport: () -> Unit, onRemove: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (currentLutName != null) Text("Active: $currentLutName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        else Text("No LUT applied", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp)); Text("Import LUT")
            }
            if (currentLutName != null) {
                FilledTonalButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove") }
            }
        }
        Text("Supports .cube (3DLUT) and HaldCLUT PNG files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransformPanel(rotation: Int, fineRotation: Float, hasCrop: Boolean, onRotateCw: () -> Unit, onRotateCcw: () -> Unit, onFineRotation: (Float) -> Unit, onStartCrop: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rotation: ${"%.1f".format(rotation.toFloat() + fineRotation)}°", style = MaterialTheme.typography.bodyMedium)
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
            Spacer(Modifier.size(6.dp)); Text(if (hasCrop) "Edit Crop" else "Crop Image")
        }
    }
}

private val frameColorSwatches = listOf(Color(1f, 1f, 1f) to 0xFFFFFFFF.toInt(), Color(0f, 0f, 0f) to 0xFF000000.toInt(), Color(0.8f, 0.8f, 0.8f) to 0xFFCCCCCC.toInt(), Color(0.267f, 0.267f, 0.267f) to 0xFF444444.toInt())
private val frameRatioOptions  = listOf("Original" to 0f, "1:1" to 1f, "4:3" to 4f/3f, "3:2" to 3f/2f, "9:16" to 9f/16f)

@Composable
private fun FramePanel(state: EditState, onEnabled: (Boolean) -> Unit, onColor: (Int) -> Unit, onRatio: (Float) -> Unit, onSize: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable frame", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.frameEnabled, onCheckedChange = onEnabled)
        }
        if (state.frameEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                frameColorSwatches.forEach { (color, argb) ->
                    val isSelected = state.frameColor == argb
                    Box(modifier = Modifier.size(28.dp).background(color, CircleShape).border(width = if (isSelected) 2.5.dp else 1.dp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape = CircleShape).clickable { onColor(argb) })
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                frameRatioOptions.forEach { (label, ratio) ->
                    FilterChip(selected = abs(state.frameRatio - ratio) < 0.01f, onClick = { onRatio(ratio) }, label = { Text(label) }, modifier = Modifier.padding(0.dp))
                }
            }
            AdjustmentSlider("Size", state.frameSizePct, 0f..30f, onSize, step = 0.5f)
        }
    }
}

// ── Date Imprint panel ────────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DateImprintPanel(
    settings: DateImprintSettings,
    photoDate: java.util.Date?,
    onEnabled: (Boolean) -> Unit,
    onStyle: (DateImprintStyle) -> Unit,
    onColor: (DateImprintColor) -> Unit,
    onFont: (DateImprintFont) -> Unit,
    onSize: (Float) -> Unit,
    onPosition: (DateImprintPosition) -> Unit,
    onGlow: (Int) -> Unit,
    onBlur: (Int) -> Unit,
    onOpacity: (Int) -> Unit,
    onBlurRepeat: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Date imprint", style = MaterialTheme.typography.bodyMedium)
                val dateLabel = when {
                    photoDate != null -> settings.style.format(photoDate)
                    else              -> "No EXIF date"
                }
                Text(dateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabled)
        }
        if (settings.enabled) {
            // Style selector — dropdown showing example output for each style
            var styleMenuExpanded by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Style", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                ExposedDropdownMenuBox(
                    expanded = styleMenuExpanded,
                    onExpandedChange = { styleMenuExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = settings.style.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleMenuExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = styleMenuExpanded,
                        onDismissRequest = { styleMenuExpanded = false },
                    ) {
                        DateImprintStyle.entries.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(style.label, style = MaterialTheme.typography.bodySmall) },
                                onClick = { onStyle(style); styleMenuExpanded = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
            // Color chips
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Color", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateImprintColor.entries.forEach { c ->
                        val awtColor = runCatching { java.awt.Color.decode(c.hex) }.getOrDefault(java.awt.Color.ORANGE)
                        val composeColor = Color(awtColor.red / 255f, awtColor.green / 255f, awtColor.blue / 255f)
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(composeColor, CircleShape)
                                .border(
                                    width = if (settings.color == c) 2.5.dp else 1.dp,
                                    color = if (settings.color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { onColor(c) },
                        )
                    }
                }
            }
            // Font chips
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Font", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DateImprintFont.entries.forEach { f ->
                        FilterChip(selected = settings.font == f, onClick = { onFont(f) }, label = { Text(f.label) })
                    }
                }
            }
            AdjustmentSlider("Size", settings.sizePercent, 1f..4f, onSize, step = 0.1f, defaultValue = 2.0f)
            // Position chips
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DateImprintPosition.entries.forEach { p ->
                        FilterChip(selected = settings.position == p, onClick = { onPosition(p) }, label = { Text(p.label) })
                    }
                }
            }
            // Sliders
            AdjustmentSlider("Opacity",  settings.opacity.toFloat(),    0f..100f, { onOpacity(it.toInt()) },    step = 1f)
            AdjustmentSlider("Glow",     settings.glowAmount.toFloat(), 0f..100f, { onGlow(it.toInt()) },       step = 1f)
            AdjustmentSlider("Blur",     settings.blurAmount.toFloat(), 0f..100f, { onBlur(it.toInt()) },       step = 1f)
            AdjustmentSlider("Repeat",   settings.blurRepeat.toFloat(), 1f..20f,  { onBlurRepeat(it.toInt()) }, step = 1f)
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Open one or more images to start editing", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onOpen) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp)); Text("Open Image(s)")
        }
    }
}

// ── Histogram ──────────────────────────────────────────────────────────────────

private fun computeHistogram(bitmap: DesktopBitmap): Triple<FloatArray, FloatArray, FloatArray> {
    val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val total = pixels.size; val step = maxOf(1, total / 8000)
    for (i in pixels.indices step step) {
        val px = pixels[i]
        r[(px shr 16) and 0xFF]++; g[(px shr 8) and 0xFF]++; b[px and 0xFF]++
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
private fun HistogramOverlay(bitmap: DesktopBitmap, modifier: Modifier = Modifier) {
    val (rBins, gBins, bBins) = remember(bitmap) { computeHistogram(bitmap) }
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height; val binW = w / 256f
        listOf(rBins to Color(0xFF00E5FF), gBins to Color(0xFF00ACC1), bBins to Color(0xFF4DD0E1)).forEach { (bins, color) ->
            for (i in 0 until 256) {
                val barH = bins[i] * h
                drawRect(color = color.copy(alpha = 0.6f), topLeft = Offset(i * binW, h - barH), size = Size(binW + 0.5f, barH))
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
                val phi = 0.6180339887f; val a = 1f - phi
                listOf(a, phi).forEach { t ->
                    drawLine(lineColor, Offset(size.width * t, 0f), Offset(size.width * t, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(lineColor, Offset(0f, size.height * t), Offset(size.width, size.height * t), strokeWidth = 1.dp.toPx())
                }
            }
            GridMode.NONE -> Unit
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(divisions: Int, color: Color) {
    val stroke = 1.dp.toPx()
    for (i in 1 until divisions) {
        val x = size.width  * i / divisions
        val y = size.height * i / divisions
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        drawLine(color, Offset(0f, y), Offset(size.width, y),  strokeWidth = stroke)
    }
}

// ── Exit dialog ────────────────────────────────────────────────────────────────

@Composable
private fun ExitWithExportsDialog(pendingCount: Int, onBackground: () -> Unit, onClose: () -> Unit, onWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text("Exports in progress") },
        text  = { Text("$pendingCount export${if (pendingCount > 1) "s" else ""} still running.\n\nContinue in background — the window will close itself when done.\nOr wait here until they complete.") },
        confirmButton = { Button(onClick = onBackground) { Text("Continue in background") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Close anyway") }
                TextButton(onClick = onWait)  { Text("Wait") }
            }
        },
    )
}
