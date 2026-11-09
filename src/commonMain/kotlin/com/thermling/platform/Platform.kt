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
    suspend fun chooseImage(): PickedImage?
    suspend fun openDocumentDialog(): String?
    suspend fun saveDocumentDialog(suggestedName: String): String?
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun createTransport(config: TransportConfig): PrinterTransport
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
    fun setLastDirectory(path: String)

    /** Short product label shown under the title in the top bar. */
    val editionLabel: String get() = "Desktop MVP"

    /** Platform-specific advice shown at the bottom of the printer dialog. */
    val connectionHint: String
        get() = "On Linux, pair the P21 first (for example with `bluetoothctl`) and make sure " +
            "your user can access the serial device. The 12 mm print head clips wider labels."

    /** Backends this platform can actually provide. */
    val transportOptions: List<TransportKind> get() = TransportKind.entries

    fun transportLabel(kind: TransportKind): String = kind.label

    /** Whether [kind] connects over Bluetooth and can use the device scanner. */
    fun transportSupportsScanning(kind: TransportKind): Boolean =
        kind == TransportKind.SOCKET || kind == TransportKind.RFCOMM

    /** Whether [kind] opens a serial device path instead of a Bluetooth address. */
    fun transportUsesSerialDevice(kind: TransportKind): Boolean =
        kind == TransportKind.RFCOMM || kind == TransportKind.SERIAL

    fun transportDescription(kind: TransportKind): String = when (kind) {
        TransportKind.SOCKET ->
            "Uses a direct Bluetooth socket. No root or /dev/rfcomm device is needed; " +
                "pair the printer with BlueZ first."
        TransportKind.RFCOMM ->
            "Thermling starts `rfcomm connect` for this address, then opens the serial " +
                "device. Pair the printer with BlueZ first."
        TransportKind.SERIAL ->
            "Use an existing /dev/rfcommN device created outside Thermling."
        TransportKind.DEMO -> ""
    }

    /** A short name for a document path, used in the window title and status text. */
    fun displayName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

    /** Returns nearby or known Bluetooth devices; platforms without scanning return none. */
    suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> = emptyList()
}
