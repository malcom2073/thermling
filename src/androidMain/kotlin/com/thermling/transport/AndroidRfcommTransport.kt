package com.thermling.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth SPP transport using Android's public RFCOMM socket API. Unlike the
 * Linux backend this needs no native binding: the platform pairs the device and
 * provides the serial port profile socket for us.
 */
@SuppressLint("MissingPermission")
class AndroidRfcommTransport(
    private val context: Context,
    private val mac: String,
    private val connectSocket: (BluetoothDevice) -> BluetoothSocket = { device ->
        device.createRfcommSocketToServiceRecord(SPP_UUID)
    },
) : BufferedTransport() {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null

    override val isOpen: Boolean get() = socket != null

    @SuppressLint("MissingPermission")
    override fun open() {
        if (socket != null) return
        val normalized = normalizeBluetoothAddress(mac)
        val activeAdapter = adapter
            ?: throw TransportException("This device does not support Bluetooth.")
        if (!hasConnectPermission()) {
            throw TransportException(
                "Bluetooth permission is required. Allow Nearby devices access for Thermling " +
                    "in Android settings and try again.",
            )
        }
        if (!activeAdapter.isEnabled) {
            throw TransportException("Bluetooth is turned off. Turn it on and try again.")
        }

        val device = try {
            activeAdapter.getRemoteDevice(normalized)
        } catch (error: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address: $mac", error)
        }

        val candidate = try {
            connectSocket(device)
        } catch (error: SecurityException) {
            throw TransportException(
                "Bluetooth permission is required to connect to the printer.",
                error,
            )
        } catch (error: Exception) {
            throw TransportException(
                "Could not open RFCOMM to $normalized: ${error.message}",
                error,
            )
        }

        try {
            activeAdapter.cancelDiscovery()
            candidate.connect()
        } catch (error: IOException) {
            runCatching { candidate.close() }
            throw TransportException(connectError(normalized, error), error)
        } catch (error: SecurityException) {
            runCatching { candidate.close() }
            throw TransportException(
                "Bluetooth permission is required to connect to the printer.",
                error,
            )
        }
        socket = candidate
        clearBuffer()
    }

    override fun write(payload: ByteArray) {
        val active = activeSocket()
        try {
            active.outputStream.run {
                write(payload)
                flush()
            }
        } catch (error: IOException) {
            throw TransportException("Could not send data to the printer: ${error.message}", error)
        }
    }

    override fun receive(timeoutMs: Long): Boolean {
        val active = activeSocket()
        val input = try {
            active.inputStream
        } catch (error: IOException) {
            throw TransportException("Could not read from the printer: ${error.message}", error)
        }
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000
        val buffer = ByteArray(4096)
        while (true) {
            val available = try {
                input.available()
            } catch (error: IOException) {
                throw TransportException("Lost the Bluetooth connection: ${error.message}", error)
            }
            if (available > 0) {
                val count = try {
                    input.read(buffer, 0, minOf(available, buffer.size))
                } catch (error: IOException) {
                    throw TransportException("Lost the Bluetooth connection: ${error.message}", error)
                }
                when {
                    count < 0 ->
                        throw TransportException("The printer closed the Bluetooth connection")
                    count > 0 -> {
                        enqueue(buffer.copyOfRange(0, count))
                        return true
                    }
                }
            }
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    override fun discardInput() {
        val active = socket ?: return
        try {
            val input = active.inputStream
            val buffer = ByteArray(4096)
            while (input.available() > 0) {
                if (input.read(buffer) <= 0) break
            }
        } catch (_: Exception) {
            // Draining is best-effort; the next read reports a real disconnect.
        }
    }

    override fun close() {
        val active = socket
        socket = null
        if (active != null) runCatching { active.close() }
        super.close()
    }

    private fun activeSocket(): BluetoothSocket =
        socket ?: throw TransportException("Not connected to the printer")

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun connectError(address: String, error: IOException): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("timeout", ignoreCase = true) ->
                "Timed out connecting to $address. Make sure the printer is awake, paired, " +
                    "and not connected to another device."
            message.contains("refused", ignoreCase = true) ->
                "The printer refused the Bluetooth connection. Make sure it is awake and " +
                    "not already connected to another device."
            else ->
                "Could not connect to $address over Bluetooth: ${message.ifBlank { "unknown error" }}. " +
                    "Pair the printer in Android Bluetooth settings first."
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
