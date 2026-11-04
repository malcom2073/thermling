"""Protocol primitives for the Nelko P21/P22 thermal label printer.

The printer speaks a small line-based command protocol over Bluetooth SPP
(RFCOMM).  Text commands end in CRLF; two status commands are raw ESC byte
sequences whose replies are raw bytes.  This module has no dependency on Qt or
on any particular transport, so everything here is directly testable.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from enum import IntEnum

BAUD = 115200
DEFAULT_DEVICE = "/dev/rfcomm0"
DEFAULT_DPI = 203
DEFAULT_HEAD_WIDTH_MM = 12.0

CMD_CONFIG = b"CONFIG?\r\n"
CMD_BATTERY = b"BATTERY?\r\n"
CMD_STATUS = b"\x1b!?\r\n"
CMD_LABEL = b"\x1b!o\r\n"
CMD_SELFTEST = b"SELFTEST\r\n"


class ProtocolError(Exception):
    """Raised when the printer sends a reply we cannot decode."""


# --- Enumerations from the protocol -----------------------------------------
class Status(IntEnum):
    """Printer status byte (reply to ``ESC ! ?``, byte 0 of the label block)."""

    READY = 0x00
    LID_OPEN = 0x01
    FEEDING = 0x02
    PAPER_ERROR = 0x03
    NO_PAPER = 0x04
    PAUSED = 0x10
    BUSY = 0x20
    OUT_OF_PAPER = 0x24
    LOW_BATTERY = 0x30
    OVERHEATED = 0x80
    NO_POWER = 0x90

    def __str__(self) -> str:
        return self.name.replace("_", " ").title()


class Color(IntEnum):
    """Label colour (byte 4 of the label block)."""

    UNKNOWN = 0
    TRANSPARENT = 2
    WHITE = 3
    PINK = 4
    BLUE = 5
    YELLOW = 6

    def __str__(self) -> str:
        return self.name.title()


class Style(IntEnum):
    """Label paper type (byte 7 of the label block)."""

    CONTINUOUS = 0
    GAPPED = 1
    BLACKMARK = 2

    def __str__(self) -> str:
        return self.name.title()


TIMER_TEXT = {0: "Never", 1: "15 minutes", 2: "30 minutes", 3: "60 minutes"}
MINUTES_TO_TIMER = {0: 0, 15: 1, 30: 2, 60: 3}


def _safe_enum(enum_cls, value, default):
    try:
        return enum_cls(value)
    except ValueError:
        return default


def describe_status(byte: int | None) -> str:
    """Human-readable text for a status byte."""
    if byte is None:
        return "Unknown"
    try:
        return str(Status(byte))
    except ValueError:
        return f"Unknown (0x{byte:02x})"


# --- Decoded responses --------------------------------------------------------
@dataclass(frozen=True)
class Config:
    """Device configuration, decoded from the ``CONFIG?`` payload."""

    dpi: int
    hardware_version: str
    firmware_version: str
    timer: int  # raw 0..3, see TIMER_TEXT
    beep: bool

    @property
    def timer_text(self) -> str:
        return TIMER_TEXT.get(self.timer, str(self.timer))

    def __str__(self) -> str:
        return (
            f"{self.dpi} dpi, firmware {self.firmware_version}"
            f" · auto-off {self.timer_text} · beep {'on' if self.beep else 'off'}"
        )


@dataclass(frozen=True)
class Battery:
    """Battery status, decoded from the ``BATTERY?`` payload."""

    percent: int
    charging: bool

    def __str__(self) -> str:
        suffix = " (charging)" if self.charging else ""
        return f"{self.percent}%{suffix}"


@dataclass(frozen=True)
class LabelRoll:
    """Loaded label roll, decoded from the 16-byte ``ESC ! o`` block."""

    width_mm: float
    length_mm: float
    gap_mm: float
    color: Color
    style: Style
    paper_code: str
    status: int = 0
    crc_ok: bool = True

    @property
    def has_tag(self) -> bool:
        return bool(self.paper_code)

    def describe(self) -> str:
        if not self.has_tag:
            return "No readable label tag"
        return (
            f"{self.width_mm:g} × {self.length_mm:g} mm · {self.style}"
            f" · {self.color} · gap {self.gap_mm:g} mm"
        )


# --- Response parsing ---------------------------------------------------------
def crc16(data: bytes) -> bytes:
    """CRC16/MODBUS (poly 0xA001, init 0xFFFF) as 2 big-endian bytes."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc.to_bytes(2, "big")


def _payload(reply: bytes, prefix: bytes, minimum: int) -> bytes:
    if not reply.startswith(prefix):
        raise ProtocolError(f"unexpected reply: {reply!r}")
    body = reply[len(prefix) :]
    if body.endswith(b"\r\n"):
        body = body[:-2]
    if len(body) < minimum:
        raise ProtocolError(f"short reply: {reply!r}")
    return body


