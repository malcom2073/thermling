package com.thermling

import com.thermling.platform.BluetoothDeviceInfo
import com.thermling.platform.looksLikePrinter
import com.thermling.platform.sortBluetoothDevices
import com.thermling.transport.TransportException
import com.thermling.transport.bluetoothAddressBytes
import com.thermling.transport.isBluetoothAddress
import com.thermling.transport.normalizeBluetoothAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothHelpersTest {
    @Test
    fun normalizesPrintedAddresses() {
        assertEquals("E4:53:A2:5F:F1:E7", normalizeBluetoothAddress(" e4-53-a2-5f-f1-e7 "))
        assertEquals("E4:53:A2:5F:F1:E7", normalizeBluetoothAddress("E4:53:A2:5F:F1:E7"))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertFailsWith<TransportException> { normalizeBluetoothAddress("E4:53:A2:5F:F1") }
        assertFailsWith<TransportException> { normalizeBluetoothAddress("printer") }
        assertFalse(isBluetoothAddress("hello"))
        assertTrue(isBluetoothAddress("e4:53:a2:5f:f1:e7"))
    }

    @Test
    fun addressBytesAreLittleEndian() {
        assertContentEquals(
            byteArrayOf(
                0xE7.toByte(),
                0xF1.toByte(),
                0x5F.toByte(),
                0xA2.toByte(),
                0x53.toByte(),
                0xE4.toByte(),
            ),
            bluetoothAddressBytes("e4-53-a2-5f-f1-e7"),
        )
    }

    @Test
    fun printersSortFirstThenNamedDevices() {
        val devices = listOf(
            BluetoothDeviceInfo("AA:00:00:00:00:01", "Keyboard"),
            BluetoothDeviceInfo("BB:00:00:00:00:02", "Nelko P21"),
            BluetoothDeviceInfo("aa:00:00:00:00:01", "Keyboard duplicate"),
            BluetoothDeviceInfo("", "No address"),
            BluetoothDeviceInfo("CC:00:00:00:00:03", "Thermal Label"),
        )

        val sorted = sortBluetoothDevices(devices)

        assertEquals(3, sorted.size)
        assertEquals(
            listOf("BB:00:00:00:00:02", "CC:00:00:00:00:03", "AA:00:00:00:00:01"),
            sorted.map { it.address },
        )
    }

    @Test
    fun printerDetectionIsCaseInsensitive() {
        assertTrue(looksLikePrinter("NELKO P21"))
        assertTrue(looksLikePrinter("Thermal Label Printer"))
        assertFalse(looksLikePrinter("Living room speaker"))
    }

    @Test
    fun unknownDevicesHaveAReadableLabel() {
        assertEquals("Unknown device", BluetoothDeviceInfo("AA:BB", "").displayName)
        assertEquals("P21", BluetoothDeviceInfo("AA:BB", "P21").displayName)
    }
}
