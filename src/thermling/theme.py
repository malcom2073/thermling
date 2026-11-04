"""Application colour themes.

The app follows the operating system's light/dark preference.  Only the
interface changes: the label canvas and print preview stay white, because that
is the paper colour the thermal head prints on.
"""

from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication, QWidget

PAGE_WHITE = "#ffffff"


@dataclass(frozen=True)
class Theme:
    name: str
    dark: bool
    window: str
    surface: str
    surface_alt: str
    border: str
    text: str
    muted: str
    accent: str
    accent_hover: str
    accent_pressed: str
    accent_disabled: str
    on_accent: str
    success: str
    warning: str
    danger: str
    canvas: str
    preview_bg: str
    page_edge: str
    grid: str
    grid_minor_alpha: int
    grid_major_alpha: int
    shadow_alpha: int


LIGHT_THEME = Theme(
    name="light",
    dark=False,
    window="#f4f6f9",
    surface="#ffffff",
    surface_alt="#f7f8fa",
    border="#d7dce4",
    text="#1f2937",
    muted="#6b7280",
    accent="#1a73e8",
    accent_hover="#1765cc",
    accent_pressed="#1257ad",
    accent_disabled="#a9c4ee",
    on_accent="#ffffff",
    success="#2e7d32",
    warning="#9a5b00",
    danger="#b3261e",
    canvas="#e9ecf1",
    preview_bg="#dfe3ea",
    page_edge="#aeb6c2",
    grid="#0f172a",
    grid_minor_alpha=18,
    grid_major_alpha=45,
    shadow_alpha=45,
)

DARK_THEME = Theme(
    name="dark",
    dark=True,
    window="#111318",
    surface="#1c1f26",
    surface_alt="#232730",
    border="#3a404c",
    text="#e7e9ee",
    muted="#9aa3b2",
    accent="#7cb0ff",
    accent_hover="#96c0ff",
    accent_pressed="#629cf0",
    accent_disabled="#3b4b63",
    on_accent="#0d1524",
    success="#74d187",
    warning="#f3b75f",
    danger="#ff8d84",
    canvas="#0d0f13",
    preview_bg="#171a20",
    page_edge="#555d6c",
    grid="#dbe3ef",
    grid_minor_alpha=14,
    grid_major_alpha=40,
    shadow_alpha=130,
)

_current_theme = LIGHT_THEME


def current_theme() -> Theme:
    return _current_theme


def _window_is_dark(app: QApplication) -> bool:
    hints = app.styleHints()
    get_scheme = getattr(hints, "colorScheme", None)
    if callable(get_scheme):
        scheme = get_scheme()
        if scheme == Qt.ColorScheme.Dark:
            return True
        if scheme == Qt.ColorScheme.Light:
            return False
    return app.palette().color(QPalette.ColorRole.Window).lightness() < 128


def detect_theme(app: QApplication) -> Theme:
    return DARK_THEME if _window_is_dark(app) else LIGHT_THEME


def build_palette(theme: Theme) -> QPalette:
    palette = QPalette()
    colors = {
        QPalette.ColorRole.Window: theme.window,
        QPalette.ColorRole.WindowText: theme.text,
        QPalette.ColorRole.Base: theme.surface,
        QPalette.ColorRole.AlternateBase: theme.surface_alt,
        QPalette.ColorRole.ToolTipBase: theme.surface,
        QPalette.ColorRole.ToolTipText: theme.text,
        QPalette.ColorRole.Text: theme.text,
        QPalette.ColorRole.Button: theme.surface_alt,
        QPalette.ColorRole.ButtonText: theme.text,
        QPalette.ColorRole.BrightText: theme.danger,
        QPalette.ColorRole.Link: theme.accent,
        QPalette.ColorRole.LinkVisited: theme.accent,
        QPalette.ColorRole.Highlight: theme.accent,
        QPalette.ColorRole.HighlightedText: theme.on_accent,
        QPalette.ColorRole.PlaceholderText: theme.muted,
        QPalette.ColorRole.Mid: theme.border,
        QPalette.ColorRole.Midlight: theme.border,
        QPalette.ColorRole.Dark: theme.border,
        QPalette.ColorRole.Light: theme.surface_alt,
        QPalette.ColorRole.Shadow: "#000000",
    }
    for role, value in colors.items():
        palette.setColor(role, QColor(value))
    if hasattr(QPalette.ColorRole, "Accent"):
        palette.setColor(QPalette.ColorRole.Accent, QColor(theme.accent))

    disabled = QColor(theme.muted)
    disabled.setAlpha(150)
    for role in (
        QPalette.ColorRole.WindowText,
        QPalette.ColorRole.Text,
        QPalette.ColorRole.ButtonText,
    ):
        palette.setColor(QPalette.ColorGroup.Disabled, role, disabled)
    palette.setColor(
        QPalette.ColorGroup.Disabled,
        QPalette.ColorRole.Window,
        QColor(theme.window),
    )
    palette.setColor(
        QPalette.ColorGroup.Disabled,
        QPalette.ColorRole.Base,
        QColor(theme.surface),
    )
    palette.setColor(
        QPalette.ColorGroup.Disabled,
        QPalette.ColorRole.Button,
        QColor(theme.surface_alt),
    )
    return palette


