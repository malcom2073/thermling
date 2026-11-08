package com.thermling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermling.app.ThermlingState
import com.thermling.model.HorizontalAlign
import com.thermling.model.ImageElement
import com.thermling.model.ImageFit
import com.thermling.model.LabelDocument
import com.thermling.model.LabelElement
import com.thermling.model.TextElement
import com.thermling.model.VerticalAlign
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun LabelCanvas(state: ThermlingState, modifier: Modifier = Modifier) {
    val palette = LocalThermlingPalette.current
    val document = state.document
    val (pageWidthMm, pageHeightMm) = state.pageSizeMm

    Box(modifier.background(palette.canvas), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(26.dp),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val maxHeightPx = with(density) { maxHeight.toPx() }
            val scale = min(
                maxWidthPx / pageWidthMm.toFloat(),
                maxHeightPx / pageHeightMm.toFloat(),
            ).coerceAtLeast(1f)
            val pageWidth = with(density) { (pageWidthMm * scale).toFloat().toDp() }
            val pageHeight = with(density) { (pageHeightMm * scale).toFloat().toDp() }

            Box(
                modifier = Modifier
                    .size(pageWidth, pageHeight)
                    .shadow(8.dp, RoundedCornerShape(3.dp))
                    .background(LabelPaper, RoundedCornerShape(3.dp))
                    .border(1.dp, palette.pageEdge, RoundedCornerShape(3.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val minor = Color(0xFF0F172A).copy(alpha = 0.055f)
                    val major = Color(0xFF0F172A).copy(alpha = 0.12f)
                    val stepMm = if (scale >= 18f) 1 else 5
                    var mm = stepMm
                    while (mm < pageWidthMm) {
                        val x = (mm * scale).toFloat()
                        drawLine(
                            color = if (mm % 5 == 0) major else minor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = if (mm % 5 == 0) 1f else 0.6f,
                        )
                        mm += stepMm
                    }
                    mm = stepMm
                    while (mm < pageHeightMm) {
                        val y = (mm * scale).toFloat()
                        drawLine(
                            color = if (mm % 5 == 0) major else minor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (mm % 5 == 0) 1f else 0.6f,
                        )
                        mm += stepMm
                    }
                    drawRect(
                        color = Color(0xFF0F172A).copy(alpha = 0.08f),
                        style = Stroke(width = 1f),
                    )
                }

                if (document.elements.isEmpty()) {
                    Text(
                        "Add text or an image",
                        color = Color(0xFF9AA3B2),
                        fontSize = with(density) { (2.6 * scale).toFloat().toSp() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                document.elements.forEach { element ->
                    ElementView(
                        state = state,
                        element = element,
                        scale = scale,
                        pageWidthMm = pageWidthMm,
                        pageHeightMm = pageHeightMm,
                    )
                }
            }
        }
    }
}

@Composable
private fun ElementView(
    state: ThermlingState,
    element: LabelElement,
    scale: Float,
    pageWidthMm: Double,
    pageHeightMm: Double,
) {
    val palette = LocalThermlingPalette.current
    val density = LocalDensity.current
    val selected = state.selectionId == element.id
    val width = with(density) { (element.widthMm * scale).toFloat().toDp() }
    val height = with(density) { (element.heightMm * scale).toFloat().toDp() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (element.xMm * scale).roundToInt(),
                    (element.yMm * scale).roundToInt(),
                )
            }
            .size(width, height)
            .border(
                if (selected) 2.dp else 0.dp,
                if (selected) palette.accent else Color.Transparent,
                RoundedCornerShape(1.dp),
            )
            .pointerInput(element.id) {
                detectTapGestures { state.select(element.id) }
            }
            .pointerInput(element.id, scale, pageWidthMm, pageHeightMm) {
                var startX = 0.0
                var startY = 0.0
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = {
                        state.select(element.id)
                        val current = state.document.find(element.id)
                        if (current != null) {
                            startX = current.xMm
                            startY = current.yMm
                            totalX = 0f
                            totalY = 0f
                            state.beginLiveEdit("Move element")
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        totalX += amount.x
                        totalY += amount.y
                        state.liveEdit { document ->
                            val current = document.find(element.id) ?: return@liveEdit document
                            val x = (startX + totalX / scale)
                                .coerceIn(0.0, max(0.0, pageWidthMm - current.widthMm))
                            val y = (startY + totalY / scale)
                                .coerceIn(0.0, max(0.0, pageHeightMm - current.heightMm))
                            when (current) {
                                is TextElement -> current.copy(xMm = x, yMm = y)
                                is ImageElement -> current.copy(xMm = x, yMm = y)
                            }.let(document::replace)
                        }
                    },
                    onDragEnd = { state.commitLiveEdit() },
                    onDragCancel = { state.commitLiveEdit() },
                )
            },
    ) {
        when (element) {
            is TextElement -> TextElementView(element, scale)
            is ImageElement -> ImageElementView(state, element)
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .border(1.dp, palette.surface, CircleShape)
                    .pointerInput(element.id, scale, pageWidthMm, pageHeightMm) {
                        var startWidth = 0.0
                        var startHeight = 0.0
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                val current = state.document.find(element.id)
                                if (current != null) {
                                    startWidth = current.widthMm
                                    startHeight = current.heightMm
                                    totalX = 0f
                                    totalY = 0f
                                    state.beginLiveEdit("Resize element")
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                totalX += amount.x
                                totalY += amount.y
                                state.liveEdit { document ->
                                    val current = document.find(element.id) ?: return@liveEdit document
                                    val widthMm = (startWidth + totalX / scale)
                                        .coerceIn(1.0, max(1.0, pageWidthMm - current.xMm))
                                    val heightMm = (startHeight + totalY / scale)
                                        .coerceIn(1.0, max(1.0, pageHeightMm - current.yMm))
                                    when (current) {
                                        is TextElement -> current.copy(widthMm = widthMm, heightMm = heightMm)
                                        is ImageElement -> current.copy(widthMm = widthMm, heightMm = heightMm)
                                    }.let(document::replace)
                                }
                            },
                            onDragEnd = { state.commitLiveEdit() },
                            onDragCancel = { state.commitLiveEdit() },
                        )
                    },
            )
        }
    }
}

