package com.thermling

import com.thermling.app.ConnectionState
import com.thermling.app.ThermlingState
import com.thermling.model.Orientation
import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.TransportKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThermlingStateTest {
    @Test
    fun addTextRendersConnectsAndPrintsThroughTheDemoTransport() = runTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)

        state.addText()
        assertEquals(1, state.document.elements.size)
        assertTrue(state.dirty)
        assertNotNull(state.selectedElement)

        state.renderPreview()
        val preview = assertNotNull(state.preview)
        assertEquals(96, preview.widthDots)
        assertEquals(320, preview.heightDots)
        assertEquals(3840, preview.data.size)

        state.connectPrinter()
        assertEquals(ConnectionState.CONNECTED, state.connection)
        assertEquals(203, state.printerConfig?.dpi)
        assertEquals(75, state.printerBattery?.percent)
        assertTrue(state.printerLabel?.hasTag == true)

        assertTrue(state.printDocument())
        val printed = platform.transport.printedPayloads.single()
        assertTrue(printed.decodeToString().startsWith("\r\nSIZE 14 mm,40 mm\r\n"))
        assertTrue(printed.decodeToString().endsWith("\r\nPRINT 1\r\n"))
        assertTrue(printed.size > 3840)

        state.disconnectPrinter()
        assertEquals(ConnectionState.DISCONNECTED, state.connection)
        assertNull(state.printerConfig)
    }

    @Test
    fun undoRedoAndNewDocumentTrackDirtyState() {
        val state = ThermlingState(TestPlatform())
        assertFalse(state.canUndo)

        state.addText()
        assertTrue(state.canUndo)
        assertTrue(state.dirty)

        state.undo()
        assertTrue(state.document.elements.isEmpty())
        assertFalse(state.dirty)
        assertTrue(state.canRedo)

        state.redo()
        assertEquals(1, state.document.elements.size)
        assertTrue(state.dirty)
        assertFalse(state.canRedo)

        state.newDocument()
        assertTrue(state.document.elements.isEmpty())
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertFalse(state.dirty)
    }

    @Test
    fun movingAndResizingAnElementIsOneUndoStep() {
        val state = ThermlingState(TestPlatform())
        state.addText()
        val element = state.selectedElement as com.thermling.model.TextElement

        state.beginLiveEdit("Move element")
        state.liveEdit { document ->
            document.replace(element.copy(xMm = element.xMm + 2.0, yMm = element.yMm + 3.0))
        }
        state.liveEdit { document ->
            document.replace(
                (document.find(element.id) as com.thermling.model.TextElement)
                    .copy(xMm = element.xMm + 4.0),
            )
        }
        state.commitLiveEdit()

        assertEquals(1, state.document.elements.size)
        assertEquals(element.xMm + 4.0, state.document.elements.single().xMm)
        assertEquals(element.yMm + 3.0, state.document.elements.single().yMm)

        state.undo()
        assertEquals(element.xMm, state.document.elements.single().xMm)
        assertEquals(element.yMm, state.document.elements.single().yMm)
    }

    @Test
    fun applyingLandscapePresetSwapsElementGeometryAndPageSize() {
        val state = ThermlingState(TestPlatform())
        state.addText()
        val before = state.document.elements.single()
        val landscape = com.thermling.model.LABEL_PRESETS.first {
            it.orientation == Orientation.LANDSCAPE
        }

        state.applyPreset(landscape)

        assertEquals(Orientation.LANDSCAPE, state.document.orientation)
        val after = state.document.elements.single()
        assertEquals(before.yMm, after.xMm)
        assertEquals(before.xMm, after.yMm)
        assertEquals(before.heightMm, after.widthMm)
        assertEquals(before.widthMm, after.heightMm)
        assertEquals(40.0, state.pageSizeMm.first)
        assertEquals(12.0, state.pageSizeMm.second)
    }

    @Test
    fun usePrinterRollAdoptsTheReportedLabelStock() = runTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)
        state.connectPrinter()

        state.usePrinterRoll()

        assertEquals(14.0, state.document.widthMm)
        assertEquals(40.0, state.document.lengthMm)
        assertEquals(5.0, state.document.gapMm)
    }

    @Test
    fun settingsPersistAndAutoDpiUsesThePrinterReport() = runTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)
        assertEquals(203, state.effectiveDpi)

        state.updateSettings { it.copy(density = 11, copies = 3, autoDpi = false, dpi = 300) }
        assertEquals(11, platform.savedSettings.density)
        assertEquals(3, platform.savedSettings.copies)
        assertEquals(300, state.effectiveDpi)

        state.connectPrinter()
        assertEquals(203, state.printerConfig?.dpi)
        assertEquals(300, state.effectiveDpi)

        state.updateSettings { it.copy(autoDpi = true) }
        assertEquals(203, state.effectiveDpi)
    }

    @Test
    fun documentCodecRoundTripsThroughThePlatformFileApi() = runTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)
        state.addText()
        state.edit("Set name") { it.copy(name = "Saved label") }

        platform.savePath = "saved.label.json"
        assertTrue(state.saveDocumentAs())
        assertFalse(state.dirty)
        assertTrue(platform.files.containsKey("saved.label.json"))

        state.newDocument()
        assertNull(state.documentPath)

        platform.openPath = "saved.label.json"
        state.openDocument()
        assertEquals("Saved label", state.document.name)
        assertEquals(1, state.document.elements.size)
        assertEquals("saved.label.json", state.documentPath)
    }

    @Test
    fun bluetoothScanPopulatesDevicesAndSelectionUpdatesSettings() = runTest {
        val platform = TestPlatform()
        val printer = BluetoothDeviceInfo("E4:53:A2:5F:F1:E7", "P21")
        platform.scanResults = listOf(printer, BluetoothDeviceInfo("00:11:22:33:44:55", "Speaker"))
        val state = ThermlingState(platform)

        state.scanBluetoothDevices()

        assertEquals(2, state.bluetoothDevices.size)
        assertFalse(state.scanningBluetooth)
        assertNull(state.bluetoothScanError)

        state.selectBluetoothDevice(printer)

        assertEquals("E4:53:A2:5F:F1:E7", state.settings.mac)
        assertEquals(TransportKind.SOCKET, state.settings.transport)
        assertEquals("Selected P21", state.statusMessage)

        state.updateSettings { it.copy(transport = TransportKind.RFCOMM) }
        state.selectBluetoothDevice(printer)
        assertEquals(TransportKind.RFCOMM, state.settings.transport)
    }

    @Test
    fun failedBluetoothScanReportsAnErrorAndClearsStaleResults() = runTest {
        val platform = TestPlatform()
        platform.scanResults = listOf(BluetoothDeviceInfo("00:11:22:33:44:55", "Old device"))
        val state = ThermlingState(platform)
        state.scanBluetoothDevices()
        assertEquals(1, state.bluetoothDevices.size)

        platform.failScan = true
        state.scanBluetoothDevices()

        assertTrue(state.bluetoothDevices.isEmpty())
        assertFalse(state.scanningBluetooth)
        assertEquals("Bluetooth is off", state.bluetoothScanError)
        assertTrue(state.statusMessage.startsWith("Bluetooth scan failed"))
    }

    @Test
    fun failedSaveKeepsTheDocumentDirty() = runTest {
        val platform = TestPlatform()
        platform.failWrites = true
        val state = ThermlingState(platform)
        state.edit("Rename") { it.copy(name = "Unsaved") }

        platform.savePath = "broken.label.json"
        assertFalse(state.saveDocumentAs())
        assertTrue(state.dirty)
        assertNull(state.documentPath)
        assertTrue(state.statusMessage.startsWith("Could not save"))
    }
}