def build_stylesheet(theme: Theme) -> str:
    return f"""
QMainWindow, QDialog {{ background: {theme.window}; color: {theme.text}; }}
QToolBar {{
    border: none;
    spacing: 4px;
    padding: 3px;
    background: {theme.surface_alt};
}}
QToolBar::separator {{
    background: {theme.border};
    width: 1px;
    margin: 4px 6px;
}}
QDockWidget::title {{
    background: {theme.surface_alt};
    padding: 6px 8px;
    font-weight: 600;
    color: {theme.text};
}}
QGroupBox {{
    border: 1px solid {theme.border};
    border-radius: 6px;
    margin-top: 12px;
    padding-top: 8px;
    background: {theme.surface};
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    left: 8px;
    padding: 0 3px;
    color: {theme.text};
    background: {theme.surface};
}}
QPushButton#primaryButton {{
    background: {theme.accent};
    color: {theme.on_accent};
    border: none;
    border-radius: 4px;
    padding: 6px 14px;
    font-weight: 600;
}}
QPushButton#primaryButton:hover {{ background: {theme.accent_hover}; }}
QPushButton#primaryButton:pressed {{ background: {theme.accent_pressed}; }}
QPushButton#primaryButton:disabled {{
    background: {theme.accent_disabled};
    color: {theme.muted};
}}
QStatusBar {{ background: {theme.surface_alt}; color: {theme.text}; }}
QToolTip {{
    background: {theme.surface};
    color: {theme.text};
    border: 1px solid {theme.border};
    padding: 4px;
}}
QLabel[role="muted"] {{ color: {theme.muted}; }}
QLabel[role="hint"] {{ color: {theme.muted}; font-size: 11px; }}
QLabel[role="info"] {{ color: {theme.muted}; font-size: 11px; }}
QLabel[role="error"] {{ color: {theme.danger}; font-size: 11px; }}
QLabel[role="warning"] {{ color: {theme.warning}; font-size: 11px; }}
QLabel[role="success"] {{ color: {theme.success}; font-weight: 600; }}
QLabel[role="danger"] {{ color: {theme.danger}; font-weight: 600; }}
QLabel[role="heading"] {{ font-weight: 600; }}
QLabel#stateDot {{ font-size: 16px; }}
QLabel#stateDot[state="disconnected"] {{ color: {theme.muted}; }}
QLabel#stateDot[state="connecting"] {{ color: {theme.warning}; }}
QLabel#stateDot[state="connected"] {{ color: {theme.success}; }}
QLabel#stateDot[state="ready"] {{ color: {theme.success}; }}
QLabel#stateDot[state="warning"] {{ color: {theme.warning}; }}
QLabel#stateDot[state="error"] {{ color: {theme.danger}; }}
"""


def repolish(widget: QWidget) -> None:
    widget.style().unpolish(widget)
    widget.style().polish(widget)
    widget.update()


def apply_theme(app: QApplication, theme: Theme | None = None) -> Theme:
    """Apply *theme* (or the detected system theme) to the whole application."""
    global _current_theme
    theme = theme or detect_theme(app)
    _current_theme = theme
    app.setPalette(build_palette(theme))
    app.setStyleSheet(build_stylesheet(theme))
    return theme


def refresh_theme(app: QApplication, theme: Theme | None = None) -> Theme:
    """Re-apply the theme and refresh custom-painted widgets."""
    theme = apply_theme(app, theme)
    for widget in app.allWidgets():
        custom = getattr(widget, "apply_theme", None)
        if callable(custom):
            custom()
        else:
            repolish(widget)
    return theme


def install_theme(app: QApplication, initial: Theme | None = None) -> Theme:
    """Apply the system theme and follow later light/dark changes.

    ``initial`` lets the caller detect the platform theme before changing the
    Qt style, which can reset the palette on some desktops.
    """
    theme = refresh_theme(app, initial or detect_theme(app))
    hints = app.styleHints()
    changed = getattr(hints, "colorSchemeChanged", None)
    if changed is not None:
        changed.connect(lambda _scheme: refresh_theme(app))
    return theme
