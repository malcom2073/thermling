package com.thermling.platform

import com.thermling.model.LabelDocument
import com.thermling.render.AwtLabelRenderer
import com.thermling.render.LabelRenderer
import com.thermling.render.RenderResult
import com.thermling.transport.FakeTransport
import com.thermling.transport.PrinterTransport
import com.thermling.transport.RfcommProcessTransport
import com.thermling.transport.RfcommSocketTransport
import com.thermling.transport.SerialTransport
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

class DesktopPlatform : Platform {
    private val renderer: LabelRenderer = AwtLabelRenderer()
    private val bluetoothScanner = BluezBluetoothScanner()
    private val preferences = Preferences.userRoot().node("com/thermling/desktop")

    override fun render(document: LabelDocument, dpi: Int, headWidthMm: Double): RenderResult =
        renderer.render(document, dpi, headWidthMm)

    override fun decodeImage(base64: String): ImageBitmap? {
        if (base64.isEmpty()) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(base64)
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            image.toComposeImageBitmap()
        }.getOrNull()
    }

    override fun chooseImage(): PickedImage? {
        val path = chooseFile("Choose image", FileDialog.LOAD, imageFilter) ?: return null
        val file = File(path)
        val source = ImageIO.read(file) ?: throw IllegalArgumentException("Unsupported image format")
        var image = source
        val longest = max(source.width, source.height)
        if (longest > 1400) {
            val scale = 1400.0 / longest
            val width = max(1, (source.width * scale).roundToInt())
            val height = max(1, (source.height * scale).roundToInt())
            val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = resized.createGraphics()
            graphics.drawImage(source, 0, 0, width, height, null)
            graphics.dispose()
            image = resized
        }
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) {
            throw IllegalArgumentException("Could not encode the image as PNG")
        }
        preferences.put("lastDirectory", file.parent ?: "")
        return PickedImage(
            path = file.absolutePath,
            name = file.name,
            base64 = Base64.getEncoder().encodeToString(output.toByteArray()),
            width = image.width,
            height = image.height,
        )
    }

    override fun openDocumentDialog(): String? =
        chooseFile("Open label document", FileDialog.LOAD, documentFilter)

    override fun saveDocumentDialog(suggestedName: String): String? {
        val suggested = if (suggestedName.endsWith(".json", ignoreCase = true)) {
            suggestedName
        } else {
            "$suggestedName.label.json"
        }
        val selected = chooseFile("Save label document", FileDialog.SAVE, documentFilter, suggested)
            ?: return null
        return if (selected.endsWith(".json", ignoreCase = true)) selected else "$selected.label.json"
    }

    override fun readText(path: String): String = Files.readString(Path.of(path))

    override fun writeText(path: String, text: String) {
        val target = Path.of(path)
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, text)
    }

    override suspend fun scanBluetoothDevices(): List<BluetoothDeviceInfo> = bluetoothScanner.scan()

    override fun createTransport(config: TransportConfig): PrinterTransport = when (config.kind) {
        TransportKind.SOCKET -> RfcommSocketTransport(
            mac = config.mac,
            channel = config.channel,
        )
        TransportKind.RFCOMM -> RfcommProcessTransport(
            mac = config.mac,
            device = config.device,
            channel = config.channel,
            takeOver = config.takeOver,
        )
        TransportKind.SERIAL -> SerialTransport(config.device)
        TransportKind.DEMO -> FakeTransport()
    }

    override fun loadSettings(): AppSettings {
        val kind = runCatching {
            TransportKind.valueOf(preferences.get("transport", TransportKind.SOCKET.name))
        }.getOrDefault(TransportKind.SOCKET)
        return AppSettings(
            transport = kind,
            mac = preferences.get("mac", ""),
            device = preferences.get("device", "/dev/rfcomm0"),
            density = preferences.getInt("density", 15).coerceIn(1, 15),
            copies = preferences.getInt("copies", 1).coerceIn(1, 99),
            dpi = preferences.getInt("dpi", 203).coerceIn(50, 1200),
            autoDpi = preferences.getBoolean("autoDpi", true),
            headWidthMm = preferences.getDouble("headWidthMm", 12.0).coerceIn(1.0, 200.0),
            lastDirectory = preferences.get("lastDirectory", ""),
        )
    }

    override fun saveSettings(settings: AppSettings) {
        preferences.put("transport", settings.transport.name)
        preferences.put("mac", settings.mac)
        preferences.put("device", settings.device)
        preferences.putInt("density", settings.density)
        preferences.putInt("copies", settings.copies)
        preferences.putInt("dpi", settings.dpi)
        preferences.putBoolean("autoDpi", settings.autoDpi)
        preferences.putDouble("headWidthMm", settings.headWidthMm)
        preferences.put("lastDirectory", settings.lastDirectory)
    }

    override fun setLastDirectory(path: String) {
        preferences.put("lastDirectory", path)
    }

    private fun chooseFile(
        title: String,
        mode: Int,
        filter: java.io.FilenameFilter,
        suggested: String? = null,
    ): String? {
        val dialog = FileDialog(null as Frame?, title, mode)
        dialog.filenameFilter = filter
        val last = preferences.get("lastDirectory", "")
        if (last.isNotBlank() && File(last).isDirectory) dialog.directory = last
        if (suggested != null) dialog.file = suggested
        dialog.isVisible = true
        val file = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        val result = File(directory, file)
        preferences.put("lastDirectory", result.parent ?: directory)
        return result.absolutePath
    }

    companion object {
        private val imageFilter = java.io.FilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".webp") ||
                lower.endsWith(".tif") || lower.endsWith(".tiff")
        }
        private val documentFilter = java.io.FilenameFilter { _, name ->
            name.endsWith(".json", ignoreCase = true)
        }
    }
}
