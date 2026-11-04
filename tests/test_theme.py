from __future__ import annotations

import pytest
from PySide6.QtGui import QPalette

from labelprinter import theme as theme_module
from labelprinter.document import LabelDocument, TextElement
from labelprinter.render import render_document
from labelprinter.theme import (
    DARK_THEME,
    LIGHT_THEME,
    PAGE_WHITE,
    apply_theme,
    build_palette,
    build_stylesheet,
    current_theme,
    refresh_theme,
)
from labelprinter.ui.main_window import MainWindow


@pytest.fixture()
def themed_app(qapp):
    original_palette = qapp.palette()
    original_stylesheet = qapp.styleSheet()
    yield qapp
    apply_theme(qapp, LIGHT_THEME)
    qapp.setPalette(original_palette)
    qapp.setStyleSheet(original_stylesheet)


def test_dark_palette_has_light_text_and_dark_surfaces():
    palette = build_palette(DARK_THEME)
    window_color = palette.color(QPalette.ColorRole.Window)
    text_color = palette.color(QPalette.ColorRole.WindowText)
    base_color = palette.color(QPalette.ColorRole.Base)

    assert window_color.lightness() < 64
    assert base_color.lightness() < 64
    assert text_color.lightness() > 160
    assert palette.color(QPalette.ColorRole.Highlight).name() == DARK_THEME.accent


def test_stylesheet_uses_theme_colours_and_label_white_is_not_overridden():
    light = build_stylesheet(LIGHT_THEME)
    dark = build_stylesheet(DARK_THEME)

    assert LIGHT_THEME.window in light
    assert DARK_THEME.window in dark
    assert "QLabel[role=\"hint\"]" in dark
    assert "QLabel#stateDot[state=\"ready\"]" in dark


def test_apply_theme_updates_palette_and_current_theme(themed_app):
    apply_theme(themed_app, DARK_THEME)
    assert current_theme() is DARK_THEME
    assert (
        themed_app.palette().color(QPalette.ColorRole.Window).name()
        == DARK_THEME.window
    )


def test_canvas_page_stays_white_in_dark_theme(monkeypatch, themed_app):
    monkeypatch.setattr("labelprinter.ui.printer_panel.list_paired_devices", lambda: [])
    apply_theme(themed_app, DARK_THEME)
    window = MainWindow()
    try:
        assert window.canvas.backgroundBrush().color().name() == DARK_THEME.canvas
        assert window.canvas._page.brush().color().name() == PAGE_WHITE
        assert window.canvas._shadow.brush().color().alpha() == DARK_THEME.shadow_alpha
    finally:
        window.controller.mark_saved()
        window._service.stop()
        window.close()
        window.deleteLater()


def test_refresh_theme_repaints_existing_custom_widgets(monkeypatch, themed_app):
    monkeypatch.setattr("labelprinter.ui.printer_panel.list_paired_devices", lambda: [])
    window = MainWindow()
    try:
        monkeypatch.setattr(theme_module, "detect_theme", lambda _app: DARK_THEME)
        refresh_theme(themed_app)
        assert current_theme() is DARK_THEME
        assert window.canvas.backgroundBrush().color().name() == DARK_THEME.canvas
        assert window.canvas._page.brush().color().name() == PAGE_WHITE
    finally:
        window.controller.mark_saved()
        window._service.stop()
        window.close()
        window.deleteLater()


def test_dark_theme_still_renders_black_text_on_white_label(themed_app):
    apply_theme(themed_app, DARK_THEME)
    document = LabelDocument()
    document.add(
        TextElement(
            text="TEST",
            x_mm=1,
            y_mm=1,
            width_mm=10,
            height_mm=8,
            font_size_pt=24,
        )
    )

    result = render_document(document, dpi=203)

    assert any(byte != 0xFF for byte in result.data)
    assert any(
        result.preview.pixelColor(x, y).value() < 128
        for x in range(result.preview.width())
        for y in range(0, result.preview.height(), 4)
    )