@Composable
private fun TextElementView(element: TextElement, scale: Float) {
    val density = LocalDensity.current
    val fontSizePx = element.fontSizePt * 25.4 / 72.0 * scale
    val fontSizeSp = with(density) { fontSizePx.toFloat().toSp() }
    val alignment = when (element.valign) {
        VerticalAlign.TOP -> Alignment.TopCenter
        VerticalAlign.MIDDLE -> Alignment.Center
        VerticalAlign.BOTTOM -> Alignment.BottomCenter
    }
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Text(
            text = element.text,
            color = Color.Black,
            textAlign = when (element.align) {
                HorizontalAlign.LEFT -> TextAlign.Left
                HorizontalAlign.CENTER -> TextAlign.Center
                HorizontalAlign.RIGHT -> TextAlign.Right
            },
            style = TextStyle(
                fontSize = fontSizeSp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (element.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (element.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (element.underline) TextDecoration.Underline else null,
                lineHeight = fontSizeSp * element.lineSpacing.toFloat(),
            ),
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ImageElementView(state: ThermlingState, element: ImageElement) {
    val bitmap = remember(element.pngBase64) { state.platform.decodeImage(element.pngBase64) }
    if (bitmap == null) {
        Box(Modifier.fillMaxSize().background(Color(0x22B3261E))) {
            Text(
                "Image unavailable",
                color = Color(0xFFB3261E),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = element.sourceName.ifBlank { "Image" },
            contentScale = when (element.fit) {
                ImageFit.CONTAIN -> ContentScale.Fit
                ImageFit.STRETCH -> ContentScale.FillBounds
                ImageFit.COVER -> ContentScale.Crop
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
