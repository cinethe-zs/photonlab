package com.photonlab

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.photonlab.rendering.GlslToneRenderer
import com.photonlab.ui.editor.EditorViewModel
import com.photonlab.ui.theme.PhotonLabTheme
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() = application {
    val viewModel = remember { EditorViewModel() }

    Window(
        onCloseRequest = {
            if (viewModel.uiState.value.pendingExportCount > 0) {
                // Prompt user — dialog shown inside EditorScreen via closeWindow state
                viewModel.requestExit()
            } else {
                GlslToneRenderer.destroy()
                exitApplication()
            }
        },
        title = "PhotonLab",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        // Register drag-and-drop on the AWT ComposeWindow
        DisposableEffect(Unit) {
            val dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    runCatching {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val transferable = event.transferable
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            val imageFiles = files.filter { f ->
                                f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "tiff", "tif")
                            }
                            if (imageFiles.isNotEmpty()) {
                                viewModel.loadImages(imageFiles)
                            }
                        }
                        event.dropComplete(true)
                    }.onFailure {
                        event.dropComplete(false)
                    }
                }
            })
            onDispose { dropTarget.isActive = false }
        }

        PhotonLabTheme {
            com.photonlab.ui.editor.EditorScreen(
                viewModel = viewModel,
                onCloseWindow = {
                    GlslToneRenderer.destroy()
                    exitApplication()
                },
            )
        }
    }
}
