package com.thermling

import com.thermling.protocol.CMD_BATTERY
import com.thermling.protocol.CMD_CONFIG
import com.thermling.protocol.CMD_LABEL
import com.thermling.protocol.PrinterClient
import com.thermling.protocol.Status
import com.thermling.transport.BufferedTransport
import com.thermling.transport.FakeTransport
import com.thermling.transport.TransportException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeTransportTest {
    @Test
    fun reportsAConnectedPrinter() = runTest {
        val transport = FakeTransport()
        val client = PrinterClient(transport)
        assertFalse(transport.isOpen)

        client.open()
        assertTrue(transport.isOpen)

        val config = client.queryConfig()
        assertEquals(203, config.dpi)
        assertEquals("4.2.14", config.firmwareVersion)

        val battery = client.queryBattery()
        assertEquals(75, battery.percent)
        assertFalse(battery.charging)

        assertEquals(Status.READY.code, client.queryStatus())

        val label = client.queryLabel()
        assertTrue(label.hasTag)
        assertEquals(14.0, label.widthMm)
        assertEquals(40.0, label.lengthMm)
        assertEquals(5.0, label.gapMm)

        val snapshot = client.refresh()
        assertEquals(203, snapshot.config?.dpi)
        assertEquals(0, snapshot.status)
        assertTrue(snapshot.errors.isEmpty())

        client.close()
        assertFalse(transport.isOpen)
    }

    @Test
    fun printJobChecksStatusAndReportsAck() = runTest {
        val transport = FakeTransport()
        val client = PrinterClient(transport)
        client.open()

        val message = client.printJob(byteArrayOf(1, 2, 3, 4))
        assertTrue(message.contains("Print"))
        assertEquals(1, transport.printedPayloads.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), transport.printedPayloads.single())

        client.close()
    }

    @Test
    fun commandsUseTheExpectedWireBytes() = runTest {
        val transport = FakeTransport()
        val client = PrinterClient(transport)
        client.open()

        client.queryConfig()
        assertContentEquals(CMD_CONFIG, transport.lastWritten())

        client.queryBattery()
        assertContentEquals(CMD_BATTERY, transport.lastWritten())

        client.queryLabel()
        assertContentEquals(CMD_LABEL, transport.lastWritten())

        client.close()
    }

    @Test
    fun resetInputDiscardsBufferedBytes() {
        val transport = FakeTransport()
        transport.open()
        transport.write(CMD_CONFIG)
        transport.resetInput()
        assertFailsWith<TransportException> {
            transport.readLine(10)
        }
        transport.close()
    }

    @Test
    fun framedReadsHandleRepliesLargerThanTheInitialBuffer() {
        val line = ByteArray(600) { 'A'.code.toByte() }
        val transport = ScriptedTransport(
            mutableListOf(
                line.copyOfRange(0, 300),
                line.copyOfRange(300, 600) + "\r\nxyz".encodeToByteArray(),
            ),
        )
        transport.open()

        assertContentEquals(line + "\r\n".encodeToByteArray(), transport.readLine(1000))
        assertContentEquals("xyz".encodeToByteArray(), transport.readExact(3, 1000))
    }

    private class ScriptedTransport(
        private val chunks: MutableList<ByteArray>,
    ) : BufferedTransport() {
        private var open = false

        override val isOpen: Boolean get() = open

        override fun open() {
            open = true
        }

        override fun receive(timeoutMs: Long): Boolean {
            if (chunks.isEmpty()) return false
            enqueue(chunks.removeAt(0))
            return true
        }

        override fun write(payload: ByteArray) = Unit

        override fun discardInput() = Unit

        override fun close() {
            open = false
        }
    }
}
