package com.thermling

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.thermling.app.ThermlingState
import com.thermling.platform.DesktopPlatform
import com.thermling.ui.ThermlingApp
import com.thermling.ui.ThermlingTheme
import com.thermling.ui.documentTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Dimension

fun main() = application {
    val platform = remember { DesktopPlatform() }
    val state = remember { ThermlingState(platform) }
    val scope = rememberCoroutineScope()
    val windowState = rememberWindowState(width = 1440.dp, height = 920.dp)

    Window(
        onCloseRequest = {
            runBlocking { state.disconnectPrinter() }
            exitApplication()
        },
        title = "Thermling — ${documentTitle(state)}",
        state = windowState,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else if (event.isCtrlPressed) {
                when {
                    event.key == Key.S && event.isShiftPressed -> {
                        scope.launch { state.saveDocumentAs() }
                        true
                    }
                    event.key == Key.S -> {
                        scope.launch { state.saveDocument() }
                        true
                    }
                    event.key == Key.O -> {
                        scope.launch { state.openDocument() }
                        true
                    }
                    event.key == Key.N -> {
                        state.newDocument()
                        true
                    }
                    event.key == Key.P -> {
                        scope.launch { state.printDocument() }
                        true
                    }
                    event.key == Key.Z -> {
                        state.undo()
                        true
                    }
                    event.key == Key.Y -> {
                        state.redo()
                        true
                    }
                    else -> false
                }
            } else if (event.key == Key.Delete) {
                if (state.selectionId != null) {
                    state.deleteSelected()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1120, 760)
        }
        ThermlingTheme {
            ThermlingApp(state)
        }
    }
}
