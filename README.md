# Thermling

Thermling is a label composer for the Nelko P21/P22 thermal printer. This
repository is the **Compose Multiplatform desktop MVP**, kept separate from the
working PySide6 application so the production app is untouched while the shared
Kotlin rewrite is validated.

## What works in this MVP

- 14 × 40 mm P21 stock by default, with the 12 mm print-head width cap.
- Vertical and horizontal page layouts; horizontal labels are rotated into the
  printer's fixed head orientation and rotated back for the preview.
- Text and embedded PNG images, drag-to-move, resize handle, alignment, styling,
  and line spacing.
- Presets, manual dimensions, paper style, halftone mode, thermal correction,
  print density, copies, and printer-reported DPI.
- Undo/redo with one history entry per drag/resize gesture.
- Schema-v2 document save/open; schema-v1 documents load with defaults.
- Printer status, configuration, battery, label-roll tag, and self-test queries.
- Print flow with a FIFO print queue in the shared client layer.
- Demo printer backend for trying the full UI and print flow without hardware.
- Linux transports: direct RFCOMM socket over a raw Bluetooth socket (no root
  and no `/dev/rfcomm` needed, the default), BlueZ `rfcomm` plus a serial device,
  or an existing serial device such as `/dev/rfcomm0`.
- Bluetooth device scanning in the printer dialog, using BlueZ `bluetoothctl`, so
  the printer can be selected instead of typing its address.
- Dark/light OS theme with a white label page in both themes.

The bitmap preview is the authoritative output. The on-canvas text layout is an
approximation because the printer's font rasteriser differs from AWT/Compose.

## Requirements

- Linux desktop session (X11 or Wayland/XWayland).
- JDK 17 or newer on `JAVA_HOME` or `PATH`.
- For hardware printing: BlueZ and a paired P21/P22. The default direct socket
  backend needs no root and no serial-device access; the `rfcomm`/serial
  backends need access to the RFCOMM device (usually the `dialout` group).
- The Gradle wrapper downloads the remaining build dependencies.

## Run

```bash
./gradlew run
```

If your shell does not select a JDK with a compiler:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew run
```

Select **Printer… → Backend → Demo printer (no hardware)** to exercise connect,
status, battery, preview, and print without a printer.

For real hardware, pair the printer first:

```bash
bluetoothctl
# pair E4:53:A2:5F:F1:E7   (example address)
```

Then open **Printer…**, leave **Backend** on **Direct RFCOMM socket (no root)**,
click **Scan for devices**, and select the printer from the list. The scan runs
`bluetoothctl --timeout 6 --monitor scan on` and merges the results with
`bluetoothctl devices`, so the last-known printer stays selectable while it is
asleep. Likely printers are listed first. Press **Connect** after selecting one:
Thermling opens RFCOMM channel 1 directly through a Bluetooth socket, with no
root privileges and no `/dev/rfcomm` tty.

If direct sockets are unavailable, choose **rfcomm helper + serial device**.
Thermling then starts `rfcomm connect` and opens the configured serial device.
Alternatively create the serial device outside the app and choose **Existing
serial device**, or type an address manually if scanning is unavailable.

Scanning requires BlueZ's `bluetoothctl`, an enabled Bluetooth adapter, and a
printer that is awake and nearby. On systems where the adapter is blocked by
rfkill or the user cannot access BlueZ, the dialog reports the scan error and the
manual address field remains available.

## Test and build

```bash
./gradlew desktopTest
./gradlew packageDeb       # or packageRpm / packageAppImage
```

The tests cover protocol framing and parsing, document serialization, transport
behavior, the renderer's packed bitmap and landscape rotation, state/undo/print
flow, and the demo transport. Transport tests cover the JNA/libc RFCOMM socket,
including address byte order, framing, timeouts, disconnects, and connection
errors. The BlueZ scanner tests cover ANSI-colored `bluetoothctl` output,
device-list merging, deduplication, blank names, and printer-first ordering.
Compose UI tests also drive the real toolbar and printer dialog: adding text,
opening the dialog from both entry points, scanning and selecting a Bluetooth
device, reporting scan failures, connecting to the demo backend, refreshing
status, and printing. They run headless and do not require a display, Bluetooth
adapter, or printer.

An optional hardware smoke test queries the printer's status over a real direct
socket when a MAC address is supplied:

```bash
./gradlew desktopTest -Dthermling.btMac=E4:53:A2:5F:F1:E7 \
  --tests 'com.thermling.RfcommSocketTransportTest.hardwarePrinterAnswersStatusWhenConfigured'
```

## Project layout

```text
src/commonMain/kotlin/com/thermling/
  app/         document editing, undo/redo, connection and print state
  model/       schema-v2 model and codec
  platform/    platform interfaces and settings
  protocol/    TSPL commands, replies, CRC, geometry, job builder
  render/      renderer interface and preview result
  transport/   shared buffered transport, printer client boundary, fake printer
  ui/          Compose UI shared by desktop and future mobile targets

src/commonTest/         protocol, document, transport, and state tests
src/desktopMain/kotlin/com/thermling/
  platform/    file dialogs, image loading, preferences, BlueZ scanning, transports
  render/      AWT rasterizer and preview conversion
  transport/   JNA/libc RFCOMM socket, jSerialComm serial, and BlueZ rfcomm backends
src/desktopTest/        AWT renderer, transport, and Compose UI tests
```

`commonMain` is free of JVM-only imports, so the same model, protocol, transport
contract, state, and UI can be reused by an Android target. Android has not yet
been added to this Gradle build; the PySide6 Android MVP remains the working
mobile app for now.

## Known limitations

- Android target and Android Bluetooth transport are not part of this build yet.
- Text rasterization is approximate; use the bitmap preview for final output.
- Labels wider than the configured head width are clipped, not scaled.
- The default direct RFCOMM socket is Linux-only. On other desktops choose a
  serial backend or the demo backend.
- RFCOMM setup through `rfcomm` depends on BlueZ and the `rfcomm` executable;
  pairing is not done inside Thermling.
- Bluetooth scanning uses `bluetoothctl` (Linux only); platforms without it still
  support manual entry and the demo backend.
- Configuration cache is disabled until the Kotlin/Java task graph supports it.
