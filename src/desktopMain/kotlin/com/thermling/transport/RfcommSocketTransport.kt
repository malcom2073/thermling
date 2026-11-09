package com.thermling.transport

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * RFCOMM channel transport over a raw Linux Bluetooth socket.
 *
 * The JVM has no Bluetooth socket API, so JNA is used to call the same libc
 * `socket`/`connect` functions that the Python application uses. Unlike the
 * `rfcomm` helper backend, this needs no root privileges and creates no
 * `/dev/rfcommN` tty: the printer is addressed directly by MAC and channel.
 */
class RfcommSocketTransport internal constructor(
    private val mac: String,
    private val channel: Int,
    private val connectTimeoutMs: Int,
    private val socketFactory: () -> RfcommSocket,
) : BufferedTransport() {
    constructor(
        mac: String,
        channel: Int = 1,
        connectTimeoutMs: Int = 6_000,
    ) : this(mac, channel, connectTimeoutMs, { LibcRfcommSocket() })

    private var socket: RfcommSocket? = null

    override val isOpen: Boolean get() = socket != null

    override fun open() {
        if (socket != null) return
        val normalized = RfcommProcessTransport.normalizeMac(mac)
        if (channel !in 1..30) {
            throw TransportException("Invalid RFCOMM channel: $channel (expected 1–30)")
        }
        val candidate = socketFactory()
        try {
            candidate.connect(parseBluetoothAddress(normalized), channel, connectTimeoutMs)
        } catch (error: Exception) {
            runCatching { candidate.close() }
            if (error is TransportException) throw error
            throw TransportException(
                "Could not open RFCOMM channel $channel to $normalized: ${error.message}",
                error,
            )
        }
        socket = candidate
        clearBuffer()
    }

    override fun write(payload: ByteArray) {
        activeSocket().write(payload)
    }

    override fun receive(timeoutMs: Long): Boolean {
        val buffer = ByteArray(4096)
        val count = activeSocket().read(buffer, timeoutMs.coerceIn(0, 80).toInt())
        if (count < 0) {
            throw TransportException("The printer closed the Bluetooth connection")
        }
        if (count == 0) return false
        enqueue(buffer.copyOfRange(0, count))
        return true
    }

    override fun discardInput() {
        val active = socket ?: return
        val buffer = ByteArray(4096)
        while (true) {
            val count = try {
                active.read(buffer, 0)
            } catch (_: Exception) {
                return
            }
            if (count <= 0) return
        }
    }

    override fun close() {
        val active = socket
        socket = null
        if (active != null) runCatching { active.close() }
        super.close()
    }

    private fun activeSocket(): RfcommSocket =
        socket ?: throw TransportException("Not connected to the printer")
}

internal interface RfcommSocket : AutoCloseable {
    fun connect(address: ByteArray, channel: Int, timeoutMs: Int)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun write(payload: ByteArray)
}

internal fun parseBluetoothAddress(value: String): ByteArray = bluetoothAddressBytes(value)

/**
 * Thin libc binding. The socket is created non-blocking so connect, reads and
 * writes can all honour timeouts through `poll(2)` instead of blocking a UI
 * thread indefinitely.
 */
internal class LibcRfcommSocket : RfcommSocket {
    private var descriptor = -1

    override fun connect(address: ByteArray, channel: Int, timeoutMs: Int) {
        require(address.size == 6) { "Bluetooth address must contain 6 bytes" }
        if (descriptor >= 0) return

        val fd = libc.socket(
            AF_BLUETOOTH,
            SOCK_STREAM or SOCK_NONBLOCK or SOCK_CLOEXEC,
            BTPROTO_RFCOMM,
        )
        if (fd < 0) throw TransportException(socketError(Native.getLastError()))

        val sockaddr = Memory(SOCKADDR_RC_SIZE.toLong())
        sockaddr.clear()
        sockaddr.setShort(0, AF_BLUETOOTH.toShort())
        sockaddr.write(2, address, 0, address.size)
        sockaddr.setByte(8, channel.toByte())

        if (libc.connect(fd, sockaddr, SOCKADDR_RC_SIZE) != 0) {
            val errno = Native.getLastError()
            if (errno != EINPROGRESS) {
                libc.close(fd)
                throw TransportException(connectError(errno))
            }
            val events = await(fd, POLLOUT, timeoutMs)
            if (events == 0) {
                libc.close(fd)
                throw TransportException(
                    "Timed out connecting to the printer over Bluetooth. " +
                        "Make sure it is awake, paired, and not connected to another device.",
                )
            }
            val error = socketErrorNumber(fd)
            if (error != 0) {
                libc.close(fd)
                throw TransportException(connectError(error))
            }
            if (events and POLLOUT == 0) {
                libc.close(fd)
                throw TransportException(
                    "The printer closed the Bluetooth connection while connecting. " +
                        "Make sure it is awake and not connected to another device.",
                )
            }
        }
        descriptor = fd
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val fd = openDescriptor()
        val events = await(fd, POLLIN, timeoutMs)
        if (events == 0) return 0
        if (events and POLLNVAL != 0) {
            throw TransportException("The Bluetooth socket is no longer valid")
        }
        if (events and POLLIN == 0) {
            if (events and (POLLERR or POLLHUP) != 0) return -1
            return 0
        }

        val memory = Memory(buffer.size.toLong())
        while (true) {
            val count = libc.read(fd, memory, NativeLong(buffer.size.toLong())).toLong()
            if (count > 0) {
                memory.read(0, buffer, 0, count.toInt())
                return count.toInt()
            }
            if (count == 0L) return -1
            val errno = Native.getLastError()
            when (errno) {
                EINTR -> continue
                EAGAIN, EWOULDBLOCK -> return 0
                else -> throw TransportException("Bluetooth read failed: ${describeErrno(errno)}")
            }
        }
    }

