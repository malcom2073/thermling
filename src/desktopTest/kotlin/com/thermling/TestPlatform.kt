package com.thermling

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.thermling.model.Halftone
import com.thermling.model.LabelDocument
import com.thermling.platform.AppSettings
import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.PickedImage
import com.thermling.platform.Platform
import com.thermling.platform.TransportConfig
import com.thermling.platform.TransportKind
import com.thermling.protocol.bitmapWidthDots
import com.thermling.protocol.mmToDots
import com.thermling.protocol.printableWidthMm
import com.thermling.render.RenderResult
import com.thermling.transport.FakeTransport
import com.thermling.transport.PrinterTransport
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/** Desktop implementation using Compose Desktop's ImageBitmap. */
actual class TestPlatform : Platform {
    actual var settings: AppSettings = AppSettings(transport = TransportKind.DEMO)
    actual val savedSettings: AppSettings get() = settings
    actual val transport = FakeTransport()
    actual val files = mutableMapOf<String, String>()
    actual var savePath: String? = null
    actual var openPath: String? = null
    actual var failWrites = false
    actual var scanResults: List<BluetoothDeviceInfo> = emptyList()
    actual var failScan = false

    actual constructor()

    actual override fun render(
        document: LabelDocument,
        dpi: Int,
        headWidthMm: Double,
        offsetXMm: Double,
        offsetYMm: Double,
    ): RenderResult {
        val printable = printableWidthMm(document.widthMm, headWidthMm)
        val widthDots = bitmapWidthDots(printable, dpi)
        val heightDots = max(1, mmToDots(document.lengthMm, dpi).roundToInt())
        val data = ByteArray(widthDots / 8 * heightDots) { 0xFF.toByte() }
        val previewBitmap = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        return RenderResult(
            widthDots = widthDots,
            heightDots = heightDots,
            data = data,
            preview = previewBitmap.toComposeImageBitmap(),
            dpi = dpi,
            printableWidthMm = printable,
            halftone = Halftone.THRESHOLD.value,
        )
    }

    actual override fun decodeImage(base64: String): ImageBitmap? = null

    actual override suspend fun chooseImage(): PickedImage? = null

    actual override suspend fun openDocumentDialog(): String? = openPath

    actual override suspend fun saveDocumentDialog(suggestedName: String): String? = savePath

    actual override fun readText(path: String): String =
        files[path] ?: error("missing test file: $path")

    actual override fun writeText(path: String, text: String) {
        if (failWrites) error("disk is full")
        files[path] = text
    }

    actual override suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> {
        if (failScan) error("Bluetooth is off")
        return scanResults
    }

    actual override fun createTransport(config: TransportConfig): PrinterTransport = transport

    actual override fun loadSettings(): AppSettings = settings

    actual override fun saveSettings(settings: AppSettings) {
        this.settings = settings
    }

    actual override fun setLastDirectory(path: String) = Unit
}