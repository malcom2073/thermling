package com.thermling.platform

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.thermling.model.LabelDocument
import com.thermling.render.AndroidLabelRenderer
import com.thermling.render.LabelRenderer
import com.thermling.render.RenderResult
import com.thermling.transport.AndroidRfcommTransport
import com.thermling.transport.FakeTransport
import com.thermling.transport.PrinterTransport
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android backend: SAF for files, SharedPreferences for settings, and the
 * platform RFCOMM socket for printing. Bluetooth discovery and rendering live
 * in their own Android classes so this type stays the platform seam.
 */
class AndroidPlatform(
    private val context: Context,
    private val dialogs: AndroidFileDialogs,
) : Platform {
    private val renderer: LabelRenderer = AndroidLabelRenderer()
    private val scanner = AndroidBluetoothScanner(context)
    private val preferences = context.getSharedPreferences("thermling", Context.MODE_PRIVATE)

    override fun render(
        document: LabelDocument,
        dpi: Int,
        headWidthMm: Double,
        offsetXMm: Double,
        offsetYMm: Double,
    ): RenderResult = renderer.render(document, dpi, headWidthMm, offsetXMm, offsetYMm)

    override fun decodeImage(base64: String): ImageBitmap? {
        if (base64.isEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            bitmap.asImageBitmap()
        }.getOrNull()
    }

    override suspend fun chooseImage(): PickedImage? {
        val uri = dialogs.pickImage() ?: return null
        return loadImage(uri)
    }

    override suspend fun openDocumentDialog(): String? = dialogs.openDocument()?.toString()

    override suspend fun saveDocumentDialog(suggestedName: String): String? {
        val suggested = if (suggestedName.endsWith(".json", ignoreCase = true)) {
            suggestedName
        } else {
            "$suggestedName.label.json"
        }
        return dialogs.createDocument(suggested)?.toString()
    }

    override fun readText(path: String): String {
        val uri = contentUri(path)
        if (uri != null) {
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: throw IllegalArgumentException("Could not open the selected document")
            } catch (error: SecurityException) {
                throw IllegalArgumentException(
                    "Thermling no longer has permission to read this document. Open it again.",
                    error,
                )
            }
        }
        return File(path).readText()
    }

    override fun writeText(path: String, text: String) {
        val uri = contentUri(path)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.bufferedWriter().use { it.write(text) }
                } ?: throw IllegalArgumentException("Could not open the selected document")
            } catch (error: SecurityException) {
                throw IllegalArgumentException(
                    "Thermling no longer has permission to write this document. Save it again.",
                    error,
                )
            }
            return
        }
        val target = File(path)
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    override fun createTransport(config: TransportConfig): PrinterTransport = when (config.kind) {
        TransportKind.SOCKET -> AndroidRfcommTransport(context, config.mac)
        TransportKind.DEMO -> FakeTransport()
        TransportKind.RFCOMM, TransportKind.SERIAL -> throw UnsupportedOperationException(
            "${config.kind.label} is not available on Android. Use the Bluetooth printer backend.",
        )
    }

    override fun loadSettings(): AppSettings {
        val stored = runCatching {
            TransportKind.valueOf(preferences.getString(KEY_TRANSPORT, TransportKind.SOCKET.name)!!)
        }.getOrDefault(TransportKind.SOCKET)
        val transport = if (stored in transportOptions) stored else TransportKind.SOCKET
        return AppSettings(
            transport = transport,
            mac = preferences.getString(KEY_MAC, "").orEmpty(),
            device = preferences.getString(KEY_DEVICE, "/dev/rfcomm0").orEmpty(),
            density = preferences.getInt(KEY_DENSITY, 15).coerceIn(1, 15),
            copies = preferences.getInt(KEY_COPIES, 1).coerceIn(1, 99),
            dpi = preferences.getInt(KEY_DPI, 203).coerceIn(50, 1200),
            autoDpi = preferences.getBoolean(KEY_AUTO_DPI, true),
            headWidthMm = preferences.getString(KEY_HEAD_WIDTH, null)
                ?.toDoubleOrNull()
                ?.coerceIn(1.0, 200.0)
                ?: 12.0,
            offsetXMm = preferences.getString(KEY_OFFSET_X, null)
                ?.toDoubleOrNull()
                ?.coerceIn(-10.0, 10.0)
                ?: 0.0,
            offsetYMm = preferences.getString(KEY_OFFSET_Y, null)
                ?.toDoubleOrNull()
                ?.coerceIn(-10.0, 10.0)
                ?: 0.0,
            lastDirectory = preferences.getString(KEY_LAST_DIRECTORY, "").orEmpty(),
        )
    }

    override fun saveSettings(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_TRANSPORT, settings.transport.name)
            .putString(KEY_MAC, settings.mac)
            .putString(KEY_DEVICE, settings.device)
            .putInt(KEY_DENSITY, settings.density)
            .putInt(KEY_COPIES, settings.copies)
            .putInt(KEY_DPI, settings.dpi)
            .putBoolean(KEY_AUTO_DPI, settings.autoDpi)
            .putString(KEY_HEAD_WIDTH, settings.headWidthMm.toString())
            .putString(KEY_OFFSET_X, settings.offsetXMm.toString())
            .putString(KEY_OFFSET_Y, settings.offsetYMm.toString())
            .putString(KEY_LAST_DIRECTORY, settings.lastDirectory)
            .apply()
    }

    override fun setLastDirectory(path: String) {
        preferences.edit().putString(KEY_LAST_DIRECTORY, path).apply()
    }

    override suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> = scanner.scan()

    override val editionLabel: String get() = "Android MVP"

    override val connectionHint: String
        get() = "Pair the printer in Android Bluetooth settings first. Thermling asks for " +
            "Nearby devices permission the first time it scans or connects. The 12 mm print " +
            "head clips wider labels."

    override val transportOptions: List<TransportKind>
        get() = listOf(TransportKind.SOCKET, TransportKind.DEMO)

    override fun transportLabel(kind: TransportKind): String = when (kind) {
        TransportKind.SOCKET -> "Bluetooth printer (SPP)"
        else -> kind.label
    }

    override fun transportSupportsScanning(kind: TransportKind): Boolean =
        kind == TransportKind.SOCKET

    override fun transportUsesSerialDevice(kind: TransportKind): Boolean = false

    override fun transportDescription(kind: TransportKind): String = when (kind) {
        TransportKind.SOCKET ->
            "Connects directly to a paired Bluetooth printer over the serial port profile. " +
                "Android handles pairing; no root or serial device is needed."
        TransportKind.DEMO -> ""
        else -> kind.label
    }

    override fun displayName(path: String): String {
        val uri = contentUri(path)
        if (uri != null) {
            val queried = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (!queried.isNullOrBlank()) return queried
            val segment = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            if (!segment.isNullOrBlank()) return segment
        }
        return super.displayName(path)
    }

    private fun contentUri(path: String): Uri? {
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT) uri else null
    }

    private fun loadImage(uri: Uri): PickedImage {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read the selected image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Unsupported image format")
        }

        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_IMAGE_DIMENSION) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalArgumentException("Could not decode the selected image")

        val scaled = if (max(decoded.width, decoded.height) > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toDouble() / max(decoded.width, decoded.height)
            val width = max(1, (decoded.width * scale).roundToInt())
            val height = max(1, (decoded.height * scale).roundToInt())
            Bitmap.createScaledBitmap(decoded, width, height, true).also { resized ->
                if (resized !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val output = ByteArrayOutputStream()
        try {
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalArgumentException("Could not encode the image as PNG")
            }
            return PickedImage(
                path = uri.toString(),
                name = displayName(uri.toString()),
                base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                width = scaled.width,
                height = scaled.height,
            )
        } finally {
            scaled.recycle()
        }
    }

    private companion object {
        const val MAX_IMAGE_DIMENSION = 1400
        const val KEY_TRANSPORT = "transport"
        const val KEY_MAC = "mac"
        const val KEY_DEVICE = "device"
        const val KEY_DENSITY = "density"
        const val KEY_COPIES = "copies"
        const val KEY_DPI = "dpi"
        const val KEY_AUTO_DPI = "autoDpi"
        const val KEY_HEAD_WIDTH = "headWidthMm"
        const val KEY_OFFSET_X = "offsetXMm"
        const val KEY_OFFSET_Y = "offsetYMm"
        const val KEY_LAST_DIRECTORY = "lastDirectory"
    }
}
