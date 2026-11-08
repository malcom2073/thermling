package com.thermling

import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared in-memory platform for state and Compose UI tests. */
class TestPlatform : Platform {
    var settings = AppSettings(transport = TransportKind.DEMO)
    val savedSettings get() = settings
    val transport = FakeTransport()
    val files = mutableMapOf<String, String>()
    var savePath: String? = null
    var openPath: String? = null
    var failWrites = false
    var scanResults: List<BluetoothDeviceInfo> = emptyList()
    var failScan = false

    override fun render(document: LabelDocument, dpi: Int, headWidthMm: Double): RenderResult {
        val printable = printableWidthMm(document.widthMm, headWidthMm)
        val widthDots = bitmapWidthDots(printable, dpi)
        val heightDots = max(1, mmToDots(document.lengthMm, dpi).roundToInt())
        val data = ByteArray(widthDots / 8 * heightDots) { 0xFF.toByte() }
        return RenderResult(
            widthDots = widthDots,
            heightDots = heightDots,
            data = data,
            preview = ImageBitmap(1, 1),
            dpi = dpi,
            printableWidthMm = printable,
            halftone = Halftone.THRESHOLD.value,
        )
    }

    override fun decodeImage(base64: String): ImageBitmap? = null

    override fun chooseImage(): PickedImage? = null

    override fun openDocumentDialog(): String? = openPath

    override fun saveDocumentDialog(suggestedName: String): String? = savePath

    override fun readText(path: String): String =
        files[path] ?: error("missing test file: $path")

    override fun writeText(path: String, text: String) {
        if (failWrites) error("disk is full")
        files[path] = text
    }

    override suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> {
        if (failScan) error("Bluetooth is off")
        return scanResults
    }

    override fun createTransport(config: TransportConfig): PrinterTransport = transport

    override fun loadSettings(): AppSettings = settings

    override fun saveSettings(settings: AppSettings) {
        this.settings = settings
    }

    override fun setLastDirectory(path: String) = Unit
}
