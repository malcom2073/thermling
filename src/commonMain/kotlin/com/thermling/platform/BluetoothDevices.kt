package com.thermling.platform

/** True for names that commonly belong to the supported label printers. */
fun looksLikePrinter(name: String): Boolean {
    val lower = name.lowercase()
    return listOf("p21", "p22", "nelko", "printer", "thermal", "label").any(lower::contains)
}

/**
 * Deduplicates scan results and puts likely printers first, then named devices
 * alphabetically. Shared by the BlueZ and Android scanners so both platforms
 * present the same list.
 */
fun sortBluetoothDevices(devices: List<BluetoothDeviceInfo>): List<BluetoothDeviceInfo> =
    devices
        .filter { it.address.isNotBlank() }
        .distinctBy { it.address.uppercase() }
        .sortedWith(
            compareByDescending<BluetoothDeviceInfo> { looksLikePrinter(it.name) }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.address },
        )
