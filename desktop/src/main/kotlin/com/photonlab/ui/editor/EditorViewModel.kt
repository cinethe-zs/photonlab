package com.photonlab.ui.editor

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.photonlab.data.lut.LutParser
import com.photonlab.domain.EditHistoryManager
import com.photonlab.domain.model.DateImprintColor
import com.photonlab.domain.model.DateImprintFont
import com.photonlab.domain.model.DateImprintPosition
import com.photonlab.domain.model.DateImprintSettings
import com.photonlab.domain.model.DateImprintStyle
import com.photonlab.domain.model.EditState
import com.photonlab.domain.model.LutFile
import com.photonlab.domain.model.LutType
import com.photonlab.domain.model.NormalizedRect
import com.photonlab.platform.DesktopBitmap
import com.photonlab.platform.DesktopPreferences
import com.photonlab.platform.DesktopSaveManager
import com.photonlab.rendering.EditPipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

// ── Tool categories ────────────────────────────────────────────────────────────

enum class ToolCategory { TONE, COLOR, LUT, TRANSFORM }

enum class GridMode { NONE, THIRDS, NINTHS, GOLDEN }

// ── Preset parameter selection ─────────────────────────────────────────────────

enum class PresetParam(val label: String) {
    EXPOSURE("Exposure"), LUMINOSITY("Luminosity"), CONTRAST("Contrast"),
    HIGHLIGHTS("Highlights"), SHADOWS("Shadows"), SATURATION("Saturation"),
    VIBRANCE("Vibrance"), TEMPERATURE("Temperature"), TINT("Tint"),
    SHARPENING("Sharpening"), NOISE("Noise"), ROTATION("Rotation"),
    LUT("LUT"), FRAME("Frame"), DATE_IMPRINT("Date Imprint");

    companion object { val ALL: Set<PresetParam> get() = entries.toSet() }
}

// ── Preset ────────────────────────────────────────────────────────────────────

data class Preset(
    val name: String,
    val state: EditState,
    val lut: LutFile?,
    val includedParams: Set<PresetParam> = PresetParam.ALL,
)

// ── UI State ──────────────────────────────────────────────────────────────────

enum class LayoutMode { BOTTOM, SIDE }

data class EditorUiState(
    val sourceBitmap: DesktopBitmap? = null,
    val previewBitmap: DesktopBitmap? = null,
    val originalGeomBitmap: DesktopBitmap? = null,
    val cropSourceBitmap: DesktopBitmap? = null,
    val editState: EditState = EditState(),
    val currentLut: LutFile? = null,
    val selectedCategory: ToolCategory = ToolCategory.TONE,
    val isProcessing: Boolean = false,
    val showOriginal: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val snackbarMessage: String? = null,
    val jpegQuality: Int = 95,
    val lutFolder: String = "",
    val showSettings: Boolean = false,
    val showCropScreen: Boolean = false,
    val showHistoryDialog: Boolean = false,
    val historyEntries: List<EditState> = emptyList(),
    val historyCursor: Int = 0,
    val savedPresets: List<Preset> = emptyList(),
    val applyBatchPreset: Preset? = null,
    val pendingPreset: Preset? = null,
    val batchCount: Int = 0,
    val batchIndex: Int = 0,
    val batchNavKey: Int = 0,
    val swipeForward: Boolean = true,
    val showHistogram: Boolean = false,
    val histogramBitmap: DesktopBitmap? = null,
    val gridMode: GridMode = GridMode.NONE,
    val pendingExportCount: Int = 0,
    val showExitConfirmDialog: Boolean = false,
    val closeWindow: Boolean = false,
    val photoDate: java.util.Date? = null,
    val layoutMode: LayoutMode = LayoutMode.BOTTOM,
)

// ── Export job ─────────────────────────────────────────────────────────────────

