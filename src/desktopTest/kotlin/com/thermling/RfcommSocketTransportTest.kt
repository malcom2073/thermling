package com.thermling

import com.thermling.protocol.CMD_STATUS
import com.thermling.transport.LibcRfcommSocket
import com.thermling.transport.RfcommSocket
import com.thermling.transport.RfcommSocketTransport
import com.thermling.transport.TransportException
import com.thermling.transport.parseBluetoothAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RfcommSocketTransportTest {
    @Test
    fun openParsesTheAddressAndForwardsWrites() {
        val socket = FakeRfcommSocket()
        val transport = RfcommSocketTransport("e4-53-a2-5f-f1-e7", 2, 1_500) { socket }

        transport.open()

        assertTrue(transport.isOpen)
        assertContentEquals(
            byteArrayOf(0xE7.toByte(), 0xF1.toByte(), 0x5F, 0xA2.toByte(), 0x53, 0xE4.toByte()),
            socket.connectedAddress,
        )
        assertEquals(2, socket.connectedChannel)
        assertEquals(1_500, socket.connectTimeout)

        transport.write("STATUS\r\n".encodeToByteArray())
        assertEquals("STATUS\r\n", socket.writes.single().decodeToString())

        transport.close()
        assertFalse(transport.isOpen)
        assertTrue(socket.closed)
    }

    @Test
    fun receivesDataAndFramesLines() {
        val socket = FakeRfcommSocket().apply {
            chunks += "OK\r\n".encodeToByteArray()
        }
        val transport = RfcommSocketTransport("E4:53:A2:5F:F1:E7", 1, 6_000) { socket }
        transport.open()

        assertEquals("OK\r\n", transport.readLine(500).decodeToString())
        assertTrue(transport.readOptional(1, 10).isEmpty())
    }

    @Test
    fun readTimeoutIsReportedAsATransportError() {
        val transport = RfcommSocketTransport("E4:53:A2:5F:F1:E7", 1, 6_000) { FakeRfcommSocket() }
        transport.open()

        val error = assertFailsWith<TransportException> { transport.readLine(20) }
        assertTrue(error.message.orEmpty().contains("timed out", ignoreCase = true))
    }

    @Test
    fun aClosedSocketIsReportedAsADisconnect() {
        val socket = FakeRfcommSocket().apply { eof = true }
        val transport = RfcommSocketTransport("E4:53:A2:5F:F1:E7", 1, 6_000) { socket }
        transport.open()

        val error = assertFailsWith<TransportException> { transport.readOptional(1, 100) }
        assertTrue(error.message.orEmpty().contains("closed", ignoreCase = true))
    }

    @Test
    fun connectFailuresCloseTheSocketAndKeepItClosed() {
        val socket = FakeRfcommSocket().apply {
            connectFailure = TransportException("Bluetooth is off")
        }
        val transport = RfcommSocketTransport("E4:53:A2:5F:F1:E7", 1, 6_000) { socket }

        val error = assertFailsWith<TransportException> { transport.open() }

        assertEquals("Bluetooth is off", error.message)
        assertTrue(socket.closed)
        assertFalse(transport.isOpen)
    }

    @Test
    fun invalidAddressesAndChannelsAreRejectedBeforeConnecting() {
        assertFailsWith<TransportException> {
            RfcommSocketTransport("not-a-mac", 1, 6_000) { FakeRfcommSocket() }.open()
        }
        assertFailsWith<TransportException> {
            RfcommSocketTransport("E4:53:A2:5F:F1:E7", 31, 6_000) { FakeRfcommSocket() }.open()
        }
    }

    @Test
    fun addressParsingStoresBlueZOrderBytes() {
        assertContentEquals(
            byteArrayOf(0x55, 0x44, 0x33, 0x22, 0x11, 0x00),
            parseBluetoothAddress("00:11:22:33:44:55"),
        )
        assertContentEquals(
            byteArrayOf(0xE7.toByte(), 0xF1.toByte(), 0x5F, 0xA2.toByte(), 0x53, 0xE4.toByte()),
            parseBluetoothAddress("e4-53-a2-5f-f1-e7"),
        )
    }

    @Test
    fun libcSocketBindingLoadsOnLinux() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return

        // No printer exists at the all-zero address; the point is to prove that
        // JNA can load libc and create/connect a Bluetooth RFCOMM socket. Any
        // failure must be a normal transport error, not a missing native binding.
        val error = assertFailsWith<TransportException> {
            LibcRfcommSocket().connect(
                parseBluetoothAddress("00:00:00:00:00:00"),
                channel = 1,
                timeoutMs = 1,
            )
        }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun hardwarePrinterAnswersStatusWhenConfigured() {
        val mac = (System.getProperty("THERMLING_BT_MAC") ?: System.getenv("THERMLING_BT_MAC"))
            ?.takeIf { it.isNotBlank() } ?: return
        val transport = RfcommSocketTransport(mac)

        transport.open()
        try {
            transport.resetInput()
            transport.write(CMD_STATUS)
            val status = transport.readExact(1, 3_000)
            assertEquals(1, status.size)
        } finally {
            transport.close()
        }
    }
}

private class FakeRfcommSocket : RfcommSocket {
    val writes = mutableListOf<ByteArray>()
    val chunks = ArrayDeque<ByteArray>()
    var connectedAddress: ByteArray? = null
    var connectedChannel = -1
    var connectTimeout = -1
    var connectFailure: Exception? = null
    var eof = false
    var closed = false

    override fun connect(address: ByteArray, channel: Int, timeoutMs: Int) {
        connectFailure?.let { throw it }
        connectedAddress = address.copyOf()
        connectedChannel = channel
        connectTimeout = timeoutMs
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (chunks.isEmpty()) return if (eof) -1 else 0
        val chunk = chunks.removeFirst()
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        return minOf(chunk.size, buffer.size)
    }

    override fun write(payload: ByteArray) {
        writes += payload.copyOf()
    }

    override fun close() {
        closed = true
    }
}