    override fun write(payload: ByteArray) {
        val fd = openDescriptor()
        val memory = Memory(payload.size.toLong())
        memory.write(0, payload, 0, payload.size)
        var offset = 0
        val deadline = System.nanoTime() + WRITE_TIMEOUT_MS * 1_000_000L
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val written = libc
                .write(fd, memory.share(offset.toLong()), NativeLong(remaining.toLong()))
                .toLong()
            if (written > 0) {
                offset += written.toInt()
                continue
            }
            val errno = Native.getLastError()
            when (errno) {
                EINTR -> continue
                EAGAIN, EWOULDBLOCK -> {
                    val waitMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (waitMs <= 0 || await(fd, POLLOUT, waitMs) == 0) {
                        throw TransportException(
                            "Timed out sending data to the printer over Bluetooth",
                        )
                    }
                }
                else -> throw TransportException(
                    "Could not send to the printer: ${describeErrno(errno)}",
                )
            }
        }
    }

    override fun close() {
        val fd = descriptor
        descriptor = -1
        if (fd >= 0) libc.close(fd)
    }

    private fun openDescriptor(): Int {
        if (descriptor < 0) throw TransportException("Not connected to the printer")
        return descriptor
    }

    private fun await(fd: Int, events: Int, timeoutMs: Int): Int {
        val pollFd = Memory(POLLFD_SIZE.toLong())
        pollFd.setInt(0, fd)
        pollFd.setShort(4, events.toShort())
        pollFd.setShort(6, 0)

        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        var timeout = timeoutMs.coerceAtLeast(0)
        while (true) {
            val result = libc.poll(pollFd, NativeLong(1), timeout)
            if (result < 0) {
                val errno = Native.getLastError()
                if (errno == EINTR) {
                    timeout = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (timeout <= 0) return 0
                    continue
                }
                throw TransportException("Bluetooth socket poll failed: ${describeErrno(errno)}")
            }
            if (result == 0) return 0
            return pollFd.getShort(6).toInt() and 0xFFFF
        }
    }

    private fun socketErrorNumber(fd: Int): Int {
        val value = Memory(4)
        value.setInt(0, 0)
        val length = Memory(4)
        length.setInt(0, 4)
        if (libc.getsockopt(fd, SOL_SOCKET, SO_ERROR, value, length) != 0) {
            return Native.getLastError()
        }
        return value.getInt(0)
    }

    private fun socketError(errno: Int): String = when (errno) {
        EAFNOSUPPORT, EPROTONOSUPPORT, EPFNOSUPPORT ->
            "Direct Bluetooth sockets are not available on this system. " +
                "Choose the rfcomm + serial backend instead."
        EPERM, EACCES ->
            "Permission denied while opening a Bluetooth socket. Check that Bluetooth " +
                "is enabled, or choose the rfcomm + serial backend."
        else ->
            "Could not create a Bluetooth RFCOMM socket: ${describeErrno(errno)}. " +
                "Choose the rfcomm + serial backend if this persists."
    }

    private fun connectError(errno: Int): String = when (errno) {
        ECONNREFUSED ->
            "The printer refused the Bluetooth connection. Make sure it is awake and " +
                "not already connected to another device."
        EHOSTUNREACH, ENETUNREACH, ETIMEDOUT, EHOSTDOWN ->
            "Could not reach the printer over Bluetooth (${describeErrno(errno)}). " +
                "Make sure it is turned on, nearby, and paired with BlueZ."
        else ->
            "Could not connect over Bluetooth: ${describeErrno(errno)}. " +
                "Pair the printer first and check the RFCOMM channel."
    }

    private fun describeErrno(errno: Int): String {
        val text = runCatching { libc.strerror(errno) }.getOrNull()
        return if (text.isNullOrBlank()) "error $errno" else text
    }

    private companion object {
        val libc: LibC = Native.load("c", LibC::class.java)

        const val AF_BLUETOOTH = 31
        const val SOCK_STREAM = 1
        const val SOCK_NONBLOCK = 0x800
        const val SOCK_CLOEXEC = 0x80000
        const val BTPROTO_RFCOMM = 3

        const val SOL_SOCKET = 1
        const val SO_ERROR = 4

        const val POLLIN = 0x001
        const val POLLOUT = 0x004
        const val POLLERR = 0x008
        const val POLLHUP = 0x010
        const val POLLNVAL = 0x020

        const val EINTR = 4
        const val EAGAIN = 11
        const val EWOULDBLOCK = EAGAIN
        const val EACCES = 13
        const val EPERM = 1
        const val EPFNOSUPPORT = 96
        const val EAFNOSUPPORT = 97
        const val EPROTONOSUPPORT = 93
        const val EINPROGRESS = 115
        const val ENETUNREACH = 101
        const val ECONNREFUSED = 111
        const val EHOSTUNREACH = 113
        const val ETIMEDOUT = 110
        const val EHOSTDOWN = 112

        const val SOCKADDR_RC_SIZE = 10
        const val POLLFD_SIZE = 8
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}

internal interface LibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(fd: Int, address: Pointer, addressLength: Int): Int
    fun close(fd: Int): Int
    fun poll(fds: Pointer, count: NativeLong, timeout: Int): Int
    fun read(fd: Int, buffer: Pointer, count: NativeLong): NativeLong
    fun write(fd: Int, buffer: Pointer, count: NativeLong): NativeLong
    fun getsockopt(fd: Int, level: Int, option: Int, value: Pointer, length: Pointer): Int
    fun strerror(errno: Int): String?
}
