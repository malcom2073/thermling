"""Main application window."""

from __future__ import annotations

import base64
from contextlib import suppress
from pathlib import Path

from PySide6.QtCore import QBuffer, QFile, QIODevice, QSettings, QSize, Qt, QTimer, QUrl
from PySide6.QtGui import QAction, QCloseEvent, QIcon, QImageReader, QKeySequence
from PySide6.QtWidgets import (
    QDockWidget,
    QFileDialog,
    QFrame,
    QLabel,
    QMainWindow,
    QMenu,
    QMessageBox,
    QScrollArea,
    QSplitter,
    QStyle,
    QTabWidget,
    QToolBar,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from ..controller import DocumentController
from ..document import DocumentError, ImageElement, LabelDocument, TextElement
from ..permissions import permission_status, request_android_permissions
from ..protocol import Config, LabelRoll, Status, build_job, describe_status
from ..render import RenderError, RenderResult, render_document
from ..runtime import ANDROID
from ..settings import AppSettings
from ..theme import repolish
from ..worker import PrinterService
from .canvas import CanvasView
from .inspector import Inspector
from .preferences import PreferencesDialog
from .preview import PreviewPanel
from .printer_panel import PrinterPanel

FILE_FILTER = "Label documents (*.label.json *.json);;All files (*)"
PREVIEW_DEBOUNCE_MS = 120


def _is_uri_path(path: str | Path) -> bool:
    return str(path).startswith(("content:", "file:"))


def _path_name(path: str | Path) -> str:
    text = str(path)
    if _is_uri_path(path):
        name = QUrl(text).fileName()
        return name or "label"
    return Path(text).name


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.settings = AppSettings.load()
        self.controller = DocumentController(LabelDocument())
        self._document_path: str | Path | None = None
        self._connected = False
        self._printing = False
        self._last_result: RenderResult | None = None
        self._printer_label: LabelRoll | None = None
        self._printer_config: Config | None = None
        self._printer_status: int | None = None

        self._preview_timer = QTimer(self)
        self._preview_timer.setSingleShot(True)
        self._preview_timer.setInterval(PREVIEW_DEBOUNCE_MS)
        self._preview_timer.timeout.connect(self._refresh_preview)

        self._service = PrinterService(self)

        self._build_actions()
        if ANDROID:
            self._build_mobile_ui()
        else:
            self._build_ui()
            self._restore_window_state()
        self._connect_signals()

        self.controller.set_document(LabelDocument())
        self._sync_panel_from_settings()
        self.canvas.configure(self._effective_dpi(), self.settings.head_width_mm)
        self.inspector.set_head_width(self.settings.head_width_mm)
        self.preview_panel.set_values(self.settings.density, self.settings.copies)
        self._update_actions()

        if self.settings.auto_connect and self.settings.mac:
            QTimer.singleShot(400, self._auto_connect)

    # --- construction --------------------------------------------------------
    def _build_actions(self) -> None:
        self.new_action = self._action(
            "New", self._new_document, "Ctrl+N", "document-new",
            QStyle.StandardPixmap.SP_FileIcon,
        )
        self.open_action = self._action(
            "Open…", self._open_document, "Ctrl+O", "document-open",
            QStyle.StandardPixmap.SP_DialogOpenButton,
        )
        self.save_action = self._action(
            "Save", self._save_document, "Ctrl+S", "document-save",
            QStyle.StandardPixmap.SP_DialogSaveButton,
        )
        self.save_as_action = self._action(
            "Save As…", self._save_document_as, "Ctrl+Shift+S"
        )
        self.quit_action = self._action("Quit", self.close, "Ctrl+Q")
        self.undo_action = self._action(
            "Undo", self.controller.undo, "Ctrl+Z", "edit-undo",
            QStyle.StandardPixmap.SP_ArrowBack,
        )
        self.redo_action = self._action(
            "Redo", self.controller.redo, "Ctrl+Shift+Z", "edit-redo",
            QStyle.StandardPixmap.SP_ArrowForward,
        )
        self.undo_action.setEnabled(False)
        self.redo_action.setEnabled(False)
        self.add_text_action = self._action(
            "Add Text", self._add_text, "Ctrl+T", "insert-text"
        )
        self.add_image_action = self._action(
            "Add Image…", self._add_image, "Ctrl+I", "insert-image"
        )
        self.duplicate_action = self._action(
            "Duplicate", self._duplicate_selected, "Ctrl+D", "edit-copy"
        )
        self.delete_action = self._action(
            "Delete", self._delete_selected, None, "edit-delete"
        )
        self.print_action = self._action(
            "Print", self._print, "Ctrl+P", "document-print",
            QStyle.StandardPixmap.SP_DialogApplyButton,
        )
        self.preferences_action = self._action(
            "Preferences…", self._open_preferences, "Ctrl+,", "preferences-system"
        )
        self.connect_action = self._action(
            "Connect", lambda: self.printer_panel.connect_button.click()
        )
        self.refresh_action = self._action(
            "Refresh status", self._service.refresh, "F5", "view-refresh"
        )
        self.self_test_action = self._action(
            "Print self-test", self._service.self_test
        )
        self.about_action = self._action("About", self._show_about)

        self.zoom_in_action = self._action(
            "Zoom In", lambda: self.canvas.zoom_by(1.2), "Ctrl++"
        )
        self.zoom_out_action = self._action(
            "Zoom Out", lambda: self.canvas.zoom_by(1 / 1.2), "Ctrl+-"
        )
        self.fit_action = self._action(
            "Fit", lambda *_: self.canvas.fit_to_view(), "Ctrl+0", "zoom-fit-best"
        )
        self.grid_action = self._action("Grid", None)
        self.grid_action.setCheckable(True)
        self.grid_action.setChecked(True)
        self.snap_action = self._action("Snap", None)
        self.snap_action.setCheckable(True)
        self.snap_action.setChecked(True)

    def _action(
        self,
        text: str,
        slot,
        shortcut: str | None = None,
        icon: str | None = None,
        standard: QStyle.StandardPixmap | None = None,
    ) -> QAction:
        action = QAction(text, self)
        if icon:
            icon_object = QIcon.fromTheme(icon)
            if icon_object.isNull() and standard is not None:
                icon_object = self.style().standardIcon(standard)
            action.setIcon(icon_object)
        if shortcut:
            action.setShortcut(QKeySequence(shortcut))
        if slot is not None:
            action.triggered.connect(slot)
        return action

    def _build_ui(self) -> None:
        self.setWindowTitle("Thermling")
        self.resize(1280, 860)

        self.canvas = CanvasView(self.controller, self)
        central = QWidget(self)
        central_layout = QVBoxLayout(central)
        central_layout.setContentsMargins(0, 0, 0, 0)
        central_layout.setSpacing(0)
        central_layout.addWidget(self.canvas)
        self.setCentralWidget(central)

        main_toolbar = QToolBar("Main", self)
        main_toolbar.setObjectName("MainToolbar")
        main_toolbar.setMovable(False)
        main_toolbar.setIconSize(QSize(18, 18))
        main_toolbar.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
        for action in (
            self.new_action,
            self.open_action,
            self.save_action,
        ):
            main_toolbar.addAction(action)
        main_toolbar.addSeparator()
        main_toolbar.addAction(self.undo_action)
        main_toolbar.addAction(self.redo_action)
        main_toolbar.addSeparator()
        main_toolbar.addAction(self.add_text_action)
        main_toolbar.addAction(self.add_image_action)
        main_toolbar.addAction(self.duplicate_action)
        main_toolbar.addAction(self.delete_action)
        main_toolbar.addSeparator()
        main_toolbar.addAction(self.print_action)
        self.addToolBar(Qt.ToolBarArea.TopToolBarArea, main_toolbar)

        view_toolbar = QToolBar("View", self)
        view_toolbar.setObjectName("ViewToolbar")
        view_toolbar.setMovable(False)
        view_toolbar.setIconSize(QSize(16, 16))
        view_toolbar.addAction(self.zoom_out_action)
        view_toolbar.addAction(self.zoom_in_action)
        view_toolbar.addAction(self.fit_action)
        view_toolbar.addSeparator()
        view_toolbar.addAction(self.grid_action)
        view_toolbar.addAction(self.snap_action)
        self.zoom_label = QLabel("100%", self)
        self.zoom_label.setMinimumWidth(48)
        self.zoom_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        view_toolbar.addWidget(self.zoom_label)
        self.addToolBar(Qt.ToolBarArea.TopToolBarArea, view_toolbar)

        self.printer_panel = PrinterPanel(self)
        self.printer_dock = QDockWidget("Printer", self)
        self.printer_dock.setObjectName("PrinterDock")
        self.printer_dock.setWidget(self.printer_panel)
        self.printer_dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        self.printer_dock.setMinimumWidth(320)
        self.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, self.printer_dock)

        self.inspector = Inspector(self.controller, self)
        self.inspector_scroll = QScrollArea(self)
        self.inspector_scroll.setWidgetResizable(True)
        self.inspector_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.inspector_scroll.setWidget(self.inspector)

        self.preview_panel = PreviewPanel(self)
        self._splitter = QSplitter(Qt.Orientation.Vertical, self)
        self._splitter.setChildrenCollapsible(False)
        self._splitter.addWidget(self.preview_panel)
        self._splitter.addWidget(self.inspector_scroll)
        self._splitter.setSizes([370, 460])

        self.right_dock = QDockWidget("Preview & inspector", self)
        self.right_dock.setObjectName("InspectorDock")
        self.right_dock.setWidget(self._splitter)
        self.right_dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        self.right_dock.setMinimumWidth(360)
        self.addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, self.right_dock)

        self._build_menus()
        self._build_status_bar()

    def _build_mobile_ui(self) -> None:
        """A touch-oriented shell for phones: canvas and panels in tabs."""

        self.setWindowTitle("Thermling")
        self.resize(420, 820)
        self.setMinimumSize(300, 480)

        self.canvas = CanvasView(self.controller, self)
        self.inspector = Inspector(self.controller, self)
        self.inspector.setMinimumWidth(0)
        self.inspector_scroll = QScrollArea(self)
        self.inspector_scroll.setWidgetResizable(True)
        self.inspector_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.inspector_scroll.setWidget(self.inspector)
        self.preview_panel = PreviewPanel(self)
        self.printer_panel = PrinterPanel(self)

        self._tabs = QTabWidget(self)
        self._tabs.setDocumentMode(True)
        self._tabs.setUsesScrollButtons(False)
        self._tabs.tabBar().setExpanding(True)
        self._tabs.addTab(self.canvas, "Label")
        self._tabs.addTab(self.inspector_scroll, "Edit")
        self._tabs.addTab(self.preview_panel, "Preview")
        self._tabs.addTab(self.printer_panel, "Printer")
        self.setCentralWidget(self._tabs)

        mobile_toolbar = QToolBar("Main", self)
        mobile_toolbar.setObjectName("MobileToolbar")
        mobile_toolbar.setMovable(False)
        mobile_toolbar.setIconSize(QSize(20, 20))
        mobile_toolbar.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
        for action in (
            self.add_text_action,
            self.add_image_action,
            self.print_action,
        ):
            mobile_toolbar.addAction(action)

        menu = QMenu(self)
        for action in (
            self.new_action,
            self.open_action,
            self.save_action,
            self.save_as_action,
        ):
            menu.addAction(action)
        menu.addSeparator()
        for action in (
            self.undo_action,
            self.redo_action,
            self.duplicate_action,
            self.delete_action,
        ):
            menu.addAction(action)
        menu.addSeparator()
        menu.addAction(self.connect_action)
        menu.addAction(self.refresh_action)
        menu.addAction(self.self_test_action)
        menu.addSeparator()
        menu.addAction(self.zoom_in_action)
        menu.addAction(self.zoom_out_action)
        menu.addAction(self.fit_action)
        menu.addAction(self.grid_action)
        menu.addAction(self.snap_action)
        menu.addAction(self.preferences_action)
        menu.addAction(self.about_action)
        menu.addAction(self.quit_action)

        menu_button = QToolButton(self)
        menu_button.setText("Menu")
        menu_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        menu_button.setMenu(menu)
        mobile_toolbar.addWidget(menu_button)
        self.mobile_menu = menu
        self.addToolBar(Qt.ToolBarArea.TopToolBarArea, mobile_toolbar)

        self.zoom_label = QLabel("100%", self)
        self.zoom_label.hide()
        self._build_status_bar()
        self.document_status.hide()
        self.size_status.hide()
        self.statusBar().setSizeGripEnabled(False)

    def _build_status_bar(self) -> None:
        self.connection_status = QLabel("Disconnected", self)
        self.connection_status.setProperty("role", "muted")
        self.document_status = QLabel("Untitled", self)
        self.document_status.setProperty("role", "muted")
        self.size_status = QLabel("", self)
        self.size_status.setProperty("role", "muted")
        self.statusBar().addPermanentWidget(self.document_status)
        self.statusBar().addPermanentWidget(self.size_status)
        self.statusBar().addPermanentWidget(self.connection_status)
        self.statusBar().showMessage("Ready", 3000)

    def _build_menus(self) -> None:
        file_menu = self.menuBar().addMenu("&File")
        file_menu.addAction(self.new_action)
        file_menu.addAction(self.open_action)
        file_menu.addAction(self.save_action)
        file_menu.addAction(self.save_as_action)
        file_menu.addSeparator()
        file_menu.addAction(self.quit_action)

        edit_menu = self.menuBar().addMenu("&Edit")
        edit_menu.addAction(self.undo_action)
        edit_menu.addAction(self.redo_action)
        edit_menu.addSeparator()
        edit_menu.addAction(self.duplicate_action)
        edit_menu.addAction(self.delete_action)
        edit_menu.addSeparator()
        edit_menu.addAction(self.preferences_action)

        insert_menu = self.menuBar().addMenu("&Insert")
        insert_menu.addAction(self.add_text_action)
        insert_menu.addAction(self.add_image_action)

        printer_menu = self.menuBar().addMenu("&Printer")
        printer_menu.addAction(self.connect_action)
        printer_menu.addAction(self.refresh_action)
        printer_menu.addAction(self.self_test_action)
        printer_menu.addSeparator()
        printer_menu.addAction(self.print_action)

        view_menu = self.menuBar().addMenu("&View")
        view_menu.addAction(self.zoom_in_action)
        view_menu.addAction(self.zoom_out_action)
        view_menu.addAction(self.fit_action)
        view_menu.addSeparator()
        view_menu.addAction(self.grid_action)
        view_menu.addAction(self.snap_action)
        view_menu.addAction(self.printer_dock.toggleViewAction())
        view_menu.addAction(self.right_dock.toggleViewAction())

        help_menu = self.menuBar().addMenu("&Help")
        help_menu.addAction(self.about_action)

    def _connect_signals(self) -> None:
        self.controller.changed.connect(self._on_document_changed)
        self.controller.reset.connect(self._on_document_reset)
        self.controller.dirtyChanged.connect(self._update_window_title)
        self.controller.undo_stack.canUndoChanged.connect(self.undo_action.setEnabled)
        self.controller.undo_stack.canRedoChanged.connect(self.redo_action.setEnabled)

        self.canvas.selectionChanged.connect(self._on_selection_changed)
        self.canvas.editRequested.connect(self._on_edit_requested)
        self.canvas.deleteRequested.connect(self._delete_selected)
        self.canvas.duplicateRequested.connect(self._duplicate_selected)
        self.canvas.zoomChanged.connect(self._on_zoom_changed)

        self.grid_action.toggled.connect(self.canvas.set_grid_visible)
        self.snap_action.toggled.connect(self.canvas.set_snap_enabled)

        self.preview_panel.printRequested.connect(self._print)
        self.preview_panel.densityChanged.connect(self._on_density_changed)
        self.preview_panel.copiesChanged.connect(self._on_copies_changed)

        self.printer_panel.connectRequested.connect(self._connect_printer)
        self.printer_panel.disconnectRequested.connect(self._disconnect_printer)
        self.printer_panel.refreshRequested.connect(self._service.refresh)
        self.printer_panel.selfTestRequested.connect(self._service.self_test)
        self.printer_panel.beepChanged.connect(self._service.set_beep)
        self.printer_panel.timerChanged.connect(self._service.set_timer)
        self.printer_panel.preferencesRequested.connect(self._open_preferences)

        self._service.connected.connect(self._on_connected)
        self._service.disconnected.connect(self._on_disconnected)
        self._service.connectionFailed.connect(self._on_connection_failed)
        self._service.statusUpdated.connect(self._on_status_updated)
        self._service.labelUpdated.connect(self._on_label_updated)
        self._service.configUpdated.connect(self._on_config_updated)
        self._service.batteryUpdated.connect(self._on_battery_updated)
        self._service.actionFinished.connect(self._on_action_finished)
        self._service.error.connect(self._on_service_error)
        self._service.printFinished.connect(self._on_print_finished)

    # --- window state ---------------------------------------------------------
    def _restore_window_state(self) -> None:
        settings = QSettings()
        geometry = settings.value("window/geometry")
        if geometry is not None:
            self.restoreGeometry(geometry)
        state = settings.value("window/state")
        if state is not None:
            self.restoreState(state)
        sizes = settings.value("window/splitter")
        if isinstance(sizes, list) and len(sizes) == 2:
            with suppress(TypeError, ValueError):
                self._splitter.setSizes([int(sizes[0]), int(sizes[1])])

    def _save_window_state(self) -> None:
        settings = QSettings()
        settings.setValue("window/geometry", self.saveGeometry())
        if hasattr(self, "_splitter"):
            settings.setValue("window/state", self.saveState())
            settings.setValue("window/splitter", self._splitter.sizes())

    # --- document lifecycle ---------------------------------------------------
    def _new_document(self) -> None:
        if not self._maybe_save():
            return
        document = LabelDocument()
        if self._printer_label is not None and self._printer_label.has_tag:
            label = self._printer_label
            if label.width_mm and label.length_mm:
                document.width_mm = label.width_mm
                document.length_mm = label.length_mm
                document.gap_mm = label.gap_mm
                document.style = label.style
        self._document_path = None
        self.controller.set_document(document)
        self.canvas.fit_to_view()
        self.statusBar().showMessage("New label", 3000)

    def _open_document(self) -> None:
        if not self._maybe_save():
            return
        path, _ = QFileDialog.getOpenFileName(
            self, "Open label", self._start_directory(), FILE_FILTER
        )
        if not path:
            return
        try:
            document = LabelDocument.load(path)
        except DocumentError as error:
            QMessageBox.warning(self, "Could not open label", str(error))
            return
        self._document_path = path if _is_uri_path(path) else Path(path)
        if isinstance(self._document_path, Path):
            self.settings.last_dir = str(self._document_path.parent)
            self.settings.save()
        self.controller.set_document(document)
        self.canvas.fit_to_view()
        self._show_mobile_tab(0)
        self.statusBar().showMessage(f"Opened {_path_name(self._document_path)}", 4000)

    def _save_document(self) -> bool:
        self.inspector.commit_pending()
        if self._document_path is None:
            return self._save_document_as()
        return self._write_document(self._document_path)

    def _save_document_as(self) -> bool:
        self.inspector.commit_pending()
        path, _ = QFileDialog.getSaveFileName(
            self,
            "Save label",
            self._default_save_path(),
            FILE_FILTER,
        )
        if not path:
            return False
        if _is_uri_path(path):
            target: str | Path = path
        else:
            local_target = Path(path)
            if local_target.suffix.lower() not in {".json"}:
                local_target = local_target.with_name(
                    local_target.name + ".label.json"
                )
            target = local_target
        return self._write_document(target)

    def _write_document(self, path: str | Path) -> bool:
        try:
            self.controller.document.save(path)
        except (DocumentError, OSError) as error:
            QMessageBox.warning(self, "Could not save label", str(error))
            return False
        self._document_path = path
        if isinstance(path, Path):
            self.settings.last_dir = str(path.parent)
            self.settings.save()
        self.controller.mark_saved()
        self._update_window_title()
        self.statusBar().showMessage(f"Saved {_path_name(path)}", 4000)
        return True

    def _maybe_save(self) -> bool:
        if not self.controller.is_dirty:
            return True
        box = QMessageBox(self)
        box.setWindowTitle("Unsaved changes")
        box.setIcon(QMessageBox.Icon.Question)
        box.setText("This label has unsaved changes. Save before continuing?")
        box.setStandardButtons(
            QMessageBox.StandardButton.Save
            | QMessageBox.StandardButton.Discard
            | QMessageBox.StandardButton.Cancel
        )
        box.setDefaultButton(QMessageBox.StandardButton.Save)
        result = box.exec()
        if result == QMessageBox.StandardButton.Save:
            return self._save_document()
        return result == QMessageBox.StandardButton.Discard

    def _start_directory(self) -> str:
        if self.settings.last_dir and Path(self.settings.last_dir).is_dir():
            return self.settings.last_dir
        return str(Path.home())

    def _default_save_path(self) -> str:
        if self._document_path is not None:
            return str(self._document_path)
        return str(Path(self._start_directory()) / "Untitled.label.json")

    # --- document updates -----------------------------------------------------
    def _on_document_changed(self) -> None:
        self.canvas.sync_from_document()
        self.inspector.sync_geometry()
        self._preview_timer.start()
        self._update_window_title()
        self._update_size_status()
        self._update_actions()

    def _on_document_reset(self) -> None:
        selected = self.canvas.selected_id()
        self.canvas.configure(self._effective_dpi(), self.settings.head_width_mm)
        self.canvas.rebuild()
        self.inspector.refresh_document()
        document = self.controller.document
        if selected and document.find(selected) is not None:
            self.canvas.select_element(selected)
            self.inspector.set_element(document.find(selected))
        else:
            self.inspector.set_element(None)
        self.preview_panel.set_document_size(document.width_mm, document.length_mm)
        self._preview_timer.start()
        self._update_window_title()
        self._update_size_status()
        self._update_actions()

    def _update_window_title(self, *_: object) -> None:
        name = _path_name(self._document_path) if self._document_path else "Untitled"
        marker = " *" if self.controller.is_dirty else ""
        self.setWindowTitle(f"{name}{marker} — Thermling")
        self.document_status.setText(f"{name}{marker}")

    def _update_size_status(self) -> None:
        document = self.controller.document
        page_width, page_height = document.page_size_mm(self.settings.head_width_mm)
        self.size_status.setText(
            f"Label {document.width_mm:g} × {document.length_mm:g} mm · "
            f"page {page_width:g} × {page_height:g} mm · "
            f"{self._effective_dpi()} dpi"
        )
        self.preview_panel.set_document_size(document.width_mm, document.length_mm)

    def _on_selection_changed(self, element_id: str) -> None:
        element = self.controller.document.find(element_id) if element_id else None
        self.inspector.set_element(element)
        self._update_actions()

    def _on_edit_requested(self, element_id: str) -> None:
        element = self.controller.document.find(element_id)
        if element is None:
            return
        self.canvas.select_element(element_id)
        self.inspector.set_element(element)
        self._show_mobile_tab(1)
        self.inspector.focus_text_editor()

    def _show_mobile_tab(self, index: int) -> None:
        if hasattr(self, "_tabs"):
            self._tabs.setCurrentIndex(index)

    def _update_actions(self) -> None:
        has_selection = bool(self.canvas.selected_id())
        self.duplicate_action.setEnabled(has_selection)
        self.delete_action.setEnabled(has_selection)
        self.print_action.setEnabled(
            self._connected and not self._printing and self._last_result is not None
        )
        self.connect_action.setText("Disconnect" if self._connected else "Connect")

    # --- element actions ------------------------------------------------------
    def _add_text(self) -> None:
        document = self.controller.document
        page_width, page_height = document.page_size_mm(self.settings.head_width_mm)
        width = max(2.0, page_width - 2.0)
        height = min(8.0, max(2.0, page_height - 2.0))
        element = TextElement(
            text="Text",
            x_mm=(page_width - width) / 2.0,
            y_mm=max(0.0, min(2.0, page_height - height)),
            width_mm=width,
            height_mm=height,
        )
        self.controller.add_element(element, "Add text")
        self.canvas.select_element(element.id)
        self.inspector.set_element(element)
        self._show_mobile_tab(1)
        self.inspector.focus_text_editor()

    def _add_image(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose image",
            self._start_directory(),
            "Images (*.png *.jpg *.jpeg *.bmp *.gif *.webp *.tif *.tiff);;All files (*)",
        )
        if not path:
            return
        try:
            png_base64, image_width, image_height = _image_to_png_base64(path)
        except ValueError as error:
            QMessageBox.warning(self, "Could not load image", str(error))
            return
        if not _is_uri_path(path):
            self.settings.last_dir = str(Path(path).parent)
            self.settings.save()

        document = self.controller.document
        page_width, page_height = document.page_size_mm(self.settings.head_width_mm)
        max_width = max(1.0, page_width - 2.0)
        max_height = max(1.0, page_height - 2.0)
        aspect = image_height / image_width if image_width else 1.0
        width = max_width
        height = width * aspect
        if height > max_height:
            height = max_height
            width = height / aspect
        element = ImageElement(
            png_base64=png_base64,
            source_name=_path_name(path),
            x_mm=(page_width - width) / 2.0,
            y_mm=(page_height - height) / 2.0,
            width_mm=max(0.5, width),
            height_mm=max(0.5, height),
        )
        self.controller.add_element(element, "Add image")
        self.canvas.select_element(element.id)
        self.inspector.set_element(element)

    def _replace_image(self, element_id: str) -> None:
        element = self.controller.document.find(element_id)
        if not isinstance(element, ImageElement):
            return
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose image",
            self._start_directory(),
            "Images (*.png *.jpg *.jpeg *.bmp *.gif *.webp *.tif *.tiff);;All files (*)",
        )
        if not path:
            return
        try:
            png_base64, _, _ = _image_to_png_base64(path)
        except ValueError as error:
            QMessageBox.warning(self, "Could not load image", str(error))
            return
        name = _path_name(path)
        self.controller.edit(
            lambda: _replace_image_data(element, png_base64, name), "Replace image"
        )
        self.inspector.set_element(element)
        self.inspector.sync_geometry()

    def _delete_selected(self) -> None:
        element_id = self.canvas.selected_id()
        if not element_id:
            return
        self.controller.remove_element(element_id)
        self.canvas.clear_selection()
        self.inspector.set_element(None)
        self._update_actions()

    def _duplicate_selected(self) -> None:
        element_id = self.canvas.selected_id()
        if not element_id:
            return
        clone = self.controller.duplicate_element(element_id)
        if clone is None:
            return
        self.canvas.sync_from_document()
        self.canvas.select_element(clone.id)
        self.inspector.set_element(clone)
        self._update_actions()

    # --- preview and printing -------------------------------------------------
    def _effective_dpi(self) -> int:
        if self.settings.auto_dpi and self._connected and self._printer_config is not None:
            return self._printer_config.dpi
        return self.settings.dpi_override

    def _refresh_preview(self) -> None:
        try:
            result = render_document(
                self.controller.document,
                dpi=self._effective_dpi(),
                head_width_mm=self.settings.head_width_mm,
            )
        except (RenderError, DocumentError, ValueError) as error:
            self._last_result = None
            self.preview_panel.set_error(str(error))
            self._update_actions()
            return
        self._last_result = result
        self.preview_panel.set_result(result)
        self.preview_panel.set_document_size(
            self.controller.document.width_mm, self.controller.document.length_mm
        )
        self._update_actions()

    def _print(self) -> None:
        if not self._connected:
            QMessageBox.information(
                self, "Printer not connected", "Connect to the printer first."
            )
            return
        if self._printer_status is not None and self._printer_status != Status.READY:
            QMessageBox.warning(
                self,
                "Printer not ready",
                f"The printer reports: {describe_status(self._printer_status)}",
            )
            return
        self.inspector.commit_pending()
        self.controller.commit("Edit")

        document = self.controller.document
        label = self._printer_label
        if (
            label is not None
            and label.has_tag
            and label.width_mm
            and label.length_mm
            and (
                abs(document.width_mm - label.width_mm) > 0.6
                or abs(document.length_mm - label.length_mm) > 0.6
            )
        ):
            answer = QMessageBox.question(
                self,
                "Label size differs",
                f"The document is {document.width_mm:g} × {document.length_mm:g} mm, "
                f"but the printer roll is {label.width_mm:g} × {label.length_mm:g} mm.\n\n"
                "Print anyway?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            if answer != QMessageBox.StandardButton.Yes:
                return

        try:
            result = render_document(
                document,
                dpi=self._effective_dpi(),
                head_width_mm=self.settings.head_width_mm,
            )
            payload = build_job(
                label_width_mm=document.width_mm,
                label_length_mm=document.length_mm,
                gap_mm=document.gap_mm,
                style=document.style,
                bitmap=result.data,
                width_dots=result.width_dots,
                height_dots=result.height_dots,
                density=self.preview_panel.density.value(),
                copies=self.preview_panel.copies.value(),
            )
        except (RenderError, DocumentError, ValueError) as error:
            QMessageBox.warning(self, "Cannot print", str(error))
            return

        self._printing = True
        self.preview_panel.set_busy(True)
        self._update_actions()
        self.statusBar().showMessage("Sending print job…")
        self._service.print_job(payload)

    def _on_print_finished(self, success: bool, message: str) -> None:
        self._printing = False
        self.preview_panel.set_busy(False)
        self._update_actions()
        if success:
            self.statusBar().showMessage(message, 5000)
            QTimer.singleShot(700, self._service.refresh)
        else:
            self.statusBar().showMessage(message, 8000)
            QMessageBox.warning(self, "Print failed", message)

    def _on_density_changed(self, value: int) -> None:
        self.settings.density = value
        self.settings.save()

    def _on_copies_changed(self, value: int) -> None:
        self.settings.copies = value
        self.settings.save()

    # --- printer connection ---------------------------------------------------
    def _auto_connect(self) -> None:
        if self.settings.mac and not self._connected:
            self._connect_printer(
                self.settings.mac, self.settings.backend, self.settings.device
            )

    def _connect_printer(self, mac: str, backend: str, device: str) -> None:
        if self._connected:
            return
        if ANDROID and permission_status() != Qt.PermissionStatus.Granted:
            request_android_permissions(
                self,
                lambda granted: self._after_bluetooth_permission(
                    granted, mac, backend, device
                ),
            )
            return
        self.settings.mac = mac.upper()
        self.settings.backend = backend
        self.settings.device = device
        self.settings.save()
        self.printer_panel.set_connecting()
        self._status_message(f"Connecting to {mac}…")
        self._service.connect_to(mac, backend, device, self.settings.take_over)

    def _after_bluetooth_permission(
        self, granted: bool, mac: str, backend: str, device: str
    ) -> None:
        if granted:
            self._connect_printer(mac, backend, device)
            return
        QMessageBox.warning(
            self,
            "Bluetooth permissions required",
            "Thermling needs Bluetooth and location access to find and talk to the "
            "printer. Grant “Nearby devices” and “Location” permission in Android "
            "settings, then try again.",
        )

    def _disconnect_printer(self) -> None:
        if not self._connected:
            self._service.disconnect_from()
            return
        self._status_message("Disconnecting…")
        self._service.disconnect_from()

    def _on_connected(self, mac: str) -> None:
        self._connected = True
        self.settings.mac = mac
        self.settings.save()
        self.printer_panel.set_connected(True)
        self.preview_panel.set_connected(True)
        self.connection_status.setText(f"Connected · {mac}")
        _set_label_role(self.connection_status, "success")
        self._status_message(f"Connected to {mac}")
        self._update_actions()

    def _on_disconnected(self) -> None:
        self._connected = False
        self._printer_label = None
        self._printer_config = None
        self._printer_status = None
        self.printer_panel.set_connected(False)
        self.preview_panel.set_connected(False)
        self.preview_panel.set_detected_label(None)
        self.inspector.set_detected_label(None)
        self.connection_status.setText("Disconnected")
        _set_label_role(self.connection_status, "muted")
        self._status_message("Disconnected")
        self.canvas.configure(self._effective_dpi(), self.settings.head_width_mm)
        self._preview_timer.start()
        self._update_actions()

    def _on_connection_failed(self, message: str) -> None:
        self._connected = False
        self.printer_panel.set_connection_failed(message)
        self.preview_panel.set_connected(False)
        self.connection_status.setText("Connection failed")
        _set_label_role(self.connection_status, "danger")
        self._status_message("Connection failed")
        self._update_actions()
        QMessageBox.warning(self, "Could not connect", message)

    def _on_status_updated(self, status: int) -> None:
        self._printer_status = status
        self.printer_panel.set_status(status)

    def _on_label_updated(self, label: LabelRoll) -> None:
        self._printer_label = label
        self.printer_panel.set_label(label)
        self.preview_panel.set_detected_label(label)
        self.inspector.set_detected_label(label)
        self._preview_timer.start()

    def _on_config_updated(self, config: Config) -> None:
        previous_dpi = self._effective_dpi()
        self._printer_config = config
        self.printer_panel.set_config(config)
        if self.settings.auto_dpi and self._effective_dpi() != previous_dpi:
            self.canvas.configure(self._effective_dpi(), self.settings.head_width_mm)
            self._preview_timer.start()
            self._update_size_status()
        self.preview_panel.set_detected_label(self._printer_label)

    def _on_battery_updated(self, battery) -> None:
        self.printer_panel.set_battery(battery)

    def _on_action_finished(self, message: str) -> None:
        self._status_message(message)

    def _on_service_error(self, message: str) -> None:
        self.printer_panel.show_error(message)
        self._status_message(message)

    def _status_message(self, message: str) -> None:
        self.statusBar().showMessage(message, 6000)

    # --- view -----------------------------------------------------------------
    def _on_zoom_changed(self, zoom: float) -> None:
        self.zoom_label.setText(f"{zoom * 100:.0f}%")

    # --- preferences ----------------------------------------------------------
    def _sync_panel_from_settings(self) -> None:
        self.printer_panel.set_settings(
            self.settings.mac, self.settings.backend, self.settings.device
        )

    def _open_preferences(self) -> None:
        dialog = PreferencesDialog(self.settings, self)
        if not dialog.exec():
            return
        old_connection = (
            self.settings.mac,
            self.settings.backend,
            self.settings.device,
        )
        self.settings = dialog.settings
        self.settings.save()
        self._sync_panel_from_settings()
        self.inspector.set_head_width(self.settings.head_width_mm)
        self.canvas.configure(self._effective_dpi(), self.settings.head_width_mm)
        self._preview_timer.start()
        self._update_size_status()
        new_connection = (
            self.settings.mac,
            self.settings.backend,
            self.settings.device,
        )
        if old_connection != new_connection:
            self._status_message("Connection settings saved — reconnect to apply them.")
        else:
            self._status_message("Preferences saved")

    def _show_about(self) -> None:
        QMessageBox.about(
            self,
            "About Thermling",
            "<h3>Thermling</h3>"
            "<p>A Linux label app for Nelko P21/P22 thermal printers.</p>"
            "<p>Protocol documentation and the reference implementation come from "
            "the <code>nelko-p21-print</code> project. Text is rasterised locally; "
            "images are embedded in saved documents.</p>",
        )

    # --- shutdown -------------------------------------------------------------
    def closeEvent(self, event: QCloseEvent) -> None:
        if not self._maybe_save():
            event.ignore()
            return
        self._save_window_state()
        self.settings.save()
        self._service.stop()
        event.accept()


# --- helpers ------------------------------------------------------------------
def _image_to_png_base64(path: str) -> tuple[str, int, int]:
    source: QFile | None = None
    if path.startswith("content:"):
        source = QFile(path)
        if not source.open(QIODevice.OpenModeFlag.ReadOnly):
            raise ValueError(source.errorString() or "Could not open the image")
    elif path.startswith("file:"):
        path = QUrl(path).toLocalFile()
    try:
        reader = QImageReader(source) if source is not None else QImageReader(path)
        reader.setAutoTransform(True)
        image = reader.read()
        if image.isNull():
            raise ValueError(reader.errorString() or "Unsupported image format")
    finally:
        if source is not None:
            source.close()
    if max(image.width(), image.height()) > 1400:
        image = image.scaled(
            1400,
            1400,
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
    buffer = QBuffer()
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    if not image.save(buffer, "PNG"):
        raise ValueError("Could not encode the image as PNG")
    encoded = base64.b64encode(bytes(buffer.data())).decode("ascii")
    return encoded, image.width(), image.height()


def _replace_image_data(element: ImageElement, png_base64: str, name: str) -> None:
    element.png_base64 = png_base64
    element.source_name = name


def _set_label_role(label: QLabel, role: str) -> None:
    if label.property("role") == role:
        return
    label.setProperty("role", role)
    repolish(label)
