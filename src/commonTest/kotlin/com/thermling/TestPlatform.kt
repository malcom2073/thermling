package com.thermling

import androidx.compose.ui.graphics.ImageBitmap
import com.thermling.model.LabelDocument
import com.thermling.platform.AppSettings
import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.PickedImage
import com.thermling.platform.Platform
import com.thermling.platform.TransportConfig
import com.thermling.platform.TransportKind
import com.thermling.render.RenderResult
import com.thermling.transport.FakeTransport
import com.thermling.transport.PrinterTransport

/** Shared in-memory platform for state and Compose UI tests. */
expect class TestPlatform : Platform {
    var settings: AppSettings
    val savedSettings: AppSettings
    val transport: FakeTransport
    val files: MutableMap<String, String>
    var savePath: String?
    var openPath: String?
    var failWrites: Boolean
    var scanResults: List<BluetoothDeviceInfo>
    var failScan: Boolean

    constructor()

    // Platform methods are implemented in the actual declaration, which may
    // differ per platform (e.g., the desktop renderer produces ImageBitmap vs.
    // a minimal stub on Android JVM).
    override fun render(
        document: LabelDocument,
        dpi: Int,
        headWidthMm: Double,
        offsetXMm: Double,
        offsetYMm: Double,
    ): RenderResult
    override fun decodeImage(base64: String): ImageBitmap?
    override suspend fun chooseImage(): PickedImage?
    override suspend fun openDocumentDialog(): String?
    override suspend fun saveDocumentDialog(suggestedName: String): String?
    override fun readText(path: String): String
    override fun writeText(path: String, text: String)
    override suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo>
    override fun createTransport(config: TransportConfig): PrinterTransport
    override fun loadSettings(): AppSettings
    override fun saveSettings(settings: AppSettings)
    override fun setLastDirectory(path: String)
}
