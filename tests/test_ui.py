from __future__ import annotations

import base64
from pathlib import Path

import pytest
from PySide6.QtCore import QPointF, QRectF
from PySide6.QtGui import QColor, QImage
from PySide6.QtWidgets import QDialog

from labelwright.document import (
    LABEL_PRESETS,
    Halftone,
    HorizontalAlign,
    ImageElement,
    ImageFit,
    LabelDocument,
    Orientation,
    TextElement,
    VerticalAlign,
)
from labelwright.protocol import Battery, Color, Config, LabelRoll, Status, Style
from labelwright.render import render_document
from labelwright.settings import AppSettings
from labelwright.transport import BACKEND_SOCKET, Device
from labelwright.ui.main_window import MainWindow, _image_to_png_base64, _replace_image_data
from labelwright.ui.preferences import PreferencesDialog
from labelwright.ui.preview import PreviewPanel
from labelwright.ui.printer_panel import PrinterPanel


@pytest.fixture()
def window(qtbot, monkeypatch):
    monkeypatch.setattr("labelwright.ui.printer_panel.list_paired_devices", lambda: [])
    window = MainWindow()
    window.show()
    try:
        yield window
    finally:
        window.controller.mark_saved()
        window._service.stop()
        window.close()
        window.deleteLater()


def test_main_window_starts_with_default_document(window: MainWindow):
    assert window.windowTitle().endswith("Untitled — Labelwright")
    assert window.controller.document.width_mm == 14
    assert window.canvas.page_width_mm == 12
    assert window.canvas.page_height_mm == 40
    assert window.connection_status.text() == "Disconnected"
    assert not window.print_action.isEnabled()
    assert not window.delete_action.isEnabled()
    assert not window.duplicate_action.isEnabled()
    assert window.zoom_label.text().endswith("%")


def test_add_text_and_selection_actions(window: MainWindow):
    window.add_text_action.trigger()
    document = window.controller.document
    assert len(document.elements) == 1
    element = document.elements[0]
    assert isinstance(element, TextElement)
    assert window.canvas.selected_id() == element.id
    assert window.undo_action.isEnabled()
    assert window.delete_action.isEnabled()
    assert window.duplicate_action.isEnabled()
    assert window.inspector.kind_label.text() == "Text element"

    window.duplicate_action.trigger()
    assert len(document.elements) == 2
    assert window.canvas.selected_id() != element.id

    window.delete_action.trigger()
    assert len(window.controller.document.elements) == 1
    assert window.canvas.selected_id() == ""

    window.undo_action.trigger()
    assert len(window.controller.document.elements) == 2
    window.redo_action.trigger()
    assert len(window.controller.document.elements) == 1


def test_edit_text_in_inspector_and_undo(window: MainWindow):
    element = TextElement(text="Before", width_mm=8, height_mm=5)
    window.controller.add_element(element)
    window.canvas.select_element(element.id)
    window.inspector.set_element(element)
    window.inspector.text_edit.setPlainText("After")
    window.inspector.commit_pending()

    current = window.controller.document.find(element.id)
    assert current.text == "After"
    assert window.controller.is_dirty

    window.controller.undo()
    assert window.controller.document.find(element.id).text == "Before"


def test_canvas_drag_snaps_and_clamps(window: MainWindow):
    element = TextElement(x_mm=1, y_mm=1, width_mm=8, height_mm=5)
    window.controller.add_element(element)
    item = window.canvas._items[element.id]
    dpm = window.canvas.dots_per_mm

    item.setPos(QPointF(1.2 * dpm, 1.3 * dpm))
    assert element.x_mm == pytest.approx(1.0)
    assert element.y_mm == pytest.approx(1.5)

    item.setPos(QPointF(10_000, 10_000))
    assert element.x_mm == pytest.approx(4.0)
    assert element.y_mm == pytest.approx(35.0)


def test_canvas_resize_clamps_to_page(window: MainWindow):
    element = TextElement(x_mm=1, y_mm=1, width_mm=3, height_mm=3)
    window.controller.add_element(element)
    item = window.canvas._items[element.id]
    assert item is not None

    item._start_rect = QRectF(item.pos().x(), item.pos().y(), item._width_dots, item._height_dots)
    item._start_scene = QPointF(0, 0)
    item._handle = "br"
    item._apply_resize(QPointF(10_000, 10_000))

    assert element.x_mm == 1.0
    assert element.y_mm == 1.0
    assert element.width_mm == 11.0
    assert element.height_mm == 39.0


