package com.thermling.platform

import com.thermling.model.LabelDocument
import com.thermling.render.RenderResult
import com.thermling.transport.PrinterTransport
import androidx.compose.ui.graphics.ImageBitmap

enum class TransportKind(val label: String) {
    SOCKET("Direct RFCOMM socket (no root)"),
    RFCOMM("rfcomm helper + serial device"),
    SERIAL("Existing serial device"),
    DEMO("Demo printer (no hardware)");
}

data class TransportConfig(
    val kind: TransportKind = TransportKind.SOCKET,
    val mac: String = "",
    val device: String = "/dev/rfcomm0",
    val channel: Int = 1,
    val takeOver: Boolean = true,
)

data class AppSettings(
    val transport: TransportKind = TransportKind.SOCKET,
    val mac: String = "",
    val device: String = "/dev/rfcomm0",
    val density: Int = 15,
    val copies: Int = 1,
    val dpi: Int = 203,
    val autoDpi: Boolean = true,
    val headWidthMm: Double = 12.0,
    val lastDirectory: String = "",
)

data class PickedImage(
    val path: String,
    val name: String,
    val base64: String,
    val width: Int,
    val height: Int,
)

data class BluetoothDeviceInfo(
    val address: String,
    val name: String = "",
) {
    val displayName: String get() = name.ifBlank { "Unknown device" }
}

interface Platform {
    fun render(document: LabelDocument, dpi: Int, headWidthMm: Double): RenderResult
    fun decodeImage(base64: String): ImageBitmap?
    fun chooseImage(): PickedImage?
    fun openDocumentDialog(): String?
    fun saveDocumentDialog(suggestedName: String): String?
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun createTransport(config: TransportConfig): PrinterTransport
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
    fun setLastDirectory(path: String)

    /** Returns nearby or known Bluetooth devices; platforms without scanning return none. */
    suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> = emptyList()
}
