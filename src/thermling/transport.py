"""Bluetooth transports for the label printer.

Two backends are supported:

* ``socket`` — open Bluetooth RFCOMM channel 1 directly.  This needs no root
  permissions and needs no ``/dev/rfcomm0`` tty; it is the default.
* ``serial`` — open an RFCOMM tty with pyserial.  If the tty does not exist and
  a MAC address is supplied, the transport starts ``rfcomm connect`` itself.

Both expose the same small command/reply interface used by ``worker.py``.
"""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import time
from contextlib import suppress
from dataclasses import dataclass

import serial

from .protocol import BAUD, DEFAULT_DEVICE

BACKEND_SOCKET = "socket"
BACKEND_SERIAL = "serial"
BACKEND_LABELS = {
    BACKEND_SOCKET: "Direct RFCOMM socket (no root)",
    BACKEND_SERIAL: "Serial device (/dev/rfcomm0)",
}

MAC_PATTERN = re.compile(r"^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$")


class TransportError(Exception):
    """Raised for connection, permission and framing failures."""


def normalize_mac(mac: str) -> str:
    mac = mac.strip().upper()
    if not MAC_PATTERN.match(mac):
        raise TransportError(
            f"Invalid Bluetooth address: {mac!r} (expected something like E4:53:A2:5F:F1:E7)"
        )
    return mac


@dataclass(frozen=True)
class Device:
    address: str
    name: str

    @property
    def display(self) -> str:
        return f"{self.name} ({self.address})" if self.name else self.address


class BaseTransport:
    def open(self) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    def close(self) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    def write(self, payload: bytes) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    def read_line(self, timeout: float = 3.0) -> bytes:  # pragma: no cover - abstract
        raise NotImplementedError

    def read_exact(self, count: int, timeout: float = 3.0) -> bytes:  # pragma: no cover
        raise NotImplementedError

    def read_optional(self, count: int, timeout: float = 1.0) -> bytes:  # pragma: no cover
        raise NotImplementedError

    def reset_input(self) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    @property
    def is_open(self) -> bool:  # pragma: no cover - abstract
        raise NotImplementedError

    def __enter__(self) -> BaseTransport:
        self.open()
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


