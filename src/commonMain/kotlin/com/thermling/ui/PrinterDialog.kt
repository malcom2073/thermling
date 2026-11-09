package com.thermling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thermling.app.ConnectionState
import com.thermling.app.ThermlingState
import com.thermling.platform.TransportKind
import com.thermling.protocol.Status
import com.thermling.protocol.describeStatus
import kotlinx.coroutines.launch

@Composable
fun PrinterDialog(
    state: ThermlingState,
    onClose: () -> Unit,
    onPrint: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = LocalThermlingPalette.current

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.width(660.dp).testTag("printer-dialog"),
            shape = RoundedCornerShape(10.dp),
            color = palette.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Printer", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            if (state.settings.transport == TransportKind.DEMO) {
                                "Demo backend — no hardware required"
                            } else {
                                "Bluetooth SPP / RFCOMM"
                            },
                            color = palette.muted,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = onClose, modifier = Modifier.testTag("printer-close")) {
                        Text("Close")
                    }
                }

                SectionCard("Connection") {
                    DropdownField(
                        label = "Backend",
                        value = state.settings.transport,
                        options = state.platform.transportOptions,
                        labelOf = state.platform::transportLabel,
                        onSelect = { kind ->
                            state.updateSettings { it.copy(transport = kind) }
                        },
                    )
                    if (state.platform.transportSupportsScanning(state.settings.transport)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { scope.launch { state.scanBluetoothDevices() } },
                                enabled = !state.scanningBluetooth,
                                modifier = Modifier.testTag("bluetooth-scan"),
                            ) {
                                Text(if (state.scanningBluetooth) "Scanning…" else "Scan for devices")
                            }
                            if (state.scanningBluetooth) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                "Turn the printer on and keep it nearby.",
                                color = palette.muted,
                                fontSize = 10.sp,
                            )
                        }
                        if (state.bluetoothDevices.isNotEmpty()) {
                            FieldLabel("Nearby / known devices")
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 168.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                state.bluetoothDevices.forEach { device ->
                                    val selected = state.settings.mac.equals(
                                        device.address,
                                        ignoreCase = true,
                                    )
                                    SelectableRow(
                                        selected = selected,
                                        onClick = { state.selectBluetoothDevice(device) },
                                        modifier = Modifier.testTag(
                                            "bluetooth-device-${device.address}",
                                        ),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                device.displayName,
                                                color = palette.text,
                                                fontSize = 12.sp,
                                            )
                                            Text(
                                                device.address,
                                                color = palette.muted,
                                                fontSize = 10.sp,
                                            )
                                        }
                                        if (selected) {
                                            Text(
                                                "Selected",
                                                color = palette.accent,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        state.bluetoothScanError?.let { error ->
                            Text(error, color = palette.warning, fontSize = 10.sp)
                        }
                        TextFieldCompact(
                            value = state.settings.mac,
                            onValueChange = { value ->
                                state.updateSettings { it.copy(mac = value) }
                            },
                            label = "Printer Bluetooth address",
                        )
                    }
                    if (state.platform.transportUsesSerialDevice(state.settings.transport)) {
                        TextFieldCompact(
                            value = state.settings.device,
                            onValueChange = { value ->
                                state.updateSettings { it.copy(device = value) }
                            },
                            label = "Serial device",
                        )
                    }
                    if (state.settings.transport != TransportKind.DEMO) {
                        Text(
                            state.platform.transportDescription(state.settings.transport),
                            color = palette.muted,
                            fontSize = 10.sp,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.isConnected) {
                            OutlinedButton(
                                onClick = { scope.launch { state.disconnectPrinter() } },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("printer-disconnect"),
                            ) { Text("Disconnect") }
                        } else {
                            Button(
                                onClick = { scope.launch { state.connectPrinter() } },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("printer-connect"),
                            ) { Text(if (state.busy) "Connecting…" else "Connect") }
                        }
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            when (state.connection) {
                                ConnectionState.CONNECTED -> "Connected"
                                ConnectionState.CONNECTING -> "Connecting…"
                                ConnectionState.ERROR -> "Connection failed"
                                ConnectionState.DISCONNECTED -> "Disconnected"
                            },
                            color = when (state.connection) {
                                ConnectionState.CONNECTED -> palette.success
                                ConnectionState.ERROR -> palette.danger
                                ConnectionState.CONNECTING -> palette.warning
                                ConnectionState.DISCONNECTED -> palette.muted
                            },
                            fontSize = 11.sp,
                        )
                    }
                    state.connectionError?.let { error ->
                        Text(error, color = palette.danger, fontSize = 10.sp)
                    }
                }

                SectionCard("Status") {
                    val status = state.printerStatus
                    val statusColor = when {
                        status == null -> palette.muted
                        status == Status.READY.code -> palette.success
                        else -> palette.warning
                    }
                    KeyValueRow("Status", describeStatus(status), statusColor)
                    KeyValueRow(
                        "Battery",
                        state.printerBattery?.toString() ?: "—",
                        if ((state.printerBattery?.percent ?: 100) <= 20) palette.warning else null,
                    )
                    KeyValueRow("Configuration", state.printerConfig?.toString() ?: "—")
                    KeyValueRow("Label roll", state.printerLabel?.describe() ?: "—")
                    if (state.printerErrors.isNotEmpty()) {
                        state.printerErrors.forEach { error ->
                            Text(error, color = palette.danger, fontSize = 10.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { state.refreshPrinter() } },
                            enabled = state.isConnected && !state.busy,
                            modifier = Modifier.testTag("printer-refresh"),
                        ) { Text("Refresh", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { scope.launch { state.selfTest() } },
                            enabled = state.isConnected && !state.busy,
                            modifier = Modifier.testTag("printer-selftest"),
                        ) { Text("Self-test", fontSize = 12.sp) }
                        Button(
                            onClick = onPrint,
                            enabled = state.isConnected && state.preview != null && !state.busy,
                            modifier = Modifier.testTag("printer-print"),
                        ) { Text("Print", fontSize = 12.sp) }
                    }
                }

                Text(
                    state.platform.connectionHint,
                    color = palette.muted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
