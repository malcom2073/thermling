package com.thermling.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thermling.model.DocumentCodec
import com.thermling.model.DocumentException
import com.thermling.model.ImageElement
import com.thermling.model.LabelDocument
import com.thermling.model.LabelElement
import com.thermling.model.TextElement
import com.thermling.platform.AppSettings
import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.PickedImage
import com.thermling.platform.Platform
import com.thermling.platform.TransportConfig
import com.thermling.platform.TransportKind
import com.thermling.protocol.Battery
import com.thermling.protocol.Config
import com.thermling.protocol.LabelRoll
import com.thermling.protocol.PrinterClient
import com.thermling.protocol.PrinterNotReadyException
import com.thermling.protocol.buildJob
import com.thermling.protocol.describeStatus
import com.thermling.render.RenderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

private data class UndoSnapshot(val document: LabelDocument, val label: String)

class ThermlingState(val platform: Platform) {
    var document by mutableStateOf(LabelDocument())
        private set
    var documentPath by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var selectionId by mutableStateOf<String?>(null)
        private set
    var statusMessage by mutableStateOf("Ready")
    var preview by mutableStateOf<RenderResult?>(null)
        private set
    var renderError by mutableStateOf<String?>(null)
        private set
    var settings by mutableStateOf(platform.loadSettings())
        private set
    var busy by mutableStateOf(false)
        private set
    var scanningBluetooth by mutableStateOf(false)
        private set
    var bluetoothDevices by mutableStateOf<List<BluetoothDeviceInfo>>(emptyList())
        private set
    var bluetoothScanError by mutableStateOf<String?>(null)
        private set

    var connection by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set
    var printerConfig by mutableStateOf<Config?>(null)
        private set
    var printerStatus by mutableStateOf<Int?>(null)
        private set
    var printerLabel by mutableStateOf<LabelRoll?>(null)
        private set
    var printerBattery by mutableStateOf<Battery?>(null)
        private set
    var printerErrors by mutableStateOf<List<String>>(emptyList())
        private set

    private val undoStack = mutableStateListOf<UndoSnapshot>()
    private val redoStack = mutableStateListOf<UndoSnapshot>()
    private var liveBefore: LabelDocument? = null
    private var liveLabel: String = "Edit"
    private var savedDocument: LabelDocument = document
    private var client: PrinterClient? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val selectedElement: LabelElement? get() = selectionId?.let(document::find)
    val effectiveDpi: Int
        get() = if (settings.autoDpi) printerConfig?.dpi ?: settings.dpi else settings.dpi
    val pageSizeMm: Pair<Double, Double> get() = document.pageSizeMm(settings.headWidthMm)
    val isConnected: Boolean get() = connection == ConnectionState.CONNECTED
    val canPrint: Boolean get() = isConnected && preview != null && !busy

    fun select(elementId: String?) {
        selectionId = elementId
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        platform.saveSettings(settings)
    }

    fun edit(label: String, transform: (LabelDocument) -> LabelDocument) {
        commitLiveEdit()
        val before = document
        val after = transform(before)
        if (after == before) return
        undoStack.add(UndoSnapshot(before, label))
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
        document = after
        dirty = after != savedDocument
        statusMessage = label
    }

    fun beginLiveEdit(label: String) {
        if (liveBefore == null) {
            liveBefore = document
            liveLabel = label
        }
    }

    fun liveEdit(transform: (LabelDocument) -> LabelDocument) {
        if (liveBefore == null) beginLiveEdit("Edit")
        val after = transform(document)
        if (after != document) {
            document = after
            dirty = after != savedDocument
        }
    }

    fun commitLiveEdit() {
        val before = liveBefore ?: return
        liveBefore = null
        if (before == document) return
        undoStack.add(UndoSnapshot(before, liveLabel))
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
        statusMessage = liveLabel
    }

    fun undo() {
        commitLiveEdit()
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.add(UndoSnapshot(document, snapshot.label))
        document = snapshot.document
        dirty = document != savedDocument
        selectionId = selectionId?.takeIf { document.find(it) != null }
        statusMessage = "Undo: ${snapshot.label}"
    }

