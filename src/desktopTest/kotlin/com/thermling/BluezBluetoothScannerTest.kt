package com.thermling

import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.mergeScanResults
import com.thermling.platform.parseKnownDevices
import com.thermling.platform.parseScanDevices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BluezBluetoothScannerTest {
    @Test
    fun scanOutputParsesAnsiColoredNewDevices() {
        val output = """
            [${'\u001B'}[0;92mNEW${'\u001B'}[0m] Device 7A:F2:D3:3A:5D:3B 305
            [${'\u001B'}[0;93mCHG${'\u001B'}[0m] Device E4:53:A2:5F:F1:E7 RSSI: 0xffffffd7 (-41)
            [${'\u001B'}[0;92mNEW${'\u001B'}[0m] Device e4:53:a2:5f:f1:e7 P21
        """.trimIndent()

        val devices = parseScanDevices(output)

        assertEquals(
            listOf(
                BluetoothDeviceInfo("7A:F2:D3:3A:5D:3B", "305"),
                BluetoothDeviceInfo("E4:53:A2:5F:F1:E7", "P21"),
            ),
            devices,
        )
    }

    @Test
    fun scanOutputIgnoresChangeLinesAndMalformedAddresses() {
        val output = """
            [${'\u001B'}[0;93mCHG${'\u001B'}[0m] Device E4:53:A2:5F:F1:E7 RSSI: 0xffffffd7 (-41)
            [${'\u001B'}[0;92mNEW${'\u001B'}[0m] Device not-an-address Some name
            [${'\u001B'}[0;92mNEW${'\u001B'}[0m] Device 00:11:22:33:44:55
        """.trimIndent()

        assertEquals(
            listOf(BluetoothDeviceInfo("00:11:22:33:44:55", "")),
            parseScanDevices(output),
        )
    }

    @Test
    fun knownDevicesParseNamesAndTolerateBlankNames() {
        val output = """
            Device E4:53:A2:5F:F1:E7 P21
            Device 00:11:22:33:44:55
            Device E4:53:A2:5F:F1:E7 P21 duplicate
        """.trimIndent()

        assertEquals(
            listOf(
                BluetoothDeviceInfo("E4:53:A2:5F:F1:E7", "P21"),
                BluetoothDeviceInfo("00:11:22:33:44:55", ""),
            ),
            parseKnownDevices(output),
        )
    }

    @Test
    fun mergeDeduplicatesAndPrefersTheKnownName() {
        val scanOutput = """
            [NEW] Device E4:53:A2:5F:F1:E7 E4-53-A2
            [NEW] Device 7A:F2:D3:3A:5D:3B 305
        """.trimIndent()
        val devicesOutput = """
            Device E4:53:A2:5F:F1:E7 P21
            Device 00:11:22:33:44:55 Speaker
        """.trimIndent()

        val devices = mergeScanResults(scanOutput, devicesOutput)

        assertEquals(3, devices.size)
        assertEquals(
            BluetoothDeviceInfo("E4:53:A2:5F:F1:E7", "P21"),
            devices.first { it.address == "E4:53:A2:5F:F1:E7" },
        )
        assertEquals(
            BluetoothDeviceInfo("7A:F2:D3:3A:5D:3B", "305"),
            devices.first { it.address == "7A:F2:D3:3A:5D:3B" },
        )
    }

    @Test
    fun printersSortBeforeOtherDevices() {
        val devicesOutput = """
            Device 00:11:22:33:44:55 Speaker
            Device AA:BB:CC:DD:EE:FF Nelko P21
            Device 11:22:33:44:55:66 Phone
            Device 22:33:44:55:66:77 Thermal Label Printer
        """.trimIndent()

        val devices = mergeScanResults("", devicesOutput)

        assertTrue(devices.first().name.contains("Nelko"))
        assertTrue(devices.take(2).all { it.name.lowercase().contains("nelko") || it.name.lowercase().contains("printer") })
        assertEquals(devices.size, 4)
    }

    @Test
    fun unknownNamesFallBackToADisplayName() {
        assertEquals("Unknown device", BluetoothDeviceInfo("00:11:22:33:44:55").displayName)
        assertEquals("P21", BluetoothDeviceInfo("00:11:22:33:44:55", "P21").displayName)
    }
}