def test_canvas_zoom_and_grid(window: MainWindow):
    window.zoom_in_action.trigger()
    zoomed = window.canvas.zoom()
    window.zoom_out_action.trigger()
    assert window.canvas.zoom() < zoomed

    window.grid_action.setChecked(False)
    assert not window.canvas.grid_visible
    window.snap_action.setChecked(False)
    assert not window.canvas.snap_enabled
    window.snap_action.setChecked(True)
    assert window.canvas.snap_enabled


def test_inspector_preset_and_document_size(window: MainWindow):
    preset = LABEL_PRESETS[1]
    index = window.inspector.preset_combo.findText(preset.name)
    assert index > 0
    window.inspector._apply_preset(index)

    document = window.controller.document
    assert document.width_mm == preset.width_mm
    assert document.length_mm == preset.length_mm
    assert document.gap_mm == preset.gap_mm

    window.inspector.length_spin.setValue(25.0)
    window.inspector.commit_pending()
    assert document.length_mm == 25.0


def test_inspector_orientation_swaps_page_and_elements(window: MainWindow):
    element = TextElement(x_mm=1.0, y_mm=2.0, width_mm=8.0, height_mm=5.0)
    window.controller.add_element(element)

    combo = window.inspector.orientation_combo
    combo.setCurrentIndex(combo.findData(Orientation.LANDSCAPE))

    document = window.controller.document
    assert document.orientation is Orientation.LANDSCAPE
    assert window.canvas.page_width_mm == 40
    assert window.canvas.page_height_mm == 12
    assert "Printable area: 40 × 12 mm" in window.inspector.width_note.text()

    rotated = document.find(element.id)
    assert rotated.x_mm == pytest.approx(2.0)
    assert rotated.y_mm == pytest.approx(1.0)
    assert rotated.width_mm == pytest.approx(5.0)
    assert rotated.height_mm == pytest.approx(8.0)

    window.controller.undo()
    restored = window.controller.document
    assert restored.orientation is Orientation.PORTRAIT
    assert window.canvas.page_width_mm == 12
    assert window.canvas.page_height_mm == 40


def test_horizontal_preset_and_add_text(window: MainWindow):
    preset = next(p for p in LABEL_PRESETS if p.orientation is Orientation.LANDSCAPE)
    index = window.inspector.preset_combo.findText(preset.name)
    assert index > 0
    window.inspector._apply_preset(index)

    document = window.controller.document
    assert document.orientation is Orientation.LANDSCAPE
    assert window.canvas.page_width_mm == 40
    assert window.canvas.page_height_mm == 12

    window._add_text()
    element = document.elements[0]
    assert element.width_mm == pytest.approx(38.0)
    assert element.height_mm == pytest.approx(8.0)
    assert element.x_mm >= 0
    assert element.y_mm >= 0
    assert element.x_mm + element.width_mm <= 40
    assert element.y_mm + element.height_mm <= 12


def test_print_horizontal_document_rotates_bitmap(window: MainWindow, monkeypatch):
    preset = next(p for p in LABEL_PRESETS if p.orientation is Orientation.LANDSCAPE)
    window.inspector._apply_preset(window.inspector.preset_combo.findText(preset.name))
    window._add_text()

    window._connected = True
    window._printer_status = Status.READY
    window._printer_label = None
    window.preview_panel.set_connected(True)
    window._refresh_preview()
    result = window._last_result
    assert result is not None
    assert (result.width_dots, result.height_dots) == (96, 320)
    assert (result.preview.width(), result.preview.height()) == (320, 96)

    sent: list[bytes] = []
    monkeypatch.setattr(window._service, "print_job", sent.append)
    window._print()
    assert len(sent) == 1
    assert sent[0].startswith(b"\r\nSIZE 14 mm,40 mm\r\n")
    assert b"BITMAP 0,0,12,320,1," in sent[0]


