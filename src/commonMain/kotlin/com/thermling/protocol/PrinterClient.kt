package com.thermling.protocol

import com.thermling.transport.PrinterTransport
import com.thermling.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PrinterSnapshot(
    val config: Config? = null,
    val status: Int? = null,
    val label: LabelRoll? = null,
    val battery: Battery? = null,
    val errors: List<String> = emptyList(),
)

class PrinterNotReadyException(message: String) : Exception(message)

class PrinterClient(private val transport: PrinterTransport) {
    private val mutex = Mutex()

    val isOpen: Boolean get() = transport.isOpen

    suspend fun open() = withContext(Dispatchers.Default) {
        mutex.withLock { transport.open() }
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        mutex.withLock {
            runCatching { transport.close() }
        }
    }

    suspend fun refresh(): PrinterSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            var config: Config? = null
            var status: Int? = null
            var label: LabelRoll? = null
            var battery: Battery? = null
            val errors = mutableListOf<String>()
            runCatching { queryConfigLocked() }
                .onSuccess { config = it }
                .onFailure { errors += "configuration: ${it.message}" }
            runCatching { queryStatusLocked() }
                .onSuccess { status = it }
                .onFailure { errors += "status: ${it.message}" }
            runCatching { queryLabelLocked() }
                .onSuccess { label = it }
                .onFailure { errors += "label roll: ${it.message}" }
            runCatching { queryBatteryLocked() }
                .onSuccess { battery = it }
                .onFailure { errors += "battery: ${it.message}" }
            PrinterSnapshot(config, status, label, battery, errors)
        }
    }

    suspend fun queryConfig(): Config = withContext(Dispatchers.Default) {
        mutex.withLock { queryConfigLocked() }
    }

    suspend fun queryStatus(): Int = withContext(Dispatchers.Default) {
        mutex.withLock { queryStatusLocked() }
    }

    suspend fun queryLabel(): LabelRoll = withContext(Dispatchers.Default) {
        mutex.withLock { queryLabelLocked() }
    }

    suspend fun queryBattery(): Battery = withContext(Dispatchers.Default) {
        mutex.withLock { queryBatteryLocked() }
    }

    suspend fun selfTest(): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureOpen()
            transport.resetInput()
            transport.write(CMD_SELFTEST)
            transport.readOptional(16, 1500)
            "Self-test sent"
        }
    }

    suspend fun printJob(payload: ByteArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val status = queryStatusLocked()
            if (status != Status.READY.code) {
                throw PrinterNotReadyException("Printer not ready: ${describeStatus(status)}")
            }
            transport.resetInput()
            transport.write(payload)
            val reply = transport.readOptional(16, 1500)
            val reported = parsePrintReply(reply)
            if (reported != null && reported != Status.READY.code) {
                throw ProtocolException("Printer reported: ${describeStatus(reported)}")
            }
            "Print job sent"
        }
    }

    private fun ensureOpen() {
        if (!transport.isOpen) throw TransportException("Not connected to the printer")
    }

    private fun sendLocked(
        payload: ByteArray,
        replyLength: Int? = null,
        timeoutMs: Int = 3000,
    ): ByteArray {
        ensureOpen()
        transport.resetInput()
        transport.write(payload)
        return when (replyLength) {
            null -> transport.readLine(timeoutMs)
            0 -> byteArrayOf()
            else -> transport.readExact(replyLength, timeoutMs)
        }
    }

    private fun queryConfigLocked(): Config = parseConfigReply(sendLocked(CMD_CONFIG))
    private fun queryStatusLocked(): Int = parseStatusReply(sendLocked(CMD_STATUS, 1))
    private fun queryBatteryLocked(): Battery = parseBatteryReply(sendLocked(CMD_BATTERY))
    private fun queryLabelLocked(): LabelRoll = parseLabelReply(sendLocked(CMD_LABEL, 16))
}