class SocketTransport(BaseTransport):
    """RFCOMM channel 1 over a raw Bluetooth socket."""

    def __init__(self, mac: str, channel: int = 1, timeout: float = 6.0) -> None:
        self.mac = normalize_mac(mac)
        self.channel = channel
        self.timeout = timeout
        self._socket: socket.socket | None = None
        self._buffer = bytearray()

    @property
    def is_open(self) -> bool:
        return self._socket is not None

    def open(self) -> None:
        if self._socket is not None:
            return
        if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
            raise TransportError(
                "This Python build has no Bluetooth socket support. "
                "Run the app with a system Python that provides socket.AF_BLUETOOTH "
                "(for example: uv venv --python /usr/bin/python3.12)."
            )
        sock = socket.socket(
            socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM
        )
        sock.settimeout(self.timeout)
        try:
            sock.connect((self.mac, self.channel))
        except OSError as error:
            sock.close()
            raise TransportError(self._friendly_error(error)) from error
        sock.settimeout(None)
        self._socket = sock
        self._buffer.clear()

    def close(self) -> None:
        if self._socket is not None:
            with suppress(OSError):
                self._socket.close()
            self._socket = None
        self._buffer.clear()

    def write(self, payload: bytes) -> None:
        if self._socket is None:
            raise TransportError("Not connected to the printer")
        try:
            self._socket.sendall(payload)
        except OSError as error:
            raise TransportError(f"Could not send to the printer: {error}") from error

    def _receive(self, deadline: float) -> bool:
        if self._socket is None:
            raise TransportError("Not connected to the printer")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        self._socket.settimeout(remaining)
        try:
            chunk = self._socket.recv(4096)
        except TimeoutError:
            return False
        except OSError as error:
            raise TransportError(f"Bluetooth read failed: {error}") from error
        if not chunk:
            raise TransportError("The printer closed the Bluetooth connection")
        self._buffer.extend(chunk)
        return True

    def read_line(self, timeout: float = 3.0) -> bytes:
        deadline = time.monotonic() + timeout
        while True:
            index = self._buffer.find(b"\r\n")
            if index >= 0:
                end = index + 2
                line = bytes(self._buffer[:end])
                del self._buffer[:end]
                return line
            if not self._receive(deadline):
                raise TransportError("Timed out waiting for a reply from the printer")

    def read_exact(self, count: int, timeout: float = 3.0) -> bytes:
        deadline = time.monotonic() + timeout
        while len(self._buffer) < count:
            if not self._receive(deadline):
                raise TransportError(
                    f"Timed out waiting for {count} bytes from the printer "
                    f"(got {len(self._buffer)})"
                )
        result = bytes(self._buffer[:count])
        del self._buffer[:count]
        return result

    def read_optional(self, count: int, timeout: float = 1.0) -> bytes:
        deadline = time.monotonic() + timeout
        while len(self._buffer) < count:
            if not self._receive(deadline):
                break
        result = bytes(self._buffer[:count])
        del self._buffer[:count]
        return result

    def reset_input(self) -> None:
        self._buffer.clear()
        if self._socket is None:
            return
        self._socket.settimeout(0.0)
        try:
            while True:
                if not self._socket.recv(4096):
                    break
        except OSError:
            pass
        finally:
            if self._socket is not None:
                self._socket.settimeout(None)

    @staticmethod
    def _friendly_error(error: OSError) -> str:
        text = str(error)
        if getattr(error, "errno", None) in {16, 98}:  # EBUSY, EADDRINUSE
            return (
                "The printer is already connected (Bluetooth allows only one SPP "
                "connection). Enable “Take over existing Bluetooth link” in "
                "Preferences, or disconnect the other link and retry."
            )
        if getattr(error, "errno", None) in {111, 112, 113}:  # ECONNREFUSED / EHOSTDOWN
            return (
                "The printer refused or could not be reached. Check that it is powered "
                f"on and in range. ({text})"
            )
        return f"Could not connect to the printer: {text}"