def parse_config_reply(reply: bytes) -> Config:
    """Decode ``CONFIG `` + 10-byte payload + CRLF."""
    payload = _payload(reply, b"CONFIG ", 10)
    dpi = struct.unpack(">H", payload[0:2])[0]
    if dpi < 50 or dpi > 1200:
        raise ProtocolError(f"nonsensical DPI in CONFIG reply: {dpi}")
    return Config(
        dpi=dpi,
        hardware_version=f"{payload[2]}.{payload[3]}.{payload[4]}",
        firmware_version=f"{payload[5]}.{payload[6]}.{payload[7]}",
        timer=payload[8],
        beep=bool(payload[9]),
    )


def parse_battery_reply(reply: bytes) -> Battery:
    """Decode ``BATTERY `` + 2 bytes + CRLF (level is BCD)."""
    payload = _payload(reply, b"BATTERY ", 2)
    level = ((payload[0] >> 4) & 0x0F) * 10 + (payload[0] & 0x0F)
    return Battery(percent=level, charging=bool(payload[1]))


def parse_status_reply(reply: bytes) -> int:
    if not reply:
        raise ProtocolError("empty status reply")
    return reply[0]


def parse_label_reply(reply: bytes) -> LabelRoll:
    """Decode the 16-byte ``ESC ! o`` label block."""
    if len(reply) != 16:
        raise ProtocolError(f"expected a 16-byte label block, got {len(reply)} bytes")
    crc_ok = crc16(reply[:14]) == reply[14:16]
    fmt = reply[3] >> 4
    style_byte = reply[7] & 0x0F if fmt >= 2 else reply[7]
    tag = reply[2:14]
    return LabelRoll(
        width_mm=reply[13],
        length_mm=reply[11],
        gap_mm=reply[8] if (fmt >= 2 and reply[8]) else 5.0,
        color=_safe_enum(Color, reply[4], Color.UNKNOWN),
        style=_safe_enum(Style, style_byte, Style.CONTINUOUS),
        paper_code=tag.hex().upper() if any(tag) else "",
        status=reply[0],
        crc_ok=crc_ok,
    )


def parse_print_reply(reply: bytes) -> int | None:
    """Return the status byte of an optional 16-byte print reply, else None."""
    if len(reply) != 16 or crc16(reply[:14]) != reply[14:16]:
        return None
    return reply[0]


# --- Geometry helpers ---------------------------------------------------------
def mm_to_dots(mm: float, dpi: int) -> float:
    return mm / 25.4 * dpi


def dots_to_mm(dots: float, dpi: int) -> float:
    return dots * 25.4 / dpi


def bitmap_width_dots(printable_width_mm: float, dpi: int) -> int:
    """Round a physical width to a whole number of bytes of print head dots."""
    return max(1, round(mm_to_dots(printable_width_mm, dpi) / 8)) * 8


def printable_width_mm(label_width_mm: float, head_width_mm: float = DEFAULT_HEAD_WIDTH_MM) -> float:
    """The print head is 12 mm wide; wider labels are clipped by it."""
    return max(1.0, min(label_width_mm, head_width_mm))


# --- Job construction ---------------------------------------------------------
def build_job(
    *,
    label_width_mm: float,
    label_length_mm: float,
    gap_mm: float,
    style: Style | int,
    bitmap: bytes,
    width_dots: int,
    height_dots: int,
    density: int = 15,
    copies: int = 1,
    direction: tuple[int, int] = (1, 1),
) -> bytes:
    """Build the TSPL sequence SIZE, GAP, DIRECTION, DENSITY, CLS, BITMAP, PRINT."""
    style = Style(style)
    if width_dots <= 0 or width_dots % 8:
        raise ValueError("bitmap width must be a positive whole number of bytes")
    if height_dots <= 0:
        raise ValueError("bitmap height must be positive")
    if label_width_mm <= 0 or label_length_mm <= 0:
        raise ValueError("label size must be positive")
    if not 1 <= density <= 15:
        raise ValueError("density must be between 1 and 15")
    if copies < 1:
        raise ValueError("copies must be at least 1")
    row_bytes = width_dots // 8
    if len(bitmap) != row_bytes * height_dots:
        raise ValueError(
            f"bitmap is {len(bitmap)} bytes, expected {row_bytes * height_dots} "
            f"({row_bytes} bytes × {height_dots} rows)"
        )

    gap = gap_mm if style == Style.GAPPED else 0.0

    def number(value: float) -> str:
        return f"{value:g}"

    header = (
        f"\r\nSIZE {number(label_width_mm)} mm,{number(label_length_mm)} mm\r\n"
        f"GAP {number(gap)} mm,0 mm\r\n"
        f"DIRECTION {direction[0]},{direction[1]}\r\n"
        f"DENSITY {density}\r\n"
        "CLS\r\n"
        f"BITMAP 0,0,{row_bytes},{height_dots},1,"
    ).encode("ascii")
    return header + bitmap + f"\r\nPRINT {copies}\r\n".encode("ascii")
