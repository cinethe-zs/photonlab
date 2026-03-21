package com.photonlab

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.photonlab.ui.editor.EditorViewModel
import com.photonlab.ui.theme.PhotonLabTheme
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() {
    // Must be set before AWT initialises so the X11 WM_CLASS matches
    // StartupWMClass=photonlab in the .desktop file → GNOME taskbar shows correct icon.
    System.setProperty("sun.awt.app.class", "photonlab")
    @Suppress("NAME_SHADOWING")
    return application {
    val viewModel = remember { EditorViewModel() }

    Window(
        onCloseRequest = {
            if (viewModel.uiState.value.pendingExportCount > 0) {
                // Prompt user — dialog shown inside EditorScreen via closeWindow state
                viewModel.requestExit()
            } else {
                viewModel.onCleared()
                exitApplication()
            }
        },
        title = "PhotonLab",
        icon = painterResource("photonlab_icon.png"),
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

        SideEffect {
            runCatching {
                // Try installed path first (jpackage puts the icon next to the binary),
                // then fall back to the classpath resource for dev runs.
                val img = sequenceOf(
                    {
                        ProcessHandle.current().info().command().orElse(null)
                            ?.let { java.io.File(it).parentFile?.parentFile?.resolve("lib/photonlab.png") }
                            ?.takeIf { it.exists() }
                            ?.let { javax.imageio.ImageIO.read(it) }
                    },
                    {
                        Thread.currentThread().contextClassLoader
                            ?.getResourceAsStream("photonlab_icon.png")
                            ?.let { javax.imageio.ImageIO.read(it) }
                    },
                ).mapNotNull { it() }.firstOrNull() ?: return@runCatching

                window.iconImage = img
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    val taskbar = java.awt.Taskbar.getTaskbar()
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                        taskbar.iconImage = img
                    }
                }
            }
        }

        PhotonLabTheme {
            com.photonlab.ui.editor.EditorScreen(
                viewModel = viewModel,
                onCloseWindow = {
                    viewModel.onCleared()
                    exitApplication()
                },
            )
        }
    }
}
}
