package com.thermling.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.thermling.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Discovers printers through Android's classic Bluetooth discovery and merges
 * the results with the bonded-device list. Bonded devices are always included,
 * so a sleeping printer stays selectable after it has been paired once.
 */
class AndroidBluetoothScanner(
    private val context: Context,
    private val scanDurationMs: Long = 6_000,
) {
    @SuppressLint("MissingPermission")
    suspend fun scan(): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw TransportException("This device does not support Bluetooth.")
        requirePermission()
        if (!adapter.isEnabled) {
            throw TransportException("Bluetooth is turned off. Turn it on and try again.")
        }

        val found = linkedMapOf<String, BluetoothDeviceInfo>()
        for (device in adapter.bondedDevices.orEmpty()) {
            found[device.address] = device.toInfo()
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    ?: device.name
                    ?: ""
                synchronized(found) {
                    found[device.address] = BluetoothDeviceInfo(device.address, name)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            runCatching { adapter.cancelDiscovery() }
            if (!adapter.startDiscovery()) {
                throw TransportException(
                    "Android could not start Bluetooth discovery. Check that Bluetooth " +
                        "and Location are enabled and try again.",
                )
            }
            delay(scanDurationMs)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }

        // A device may have been bonded while discovery was running.
        for (device in adapter.bondedDevices.orEmpty()) {
            found.putIfAbsent(device.address, device.toInfo())
        }
        sortBluetoothDevices(synchronized(found) { found.values.toList() })
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toInfo(): BluetoothDeviceInfo =
        BluetoothDeviceInfo(address = address, name = name.orEmpty())

    private fun requirePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted =
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw TransportException(
                    "Bluetooth permission is required. Allow Nearby devices access for " +
                        "Thermling in Android settings and try again.",
                )
            }
        } else {
            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw TransportException(
                    "Location permission is required to scan for Bluetooth devices on this " +
                        "Android version. Allow it for Thermling and try again.",
                )
            }
        }
    }
}
