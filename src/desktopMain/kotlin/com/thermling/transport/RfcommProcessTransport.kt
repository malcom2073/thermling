package com.thermling.transport

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Linux Bluetooth SPP transport. The Java standard library has no RFCOMM
 * socket API, so this starts BlueZ's `rfcomm` helper to create a serial device
 * and then speaks to that device. It is intentionally behind the same
 * [PrinterTransport] interface as the serial and fake transports so the rest of
 * the application never depends on the command-line helper.
 */
class RfcommProcessTransport(
    private val mac: String,
    private val device: String = "/dev/rfcomm0",
    private val channel: Int = 1,
    private val takeOver: Boolean = true,
    private val connectTimeoutMs: Long = 12_000,
) : SerialTransport(device) {
    private var process: Process? = null
    private var createdDevice = false

    override fun open() {
        if (isOpen) return
        val normalized = normalizeMac(mac)
        if (takeOver) runRfcomm("release", device)

        val deviceFile = File(device)
        if (!deviceFile.exists()) {
            val command = listOf("rfcomm", "connect", device, normalized, channel.toString())
            val started = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (error: Exception) {
                throw TransportException(
                    "Could not start rfcomm. Install the BlueZ tools (rfcomm) and make " +
                        "sure Bluetooth is enabled.",
                    error,
                )
            }
            process = started
            val deadline = System.currentTimeMillis() + connectTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (deviceFile.exists()) break
                if (!started.isAlive) break
                Thread.sleep(100)
            }
            if (!deviceFile.exists()) {
                val exit = if (started.isAlive) null else started.exitValue()
                started.destroyForcibly()
                process = null
                throw TransportException(
                    "rfcomm did not create $device for $normalized" +
                        (exit?.let { " (rfcomm exited with status $it)" } ?: " within the timeout") +
                        ". Pair the printer first and check the RFCOMM channel.",
                )
            }
            createdDevice = true
        }

        try {
            super.open()
        } catch (error: Exception) {
            stopProcess()
            throw error
        }
    }

    override fun close() {
        super.close()
        stopProcess()
    }

    private fun stopProcess() {
        val running = process
        process = null
        if (running != null) {
            running.destroy()
            if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        if (createdDevice) {
            runRfcomm("release", device)
            createdDevice = false
        }
    }

    private fun runRfcomm(vararg arguments: String) {
        try {
            val command = listOf("rfcomm") + arguments
            val child = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            child.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Releasing is best-effort; the actual connect error is reported by open().
        }
    }

    companion object {
        private val MAC_PATTERN = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

        fun normalizeMac(value: String): String {
            val trimmed = value.trim().uppercase().replace('-', ':')
            if (!MAC_PATTERN.matches(trimmed)) {
                throw TransportException("Invalid Bluetooth address: $value")
            }
            return trimmed
        }
    }
}
