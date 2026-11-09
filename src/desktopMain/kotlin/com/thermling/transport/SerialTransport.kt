package com.thermling.transport

import com.fazecast.jSerialComm.SerialPort
import com.thermling.protocol.BAUD

open class SerialTransport(
    private val portName: String,
    private val baud: Int = BAUD,
) : BufferedTransport() {
    private var port: SerialPort? = null

    override val isOpen: Boolean get() = port?.isOpen == true

    override fun open() {
        if (port?.isOpen == true) return
        val candidate = SerialPort.getCommPort(portName)
        candidate.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        candidate.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0)
        if (!candidate.openPort()) {
            throw TransportException(
                "Cannot open $portName: ${candidate.lastErrorCode}. " +
                    "Check that the device exists and that your user can access it " +
                    "(usually the dialout group).",
            )
        }
        port = candidate
        clearBuffer()
    }

    override fun close() {
        port?.closePort()
        port = null
        clearBuffer()
    }

    override fun write(payload: ByteArray) {
        val active = port ?: throw TransportException("Not connected to the printer")
        val written = active.writeBytes(payload, payload.size)
        if (written != payload.size) {
            throw TransportException("Serial write failed after $written of ${payload.size} bytes")
        }
    }

    override fun receive(timeoutMs: Long): Boolean {
        val active = port ?: throw TransportException("Not connected to the printer")
        val wait = timeoutMs.coerceIn(1L, 80L).toInt()
        active.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, wait, 0)
        val bytes = ByteArray(4096)
        val count = active.readBytes(bytes, bytes.size)
        if (count > 0) {
            enqueue(bytes.copyOfRange(0, count))
            return true
        }
        return false
    }

    override fun discardInput() {
        val active = port ?: return
        val bytes = ByteArray(4096)
        while (active.bytesAvailable() > 0) {
            val count = active.readBytes(bytes, minOf(bytes.size, active.bytesAvailable()))
            if (count <= 0) break
        }
    }
}
