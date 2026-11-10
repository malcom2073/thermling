package com.thermling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermling.app.ThermlingState
import com.thermling.model.Halftone
import com.thermling.model.HorizontalAlign
import com.thermling.model.ImageElement
import com.thermling.model.ImageFit
import com.thermling.model.LabelDocument
import com.thermling.model.LabelElement
import com.thermling.model.Orientation
import com.thermling.model.TextElement
import com.thermling.model.VerticalAlign
import com.thermling.protocol.Style
import com.thermling.protocol.formatNumber
import kotlinx.coroutines.launch

@Composable
fun InspectorPanel(state: ThermlingState, modifier: Modifier = Modifier) {
    val palette = LocalThermlingPalette.current
    val document = state.document
    val selected = state.selectionId?.let(document::find)
    Column(
        modifier = modifier
            .background(palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DocumentSection(state)
        if (selected != null) {
            ElementSection(state, selected)
        } else {
            SectionCard("Element") {
                Text(
                    "Select an element on the canvas, or add text or an image.",
                    color = palette.muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DocumentSection(state: ThermlingState) {
    val palette = LocalThermlingPalette.current
    val document = state.document
    val scope = rememberCoroutineScope()
    SectionCard("Document") {
        TextFieldCompact(
            value = document.name,
            onValueChange = { value ->
                state.edit("Rename document") { it.copy(name = value) }
            },
            label = "Name",
        )
        DropdownField(
            label = "Orientation",
            value = document.orientation,
            options = Orientation.entries,
            labelOf = { it.label },
            onSelect = state::changeOrientation,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = document.widthMm,
                onValueChange = { value ->
                    state.edit("Change label size") { it.copy(widthMm = value.coerceIn(1.0, 200.0)) }
                },
                label = "Width",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = document.lengthMm,
                onValueChange = { value ->
                    state.edit("Change label size") { it.copy(lengthMm = value.coerceIn(1.0, 500.0)) }
                },
                label = "Length",
                modifier = Modifier.weight(1f),
            )
        }
        NumberField(
            value = document.gapMm,
            onValueChange = { value ->
                state.edit("Change label gap") { it.copy(gapMm = value.coerceIn(0.0, 50.0)) }
            },
            label = "Gap",
        )
        DropdownField(
            label = "Paper type",
            value = document.style,
            options = listOf(Style.GAPPED, Style.CONTINUOUS, Style.BLACKMARK),
            labelOf = { it.label },
            onSelect = { style ->
                state.edit("Change paper type") { it.copy(style = style) }
            },
        )
        val (pageWidth, pageHeight) = state.pageSizeMm
        KeyValueRow("Printable", "${formatNumber(pageWidth)} × ${formatNumber(pageHeight)} mm")
        if (document.widthMm > state.settings.headWidthMm) {
            Text(
                "The label is wider than the print head; the right edge will be clipped.",
                color = palette.warning,
                fontSize = 10.sp,
            )
        }

        if (state.printerLabel != null) {
            InlineDivider()
            Text(
                "Printer roll: ${state.printerLabel!!.describe()}",
                color = palette.muted,
                fontSize = 10.sp,
            )
            OutlinedButton(
                onClick = state::usePrinterRoll,
                enabled = state.printerLabel?.hasTag == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use printer roll", fontSize = 12.sp)
            }
        }
    }

    SectionCard("Output") {
        DropdownField(
            label = "Halftone",
            value = document.halftone,
            options = Halftone.entries,
            labelOf = { it.label },
            onSelect = { mode ->
                state.edit("Change output settings") { it.copy(halftone = mode) }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = document.thermalCorrection,
                onCheckedChange = { checked ->
                    state.edit("Change output settings") { it.copy(thermalCorrection = checked) }
                },
            )
            Text(
                "Lift shadows for the thermal head",
                color = palette.text,
                fontSize = 11.sp,
            )
        }
        val settings = state.settings
        NumberField(
            value = settings.dpi.toDouble(),
            onValueChange = { value ->
                state.updateSettings {
                    it.copy(dpi = value.toInt().coerceIn(50, 1200), autoDpi = false)
                }
            },
            label = "DPI",
            suffix = "",
            decimals = 0,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.autoDpi,
                onCheckedChange = { checked ->
                    state.updateSettings { it.copy(autoDpi = checked) }
                },
            )
            Text("Use printer-reported DPI", color = palette.text, fontSize = 11.sp)
        }
        NumberField(
            value = settings.headWidthMm,
            onValueChange = { value ->
                state.updateSettings { it.copy(headWidthMm = value.coerceIn(1.0, 200.0)) }
            },
            label = "Print head width",
        )
        InlineDivider()
        Text("Calibration offset", color = palette.text, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = settings.offsetXMm,
                onValueChange = { value ->
                    state.updateSettings { it.copy(offsetXMm = value.coerceIn(-10.0, 10.0)) }
                },
                label = "X",
                suffix = "mm",
                decimals = 2,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = settings.offsetYMm,
                onValueChange = { value ->
                    state.updateSettings { it.copy(offsetYMm = value.coerceIn(-10.0, 10.0)) }
                },
                label = "Y",
                suffix = "mm",
                decimals = 2,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Positive X moves right across the head; positive Y moves down the feed. " +
                "The head is only 12 mm wide, so a right shift trades away the same width on the right.",
            color = palette.muted,
            fontSize = 10.sp,
        )
        InlineDivider()
        OutlinedButton(
            onClick = { scope.launch { state.printCalibrationPattern() } },
            enabled = state.isConnected && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("print-calibration"),
        ) {
            Text("Print calibration pattern", fontSize = 12.sp)
        }
        Text(
            if (state.isConnected) {
                "Prints edge marks and a 1.5 mm-inset frame with a 1 mm ruler, so you can " +
                    "measure where the printable band sits and verify the offset."
            } else {
                "Connect to a printer to print a calibration pattern."
            },
            color = palette.muted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ElementSection(state: ThermlingState, element: LabelElement) {
    val palette = LocalThermlingPalette.current
    val id = element.id
    val title = if (element is TextElement) "Text element" else "Image element"

    SectionCard(title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = element.xMm,
                onValueChange = { value ->
                    updateElement(state, id, "Move element") { current ->
                        current.withX(value.coerceIn(0.0, 200.0))
                    }
                },
                label = "X",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = element.yMm,
                onValueChange = { value ->
                    updateElement(state, id, "Move element") { current ->
                        current.withY(value.coerceIn(0.0, 500.0))
                    }
                },
                label = "Y",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = element.widthMm,
                onValueChange = { value ->
                    updateElement(state, id, "Resize element") { current ->
                        current.withWidth(value.coerceIn(0.5, 200.0))
                    }
                },
                label = "Width",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = element.heightMm,
                onValueChange = { value ->
                    updateElement(state, id, "Resize element") { current ->
                        current.withHeight(value.coerceIn(0.5, 500.0))
                    }
                },
                label = "Height",
                modifier = Modifier.weight(1f),
            )
        }

        when (element) {
            is TextElement -> TextElementFields(state, element)
            is ImageElement -> ImageElementFields(state, element)
        }

        InlineDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = state::duplicateSelected,
                modifier = Modifier.weight(1f),
            ) { Text("Duplicate", fontSize = 12.sp) }
            OutlinedButton(
                onClick = state::deleteSelected,
                modifier = Modifier.weight(1f),
            ) { Text("Delete", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun TextElementFields(state: ThermlingState, element: TextElement) {
    val palette = LocalThermlingPalette.current
    val id = element.id
    TextFieldCompact(
        value = element.text,
        onValueChange = { value ->
            updateElement(state, id, "Edit text") { current ->
                (current as TextElement).copy(text = value)
            }
        },
        label = "Text",
        singleLine = false,
        minLines = 3,
    )
    TextFieldCompact(
        value = element.fontFamily,
        onValueChange = { value ->
            updateElement(state, id, "Change font") { current ->
                (current as TextElement).copy(fontFamily = value)
            }
        },
        label = "Font family",
    )
    NumberField(
        value = element.fontSizePt,
        onValueChange = { value ->
            updateElement(state, id, "Change font size") { current ->
                (current as TextElement).copy(fontSizePt = value.coerceIn(3.0, 200.0))
            }
        },
        label = "Font size",
        suffix = "pt",
        decimals = 1,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CheckboxLabel("Bold", element.bold) { checked ->
            updateElement(state, id, "Change text style") { current ->
                (current as TextElement).copy(bold = checked)
            }
        }
        CheckboxLabel("Italic", element.italic) { checked ->
            updateElement(state, id, "Change text style") { current ->
                (current as TextElement).copy(italic = checked)
            }
        }
        CheckboxLabel("Underline", element.underline) { checked ->
            updateElement(state, id, "Change text style") { current ->
                (current as TextElement).copy(underline = checked)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            label = "Align",
            value = element.align,
            options = HorizontalAlign.entries,
            labelOf = { it.label },
            onSelect = { align ->
                updateElement(state, id, "Change alignment") { current ->
                    (current as TextElement).copy(align = align)
                }
            },
            modifier = Modifier.weight(1f),
        )
        DropdownField(
            label = "Vertical",
            value = element.valign,
            options = VerticalAlign.entries,
            labelOf = { it.label },
            onSelect = { valign ->
                updateElement(state, id, "Change alignment") { current ->
                    (current as TextElement).copy(valign = valign)
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
    NumberField(
        value = element.lineSpacing,
        onValueChange = { value ->
            updateElement(state, id, "Change line spacing") { current ->
                (current as TextElement).copy(lineSpacing = value.coerceIn(0.5, 3.0))
            }
        },
        label = "Line spacing",
        suffix = "×",
        decimals = 2,
    )
    Text(
        "The editor preview approximates the printer's font rasteriser; use the bitmap preview for the final result.",
        color = palette.muted,
        fontSize = 10.sp,
    )
}

@Composable
private fun ImageElementFields(state: ThermlingState, element: ImageElement) {
    val palette = LocalThermlingPalette.current
    val scope = rememberCoroutineScope()
    val id = element.id
    Text(
        element.sourceName.ifBlank { "Embedded image" },
        color = palette.text,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    DropdownField(
        label = "Fit",
        value = element.fit,
        options = ImageFit.entries,
        labelOf = { it.label },
        onSelect = { fit ->
            updateElement(state, id, "Change image fit") { current ->
                (current as ImageElement).copy(fit = fit)
            }
        },
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                try {
                    val image = state.platform.chooseImage()
                    if (image != null) state.replaceImage(id, image)
                } catch (error: Exception) {
                    state.statusMessage = "Could not load image: ${error.message}"
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Replace image…", fontSize = 12.sp) }
}

@Composable
private fun CheckboxLabel(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = LocalThermlingPalette.current.text, fontSize = 10.sp)
    }
}

private fun updateElement(
    state: ThermlingState,
    id: String,
    label: String,
    transform: (LabelElement) -> LabelElement,
) {
    state.edit(label) { document ->
        val current = document.find(id) ?: return@edit document
        document.replace(transform(current))
    }
}

private fun LabelElement.withX(value: Double): LabelElement = when (this) {
    is TextElement -> copy(xMm = value)
    is ImageElement -> copy(xMm = value)
}

private fun LabelElement.withY(value: Double): LabelElement = when (this) {
    is TextElement -> copy(yMm = value)
    is ImageElement -> copy(yMm = value)
}

private fun LabelElement.withWidth(value: Double): LabelElement = when (this) {
    is TextElement -> copy(widthMm = value)
    is ImageElement -> copy(widthMm = value)
}

private fun LabelElement.withHeight(value: Double): LabelElement = when (this) {
    is TextElement -> copy(heightMm = value)
    is ImageElement -> copy(heightMm = value)
}