private data class ExportJob(
    val bitmap: DesktopBitmap,
    val state: EditState,
    val lut: LutFile?,
    val quality: Int,
    val photoDate: java.util.Date? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class EditorViewModel {

    private val pipeline = EditPipeline()
    private val history  = EditHistoryManager()
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingExports = java.util.concurrent.atomic.AtomicInteger(0)
    private var shouldAutoClose = false

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var renderJob: Job? = null
    private var geomRenderJob: Job? = null
    private var quickSourceBitmap: DesktopBitmap? = null
    private var previewSourceBitmap: DesktopBitmap? = null
    private var currentZoom: Float = 1f

    private var batchFiles: List<File> = emptyList()
    private var batchIndex: Int = 0
    private val batchSnapshots = mutableMapOf<Int, Pair<EditState, LutFile?>>()

    private val exportChannel = Channel<ExportJob>(Channel.UNLIMITED)

    init {
        _uiState.update {
            it.copy(
                savedPresets = loadPresets(),
                jpegQuality  = DesktopPreferences.getInt("jpeg_quality", 95),
                lutFolder    = DesktopPreferences.getString("lut_folder", ""),
                layoutMode   = LayoutMode.valueOf(DesktopPreferences.getString("layout_mode", LayoutMode.BOTTOM.name)),
            )
        }
        scope.launch(Dispatchers.IO) {
            for (job in exportChannel) {
                val count = pendingExports.incrementAndGet()
                _uiState.update { it.copy(pendingExportCount = count) }
                runCatching {
                    val processed = withContext(Dispatchers.Default) { pipeline.process(job.bitmap, job.state, job.lut, job.photoDate) }
                    val file = DesktopSaveManager.saveJpeg(processed, job.quality)
                    processed.recycle()
                    job.bitmap.recycle()
                    _uiState.update { it.copy(snackbarMessage = "Saved to ${file.path}") }
                }.onFailure { err ->
                    job.bitmap.recycle()
                    _uiState.update { it.copy(snackbarMessage = "Export failed: ${err.message}") }
                }
                val remaining = pendingExports.decrementAndGet()
                _uiState.update { it.copy(pendingExportCount = remaining) }
                if (remaining == 0 && shouldAutoClose) {
                    _uiState.update { it.copy(closeWindow = true) }
                }
            }
        }
    }

    fun onCleared() {
        exportChannel.close()
        scope.cancel()
        pipeline.destroy()
    }

    // ── Image loading ──────────────────────────────────────────────────────

    fun loadImages(files: List<File>) {
        if (files.isEmpty()) return
        batchFiles = files
        batchIndex = 0
        batchSnapshots.clear()
        _uiState.update { it.copy(batchCount = files.size, batchIndex = 0) }
        loadSingleImage(files[0], initialState = EditState(), initialLut = null)
    }

    private fun saveSnapshot() {
        val s = _uiState.value
        batchSnapshots[batchIndex] = Pair(s.editState, s.currentLut)
    }

    private fun goToImage(index: Int) {
        saveSnapshot()
        val prevIndex = batchIndex
        batchIndex = index
        val pending = _uiState.value.pendingPreset
        _uiState.update {
            it.copy(
                batchCount   = batchFiles.size,
                batchIndex   = index,
                batchNavKey  = it.batchNavKey + 1,
                swipeForward = index > prevIndex,
            )
        }
        val (savedState, savedLut) = batchSnapshots[index] ?: Pair(EditState(), null)
        val (initialState, initialLut) = if (pending != null && !batchSnapshots.containsKey(index)) {
            Pair(applyPresetToState(pending, EditState()),
                 if (PresetParam.LUT in pending.includedParams) pending.lut else null)
        } else Pair(savedState, savedLut)
        loadSingleImage(batchFiles[index], initialState = initialState, initialLut = initialLut)
    }

    fun nextImage() { if (batchIndex < batchFiles.size - 1) goToImage(batchIndex + 1) }
    fun prevImage() { if (batchIndex > 0) goToImage(batchIndex - 1) }

    private fun loadSingleImage(file: File, initialState: EditState, initialLut: LutFile?) {
        scope.launch(Dispatchers.IO) {
            val rawImage = runCatching { ImageIO.read(file) }.getOrNull() ?: return@launch
            val bitmap = DesktopBitmap.fromBufferedImage(rawImage)
            val photoDate = readExifDate(file)

            quickSourceBitmap   = downsample(bitmap, 512)
            previewSourceBitmap = bitmap
            history.reset(initialState)
            _uiState.update {
                it.copy(
                    sourceBitmap        = bitmap,
                    previewBitmap       = quickSourceBitmap,
                    originalGeomBitmap  = previewSourceBitmap ?: quickSourceBitmap,
                    cropSourceBitmap    = null,
                    editState           = initialState,
                    currentLut          = initialLut,
                    canUndo             = false,
                    canRedo             = false,
                    historyEntries      = listOf(initialState),
                    historyCursor       = 0,
                    histogramBitmap     = null,
                    photoDate           = photoDate,
                )
            }
            scheduleRender(initialState, immediate = true)
        }
    }

    private fun readExifDate(file: File): java.util.Date? = runCatching {
        val metadata = ImageMetadataReader.readMetadata(file)
        val tz = java.util.TimeZone.getDefault()
        val subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val ifd0   = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        // Ordered by preference: most-specific tag first, then broader fallbacks
        val candidates = listOf(
            subIfd to ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL,
            subIfd to ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED,
            ifd0   to ExifIFD0Directory.TAG_DATETIME,
        )
        for ((dir, tag) in candidates) {
            if (dir == null) continue
            // First try the library's own parser (handles sub-seconds and offset tags)
            runCatching { dir.getDate(tag, tz) }.getOrNull()?.let { return@runCatching it }
            // Fall back to manual parsing of the raw EXIF string
            parseExifDateString(dir.getString(tag) ?: continue, tz)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun parseExifDateString(raw: String, tz: java.util.TimeZone): java.util.Date? {
        val s = raw.trim()
        if (s.isEmpty() || s == "0000:00:00 00:00:00") return null
        val formats = listOf(
            "yyyy:MM:dd HH:mm:ss",   // standard EXIF
            "yyyy-MM-dd HH:mm:ss",   // some editors
            "yyyy-MM-dd'T'HH:mm:ss", // ISO 8601 variant
            "yyyy:MM:dd",
            "yyyy-MM-dd",
        )
        for (fmt in formats) {
            runCatching {
                java.text.SimpleDateFormat(fmt, java.util.Locale.US).also { it.timeZone = tz }.parse(s)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun downsample(src: DesktopBitmap, maxPx: Int): DesktopBitmap {
        if (src.width <= maxPx && src.height <= maxPx) return src
        val scale = maxPx.toFloat() / maxOf(src.width, src.height)
        return DesktopBitmap.createScaled(
            src,
            (src.width  * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ── Edit sliders ───────────────────────────────────────────────────────

    fun setExposure(v: Float)     = updateEdit(immediate = true,  paramKey = "exposure")     { it.copy(exposure     = v) }
    fun setLuminosity(v: Float)   = updateEdit(immediate = true,  paramKey = "luminosity")   { it.copy(luminosity   = v) }
    fun setContrast(v: Float)     = updateEdit(paramKey = "contrast")     { it.copy(contrast    = v) }
    fun setHighlights(v: Float)   = updateEdit(paramKey = "highlights")   { it.copy(highlights  = v) }
    fun setShadows(v: Float)      = updateEdit(paramKey = "shadows")      { it.copy(shadows     = v) }
    fun setSaturation(v: Float)   = updateEdit(paramKey = "saturation")   { it.copy(saturation  = v) }
    fun setVibrance(v: Float)     = updateEdit(paramKey = "vibrance")     { it.copy(vibrance    = v) }
    fun setTemperature(v: Float)  = updateEdit(paramKey = "temperature")  { it.copy(temperature = v) }
    fun setTint(v: Float)         = updateEdit(paramKey = "tint")         { it.copy(tint        = v) }
    fun setSharpening(v: Float)   = updateEdit(paramKey = "sharpening")   { it.copy(sharpening  = v) }
    fun setNoise(v: Float)        = updateEdit(paramKey = "noise")        { it.copy(noise       = v) }
    fun setFineRotation(v: Float) = updateEdit(immediate = true, paramKey = "fineRotation") { it.copy(fineRotation = v) }

    fun rotateClockwise()        = updateEdit { it.copy(rotation = (it.rotation + 90)       % 360, cropRect = null) }
    fun rotateCounterClockwise() = updateEdit { it.copy(rotation = (it.rotation - 90 + 360) % 360, cropRect = null) }

    fun setFrameEnabled(v: Boolean) = updateEdit { it.copy(frameEnabled = v) }
    fun setFrameColor(v: Int)       = updateEdit { it.copy(frameColor   = v) }
    fun setFrameRatio(v: Float)     = updateEdit { it.copy(frameRatio   = v) }
    fun setFrameSizePct(v: Float)   = updateEdit(paramKey = "frameSizePct") { it.copy(frameSizePct = v) }

    fun setDateImprintEnabled(v: Boolean)         = updateEdit { it.copy(dateImprint = it.dateImprint.copy(enabled   = v)) }
    fun setDateImprintStyle(v: DateImprintStyle)  = updateEdit { it.copy(dateImprint = it.dateImprint.copy(style     = v)) }
    fun setDateImprintColor(v: DateImprintColor)  = updateEdit { it.copy(dateImprint = it.dateImprint.copy(color     = v)) }
    fun setDateImprintFont(v: DateImprintFont)    = updateEdit { it.copy(dateImprint = it.dateImprint.copy(font      = v)) }
    fun setDateImprintSize(v: Float)              = updateEdit(paramKey = "diSize") { it.copy(dateImprint = it.dateImprint.copy(sizePercent = v)) }
    fun setDateImprintPosition(v: DateImprintPosition) = updateEdit { it.copy(dateImprint = it.dateImprint.copy(position  = v)) }
    fun setDateImprintGlow(v: Int)                = updateEdit(paramKey = "diGlow")   { it.copy(dateImprint = it.dateImprint.copy(glowAmount  = v)) }
    fun setDateImprintBlur(v: Int)                = updateEdit(paramKey = "diBlur")   { it.copy(dateImprint = it.dateImprint.copy(blurAmount  = v)) }
    fun setDateImprintOpacity(v: Int)             = updateEdit(paramKey = "diOpacity"){ it.copy(dateImprint = it.dateImprint.copy(opacity     = v)) }
    fun setDateImprintBlurRepeat(v: Int)          = updateEdit(paramKey = "diRepeat") { it.copy(dateImprint = it.dateImprint.copy(blurRepeat  = v)) }

    private fun updateEdit(
        immediate: Boolean = false,
        paramKey: String? = null,
        transform: (EditState) -> EditState,
    ) {
        val newState = transform(_uiState.value.editState)
        history.push(newState, paramKey)
        _uiState.update {
            it.copy(
                editState      = newState,
                canUndo        = history.canUndo,
                canRedo        = history.canRedo,
                historyEntries = history.entries,
                historyCursor  = history.currentIndex,
            )
        }
        scheduleRender(newState, immediate = immediate)
    }

    // ── Undo / Redo ────────────────────────────────────────────────────────

    fun undo() {
        val state = history.undo() ?: return
        _uiState.update {
            it.copy(editState = state, canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex)
        }
        scheduleRender(state)
    }

    fun redo() {
        val state = history.redo() ?: return
        _uiState.update {
            it.copy(editState = state, canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex)
        }
        scheduleRender(state)
    }

    fun resetAll() {
        history.reset(EditState())
        _uiState.update {
            it.copy(editState = EditState(), currentLut = null,
                    previewBitmap = quickSourceBitmap ?: it.sourceBitmap,
                    canUndo = false, canRedo = false,
                    historyEntries = listOf(EditState()), historyCursor = 0)
        }
        scheduleGeomRender(EditState())
    }

    // ── History dialog ─────────────────────────────────────────────────────

    fun openHistory()  = _uiState.update { it.copy(showHistoryDialog = true) }
    fun closeHistory() = _uiState.update { it.copy(showHistoryDialog = false) }
    fun jumpToHistory(index: Int) {
        val state = history.jumpTo(index) ?: return
        _uiState.update {
            it.copy(editState = state, canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex,
                    showHistoryDialog = false)
        }
        scheduleRender(state)
    }

    // ── LUT ───────────────────────────────────────────────────────────────

    fun importLut(file: File) {
        scope.launch {
            runCatching { LutParser.parse(file) }.fold(
                onSuccess = { lut ->
                    val newState = _uiState.value.editState.copy(lutPath = file.canonicalPath)
                    history.push(newState)
                    _uiState.update {
                        it.copy(currentLut = lut, editState = newState,
                                canUndo = history.canUndo, canRedo = history.canRedo,
                                historyEntries = history.entries, historyCursor = history.currentIndex,
                                snackbarMessage = "LUT applied: ${lut.name}")
                    }
                    scheduleRender(newState, immediate = true)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(snackbarMessage = "LUT error: ${err.message}") }
                },
            )
        }
    }

    fun removeLut() {
        val newState = _uiState.value.editState.copy(lutPath = "")
        history.push(newState)
        _uiState.update {
            it.copy(currentLut = null, editState = newState,
                    canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex)
        }
        scheduleRender(newState)
    }

    // ── Crop ──────────────────────────────────────────────────────────────

    fun startCrop() {
        scope.launch(Dispatchers.Default) {
            val src   = _uiState.value.sourceBitmap ?: return@launch
            val state = _uiState.value.editState
            val rotOnlyState = EditState(rotation = state.rotation, fineRotation = state.fineRotation)
            val rotated = pipeline.process(src, rotOnlyState, null)
            _uiState.update { it.copy(showCropScreen = true, cropSourceBitmap = rotated) }
        }
    }

    fun cancelCrop() = _uiState.update { it.copy(showCropScreen = false) }

    fun confirmCrop(rect: NormalizedRect?) {
        val newState = _uiState.value.editState.copy(cropRect = rect)
        history.push(newState)
        _uiState.update {
            it.copy(editState = newState, showCropScreen = false,
                    canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex)
        }
        scheduleRender(newState)
    }

    // ── Settings ──────────────────────────────────────────────────────────

    fun openSettings()  = _uiState.update { it.copy(showSettings = true) }
    fun closeSettings() = _uiState.update { it.copy(showSettings = false) }
    fun setJpegQuality(q: Int) {
        DesktopPreferences.putInt("jpeg_quality", q)
        _uiState.update { it.copy(jpegQuality = q) }
    }
    fun setLutFolder(path: String) {
        DesktopPreferences.putString("lut_folder", path)
        _uiState.update { it.copy(lutFolder = path) }
    }
    fun setLayoutMode(mode: LayoutMode) {
        DesktopPreferences.putString("layout_mode", mode.name)
        _uiState.update { it.copy(layoutMode = mode) }
    }

    // ── UI events ─────────────────────────────────────────────────────────

    fun selectCategory(cat: ToolCategory) = _uiState.update { it.copy(selectedCategory = cat) }
    fun setShowOriginal(show: Boolean)    = _uiState.update { it.copy(showOriginal = show) }
    fun clearSnackbar()                   = _uiState.update { it.copy(snackbarMessage = null) }
    fun toggleHistogram()                 = _uiState.update { it.copy(showHistogram = !it.showHistogram) }
    fun cycleGridMode()                   = _uiState.update {
        val modes = GridMode.entries
        it.copy(gridMode = modes[(it.gridMode.ordinal + 1) % modes.size])
    }

    // ── Exit / background export ───────────────────────────────────────────

    fun requestExit()        = _uiState.update { it.copy(showExitConfirmDialog = true) }
    fun dismissExitDialog()  = _uiState.update { it.copy(showExitConfirmDialog = false) }
    fun runExportsInBackground() {
        shouldAutoClose = true
        _uiState.update { it.copy(showExitConfirmDialog = false) }
    }
    fun closeWindowHandled() = _uiState.update { it.copy(closeWindow = false) }

    // ── Preset ────────────────────────────────────────────────────────────

    fun savePreset(name: String, includedParams: Set<PresetParam>) {
        val preset = Preset(name.trim(), _uiState.value.editState, _uiState.value.currentLut, includedParams)
        val newList = _uiState.value.savedPresets + preset
        _uiState.update { it.copy(savedPresets = newList) }
        persistPresets(newList)
        if (batchFiles.size > 1) _uiState.update { it.copy(applyBatchPreset = preset) }
    }

    fun applyPresetNow(preset: Preset) {
        val newState = applyPresetToState(preset, _uiState.value.editState)
        val newLut   = if (PresetParam.LUT in preset.includedParams) preset.lut else _uiState.value.currentLut
        history.push(newState)
        _uiState.update {
            it.copy(editState = newState, currentLut = newLut,
                    canUndo = history.canUndo, canRedo = history.canRedo,
                    historyEntries = history.entries, historyCursor = history.currentIndex)
        }
        scheduleRender(newState)
        if (batchFiles.size > 1) _uiState.update { it.copy(applyBatchPreset = preset) }
    }

    fun confirmApplyBatch(preset: Preset) = _uiState.update { it.copy(applyBatchPreset = null, pendingPreset = preset) }
    fun skipApplyBatch()                  = _uiState.update { it.copy(applyBatchPreset = null, pendingPreset = null) }

    fun renamePreset(preset: Preset, newName: String) {
        val updated = preset.copy(name = newName.trim())
        val newList = _uiState.value.savedPresets.map { if (it == preset) updated else it }
        _uiState.update {
            it.copy(savedPresets = newList, pendingPreset = if (it.pendingPreset == preset) updated else it.pendingPreset)
        }
        persistPresets(newList)
    }

    fun deletePreset(preset: Preset) {
        val newList = _uiState.value.savedPresets - preset
        _uiState.update {
            it.copy(savedPresets = newList, pendingPreset = if (it.pendingPreset == preset) null else it.pendingPreset)
        }
        persistPresets(newList)
    }

    private fun applyPresetToState(preset: Preset, base: EditState): EditState {
        val s = preset.state; val p = preset.includedParams; var r = base
        if (PresetParam.EXPOSURE    in p) r = r.copy(exposure    = s.exposure)
        if (PresetParam.LUMINOSITY  in p) r = r.copy(luminosity  = s.luminosity)
        if (PresetParam.CONTRAST    in p) r = r.copy(contrast    = s.contrast)
        if (PresetParam.HIGHLIGHTS  in p) r = r.copy(highlights  = s.highlights)
        if (PresetParam.SHADOWS     in p) r = r.copy(shadows     = s.shadows)
        if (PresetParam.SATURATION  in p) r = r.copy(saturation  = s.saturation)
        if (PresetParam.VIBRANCE    in p) r = r.copy(vibrance    = s.vibrance)
        if (PresetParam.TEMPERATURE in p) r = r.copy(temperature = s.temperature)
        if (PresetParam.TINT        in p) r = r.copy(tint        = s.tint)
        if (PresetParam.SHARPENING  in p) r = r.copy(sharpening  = s.sharpening)
        if (PresetParam.NOISE       in p) r = r.copy(noise       = s.noise)
        if (PresetParam.ROTATION    in p) r = r.copy(rotation = s.rotation, fineRotation = s.fineRotation, cropRect = null)
        if (PresetParam.FRAME       in p) r = r.copy(frameEnabled = s.frameEnabled, frameColor = s.frameColor, frameRatio = s.frameRatio, frameSizePct = s.frameSizePct)
        if (PresetParam.DATE_IMPRINT in p) r = r.copy(dateImprint = s.dateImprint)
        return r
    }

    // ── Skip image ────────────────────────────────────────────────────────

    fun skipImage() {
        if (batchIndex < batchFiles.size - 1) goToImage(batchIndex + 1) else resetToEmpty()
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun export() {
        val src     = _uiState.value.sourceBitmap ?: return
        val state   = _uiState.value.editState
        val lut     = _uiState.value.currentLut
        val quality = _uiState.value.jpegQuality
        val date    = _uiState.value.photoDate
        exportChannel.trySend(ExportJob(src, state, lut, quality, date))
        if (batchIndex < batchFiles.size - 1) {
            saveSnapshot()
            batchIndex++
            _uiState.update {
                it.copy(batchCount = batchFiles.size, batchIndex = batchIndex,
                        batchNavKey = it.batchNavKey + 1, swipeForward = true)
            }
            val pending = _uiState.value.pendingPreset
            val (savedState, savedLut) = batchSnapshots[batchIndex] ?: Pair(EditState(), null)
            val (initialState, initialLut) = if (pending != null && !batchSnapshots.containsKey(batchIndex)) {
                Pair(applyPresetToState(pending, EditState()), if (PresetParam.LUT in pending.includedParams) pending.lut else null)
            } else Pair(savedState, savedLut)
            loadSingleImage(batchFiles[batchIndex], initialState = initialState, initialLut = initialLut)
        } else {
            resetToEmpty()
        }
    }

    fun exportAll() {
        if (batchFiles.isEmpty()) return
        saveSnapshot()
        val files     = batchFiles.toList()
        val snapshots = batchSnapshots.toMap()
        val pending   = _uiState.value.pendingPreset
        val quality   = _uiState.value.jpegQuality
        scope.launch(Dispatchers.IO) {
            files.forEachIndexed { index, file ->
                val (state, lut) = when {
                    snapshots.containsKey(index) -> snapshots[index]!!
                    pending != null -> Pair(
                        applyPresetToState(pending, EditState()),
                        if (PresetParam.LUT in pending.includedParams) pending.lut else null,
                    )
                    else -> Pair(EditState(), null)
                }
                val rawImage = runCatching { ImageIO.read(file) }.getOrNull() ?: return@forEachIndexed
                val photoDate = readExifDate(file)
                exportChannel.trySend(ExportJob(DesktopBitmap.fromBufferedImage(rawImage), state, lut, quality, photoDate))
            }
        }
    }

    private fun resetToEmpty(snackbar: String? = null) {
        quickSourceBitmap   = null
        previewSourceBitmap = null
        batchFiles          = emptyList()
        batchIndex          = 0
        batchSnapshots.clear()
        history.reset(EditState())
        _uiState.update {
            it.copy(sourceBitmap = null, previewBitmap = null, originalGeomBitmap = null,
                    cropSourceBitmap = null, editState = EditState(), currentLut = null,
                    canUndo = false, canRedo = false, isProcessing = false, pendingPreset = null,
                    batchCount = 0, batchIndex = 0, histogramBitmap = null,
                    historyEntries = emptyList(), historyCursor = 0, snackbarMessage = snackbar)
        }
    }

    // ── Render scheduling ─────────────────────────────────────────────────

    fun onZoomChanged(zoom: Float) {
        currentZoom = zoom
        scheduleRender(_uiState.value.editState)
    }

    private fun scheduleRender(state: EditState, immediate: Boolean = false) {
        renderJob?.cancel()
        renderJob = scope.launch {
            val quick = quickSourceBitmap
            if (quick != null) {
                val lut  = _uiState.value.currentLut
                val date = _uiState.value.photoDate
                val (q, histo) = withContext(Dispatchers.Default) { pipeline.processAll(quick, state, lut, date) }
                _uiState.update { it.copy(previewBitmap = q, histogramBitmap = histo) }
            }
            if (!immediate) delay(250)
            val full = previewSourceBitmap ?: return@launch
            // Adaptive resolution: 1920px at zoom 1×, scales up with zoom, capped at original
            val targetMaxPx = (1920f * currentZoom).toInt()
            val src = downsample(full, targetMaxPx)
            val lut  = _uiState.value.currentLut
            val date = _uiState.value.photoDate
            _uiState.update { it.copy(isProcessing = true) }
            val preview = withContext(Dispatchers.Default) { pipeline.process(src, state, lut, date) }
            _uiState.update { it.copy(previewBitmap = preview, isProcessing = false) }
        }
        scheduleGeomRender(state)
    }

    private fun scheduleGeomRender(state: EditState) {
        geomRenderJob?.cancel()
        geomRenderJob = scope.launch {
            val src = previewSourceBitmap ?: quickSourceBitmap ?: return@launch
            val geomState = EditState(rotation = state.rotation, fineRotation = state.fineRotation,
                                      cropRect = state.cropRect, frameEnabled = state.frameEnabled,
                                      frameColor = state.frameColor, frameRatio = state.frameRatio,
                                      frameSizePct = state.frameSizePct)
            val geom = withContext(Dispatchers.Default) { pipeline.process(src, geomState, null) }
            _uiState.update { it.copy(originalGeomBitmap = geom) }
        }
    }

    // ── Preset persistence ─────────────────────────────────────────────────

    private val configDir: File
        get() = File(System.getProperty("user.home"), ".config/photonlab").also { it.mkdirs() }
    private val presetsFile: File get() = File(configDir, "presets.json")

    private fun persistPresets(presets: List<Preset>) {
        runCatching {
            val lutsDir = File(configDir, "luts").also { it.mkdirs() }
            val arr = JSONArray()
            for (p in presets) {
                val s = p.state
                val paramsArr = JSONArray().also { a -> p.includedParams.forEach { a.put(it.name) } }
                val obj = JSONObject().apply {
                    put("name", p.name); put("exposure", s.exposure.toDouble()); put("luminosity", s.luminosity.toDouble())
                    put("contrast", s.contrast.toDouble()); put("highlights", s.highlights.toDouble())
                    put("shadows", s.shadows.toDouble()); put("saturation", s.saturation.toDouble())
                    put("vibrance", s.vibrance.toDouble()); put("temperature", s.temperature.toDouble())
                    put("tint", s.tint.toDouble()); put("sharpening", s.sharpening.toDouble())
                    put("noise", s.noise.toDouble()); put("rotation", s.rotation)
                    put("fineRotation", s.fineRotation.toDouble()); put("frameEnabled", s.frameEnabled)
                    put("frameColor", s.frameColor); put("frameRatio", s.frameRatio.toDouble())
                    put("frameSizePct", s.frameSizePct.toDouble()); put("includedParams", paramsArr)
                    val di = s.dateImprint
                    put("diEnabled", di.enabled); put("diStyle", di.style.name); put("diColor", di.color.name)
                    put("diFont", di.font.name); put("diSizePct", di.sizePercent.toDouble()); put("diPosition", di.position.name)
                    put("diGlow", di.glowAmount); put("diBlur", di.blurAmount); put("diOpacity", di.opacity); put("diRepeat", di.blurRepeat)
                    val lut = p.lut
                    if (lut != null) {
                        val binName = sanitizeLutName(lut.name) + ".bin"
                        val binFile = File(lutsDir, binName)
                        if (!binFile.exists()) {
                            DataOutputStream(binFile.outputStream().buffered()).use { dos ->
                                dos.writeInt(lut.size); dos.writeInt(lut.type.ordinal)
                                for (f in lut.data) dos.writeFloat(f)
                            }
                        }
                        put("lutPath", binName); put("lutName", lut.name)
                    }
                }
                arr.put(obj)
            }
            presetsFile.writeText(arr.toString())
            val referenced = presets.mapNotNull { it.lut?.let { l -> sanitizeLutName(l.name) + ".bin" } }.toSet()
            lutsDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        }
    }

    private fun sanitizeLutName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun loadPresets(): List<Preset> = runCatching {
        if (!presetsFile.exists()) return@runCatching emptyList()
        val arr = JSONArray(presetsFile.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val state = EditState(
                    exposure = obj.getDouble("exposure").toFloat(), luminosity = obj.getDouble("luminosity").toFloat(),
                    contrast = obj.getDouble("contrast").toFloat(), highlights = obj.getDouble("highlights").toFloat(),
                    shadows = obj.getDouble("shadows").toFloat(), saturation = obj.getDouble("saturation").toFloat(),
                    vibrance = obj.optDouble("vibrance", 0.0).toFloat(), temperature = obj.optDouble("temperature", 0.0).toFloat(),
                    tint = obj.optDouble("tint", 0.0).toFloat(), sharpening = obj.optDouble("sharpening", 0.0).toFloat(),
                    noise = obj.optDouble("noise", 0.0).toFloat(), rotation = obj.optInt("rotation", 0),
                    fineRotation = obj.optDouble("fineRotation", 0.0).toFloat(),
                    frameEnabled = obj.optBoolean("frameEnabled", false), frameColor = obj.optInt("frameColor", 0xFFFFFFFF.toInt()),
                    frameRatio = obj.optDouble("frameRatio", 0.0).toFloat(), frameSizePct = obj.optDouble("frameSizePct", 3.0).toFloat(),
                    dateImprint = com.photonlab.domain.model.DateImprintSettings(
                        enabled     = obj.optBoolean("diEnabled", false),
                        style       = runCatching { com.photonlab.domain.model.DateImprintStyle.valueOf(obj.optString("diStyle", "CLASSIC")) }.getOrDefault(com.photonlab.domain.model.DateImprintStyle.CLASSIC),
                        color       = runCatching { com.photonlab.domain.model.DateImprintColor.valueOf(obj.optString("diColor", "AMBER")) }.getOrDefault(com.photonlab.domain.model.DateImprintColor.AMBER),
                        font        = runCatching { com.photonlab.domain.model.DateImprintFont.valueOf(obj.optString("diFont", "LED")) }.getOrDefault(com.photonlab.domain.model.DateImprintFont.LED),
                        sizePercent = obj.optDouble("diSizePct", 2.0).toFloat(),
                        position    = runCatching { com.photonlab.domain.model.DateImprintPosition.valueOf(obj.optString("diPosition", "BOTTOM_RIGHT")) }.getOrDefault(com.photonlab.domain.model.DateImprintPosition.BOTTOM_RIGHT),
                        glowAmount  = obj.optInt("diGlow", 100),
                        blurAmount  = obj.optInt("diBlur", 50),
                        opacity     = obj.optInt("diOpacity", 50),
                        blurRepeat  = obj.optInt("diRepeat", 3),
                    ),
                )
                val paramsArr = obj.optJSONArray("includedParams")
                val includedParams: Set<PresetParam> = if (paramsArr != null) {
                    buildSet { for (j in 0 until paramsArr.length()) runCatching { add(PresetParam.valueOf(paramsArr.getString(j))) } }
                        .ifEmpty { PresetParam.ALL }
                } else PresetParam.ALL
                val lut = run {
                    val lutPath = obj.optString("lutPath", ""); val lutName = obj.optString("lutName", "")
                    if (lutPath.isEmpty()) return@run null
                    val binFile = File(configDir, "luts/$lutPath")
                    if (!binFile.exists()) return@run null
                    runCatching {
                        DataInputStream(binFile.inputStream().buffered()).use { dis ->
                            val size = dis.readInt()
                            val type = if (dis.readInt() == com.photonlab.domain.model.LutType.HALD_CLUT.ordinal) com.photonlab.domain.model.LutType.HALD_CLUT else com.photonlab.domain.model.LutType.CUBE_3D
                            val data = FloatArray(size * size * size * 3) { dis.readFloat() }
                            LutFile(binFile, lutName, type, data, size)
                        }
                    }.getOrNull()
                }
                add(Preset(obj.getString("name"), state, lut, includedParams))
            }
        }
    }.getOrDefault(emptyList())
}
