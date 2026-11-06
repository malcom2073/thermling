from __future__ import annotations

import errno
from pathlib import Path

import pytest

from thermling import transport as transport_module
from thermling.transport import (
    BACKEND_LABELS,
    BACKEND_QT,
    BACKEND_SERIAL,
    BACKEND_SOCKET,
    Device,
    QtBluetoothTransport,
    SerialTransport,
    SocketTransport,
    TransportError,
    disconnect_bluetooth,
    make_transport,
    normalize_mac,
    parse_bluetoothctl_devices,
)


class FakeSocket:
    def __init__(self, chunks: list[bytes] | None = None) -> None:
        self.chunks = list(chunks or [])
        self.sent = bytearray()
        self.closed = False
        self.timeout = None
        self.recv_error: OSError | None = None

    def settimeout(self, timeout):
        self.timeout = timeout

    def sendall(self, payload: bytes) -> None:
        self.sent.extend(payload)

    def recv(self, size: int) -> bytes:
        if self.recv_error is not None:
            raise self.recv_error
        if self.chunks:
            return self.chunks.pop(0)
        raise TimeoutError

    def close(self) -> None:
        self.closed = True


def test_normalize_mac():
    assert normalize_mac(" e4:53:a2:5f:f1:e7 ") == "E4:53:A2:5F:F1:E7"
    for bad in ("", "E4:53:A2:5F:F1", "E4-53-A2-5F-F1-E7", "GG:53:A2:5F:F1:E7"):
        with pytest.raises(TransportError, match="Invalid Bluetooth address"):
            normalize_mac(bad)


def test_parse_bluetoothctl_devices():
    output = (
        "Device E4:53:A2:5F:F1:E7 P21\n"
        "Device AA:BB:CC:DD:EE:FF\n"
        "not a device line\n"
        "Device invalid\n"
        "Device 11:22:33:44:55:66 Printer With Spaces\n"
    )
    devices = parse_bluetoothctl_devices(output)
    assert devices == [
        Device("E4:53:A2:5F:F1:E7", "P21"),
        Device("AA:BB:CC:DD:EE:FF", ""),
        Device("11:22:33:44:55:66", "Printer With Spaces"),
    ]
    assert devices[0].display == "P21 (E4:53:A2:5F:F1:E7)"
    assert devices[1].display == "AA:BB:CC:DD:EE:FF"


def test_make_transport():
    socket_transport = make_transport(BACKEND_SOCKET, "E4:53:A2:5F:F1:E7")
    assert isinstance(socket_transport, SocketTransport)
    assert socket_transport.mac == "E4:53:A2:5F:F1:E7"

    serial_transport = make_transport(BACKEND_SERIAL, "", "/dev/ttyUSB7")
    assert isinstance(serial_transport, SerialTransport)
    assert serial_transport.device == "/dev/ttyUSB7"

    qt_transport = make_transport(BACKEND_QT, "E4:53:A2:5F:F1:E7")
    assert isinstance(qt_transport, QtBluetoothTransport)
    assert qt_transport.mac == "E4:53:A2:5F:F1:E7"

    with pytest.raises(ValueError, match="Unknown transport"):
        make_transport("carrier-pigeon")


def test_desktop_backend_labels():
    assert BACKEND_LABELS[BACKEND_SOCKET].startswith("Direct RFCOMM")
    assert BACKEND_LABELS[BACKEND_SERIAL].startswith("Serial device")


def test_qt_transport_friendly_errors():
    permission = QtBluetoothTransport._friendly_error("Permission denied")
    assert "permission" in permission.lower()
    service = QtBluetoothTransport._friendly_error("Service not found")
    assert "pair" in service.lower()
    generic = QtBluetoothTransport._friendly_error("")
    assert "Could not connect" in generic


def test_qt_transport_can_use_prefilled_buffer():
    transport = QtBluetoothTransport("E4:53:A2:5F:F1:E7")
    transport._buffer.extend(b"OK\r\nPAYLOAD")
    assert transport.read_line() == b"OK\r\n"
    assert transport.read_exact(7) == b"PAYLOAD"
    with pytest.raises(TransportError, match="Not connected"):
        transport.read_exact(1, timeout=0)


class PartialWriteSocket:
    """Accepts zero bytes once, then swallows everything on later writes."""

    def __init__(self) -> None:
        self.sent = bytearray()
        self.calls = 0

    def write(self, payload: bytes) -> int:
        self.calls += 1
        if self.calls == 1:
            return 0
        self.sent.extend(payload)
        return len(payload)

    def bytesToWrite(self) -> int:
        return 0

    def errorString(self) -> str:
        return "fake error"


def test_qt_transport_retries_partial_writes(monkeypatch):
    transport = QtBluetoothTransport("E4:53:A2:5F:F1:E7")
    sock = PartialWriteSocket()
    transport._socket = sock  # type: ignore[assignment]
    monkeypatch.setattr(
        QtBluetoothTransport, "is_open", property(lambda self: True)
    )
    monkeypatch.setattr(transport, "_wait_socket", lambda *args: True)

    transport.write(b"print job")

    assert bytes(sock.sent) == b"print job"
    assert sock.calls == 2


