package com.thermling.transport

private val BLUETOOTH_ADDRESS_PATTERN = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

/** Normalizes a printed Bluetooth address such as `e4-53-a2-5f-f1-e7`. */
fun normalizeBluetoothAddress(value: String): String {
    val trimmed = value.trim().uppercase().replace('-', ':')
    if (!BLUETOOTH_ADDRESS_PATTERN.matches(trimmed)) {
        throw TransportException("Invalid Bluetooth address: $value")
    }
    return trimmed
}

fun isBluetoothAddress(value: String): Boolean = runCatching {
    normalizeBluetoothAddress(value)
}.isSuccess

/**
 * Linux Bluetooth sockets store the address as `bdaddr_t`, which is the printed
 * address with the least-significant byte first.
 */
fun bluetoothAddressBytes(value: String): ByteArray {
    val bytes = normalizeBluetoothAddress(value).split(':').map { it.toInt(16).toByte() }
    return ByteArray(bytes.size) { index -> bytes[bytes.size - 1 - index] }
}
