package com.thermling

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.thermling.app.ConnectionState
import com.thermling.app.ThermlingState
import com.thermling.platform.AppSettings
import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.TransportKind
import com.thermling.ui.ThermlingApp
import com.thermling.ui.ThermlingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ThermlingAppUiTest {
    @Test
    fun addingTextFromTheToolbarUpdatesStateAndPreview() = runComposeUiTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("add-text-top").assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(1, state.document.elements.size)
        waitUntil(timeoutMillis = 5_000) { state.preview != null }
        val preview = assertNotNull(state.preview)
        assertEquals(96, preview.widthDots)
        assertEquals(320, preview.heightDots)
    }

    @Test
    fun previewConnectionButtonOpensThePrinterDialog() = runComposeUiTest {
        val state = ThermlingState(TestPlatform())
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("printer-preview").assertIsDisplayed().performClick()

        onNodeWithTag("printer-dialog").assertIsDisplayed()
        onNodeWithText("Demo backend — no hardware required").assertIsDisplayed()
    }

    @Test
    fun toolbarPrinterButtonOpensThePrinterDialog() = runComposeUiTest {
        val state = ThermlingState(TestPlatform())
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("printer-toolbar").assertIsDisplayed().performClick()

        onNodeWithTag("printer-dialog").assertIsDisplayed()
    }

    @Test
    fun scanningAndSelectingABluetoothDeviceUpdatesTheAddress() = runComposeUiTest {
        val platform = TestPlatform()
        val printer = BluetoothDeviceInfo("E4:53:A2:5F:F1:E7", "P21")
        platform.settings = AppSettings(transport = TransportKind.SOCKET)
        platform.scanResults = listOf(
            printer,
            BluetoothDeviceInfo("00:11:22:33:44:55", "Speaker"),
        )
        val state = ThermlingState(platform)
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("printer-preview").assertIsDisplayed().performClick()
        onNodeWithTag("bluetooth-scan").assertIsDisplayed().performClick()

        waitUntil(timeoutMillis = 5_000) {
            state.bluetoothDevices.size == 2 && !state.scanningBluetooth
        }

        onNodeWithTag("bluetooth-device-E4:53:A2:5F:F1:E7")
            .assertIsDisplayed()
            .performClick()

        assertEquals("E4:53:A2:5F:F1:E7", state.settings.mac)
        assertEquals(TransportKind.SOCKET, state.settings.transport)
        onNodeWithText("Selected").assertIsDisplayed()
    }

    @Test
    fun failedScanShowsTheErrorInTheDialog() = runComposeUiTest {
        val platform = TestPlatform().apply {
            settings = AppSettings(transport = TransportKind.RFCOMM)
            failScan = true
        }
        val state = ThermlingState(platform)
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("printer-preview").assertIsDisplayed().performClick()
        onNodeWithTag("bluetooth-scan").assertIsDisplayed().performClick()

        waitUntil(timeoutMillis = 5_000) { state.bluetoothScanError != null }
        onNodeWithText("Bluetooth is off").assertIsDisplayed()
    }

    @Test
    fun demoDialogConnectsRefreshesAndPrintsThroughTheUi() = runComposeUiTest {
        val platform = TestPlatform()
        val state = ThermlingState(platform)
        setContent { ThermlingTheme { ThermlingApp(state) } }

        onNodeWithTag("printer-preview").performClick()
        onNodeWithTag("printer-connect").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) {
            state.connection == ConnectionState.CONNECTED &&
                !state.busy &&
                state.printerConfig != null
        }
        assertEquals(203, state.printerConfig?.dpi)
        assertEquals(75, state.printerBattery?.percent)

        onNodeWithTag("printer-refresh").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { state.preview != null }

        onNodeWithTag("printer-print").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { platform.transport.printedPayloads.isNotEmpty() }

        assertTrue(platform.transport.printedPayloads.single().decodeToString().contains("PRINT 1"))
    }
}