    fun redo() {
        commitLiveEdit()
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.add(UndoSnapshot(document, snapshot.label))
        document = snapshot.document
        dirty = document != savedDocument
        selectionId = selectionId?.takeIf { document.find(it) != null }
        statusMessage = "Redo: ${snapshot.label}"
    }

    fun newDocument() {
        savedDocument = LabelDocument()
        document = savedDocument
        documentPath = null
        selectionId = null
        undoStack.clear()
        redoStack.clear()
        liveBefore = null
        dirty = false
        statusMessage = "New label"
    }

    fun applyPreset(preset: com.thermling.model.LabelPreset) {
        val current = document
        val orientationChanged = current.orientation != preset.orientation
        val pageWidth = if (preset.orientation == com.thermling.model.Orientation.LANDSCAPE) {
            preset.lengthMm
        } else {
            min(preset.widthMm, settings.headWidthMm)
        }
        val pageHeight = if (preset.orientation == com.thermling.model.Orientation.LANDSCAPE) {
            min(preset.widthMm, settings.headWidthMm)
        } else {
            preset.lengthMm
        }
        edit("Change label preset") { document ->
            val rotated = if (orientationChanged) {
                document.elements.map { element ->
                    when (element) {
                        is TextElement -> element.copy(
                            xMm = element.yMm,
                            yMm = element.xMm,
                            widthMm = element.heightMm,
                            heightMm = element.widthMm,
                        )
                        is ImageElement -> element.copy(
                            xMm = element.yMm,
                            yMm = element.xMm,
                            widthMm = element.heightMm,
                            heightMm = element.widthMm,
                        )
                    }
                }
            } else {
                document.elements
            }
            val clamped = rotated.map { element ->
                when (element) {
                    is TextElement -> element.copy(
                        xMm = min(element.xMm, max(0.0, pageWidth - element.widthMm)),
                        yMm = min(element.yMm, max(0.0, pageHeight - element.heightMm)),
                    )
                    is ImageElement -> element.copy(
                        xMm = min(element.xMm, max(0.0, pageWidth - element.widthMm)),
                        yMm = min(element.yMm, max(0.0, pageHeight - element.heightMm)),
                    )
                }
            }
            document.copy(
                widthMm = preset.widthMm,
                lengthMm = preset.lengthMm,
                gapMm = preset.gapMm,
                style = preset.style,
                orientation = preset.orientation,
                elements = clamped,
            )
        }
    }

    fun changeOrientation(orientation: com.thermling.model.Orientation) {
        if (document.orientation == orientation) return
        edit("Change orientation") { document ->
            document.copy(
                orientation = orientation,
                elements = document.elements.map { element ->
                    when (element) {
                        is TextElement -> element.copy(
                            xMm = element.yMm,
                            yMm = element.xMm,
                            widthMm = element.heightMm,
                            heightMm = element.widthMm,
                        )
                        is ImageElement -> element.copy(
                            xMm = element.yMm,
                            yMm = element.xMm,
                            widthMm = element.heightMm,
                            heightMm = element.widthMm,
                        )
                    }
                },
            )
        }
    }

    fun usePrinterRoll() {
        val roll = printerLabel ?: return
        if (!roll.hasTag) return
        edit("Use printer roll") {
            it.copy(
                widthMm = roll.widthMm,
                lengthMm = roll.lengthMm,
                gapMm = roll.gapMm,
                style = roll.style,
            )
        }
    }

    fun addText() {
        val (pageWidth, _) = pageSizeMm
        val width = min(max(1.0, pageWidth - 2.0), 10.0)
        val (_, pageHeight) = pageSizeMm
        val height = min(max(4.0, min(8.0, pageHeight - 2.0)), 8.0)
        val element = TextElement(
            text = "Text",
            xMm = (pageWidth - width) / 2.0,
            yMm = max(0.0, min(2.0, pageHeight - height)),
            widthMm = width,
            heightMm = height,
        )
        edit("Add text") { it.add(element) }
        selectionId = element.id
    }

