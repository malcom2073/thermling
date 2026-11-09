package com.thermling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalThermlingPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.surface, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = palette.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        content()
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalThermlingPalette.current.muted,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@Composable
fun NumberField(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    suffix: String = "mm",
    decimals: Int = 1,
) {
    var text by remember { mutableStateOf(formatDecimal(value, decimals)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!focused) text = formatDecimal(value, decimals)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) FieldLabel(label)
        OutlinedTextField(
            value = text,
            onValueChange = { candidate ->
                text = candidate
                candidate.replace(',', '.').toDoubleOrNull()?.let(onValueChange)
            },
            singleLine = true,
            suffix = { if (suffix.isNotEmpty()) Text(suffix, fontSize = 11.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focused && !focusState.isFocused) {
                        text = formatDecimal(value, decimals)
                    }
                    focused = focusState.isFocused
                },
        )
    }
}

@Composable
fun TextFieldCompact(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    value: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldLabel(label)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    labelOf(value),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    fontSize = 12.sp,
                )
                Text("▾", fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(labelOf(option), fontSize = 12.sp) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    val palette = LocalThermlingPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = palette.muted, fontSize = 11.sp, modifier = Modifier.width(86.dp))
        Text(value, color = valueColor ?: palette.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun InlineDivider() {
    HorizontalDivider(color = LocalThermlingPalette.current.border, thickness = 1.dp)
}

@Composable
fun SmallSpacer(height: Int = 4) {
    Spacer(Modifier.height(height.dp))
}

fun formatDecimal(value: Double, decimals: Int = 1): String {
    if (decimals <= 0) return value.toInt().toString()
    val factor = when (decimals) {
        1 -> 10.0
        2 -> 100.0
        else -> 1000.0
    }
    val rounded = kotlin.math.round(value * factor) / factor
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
}

@Composable
fun Stepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { onChange((value - 1).coerceIn(range)) },
                enabled = value > range.first,
            ) { Text("−", fontSize = 13.sp) }
            Text(
                value.toString(),
                color = LocalThermlingPalette.current.text,
                fontSize = 12.sp,
                modifier = Modifier.width(28.dp),
            )
            OutlinedButton(
                onClick = { onChange((value + 1).coerceIn(range)) },
                enabled = value < range.last,
            ) { Text("+", fontSize = 13.sp) }
        }
    }
}

@Composable
fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalThermlingPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
