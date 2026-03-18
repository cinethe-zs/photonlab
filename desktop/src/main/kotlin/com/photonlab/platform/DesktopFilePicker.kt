package com.photonlab.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser

/**
 * Desktop file picker utilities. Uses java.awt.FileDialog for native GTK dialogs on Linux.
 * All functions are suspending and switch to the Swing/AWT event dispatch thread automatically.
 */
object DesktopFilePicker {

    private val imageFilter = FilenameFilter { _, name ->
        val n = name.lowercase()
        n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif") ||
            n.endsWith(".tiff") || n.endsWith(".tif")
    }

    private val lutFilter = FilenameFilter { _, name ->
        val n = name.lowercase()
        n.endsWith(".cube") || n.endsWith(".png") || n.endsWith(".tiff") || n.endsWith(".tif")
    }

    /** Show a multi-select file dialog for images. Returns chosen files, or empty if cancelled. */
    suspend fun pickImageFiles(parentFrame: Frame? = null): List<File> = withContext(Dispatchers.Main) {
        val dialog = FileDialog(parentFrame, "Open Images", FileDialog.LOAD).apply {
            isMultipleMode = true
            filenameFilter = imageFilter
        }
        dialog.isVisible = true
        dialog.files?.toList() ?: emptyList()
    }

    /** Show a single-file dialog for a LUT file (.cube, .png). Returns null if cancelled. */
    suspend fun pickLutFile(parentFrame: Frame? = null): File? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(parentFrame, "Open LUT File", FileDialog.LOAD).apply {
            isMultipleMode = false
            filenameFilter = lutFilter
        }
        dialog.isVisible = true
        dialog.files?.firstOrNull()
    }

    /** Show a directory picker for the LUT folder. Returns null if cancelled. */
    suspend fun pickDirectory(parentFrame: Frame? = null): File? = withContext(Dispatchers.Main) {
        // AWT FileDialog doesn't support directory selection on Linux; use JFileChooser instead
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select LUT Folder"
        }
        val result = chooser.showOpenDialog(parentFrame)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
