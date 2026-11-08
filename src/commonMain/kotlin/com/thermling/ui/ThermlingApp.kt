package com.thermling.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermling.app.ConnectionState
import com.thermling.app.ThermlingState
import com.thermling.model.ImageElement
import com.thermling.model.LabelDocument
import com.thermling.model.LABEL_PRESETS
import com.thermling.model.TextElement
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun ThermlingApp(state: ThermlingState) {
    val scope = rememberCoroutineScope()
    var showPrinter by remember { mutableStateOf(false) }
    val palette = LocalThermlingPalette.current

    LaunchedEffect(state.document, state.effectiveDpi, state.settings.headWidthMm) {
        state.renderPreview()
    }

    Surface(color = palette.window, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                state = state,
                onNew = { state.newDocument() },
                onOpen = { scope.launch { state.openDocument() } },
                onSave = { scope.launch { state.saveDocument() } },
                onSaveAs = { scope.launch { state.saveDocumentAs() } },
                onAddText = { state.addText() },
                onAddImage = {
                    runCatching { state.platform.chooseImage() }.onSuccess { image ->
                        if (image != null) state.addImage(image)
                    }.onFailure {
                        state.statusMessage = "Could not load image: ${it.message}"
                    }
                },
                onPrinter = { showPrinter = true },
                onPrint = { scope.launch { state.printDocument() } },
            )

            HorizontalDivider(color = palette.border)

            Row(Modifier.weight(1f).fillMaxWidth()) {
                DocumentSidebar(
                    state = state,
                    onAddText = { state.addText() },
                    onAddImage = {
                        runCatching { state.platform.chooseImage() }.onSuccess { image ->
                            if (image != null) state.addImage(image)
                        }.onFailure {
                            state.statusMessage = "Could not load image: ${it.message}"
                        }
                    },
                    modifier = Modifier.width(252.dp).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LabelCanvas(state, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = palette.border)
                    PreviewBar(
                        state = state,
                        onPrinter = { showPrinter = true },
                        onPrint = { scope.launch { state.printDocument() } },
                        modifier = Modifier.fillMaxWidth().height(214.dp),
                    )
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(palette.border),
                )
                InspectorPanel(state, Modifier.width(334.dp).fillMaxHeight())
            }

            HorizontalDivider(color = palette.border)
            StatusBar(state)
        }
    }

    if (showPrinter) {
        PrinterDialog(
            state = state,
            onClose = { showPrinter = false },
            onPrint = {
                scope.launch {
                    state.printDocument()
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    state: ThermlingState,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onPrinter: () -> Unit,
    onPrint: () -> Unit,
) {
    val palette = LocalThermlingPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Text("Thermling", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Desktop MVP",
                color = palette.muted,
                fontSize = 10.sp,
            )
        }
        TextButton(onClick = onNew) { Text("New") }
        TextButton(onClick = onOpen) { Text("Open") }
        TextButton(onClick = onSave) { Text("Save") }
        TextButton(onClick = onSaveAs) { Text("Save as") }
        Box(
            Modifier
                .width(1.dp)
                .height(22.dp)
                .background(palette.border),
        )
        TextButton(onClick = { state.undo() }, enabled = state.canUndo) { Text("Undo") }
        TextButton(onClick = { state.redo() }, enabled = state.canRedo) { Text("Redo") }
        Box(
            Modifier
                .width(1.dp)
                .height(22.dp)
                .background(palette.border),
        )
        TextButton(onClick = onAddText, modifier = Modifier.testTag("add-text-top")) { Text("+ Text") }
        TextButton(onClick = onAddImage, modifier = Modifier.testTag("add-image-top")) {
            Text("+ Image")
        }
        Text(
            documentTitle(state),
            color = palette.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TextButton(onClick = onPrinter, modifier = Modifier.testTag("printer-toolbar")) {
            Text(if (state.isConnected) "Printer ●" else "Printer…")
        }
        Button(onClick = onPrint, enabled = state.canPrint) {
            Text(if (state.busy) "Working…" else "Print")
        }
    }
}

@Composable
private fun DocumentSidebar(
    state: ThermlingState,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalThermlingPalette.current
    val document = state.document
    val currentPreset = LABEL_PRESETS.firstOrNull(document::matchesPreset)
    var presetsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(palette.surfaceAlt)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard("Label") {
            FieldLabel("Preset")
            Box {
                TextButton(onClick = { presetsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        currentPreset?.name ?: "Custom",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                    )
                    Text("▾", fontSize = 12.sp)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = presetsOpen,
                    onDismissRequest = { presetsOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Custom", fontSize = 12.sp) },
                        onClick = { presetsOpen = false },
                    )
                    LABEL_PRESETS.forEach { preset ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(preset.name, fontSize = 12.sp) },
                            onClick = {
                                presetsOpen = false
                                state.applyPreset(preset)
                            },
                        )
                    }
                }
            }
            val (pageWidth, pageHeight) = state.pageSizeMm
            KeyValueRow("Page", "${formatDecimal(pageWidth)} × ${formatDecimal(pageHeight)} mm")
            KeyValueRow("Paper", document.style.label)
            if (document.widthMm > state.settings.headWidthMm) {
                Text(
                    "Wider than the ${formatDecimal(state.settings.headWidthMm)} mm head; the right edge will be clipped.",
                    color = palette.warning,
                    fontSize = 10.sp,
                )
            }
        }

        SectionCard("Elements") {
            if (document.elements.isEmpty()) {
                Text(
                    "Add text or an image to start composing.",
                    color = palette.muted,
                    fontSize = 11.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    document.elements.asReversed().forEach { element ->
                        SelectableRow(
                            selected = state.selectionId == element.id,
                            onClick = { state.select(element.id) },
                        ) {
                            Text(
                                if (element is TextElement) "T" else "IMG",
                                color = palette.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.width(30.dp),
                            )
                            Text(
                                elementLabel(element),
                                color = palette.text,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onAddText,
                    modifier = Modifier.weight(1f),
                ) { Text("+ Text", fontSize = 12.sp) }
                TextButton(
                    onClick = onAddImage,
                    modifier = Modifier.weight(1f),
                ) { Text("+ Image", fontSize = 12.sp) }
            }
        }

        SectionCard("Output") {
            KeyValueRow("DPI", state.effectiveDpi.toString())
            KeyValueRow("Head", "${formatDecimal(state.settings.headWidthMm)} mm")
            KeyValueRow("Halftone", document.halftone.label)
            if (state.preview != null) {
                KeyValueRow("Bitmap", state.preview!!.sizeText)
            }
        }
    }
}

@Composable
private fun PreviewBar(
    state: ThermlingState,
    onPrinter: () -> Unit,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalThermlingPalette.current
    val result = state.preview
    Row(
        modifier = modifier
            .background(palette.surfaceAlt)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(palette.previewBg)
                .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (result != null) {
                val ratio = result.preview.width.toFloat() / max(1, result.preview.height).toFloat()
                Image(
                    bitmap = result.preview,
                    contentDescription = "Print preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(ratio)
                        .background(LabelPaper)
                        .border(1.dp, palette.pageEdge),
                )
            } else {
                Text(
                    state.renderError ?: "Rendering…",
                    color = if (state.renderError == null) palette.muted else palette.danger,
                    fontSize = 12.sp,
                )
            }
        }

        Column(
            modifier = Modifier.width(380.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Print preview", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    result?.sizeText ?: "No bitmap",
                    color = palette.muted,
                    fontSize = 10.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(
                    label = "Density",
                    value = state.settings.density,
                    range = 1..15,
                    onChange = { value -> state.updateSettings { it.copy(density = value) } },
                    modifier = Modifier.weight(1f),
                )
                Stepper(
                    label = "Copies",
                    value = state.settings.copies,
                    range = 1..20,
                    onChange = { value -> state.updateSettings { it.copy(copies = value) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KeyValueRow("Dots", result?.let { "${it.widthDots} × ${it.heightDots}" } ?: "—")
                KeyValueRow("Mode", result?.halftone ?: "—")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPrint, enabled = state.canPrint) {
                    Text(if (state.busy) "Working…" else "Print label")
                }
                TextButton(onClick = onPrinter, modifier = Modifier.testTag("printer-preview")) {
                    Text("Connection…", fontSize = 12.sp)
                }
                if (state.busy && state.connection == ConnectionState.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                state.statusMessage,
                color = if (state.connection == ConnectionState.ERROR) palette.danger else palette.muted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusBar(state: ThermlingState) {
    val palette = LocalThermlingPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.statusMessage,
            color = palette.muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val (pageWidth, pageHeight) = state.pageSizeMm
        Text(
            "${formatDecimal(pageWidth)} × ${formatDecimal(pageHeight)} mm · ${state.effectiveDpi} dpi",
            color = palette.muted,
            fontSize = 10.sp,
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (state.connection) {
                        ConnectionState.CONNECTED -> palette.success
                        ConnectionState.CONNECTING -> palette.warning
                        ConnectionState.ERROR -> palette.danger
                        ConnectionState.DISCONNECTED -> palette.muted
                    },
                ),
        )
        Text(
            when (state.connection) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting"
                ConnectionState.ERROR -> "Connection error"
                ConnectionState.DISCONNECTED -> "No printer"
            },
            color = palette.muted,
            fontSize = 10.sp,
        )
    }
}

private fun elementLabel(element: com.thermling.model.LabelElement): String = when (element) {
    is TextElement -> element.text.lineSequence().firstOrNull()?.ifBlank { "Text" } ?: "Text"
    is ImageElement -> element.sourceName.ifBlank { "Image" }
}

fun documentTitle(state: ThermlingState): String {
    val name = state.documentPath?.substringAfterLast('/') ?: state.document.name
    return "$name${if (state.dirty) " *" else ""}"
}