    fun addImage(image: PickedImage) {
        val (pageWidth, pageHeight) = pageSizeMm
        val maxWidth = max(1.0, pageWidth - 2.0)
        val maxHeight = max(1.0, pageHeight - 2.0)
        val aspect = if (image.width != 0) image.height.toDouble() / image.width else 1.0
        var width = maxWidth
        var height = width * aspect
        if (height > maxHeight) {
            height = maxHeight
            width = if (aspect != 0.0) height / aspect else maxWidth
        }
        val element = ImageElement(
            pngBase64 = image.base64,
            sourceName = image.name,
            xMm = (pageWidth - width) / 2.0,
            yMm = (pageHeight - height) / 2.0,
            widthMm = max(0.5, width),
            heightMm = max(0.5, height),
        )
        edit("Add image") { it.add(element) }
        selectionId = element.id
    }

    fun replaceImage(elementId: String, image: PickedImage) {
        val element = document.find(elementId) as? ImageElement ?: return
        edit("Replace image") {
            it.replace(element.copy(pngBase64 = image.base64, sourceName = image.name))
        }
    }

    fun duplicateSelected() {
        val id = selectionId ?: return
        val source = document.find(id) ?: return
        val (pageWidth, pageHeight) = pageSizeMm
        val duplicate = when (source) {
            is TextElement -> source.copy(
                id = com.thermling.model.newId(),
                xMm = min(source.xMm + 1.0, max(0.0, pageWidth - source.widthMm)),
                yMm = min(source.yMm + 1.0, max(0.0, pageHeight - source.heightMm)),
            )
            is ImageElement -> source.copy(
                id = com.thermling.model.newId(),
                xMm = min(source.xMm + 1.0, max(0.0, pageWidth - source.widthMm)),
                yMm = min(source.yMm + 1.0, max(0.0, pageHeight - source.heightMm)),
            )
        }
        edit("Duplicate element") { it.add(duplicate) }
        selectionId = duplicate.id
    }

    fun deleteSelected() {
        val id = selectionId ?: return
        edit("Delete element") { it.remove(id) }
        selectionId = null
    }

    suspend fun renderPreview() {
        val current = document
        val dpi = effectiveDpi
        val head = settings.headWidthMm
        try {
            val result = withContext(Dispatchers.Default) { platform.render(current, dpi, head) }
            if (current == document) {
                preview = result
                renderError = null
            }
        } catch (error: Exception) {
            if (current == document) {
                preview = null
                renderError = error.message ?: "Could not render this label"
            }
        }
    }

    suspend fun openDocument() {
        val path = platform.openDocumentDialog() ?: return
        try {
            val loaded = withContext(Dispatchers.Default) { DocumentCodec.decode(platform.readText(path)) }
            savedDocument = loaded
            document = loaded
            documentPath = path
            selectionId = null
            undoStack.clear()
            redoStack.clear()
            liveBefore = null
            dirty = false
            statusMessage = "Opened ${fileName(path)}"
        } catch (error: Exception) {
            statusMessage = "Could not open document: ${error.message}"
        }
    }

    suspend fun saveDocument(): Boolean {
        val path = documentPath ?: return saveDocumentAs()
        return writeDocument(path)
    }

    suspend fun saveDocumentAs(): Boolean {
        val suggested = document.name.ifBlank { "Untitled" }
        val path = platform.saveDocumentDialog(suggested) ?: return false
        return writeDocument(path)
    }

    private suspend fun writeDocument(path: String): Boolean {
        return try {
            val text = withContext(Dispatchers.Default) { DocumentCodec.encode(document) }
            withContext(Dispatchers.Default) { platform.writeText(path, text) }
            savedDocument = document
            documentPath = path
            dirty = false
            statusMessage = "Saved ${fileName(path)}"
            true
        } catch (error: Exception) {
            statusMessage = "Could not save document: ${error.message}"
            false
        }
    }

    suspend fun scanBluetoothDevices() {
        if (scanningBluetooth) return
        scanningBluetooth = true
        bluetoothScanError = null
        try {
            val devices = platform.scanBluetoothDevices()
            bluetoothDevices = devices
            if (devices.isEmpty()) {
                bluetoothScanError =
                    "No Bluetooth devices found. Make sure Bluetooth is on and the printer is awake."
                statusMessage = "No Bluetooth devices found"
            } else {
                statusMessage = "Found ${devices.size} Bluetooth device${if (devices.size == 1) "" else "s"}"
            }
        } catch (error: Exception) {
            bluetoothDevices = emptyList()
            bluetoothScanError = error.message ?: "Bluetooth scan failed"
            statusMessage = "Bluetooth scan failed: ${error.message}"
        } finally {
            scanningBluetooth = false
        }
    }