def test_inspector_output_and_head_width(window: MainWindow):
    window.inspector.halftone_combo.setCurrentIndex(
        window.inspector.halftone_combo.findData(Halftone.FLOYD)
    )
    assert window.controller.document.halftone is Halftone.FLOYD

    window.inspector.thermal_check.setChecked(True)
    assert window.controller.document.thermal_correction

    window.inspector.set_head_width(12)
    assert "12 mm print head" in window.inspector.width_note.text()
    window.inspector.set_detected_label(None)
    assert "No readable roll tag" in window.inspector.roll_label.text()


def test_inspector_str_enum_combos(window: MainWindow):
    text = TextElement(text="Align", width_mm=8, height_mm=5)
    window.controller.add_element(text)
    window.inspector.set_element(text)

    window.inspector.align_combo.setCurrentIndex(
        window.inspector.align_combo.findData(HorizontalAlign.RIGHT)
    )
    window.inspector.valign_combo.setCurrentIndex(
        window.inspector.valign_combo.findData(VerticalAlign.BOTTOM)
    )
    assert text.align is HorizontalAlign.RIGHT
    assert text.valign is VerticalAlign.BOTTOM

    image = ImageElement(width_mm=8, height_mm=8)
    window.controller.add_element(image)
    window.inspector.set_element(image)
    window.inspector.fit_combo.setCurrentIndex(
        window.inspector.fit_combo.findData(ImageFit.COVER)
    )
    assert image.fit is ImageFit.COVER


def test_inspector_clamps_geometry_to_printable_width(window: MainWindow):
    window.controller.edit(
        lambda: setattr(window.controller.document, "width_mm", 40), "Resize"
    )
    element = TextElement(x_mm=1, y_mm=1, width_mm=8, height_mm=5)
    window.controller.add_element(element)
    window.inspector.set_element(element)

    window.inspector.x_spin.setValue(30)
    assert element.x_mm == pytest.approx(4.0)
    window.inspector.w_spin.setValue(30)
    assert element.width_mm == pytest.approx(8.0)
    assert element.x_mm + element.width_mm <= 12.0


def test_inspector_use_printer_roll(window: MainWindow):
    label = LabelRoll(
        width_mm=15,
        length_mm=30,
        gap_mm=3,
        color=Color.WHITE,
        style=Style.GAPPED,
        paper_code="AABBCC",
    )
    window.inspector.set_detected_label(label)
    assert window.inspector.use_roll_button.isEnabled()
    window.inspector.use_roll_button.click()

    document = window.controller.document
    assert (document.width_mm, document.length_mm, document.gap_mm) == (15, 30, 3)
    assert document.style is Style.GAPPED


def test_preview_panel_state(qtbot):
    panel = PreviewPanel()
    qtbot.addWidget(panel)
    result = render_document(LabelDocument())
    assert not panel.print_button.isEnabled()

    panel.set_result(result)
    assert "96 × 320 dots" in panel.info.text()
    assert not panel.print_button.isEnabled()

    panel.set_connected(True)
    assert panel.print_button.isEnabled()
    panel.set_busy(True)
    assert not panel.print_button.isEnabled()
    assert panel.print_button.text() == "Printing…"
    panel.set_busy(False)
    assert panel.print_button.isEnabled()

    panel.set_error("boom")
    assert not panel.print_button.isEnabled()
    assert panel.preview._message == "boom"


def test_preview_panel_roll_warning(qtbot):
    panel = PreviewPanel()
    qtbot.addWidget(panel)
    panel.set_document_size(14, 40)

    label = LabelRoll(
        width_mm=14,
        length_mm=30,
        gap_mm=5,
        color=Color.WHITE,
        style=Style.GAPPED,
        paper_code="AABBCC",
    )
    panel.set_detected_label(label)
    assert "printer roll is 14 × 30 mm" in panel.warning.text()
    assert not panel.warning.isHidden()

    matching = LabelRoll(
        width_mm=14,
        length_mm=40,
        gap_mm=5,
        color=Color.WHITE,
        style=Style.GAPPED,
        paper_code="AABBCC",
    )
    panel.set_detected_label(matching)
    assert panel.warning.isHidden()

    panel.set_detected_label(None)
    assert panel.warning.isHidden()


