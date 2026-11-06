# Thermling

Thermling is a label app for Nelko P21/P22 thermal label printers, written in
Python with PySide6. Lay out text and images on a label in millimetres, see a
faithful 1-bit preview of what the print head will produce, and send it over
Bluetooth. It runs on Linux and has an Android MVP build.

This is an independent tool built from the protocol documentation and example
script in
[`nelko-p21-print`](https://github.com/merlinschumacher/nelko-p21-print). It is
not affiliated with Nelko.

## Screenshots

![Thermling main window](MainApp.png)

*The composer, inspector, and live 1-bit print preview.*

<img src="label.jpeg" alt="A printed label made with Thermling" width="520">

*A label composed in Thermling and printed on a Nelko P21.*

## Features

- Visual composer with draggable, resizable text and image elements
- Vertical or horizontal label orientation, with horizontal presets; the editor
  and preview stay in the chosen orientation while the print job is rotated for
  the printer's fixed-width head
- Live 1-bpp preview with Floyd–Steinberg dithering or a hard threshold
- Label presets plus manual width × length override, for rolls with no readable
  NFC tag
- Reads the loaded roll (size, type, colour, paper code), battery, firmware and
  configuration from the printer
- Density and copy count, self-test, beep and auto-power-off controls
- Save/load label documents as portable JSON (images are embedded)
- Undo/redo, snapping, zoom, grid
- Follows the desktop light/dark theme; the label itself always stays white
- Desktop transports:
  - **Direct RFCOMM socket** (default) — the app opens Bluetooth channel 1
    itself. No root access or `/dev/rfcomm0` needed.
  - **`/dev/rfcomm0` serial** — opens an existing tty or asks `rfcomm` to
    connect one.
- Android MVP:
  - Touch-oriented tabbed layout for the label, inspector, preview and printer
  - Bluetooth RFCOMM/SPP through QtBluetooth, with runtime permission handling
  - Android system file picker support (`content://` URIs) for labels and images

## Requirements

- Linux with BlueZ
- Python 3.11+
- A paired P21/P22 printer
- A Python build with Bluetooth socket support (`socket.AF_BLUETOOTH`). The
  system Python on Debian/Ubuntu and many distro builds have it; some
  pyenv/standalone builds do not. `uv venv --python /usr/bin/python3.12` is a
  reliable way to pick the system interpreter.

Android builds need a Unix host (Linux or macOS), JDK 17, the Android SDK/NDK,
and the matching PySide6 and Shiboken Android wheels.

## Setup

```bash
cd ~/code/printer
uv venv --python /usr/bin/python3.12 .venv
uv sync
```

Run the app:

```bash
uv run thermling
# or
uv run python -m thermling
```

## Android MVP

The Android build keeps the same document, rendering and protocol code. It
adds a tabbed phone layout and uses QtBluetooth rather than Python's Bluetooth
socket module, which Android does not provide. Pair the printer in Android's
**Settings → Bluetooth** first; inside Thermling, tap **Scan**, choose it, and
grant the **Nearby devices** permission when prompted.

Build prerequisites:

- A Unix host (Linux or macOS) with **JDK 17**. `python-for-android` is pinned
  to that major version, so a newer JDK makes the build stop.
- Android SDK, build-tools, platform-tools and NDK r28c
- PySide6 and Shiboken **Android** wheels for `aarch64` (the debug APK target)

If your system only has a newer JDK, install JDK 17 in user space:

```bash
mkdir -p ~/.local/opt
curl -L 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' \
  -o /tmp/jdk17.tar.gz
tar -xzf /tmp/jdk17.tar.gz -C ~/.local/opt
export JAVA_HOME="$(find ~/.local/opt -maxdepth 1 -type d -name 'jdk-17*' | head -n1)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # should report 17
```

On Debian/Ubuntu with sudo, `sudo apt install openjdk-17-jdk` is equivalent.

Install the deployment dependencies and fetch matching Android wheels from
Qt's public download server. Run the downloads in the directory where you want
to keep the wheels:

```bash
uv sync --group android

mkdir -p ~/Downloads/thermling-android
cd ~/Downloads/thermling-android
curl -LO https://download.qt.io/official_releases/QtForPython/pyside6/pyside6-6.12.0-6.12.0-cp310-abi3-android_aarch64.whl
curl -LO https://download.qt.io/official_releases/QtForPython/shiboken6/shiboken6-6.12.0-6.12.0-cp310-abi3-android_aarch64.whl
cd -
```

The easiest way to get the Android NDK/SDK is Qt's download script. Run this
from the Thermling checkout; it stores both under `~/.pyside6_android_deploy`,
where the deploy tool finds them automatically:

```bash
git clone --depth 1 https://code.qt.io/pyside/pyside-setup ~/.cache/pyside-setup
uv run --group android python \
  ~/.cache/pyside-setup/tools/cross_compile_android/main.py \
  --download-only --skip-update --auto-accept-license -p aarch64
```

Then build from this repository (paths vary by wheel version):

```bash
uv run pyside6-android-deploy \
  --config-file pysidedeploy.spec \
  --wheel-pyside ~/Downloads/thermling-android/pyside6-6.12.0-6.12.0-cp310-abi3-android_aarch64.whl \
  --wheel-shiboken ~/Downloads/thermling-android/shiboken6-6.12.0-6.12.0-cp310-abi3-android_aarch64.whl
```

`pysidedeploy.spec` produces a debug APK in `dist/`. Install it with
`adb install`, then test Bluetooth on a physical device; emulators cannot use
Bluetooth. The Qt Bluetooth Android dependency automatically contributes the
`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and legacy location permissions to the
generated manifest. Thermling requests runtime access at startup, before
scanning, and again before connecting.

The Android MVP has these known limits:

- A printer already connected by another Android app must be disconnected there
  first; Android does not let Thermling take over another app's SPP link.
- If Android's permission prompt is permanently denied, grant **Nearby devices**
  in the system app settings and restart Thermling.
- The first APK is large because it bundles Qt and Python; this is expected.

## Bluetooth notes

The printer accepts one SPP connection at a time. Blueman often keeps a
`/dev/rfcomm0` link open, which prevents a second connection. The app's direct
socket backend therefore disconnects any existing link first (controlled by
**Preferences → Take over existing Bluetooth link**) and connects itself. When
the app closes, Blueman may reconnect the tty again.

To use the serial backend, your user must be able to open `/dev/rfcomm0`:

```bash
sudo usermod -aG dialout "$USER"   # then log out and back in
```

or use the default direct socket backend, which needs no device permissions.

The printer's paired address is remembered between runs. You can pick it from
the device list in the Printer panel or type it directly.

## Usage

1. Select the printer and press **Connect**.
2. Add text or an image with the toolbar buttons.
3. Choose **Orientation → Horizontal** for a wide label, or use a horizontal
   preset. Switching orientation rotates the existing layout with the page.
4. Drag to move, drag the corner handles to resize, edit details in the
   Inspector.
5. Check the live preview, choose density/copies, and press **Print**.

Documents use millimetres throughout. Label size (`SIZE`) and gap (`GAP`) come
from the document; the bitmap is rendered at the printer's DPI and capped to the
12 mm / 96-dot print head, matching the known behaviour of the hardware. For a
horizontal label the wide page is rotated 90° at print time so it fits through
the printer, while the editor and preview show the label the way it will read
after it is peeled off.

## Testing

```bash
QT_QPA_PLATFORM=offscreen uv run pytest
```

The protocol, document format, rendering pipeline and basic UI construction are
covered by tests. No printer is required.

## Project layout

```
src/thermling/
  protocol.py      printer commands, CRC, response decoding, job builder
  document.py      JSON document model (text/image elements, label settings)
  render.py        millimetre document -> 1-bpp printer bitmap
  theme.py         light/dark palettes and application stylesheet
  runtime.py       desktop/Android runtime detection
  permissions.py   Android Bluetooth runtime permission requests
  transport.py     RFCOMM socket, serial and QtBluetooth transports
  worker.py        printer I/O worker running on a QThread
  settings.py      persistent application preferences
  ui/              main window, canvas, inspector, preview, printer panel
main.py            entry point used by pyside6-android-deploy
pysidedeploy.spec  Android debug build configuration
tests/             protocol, rendering, document and UI tests
```