class SerialTransport(BaseTransport):
    """RFCOMM tty transport using pyserial."""

    def __init__(
        self,
        device: str = DEFAULT_DEVICE,
        mac: str = "",
        baud: int = BAUD,
        timeout: float = 3.0,
    ) -> None:
        self.device = device
        self.mac = normalize_mac(mac) if mac else ""
        self.baud = baud
        self.timeout = timeout
        self._port: serial.Serial | None = None
        self._rfcomm: subprocess.Popen[str] | None = None

    @property
    def is_open(self) -> bool:
        return self._port is not None and self._port.is_open

    def open(self) -> None:
        if self.is_open:
            return
        spawned = False
        if not os.path.exists(self.device):
            if not self.mac:
                raise TransportError(
                    f"{self.device} does not exist. Connect it with “rfcomm connect "
                    f"{self.device} <MAC>” or enter the printer's address."
                )
            self._start_rfcomm()
            spawned = True
        try:
            self._port = serial.Serial(self.device, self.baud, timeout=self.timeout)
        except (serial.SerialException, OSError) as error:
            self._stop_rfcomm()
            raise TransportError(self._friendly_error(error)) from error
        if spawned:
            time.sleep(0.4)

    def _start_rfcomm(self) -> None:
        executable = shutil.which("rfcomm")
        if not executable:
            raise TransportError(
                "The rfcomm tool is not installed. Install BlueZ utilities or use the "
                "direct RFCOMM socket backend."
            )
        try:
            self._rfcomm = subprocess.Popen(
                [executable, "connect", self.device, self.mac],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True,
            )
        except OSError as error:
            raise TransportError(f"Could not start rfcomm: {error}") from error

        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if os.path.exists(self.device):
                return
            if self._rfcomm.poll() is not None:
                stderr = (self._rfcomm.stderr.read() if self._rfcomm.stderr else "").strip()
                self._rfcomm = None
                raise TransportError(
                    "rfcomm could not create the serial device. "
                    + (stderr or "Try the direct RFCOMM socket backend instead.")
                )
            time.sleep(0.1)
        self._stop_rfcomm()
        raise TransportError("Timed out waiting for rfcomm to create the serial device")

    def _stop_rfcomm(self) -> None:
        process = self._rfcomm
        self._rfcomm = None
        if process is None or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            process.kill()

    def close(self) -> None:
        if self._port is not None:
            with suppress(serial.SerialException, OSError):
                self._port.close()
            self._port = None
        self._stop_rfcomm()

    def write(self, payload: bytes) -> None:
        if self._port is None or not self._port.is_open:
            raise TransportError("Not connected to the printer")
        try:
            self._port.write(payload)
            self._port.flush()
        except (serial.SerialException, OSError) as error:
            raise TransportError(f"Could not send to the printer: {error}") from error

    def read_line(self, timeout: float = 3.0) -> bytes:
        if self._port is None:
            raise TransportError("Not connected to the printer")
        self._port.timeout = timeout
        try:
            line = self._port.readline()
        except (serial.SerialException, OSError) as error:
            raise TransportError(f"Serial read failed: {error}") from error
        if not line:
            raise TransportError("Timed out waiting for a reply from the printer")
        if not line.endswith(b"\n"):
            raise TransportError(f"Incomplete reply from the printer: {line!r}")
        return line

    def read_exact(self, count: int, timeout: float = 3.0) -> bytes:
        if self._port is None:
            raise TransportError("Not connected to the printer")
        deadline = time.monotonic() + timeout
        data = bytearray()
        while len(data) < count:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TransportError(
                    f"Timed out waiting for {count} bytes from the printer "
                    f"(got {len(data)})"
                )
            self._port.timeout = remaining
            try:
                data.extend(self._port.read(count - len(data)))
            except (serial.SerialException, OSError) as error:
                raise TransportError(f"Serial read failed: {error}") from error
        return bytes(data)

    def read_optional(self, count: int, timeout: float = 1.0) -> bytes:
        if self._port is None:
            raise TransportError("Not connected to the printer")
        self._port.timeout = timeout
        try:
            return bytes(self._port.read(count))
        except (serial.SerialException, OSError) as error:
            raise TransportError(f"Serial read failed: {error}") from error

    def reset_input(self) -> None:
        if self._port is not None:
            self._port.reset_input_buffer()

    @staticmethod
    def _friendly_error(error: Exception) -> str:
        text = str(error)
        if isinstance(error, PermissionError) or "permission" in text.lower():
            return (
                f"Cannot open the serial device: {text}. Add your user to the dialout "
                "group (sudo usermod -aG dialout $USER, then log in again), or use the "
                "direct RFCOMM socket backend."
            )
        return f"Cannot open the serial device: {text}"


def make_transport(
    backend: str, mac: str = "", device: str = DEFAULT_DEVICE
) -> BaseTransport:
    if backend == BACKEND_SOCKET:
        return SocketTransport(mac)
    if backend == BACKEND_SERIAL:
        return SerialTransport(device, mac)
    raise ValueError(f"Unknown transport backend: {backend!r}")


# --- BlueZ device discovery ---------------------------------------------------
def parse_bluetoothctl_devices(output: str) -> list[Device]:
    devices: list[Device] = []
    for line in output.splitlines():
        parts = line.strip().split(maxsplit=2)
        if len(parts) >= 2 and parts[0] == "Device" and MAC_PATTERN.match(parts[1]):
            name = parts[2] if len(parts) > 2 else ""
            devices.append(Device(parts[1].upper(), name))
    return devices


def list_paired_devices(timeout: float = 5.0) -> list[Device]:
    executable = shutil.which("bluetoothctl")
    if not executable:
        return []
    try:
        result = subprocess.run(
            [executable, "devices"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_bluetoothctl_devices(result.stdout)


def disconnect_bluetooth(mac: str, timeout: float = 8.0) -> bool:
    """Disconnect any existing link to ``mac`` (used for take-over)."""
    executable = shutil.which("bluetoothctl")
    if not executable:
        return False
    try:
        result = subprocess.run(
            [executable, "disconnect", normalize_mac(mac)],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired, TransportError):
        return False
    return result.returncode == 0