def test_printer_panel_device_and_errors(qtbot, monkeypatch):
    devices = [Device("E4:53:A2:5F:F1:E7", "P21"), Device("AA:BB:CC:DD:EE:FF", "Other")]
    monkeypatch.setattr(
        "labelwright.ui.printer_panel.list_paired_devices", lambda: devices
    )
    panel = PrinterPanel()
    qtbot.addWidget(panel)
    panel.set_settings("E4:53:A2:5F:F1:E7", BACKEND_SOCKET, "/dev/rfcomm0")
    assert panel.current_mac() == "E4:53:A2:5F:F1:E7"
    assert panel.backend() == BACKEND_SOCKET
    assert panel.connect_button.text() == "Connect"

    emitted: list[tuple[str, str, str]] = []
    panel.connectRequested.connect(lambda *args: emitted.append(args))

    panel.device_combo.setCurrentIndex(-1)
    panel.device_combo.setEditText("not a mac")
    panel.connect_button.click()
    assert emitted == []
    assert "Bluetooth address" in panel.error_label.text()
    assert not panel.error_label.isHidden()

    index = panel.device_combo.findData("E4:53:A2:5F:F1:E7")
    panel.device_combo.setCurrentIndex(index)
    panel.connect_button.click()
    assert emitted == [("E4:53:A2:5F:F1:E7", "socket", "/dev/rfcomm0")]
    assert panel.connect_button.text() == "Connecting…"
    assert not panel.connect_button.isEnabled()

    panel.set_connected(True)
    assert panel.connect_button.text() == "Disconnect"
    assert panel.refresh_button.isEnabled()
    panel.set_status(Status.READY)
    assert panel.state_text.text() == "Ready"
    panel.set_status(Status.NO_PAPER)
    assert panel.state_text.text() == "No Paper"

    panel.set_config(Config(203, "0.2.2", "4.2.14", 2, True))
    assert "203 dpi" in panel.config_value.text()
    assert panel.beep_check.isChecked()
    assert panel.timer_combo.currentData() == 30

    panel.set_battery(Battery(99, True))
    assert "99%" in panel.battery_value.text()
    assert "charging" in panel.battery_value.text()

    label = LabelRoll(
        width_mm=14,
        length_mm=40,
        gap_mm=5,
        color=Color.WHITE,
        style=Style.GAPPED,
        paper_code="011203000301121215280F0E",
    )
    panel.set_label(label)
    assert "14 × 40 mm" in panel.roll_value.text()
    assert "011203" in panel.roll_value.text()

    panel.set_connected(False)
    assert panel.state_text.text() == "Disconnected"
    assert panel.roll_value.text() == "—"

    panel.set_connection_failed("nope")
    assert panel.state_text.text() == "Connection failed"
    assert panel.error_label.text() == "nope"


def test_preferences_dialog_accepts_values(qtbot):
    settings = AppSettings(
        mac="E4:53:A2:5F:F1:E7",
        backend=BACKEND_SOCKET,
        auto_dpi=False,
        dpi_override=300,
        head_width_mm=12,
    )
    dialog = PreferencesDialog(settings)
    qtbot.addWidget(dialog)
    assert dialog.dpi_spin.isEnabled()
    dialog.auto_dpi_check.setChecked(True)
    assert not dialog.dpi_spin.isEnabled()

    dialog.auto_dpi_check.setChecked(False)
    dialog.dpi_spin.setValue(300)
    dialog.head_width_spin.setValue(11.5)
    dialog.mac_edit.setText("e4:53:a2:5f:f1:e7")
    dialog._accept()
    assert dialog.result() == QDialog.DialogCode.Accepted
    assert dialog.settings.mac == "E4:53:A2:5F:F1:E7"
    assert dialog.settings.dpi_override == 300
    assert dialog.settings.head_width_mm == 11.5


def test_preferences_dialog_rejects_invalid_mac(qtbot, monkeypatch):
    warnings: list[str] = []
    monkeypatch.setattr(
        "labelwright.ui.preferences.QMessageBox.warning",
        lambda *args: warnings.append(args[2]),
    )
    dialog = PreferencesDialog(AppSettings())
    qtbot.addWidget(dialog)
    dialog.mac_edit.setText("nope")
    dialog._accept()
    assert dialog.result() != QDialog.DialogCode.Accepted
    assert warnings and "should look like" in warnings[0]


