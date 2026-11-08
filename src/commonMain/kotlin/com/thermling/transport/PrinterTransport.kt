package com.thermling.transport

import com.thermling.protocol.CMD_BATTERY
import com.thermling.protocol.CMD_CONFIG
import com.thermling.protocol.CMD_LABEL
import com.thermling.protocol.CMD_SELFTEST
import com.thermling.protocol.CMD_STATUS
import com.thermling.protocol.crc16
import kotlin.time.TimeSource

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface PrinterTransport : AutoCloseable {
    val isOpen: Boolean
    fun open()
    override fun close()
    fun write(payload: ByteArray)
    fun readLine(timeoutMs: Int = 3000): ByteArray
    fun readExact(count: Int, timeoutMs: Int = 3000): ByteArray
    fun readOptional(count: Int, timeoutMs: Int = 1500): ByteArray
    fun resetInput()
}

/**
 * Common byte-oriented transport. Platform implementations only need to provide
 * [receive] and [discardInput]; framing and timeout behavior lives here so the
 * fake and the real serial transport behave the same way in tests.
 */
abstract class BufferedTransport : PrinterTransport {
    private val buffer = ByteQueue()

    protected abstract fun receive(timeoutMs: Long): Boolean
    abstract fun discardInput()

    override fun resetInput() {
        clearBuffer()
        discardInput()
    }

    protected fun buffered(): ByteArray = buffer.toByteArray()

    private fun consume(count: Int) {
        buffer.consume(count)
    }

    private fun pumpUntil(timeoutMs: Int, predicate: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        if (predicate()) return true
        while (true) {
            val elapsed = started.elapsedNow().inWholeMilliseconds
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) return false
            receive(remaining.coerceAtMost(80))
            if (predicate()) return true
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) return false
        }
    }

    override fun readLine(timeoutMs: Int): ByteArray {
        val found = pumpUntil(timeoutMs) {
            val bytes = buffered()
            var index = 0
            while (index + 1 < bytes.size) {
                if (bytes[index] == 0x0D.toByte() && bytes[index + 1] == 0x0A.toByte()) return@pumpUntil true
                index++
            }
            false
        }
        if (!found) {
            throw TransportException("timed out waiting for a line from the printer")
        }
        val bytes = buffered()
        var end = 0
        while (end + 1 < bytes.size) {
            if (bytes[end] == 0x0D.toByte() && bytes[end + 1] == 0x0A.toByte()) {
                end += 2
                break
            }
            end++
        }
        val line = bytes.copyOfRange(0, end)
        consume(end)
        return line
    }

    override fun readExact(count: Int, timeoutMs: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        val found = pumpUntil(timeoutMs) { buffered().size >= count }
        if (!found) {
            throw TransportException("timed out waiting for $count bytes from the printer")
        }
        val bytes = buffered().copyOfRange(0, count)
        consume(count)
        return bytes
    }

    override fun readOptional(count: Int, timeoutMs: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        pumpUntil(timeoutMs) { buffered().size >= count }
        val available = buffered()
        val take = minOf(count, available.size)
        val result = available.copyOfRange(0, take)
        consume(take)
        return result
    }

    protected fun enqueue(bytes: ByteArray) {
        buffer.write(bytes)
    }

    protected fun clearBuffer() {
        buffer.reset()
    }

    override fun close() {
        clearBuffer()
    }
}

/**
 * Small common-Main byte queue.  Keeping this in common code means the transport
 * abstraction compiles for Android without depending on JVM-only stream classes.
 */
private class ByteQueue(initialCapacity: Int = 256) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(1))
    private var start = 0
    private var end = 0

    fun toByteArray(): ByteArray = data.copyOfRange(start, end)

    fun write(bytes: ByteArray) {
        ensureCapacity(end + bytes.size)
        bytes.copyInto(data, end)
        end += bytes.size
    }

    fun consume(count: Int) {
        require(count >= 0 && count <= end - start) { "cannot consume $count bytes" }
        start += count
        if (start == end) {
            reset()
        } else if (start > data.size / 2) {
            data.copyInto(data, 0, start, end)
            end -= start
            start = 0
        }
    }

    fun reset() {
        start = 0
        end = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) capacity *= 2
        data = data.copyOf(capacity)
    }
}

/** In-memory printer used by tests and by the desktop app's demo backend. */
class FakeTransport : BufferedTransport() {
    private var open = false
    private var lastPayload: ByteArray = byteArrayOf()
    val printedPayloads = mutableListOf<ByteArray>()

    override val isOpen: Boolean get() = open

    override fun open() {
        open = true
        clearBuffer()
    }

    override fun close() {
        open = false
        clearBuffer()
    }

    override fun write(payload: ByteArray) {
        check(open) { "transport is not open" }
        lastPayload = payload.copyOf()
        when {
            payload.contentEquals(CMD_CONFIG) -> enqueue(
                "CONFIG ".encodeToByteArray() + hex("00CB00020204020E0201") + "\r\n".encodeToByteArray(),
            )
            payload.contentEquals(CMD_BATTERY) -> enqueue("BATTERY \u0075\u0000\r\n".encodeToByteArray())
            payload.contentEquals(CMD_STATUS) -> enqueue(byteArrayOf(0x00))
            payload.contentEquals(CMD_LABEL) -> enqueue(hex("000c011203000301121215280f0eed03"))
            payload.contentEquals(CMD_SELFTEST) -> enqueue(printAck())
            else -> {
                if (payload.decodeToString().contains("SIZE ") || payload.isNotEmpty()) {
                    printedPayloads += payload.copyOf()
                }
                enqueue(printAck())
            }
        }
    }

    override fun receive(timeoutMs: Long): Boolean = false

    override fun discardInput() {
        clearBuffer()
    }

    fun lastWritten(): ByteArray = lastPayload.copyOf()

    private fun printAck(): ByteArray {
        val payload = ByteArray(14)
        return payload + crc16(payload)
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