    fun selectBluetoothDevice(device: BluetoothDeviceInfo) {
        val transport = if (settings.transport == TransportKind.RFCOMM) {
            TransportKind.RFCOMM
        } else {
            TransportKind.SOCKET
        }
        updateSettings {
            it.copy(
                transport = transport,
                mac = device.address,
            )
        }
        statusMessage = "Selected ${device.displayName}"
    }

    suspend fun connectPrinter() {
        if (busy) return
        busy = true
        connection = ConnectionState.CONNECTING
        connectionError = null
        printerErrors = emptyList()
        try {
            client?.let {
                runCatching { it.close() }
                client = null
            }
            val transport = platform.createTransport(
                TransportConfig(
                    kind = settings.transport,
                    mac = settings.mac,
                    device = settings.device,
                ),
            )
            val newClient = PrinterClient(transport)
            newClient.open()
            client = newClient
            connection = ConnectionState.CONNECTED
            statusMessage = "Connected"
            refreshPrinter()
        } catch (error: Exception) {
            client = null
            connection = ConnectionState.ERROR
            connectionError = error.message ?: error::class.simpleName ?: "Connection failed"
            statusMessage = "Connection failed: $connectionError"
        } finally {
            busy = false
        }
    }

    suspend fun disconnectPrinter() {
        if (busy) return
        busy = true
        try {
            client?.let { runCatching { it.close() } }
            client = null
            connection = ConnectionState.DISCONNECTED
            printerConfig = null
            printerStatus = null
            printerLabel = null
            printerBattery = null
            printerErrors = emptyList()
            statusMessage = "Disconnected"
        } finally {
            busy = false
        }
    }

    suspend fun refreshPrinter() {
        val active = client ?: return
        if (busy && connection == ConnectionState.CONNECTING) return
        try {
            val snapshot = active.refresh()
            printerConfig = snapshot.config
            printerStatus = snapshot.status
            printerLabel = snapshot.label
            printerBattery = snapshot.battery
            printerErrors = snapshot.errors
            if (snapshot.errors.isNotEmpty()) {
                statusMessage = "Printer: ${snapshot.errors.first()}"
            } else {
                statusMessage = "Printer status refreshed"
            }
        } catch (error: Exception) {
            statusMessage = "Could not refresh printer: ${error.message}"
        }
    }

    suspend fun printDocument(): Boolean {
        if (busy) return false
        val active = client
        if (active == null) {
            statusMessage = "Connect to a printer first"
            return false
        }
        val result = preview
        if (result == null) {
            statusMessage = renderError ?: "Nothing to print"
            return false
        }
        busy = true
        return try {
            val payload = buildJob(
                labelWidthMm = document.widthMm,
                labelLengthMm = document.lengthMm,
                gapMm = document.gapMm,
                style = document.style,
                bitmap = result.data,
                widthDots = result.widthDots,
                heightDots = result.heightDots,
                density = settings.density,
                copies = settings.copies,
            )
            statusMessage = "Sending print job…"
            val message = active.printJob(payload)
            statusMessage = "$message (${result.widthDots} × ${result.heightDots} dots)"
            refreshPrinter()
            true
        } catch (error: PrinterNotReadyException) {
            statusMessage = error.message ?: "Printer not ready"
            false
        } catch (error: Exception) {
            statusMessage = "Print failed: ${error.message}"
            false
        } finally {
            busy = false
        }
    }

    suspend fun selfTest() {
        val active = client ?: run {
            statusMessage = "Connect to a printer first"
            return
        }
        busy = true
        try {
            statusMessage = active.selfTest()
            refreshPrinter()
        } catch (error: Exception) {
            statusMessage = "Self-test failed: ${error.message}"
        } finally {
            busy = false
        }
    }

    fun printerStatusText(): String = describeStatus(printerStatus)

    private fun fileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
}