def test_image_helper_embeds_png(tmp_path: Path):
    source = tmp_path / "logo.png"
    image = QImage(64, 32, QImage.Format.Format_RGB32)
    image.fill(QColor("red"))
    assert image.save(str(source), "PNG")

    encoded, width, height = _image_to_png_base64(str(source))
    assert (width, height) == (64, 32)
    assert base64.b64decode(encoded).startswith(b"\x89PNG")

    element = ImageElement(png_base64=encoded, source_name="logo.png")
    _replace_image_data(element, "bmV3", "new.png")
    assert element.png_base64 == "bmV3"
    assert element.source_name == "new.png"


def test_image_helper_rejects_unreadable_file(tmp_path: Path):
    path = tmp_path / "not-an-image.txt"
    path.write_text("hello", encoding="utf-8")
    with pytest.raises(ValueError):
        _image_to_png_base64(str(path))


def test_window_renders_image_document(window: MainWindow, tmp_path: Path):
    source = tmp_path / "dot.png"
    image = QImage(32, 32, QImage.Format.Format_RGB32)
    image.fill(QColor("black"))
    assert image.save(str(source), "PNG")
    encoded, _, _ = _image_to_png_base64(str(source))
    element = ImageElement(
        png_base64=encoded,
        source_name="dot.png",
        width_mm=8,
        height_mm=8,
        x_mm=1,
        y_mm=1,
    )
    window.controller.add_element(element)
    window._refresh_preview()
    assert window._last_result is not None
    assert window._last_result.halftone is Halftone.FLOYD
    assert window.preview_panel._result is not None


def test_effective_dpi_uses_printer_config(window: MainWindow):
    window.settings.auto_dpi = True
    window.settings.dpi_override = 203
    window._connected = False
    window._printer_config = Config(300, "0.2.2", "4.2.14", 2, False)
    assert window._effective_dpi() == 203

    window._connected = True
    assert window._effective_dpi() == 300

    window.settings.auto_dpi = False
    assert window._effective_dpi() == 203


def test_print_flow_builds_and_sends_job(window: MainWindow, monkeypatch):
    window.controller.add_element(
        TextElement(text="PRINT", x_mm=1, y_mm=1, width_mm=10, height_mm=8)
    )
    window._connected = True
    window._printer_status = Status.READY
    window._printer_label = None
    window.preview_panel.set_connected(True)
    window._refresh_preview()

    sent: list[bytes] = []
    monkeypatch.setattr(window._service, "print_job", sent.append)
    window._print()

    assert len(sent) == 1
    payload = sent[0]
    assert payload.startswith(b"\r\nSIZE 14 mm,40 mm\r\nGAP 5 mm,0 mm\r\n")
    assert b"BITMAP 0,0,12,320,1," in payload
    assert payload.endswith(b"\r\nPRINT 1\r\n")
    assert window._printing
    assert window.preview_panel.print_button.text() == "Printing…"


def test_save_and_reopen_document(window: MainWindow, tmp_path: Path):
    window.controller.add_element(TextElement(text="Saved", width_mm=8, height_mm=5))
    path = tmp_path / "saved.label.json"
    assert window._write_document(path)
    assert path.exists()
    assert not window.controller.is_dirty
    assert "saved.label.json" in window.windowTitle()

    loaded = LabelDocument.load(path)
    assert loaded.elements[0].text == "Saved"
    assert loaded.width_mm == 14


def test_new_document_uses_printer_roll(window: MainWindow, monkeypatch):
    window._printer_label = LabelRoll(
        width_mm=15,
        length_mm=30,
        gap_mm=3,
        color=Color.WHITE,
        style=Style.GAPPED,
        paper_code="AABBCC",
    )
    monkeypatch.setattr(window, "_maybe_save", lambda: True)
    window._new_document()

    document = window.controller.document
    assert (document.width_mm, document.length_mm, document.gap_mm) == (15, 30, 3)
    assert window._document_path is None


def test_add_text_respects_printable_width(window: MainWindow):
    window.controller.edit(
        lambda: setattr(window.controller.document, "width_mm", 40), "Resize"
    )
    window._add_text()
    element = window.controller.document.elements[0]
    assert element.width_mm <= 10.0
    assert element.x_mm >= 0
    assert element.x_mm + element.width_mm <= 12.0