def test_qt_transport_reports_write_timeout(monkeypatch):
    transport = QtBluetoothTransport("E4:53:A2:5F:F1:E7")
    transport._socket = PartialWriteSocket()  # type: ignore[assignment]
    monkeypatch.setattr(
        QtBluetoothTransport, "is_open", property(lambda self: True)
    )
    monkeypatch.setattr(transport, "_wait_socket", lambda *args: False)

    with pytest.raises(TransportError, match="Timed out sending"):
        transport.write(b"print job")


def test_socket_transport_framing_from_buffer():
    transport = SocketTransport("E4:53:A2:5F:F1:E7")
    transport._socket = FakeSocket([])  # type: ignore[assignment]
    transport._buffer.extend(b"CONFIG abc\r\nREST")
    assert transport.read_line() == b"CONFIG abc\r\n"
    assert transport.read_exact(4) == b"REST"
    with pytest.raises(TransportError, match="Timed out"):
        transport.read_line(timeout=0)


def test_socket_transport_receives_and_sends():
    sock = FakeSocket([b"part", b"ial\r\n"])
    transport = SocketTransport("E4:53:A2:5F:F1:E7")
    transport._socket = sock  # type: ignore[assignment]
    assert transport.is_open
    transport.write(b"CONFIG?\r\n")
    assert bytes(sock.sent) == b"CONFIG?\r\n"
    assert transport.read_line(timeout=1) == b"partial\r\n"

    sock.chunks = [b"abc"]
    assert transport.read_optional(5, timeout=1) == b"abc"
    transport.close()
    assert sock.closed
    assert not transport.is_open


def test_socket_transport_reports_closed_and_errors():
    transport = SocketTransport("E4:53:A2:5F:F1:E7")
    with pytest.raises(TransportError, match="Not connected"):
        transport.write(b"x")
    with pytest.raises(TransportError, match="Not connected"):
        transport.read_exact(1)

    transport._socket = FakeSocket([b""])  # type: ignore[assignment]
    with pytest.raises(TransportError, match="closed"):
        transport.read_exact(1)

    transport._socket = FakeSocket([])  # type: ignore[assignment]
    transport._socket.recv_error = OSError(errno.ECONNRESET, "reset")
    with pytest.raises(TransportError, match="read failed"):
        transport.read_exact(1)


def test_socket_friendly_error_messages():
    busy = OSError(errno.EBUSY, "Device or resource busy")
    assert "already connected" in SocketTransport._friendly_error(busy)
    refused = OSError(errno.ECONNREFUSED, "Connection refused")
    assert "refused" in SocketTransport._friendly_error(refused)
    other = OSError(errno.ENOENT, "No such device")
    assert "Could not connect" in SocketTransport._friendly_error(other)


def test_socket_open_requires_bluetooth_support(monkeypatch):
    monkeypatch.delattr(transport_module.socket, "AF_BLUETOOTH", raising=False)
    transport = SocketTransport("E4:53:A2:5F:F1:E7")
    with pytest.raises(TransportError, match="no Bluetooth socket support"):
        transport.open()


def test_socket_open_failure_is_wrapped(monkeypatch):
    class FailingSocket(FakeSocket):
        def connect(self, address):
            raise OSError(errno.ECONNREFUSED, "Connection refused")

    monkeypatch.setattr(
        transport_module.socket, "socket", lambda *args, **kwargs: FailingSocket()
    )
    transport = SocketTransport("E4:53:A2:5F:F1:E7")
    with pytest.raises(TransportError, match="refused"):
        transport.open()
    assert not transport.is_open


def test_serial_open_missing_device_without_mac(tmp_path: Path):
    transport = SerialTransport(str(tmp_path / "missing-tty"))
    with pytest.raises(TransportError, match="does not exist"):
        transport.open()
    assert not transport.is_open


def test_serial_friendly_error_messages():
    message = SerialTransport._friendly_error(PermissionError("denied"))
    assert "dialout" in message
    message = SerialTransport._friendly_error(OSError("other"))
    assert "Cannot open the serial device" in message


def test_disconnect_bluetooth_without_binary(monkeypatch):
    monkeypatch.setattr(transport_module.shutil, "which", lambda _: None)
    assert disconnect_bluetooth("E4:53:A2:5F:F1:E7") is False


def test_disconnect_bluetooth_runs_bluetoothctl(monkeypatch):
    calls: list[list[str]] = []

    class Result:
        returncode = 0

    def fake_run(command, **kwargs):
        calls.append(command)
        return Result()

    monkeypatch.setattr(transport_module.shutil, "which", lambda name: "/usr/bin/bluetoothctl")
    monkeypatch.setattr(transport_module.subprocess, "run", fake_run)
    assert disconnect_bluetooth("e4:53:a2:5f:f1:e7") is True
    assert calls == [["/usr/bin/bluetoothctl", "disconnect", "E4:53:A2:5F:F1:E7"]]


def test_disconnect_bluetooth_handles_errors(monkeypatch):
    monkeypatch.setattr(transport_module.shutil, "which", lambda name: "/usr/bin/bluetoothctl")

    def fake_run(command, **kwargs):
        raise transport_module.subprocess.TimeoutExpired(command, 1)

    monkeypatch.setattr(transport_module.subprocess, "run", fake_run)
    assert disconnect_bluetooth("E4:53:A2:5F:F1:E7") is False
    assert disconnect_bluetooth("nope") is False
