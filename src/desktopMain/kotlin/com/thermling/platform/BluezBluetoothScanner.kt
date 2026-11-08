package com.thermling.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class BluetoothScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * BlueZ has no Java RFCOMM or discovery API, so scanning is delegated to the
 * `bluetoothctl` helper. The scan output is merged with BlueZ's device list:
 * the former finds new devices, while the latter supplies stable names and
 * keeps the last-known printer available even when it is asleep.
 */
class BluezBluetoothScanner(
    private val bluetoothctl: String = "bluetoothctl",
    private val scanSeconds: Int = 6,
) {
    suspend fun scan(): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        val scanOutput = runCommand(
            listOf(bluetoothctl, "--timeout", scanSeconds.toString(), "--monitor", "scan", "on"),
            timeoutSeconds = scanSeconds + 4L,
        )
        val devicesOutput = runCommand(listOf(bluetoothctl, "devices"), timeoutSeconds = 5)
        mergeScanResults(scanOutput, devicesOutput)
    }

    private fun runCommand(args: List<String>, timeoutSeconds: Long): String {
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw BluetoothScanException(
                "Bluetooth scanning needs BlueZ's bluetoothctl command. " +
                    "Install BlueZ or enter the printer address manually.",
                error,
            )
        }

        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { output.appendLine(it) }
            }
        }.apply { isDaemon = true }
        reader.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1_000)

        if (!finished) {
            throw BluetoothScanException(
                "Bluetooth scan timed out. Check that Bluetooth is enabled and try again.",
            )
        }
        if (process.exitValue() != 0 && output.isBlank()) {
            throw BluetoothScanException(
                "Bluetooth scan failed. Check that Bluetooth is enabled and try again.",
            )
        }
        return output.toString()
    }
}

internal fun mergeScanResults(
    scanOutput: String,
    devicesOutput: String,
): List<BluetoothDeviceInfo> {
    val discovered = parseScanDevices(scanOutput).associateBy { it.address }
    val known = parseKnownDevices(devicesOutput).associateBy { it.address }
    return (known.keys + discovered.keys)
        .map { address ->
            val knownName = known[address]?.name.orEmpty()
            val discoveredName = discovered[address]?.name.orEmpty()
            BluetoothDeviceInfo(
                address = address,
                name = knownName.ifBlank { discoveredName },
            )
        }
        .sortedWith(
            compareByDescending<BluetoothDeviceInfo> { looksLikePrinter(it.name) }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.address },
        )
}

internal fun parseKnownDevices(output: String): List<BluetoothDeviceInfo> =
    output.lineSequence()
        .map { stripAnsi(it).trim() }
        .mapNotNull { line -> DEVICE_LINE.matchEntire(line)?.toDevice() }
        .distinctBy { it.address }
        .toList()

internal fun parseScanDevices(output: String): List<BluetoothDeviceInfo> =
    output.lineSequence()
        .map { stripAnsi(it).trim() }
        .filter { it.contains("[NEW]") }
        .mapNotNull { line -> DEVICE_LINE.find(line)?.toDevice() }
        .filter { it.address.isNotBlank() }
        .distinctBy { it.address }
        .toList()

private fun MatchResult.toDevice(): BluetoothDeviceInfo = BluetoothDeviceInfo(
    address = groupValues[1].uppercase(),
    name = groupValues.getOrElse(2) { "" }.trim(),
)

private fun looksLikePrinter(name: String): Boolean {
    val lower = name.lowercase()
    return listOf("p21", "p22", "nelko", "printer", "thermal", "label").any(lower::contains)
}

private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "")

private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*m")
private val DEVICE_LINE = Regex(
    """Device\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})(?:\s+(.*))?""",
)
