from __future__ import annotations

import pytest

from labelprinter.protocol import (
    Battery,
    Color,
    ProtocolError,
    Status,
    Style,
    bitmap_width_dots,
    build_job,
    crc16,
    describe_status,
    parse_battery_reply,
    parse_config_reply,
    parse_label_reply,
    parse_print_reply,
    parse_status_reply,
    printable_width_mm,
)

LABEL_BLOCK = bytes.fromhex("000c011203000301121215280f0eed03")
CONFIG_PAYLOAD = bytes.fromhex("00cb00020204020e0201")


def test_crc16_known_label_vector():
    assert crc16(LABEL_BLOCK[:14]) == LABEL_BLOCK[14:16]
    assert crc16(bytes.fromhex("000c011203000301121215280f0e")).hex() == "ed03"


def test_parse_label_reply():
    label = parse_label_reply(LABEL_BLOCK)
    assert label.width_mm == 14
    assert label.length_mm == 40
    assert label.gap_mm == 5
    assert label.style is Style.GAPPED
    assert label.color is Color.WHITE
    assert label.paper_code == "011203000301121215280F0E"
    assert label.has_tag
    assert label.crc_ok
    assert label.status == 0


def test_parse_label_without_tag():
    payload = bytes(14)
    block = payload + crc16(payload)
    label = parse_label_reply(block)
    assert not label.has_tag
    assert label.width_mm == 0
    assert label.length_mm == 0
    assert label.style is Style.CONTINUOUS
    assert label.crc_ok


def test_parse_label_bad_crc_is_reported_but_still_decoded():
    broken = bytearray(LABEL_BLOCK)
    broken[13] = 99
    label = parse_label_reply(bytes(broken))
    assert not label.crc_ok
    assert label.width_mm == 99


def test_parse_label_wrong_length():
    with pytest.raises(ProtocolError):
        parse_label_reply(LABEL_BLOCK[:10])


def test_parse_config_10_and_20_byte_variants():
    reply = b"CONFIG " + CONFIG_PAYLOAD + b"\r\n"
    config = parse_config_reply(reply)
    assert config.dpi == 203
    assert config.hardware_version == "0.2.2"
    assert config.firmware_version == "4.2.14"
    assert config.timer == 2
    assert config.beep
    assert config.timer_text == "30 minutes"

    long_reply = b"CONFIG " + CONFIG_PAYLOAD + bytes(10) + b"\r\n"
    assert parse_config_reply(long_reply) == config


def test_parse_config_errors():
    with pytest.raises(ProtocolError):
        parse_config_reply(b"NOPE\r\n")
    with pytest.raises(ProtocolError):
        parse_config_reply(b"CONFIG \x00\x00\x00\r\n")
    with pytest.raises(ProtocolError):
        parse_config_reply(b"CONFIG " + bytes([0x0F, 0xA0]) + bytes(8) + b"\r\n")


def test_parse_battery_bcd_and_charging():
    battery = parse_battery_reply(b"BATTERY \x99\x01\r\n")
    assert battery == Battery(percent=99, charging=True)
    assert "charging" in str(battery)
    battery = parse_battery_reply(b"BATTERY \x42\x00\r\n")
    assert battery.percent == 42
    assert not battery.charging


def test_parse_status_and_descriptions():
    assert parse_status_reply(b"\x00") == Status.READY
    assert parse_status_reply(b"\x04") == Status.NO_PAPER
    assert describe_status(0) == "Ready"
    assert describe_status(0x24) == "Out Of Paper"
    assert describe_status(0x7F).startswith("Unknown (0x7f)")
    with pytest.raises(ProtocolError):
        parse_status_reply(b"")


def test_parse_print_reply():
    payload = bytes(14)
    reply = payload + crc16(payload)
    assert parse_print_reply(reply) == 0
    assert parse_print_reply(b"") is None
    assert parse_print_reply(reply[:-1] + b"\x00") is None


def test_geometry_helpers():
    assert printable_width_mm(14) == 12
    assert printable_width_mm(10) == 10
    assert bitmap_width_dots(12, 203) == 96
    assert bitmap_width_dots(10, 203) == 80
    assert bitmap_width_dots(12, 300) % 8 == 0


def _job(**overrides):
    values = {
        "label_width_mm": 14,
        "label_length_mm": 40,
        "gap_mm": 5,
        "style": Style.GAPPED,
        "bitmap": b"\xff" * (12 * 320),
        "width_dots": 96,
        "height_dots": 320,
        "density": 15,
        "copies": 2,
    }
    values.update(overrides)
    return build_job(**values)


def test_build_job_layout():
    payload = _job()
    assert payload.startswith(b"\r\nSIZE 14 mm,40 mm\r\nGAP 5 mm,0 mm\r\n")
    assert b"DIRECTION 1,1\r\nDENSITY 15\r\nCLS\r\n" in payload
    assert b"BITMAP 0,0,12,320,1," in payload
    assert payload.endswith(b"\r\nPRINT 2\r\n")
    assert len(payload) == len(_job(copies=2))


def test_build_job_continuous_roll_has_no_gap():
    payload = _job(style=Style.CONTINUOUS)
    assert b"GAP 0 mm,0 mm\r\n" in payload


@pytest.mark.parametrize(
    "overrides",
    [
        {"width_dots": 95},
        {"height_dots": 0},
        {"bitmap": b"\xff" * 10},
        {"density": 0},
        {"density": 16},
        {"copies": 0},
        {"label_width_mm": 0},
        {"label_length_mm": 0},
    ],
)
def test_build_job_validation(overrides):
    with pytest.raises(ValueError):
        _job(**overrides)
