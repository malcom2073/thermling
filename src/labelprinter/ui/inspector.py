"""Property inspector for the selected element and document settings."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDoubleSpinBox,
    QFontComboBox,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QPlainTextEdit,
    QPushButton,
    QStackedWidget,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from ..controller import DocumentController
from ..document import (
    LABEL_PRESETS,
    Halftone,
    HorizontalAlign,
    ImageElement,
    ImageFit,
    LabelPreset,
    Orientation,
    TextElement,
    VerticalAlign,
)
from ..protocol import LabelRoll, Style
from ..render import RenderError, decode_image


class CommitPlainTextEdit(QPlainTextEdit):
    """A plain text edit that reports when editing should become an undo step."""

    editingFinished = Signal()

    def focusOutEvent(self, event) -> None:
        super().focusOutEvent(event)
        self.editingFinished.emit()

    def keyPressEvent(self, event) -> None:
        if (
            event.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter)
            and event.modifiers() & Qt.KeyboardModifier.ControlModifier
        ):
            self.editingFinished.emit()
            event.accept()
            return
        super().keyPressEvent(event)


def _mm_spin(maximum: float, minimum: float = 0.0) -> QDoubleSpinBox:
    spin = QDoubleSpinBox()
    spin.setRange(minimum, maximum)
    spin.setDecimals(1)
    spin.setSingleStep(0.5)
    spin.setSuffix(" mm")
    spin.setAccelerated(True)
    return spin


def _enum_value(combo: QComboBox, enum_type):
    """Read combo data as *enum_type*.

    PySide stores ``StrEnum`` members in a QVariant as plain strings, so an
    ``isinstance`` check on ``currentData()`` is not reliable.
    """
    raw = combo.currentData()
    if isinstance(raw, enum_type):
        return raw
    try:
        return enum_type(str(raw))
    except (TypeError, ValueError):
        return None


class Inspector(QWidget):
    duplicateRequested = Signal()
    deleteRequested = Signal()
    replaceImageRequested = Signal(str)

    def __init__(self, controller: DocumentController, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.controller = controller
        self._element_id = ""
        self._updating = False
        self._detected_label: LabelRoll | None = None
        self._head_width_mm = 12.0
        self.setMinimumWidth(300)

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.setSpacing(10)

        hint = QLabel(
            "Drag on the canvas to move an element; drag its handles to resize.",
            self,
        )
        hint.setWordWrap(True)
        hint.setProperty("role", "hint")
        root.addWidget(hint)

        self.selection_group = QGroupBox("Selection", self)
        selection_layout = QVBoxLayout(self.selection_group)
        selection_layout.setSpacing(8)

        self.kind_label = QLabel("", self)
        self.kind_label.setProperty("role", "heading")
        selection_layout.addWidget(self.kind_label)

        geometry = QGridLayout()
        geometry.setHorizontalSpacing(8)
        geometry.setVerticalSpacing(4)
        self.x_spin = _mm_spin(500, 0)
        self.y_spin = _mm_spin(500, 0)
        self.w_spin = _mm_spin(500, 0.5)
        self.h_spin = _mm_spin(500, 0.5)
        geometry.addWidget(QLabel("X"), 0, 0)
        geometry.addWidget(self.x_spin, 0, 1)
        geometry.addWidget(QLabel("Y"), 0, 2)
        geometry.addWidget(self.y_spin, 0, 3)
        geometry.addWidget(QLabel("Width"), 1, 0)
        geometry.addWidget(self.w_spin, 1, 1)
        geometry.addWidget(QLabel("Height"), 1, 2)
        geometry.addWidget(self.h_spin, 1, 3)
        selection_layout.addLayout(geometry)

        self.specific = QStackedWidget(self)
        self.specific.addWidget(self._build_empty_page())
        self.specific.addWidget(self._build_text_page())
        self.specific.addWidget(self._build_image_page())
        selection_layout.addWidget(self.specific)

        actions = QHBoxLayout()
        self.duplicate_button = QPushButton("Duplicate", self)
        self.delete_button = QPushButton("Delete", self)
        actions.addWidget(self.duplicate_button)
        actions.addWidget(self.delete_button)
        actions.addStretch(1)
        selection_layout.addLayout(actions)

        root.addWidget(self.selection_group)

        self.document_group = QGroupBox("Label & output", self)
        document_layout = QVBoxLayout(self.document_group)
        document_layout.setSpacing(8)

        document_form = QFormLayout()
        document_form.setHorizontalSpacing(8)
        document_form.setVerticalSpacing(4)
        self.preset_combo = QComboBox(self)
        self.preset_combo.addItem("Custom", None)
        for preset in LABEL_PRESETS:
            self.preset_combo.addItem(preset.name, preset)
        self.width_spin = _mm_spin(200, 1)
        self.length_spin = _mm_spin(500, 1)
        self.gap_spin = _mm_spin(50, 0)
        self.orientation_combo = QComboBox(self)
        for orientation in (Orientation.PORTRAIT, Orientation.LANDSCAPE):
            self.orientation_combo.addItem(orientation.label, orientation)
        self.style_combo = QComboBox(self)
        for style in (Style.GAPPED, Style.CONTINUOUS, Style.BLACKMARK):
            self.style_combo.addItem(style.name.replace("_", " ").title(), style)
        document_form.addRow("Preset", self.preset_combo)
        document_form.addRow("Orientation", self.orientation_combo)
        document_form.addRow("Width", self.width_spin)
        document_form.addRow("Length", self.length_spin)
        document_form.addRow("Gap", self.gap_spin)
        document_form.addRow("Paper type", self.style_combo)
        document_layout.addLayout(document_form)

        self.roll_label = QLabel("No roll detected", self)
        self.roll_label.setWordWrap(True)
        self.roll_label.setProperty("role", "info")
        self.use_roll_button = QPushButton("Use printer roll", self)
        roll_row = QHBoxLayout()
        roll_row.addWidget(self.roll_label, 1)
        roll_row.addWidget(self.use_roll_button)
        document_layout.addLayout(roll_row)

        output_form = QFormLayout()
        output_form.setHorizontalSpacing(8)
        output_form.setVerticalSpacing(4)
        self.halftone_combo = QComboBox(self)
        for mode in (Halftone.AUTO, Halftone.FLOYD, Halftone.THRESHOLD):
            self.halftone_combo.addItem(mode.label, mode)
        self.thermal_check = QCheckBox("Lift shadows for thermal head", self)
        output_form.addRow("Halftone", self.halftone_combo)
        output_form.addRow("", self.thermal_check)
        document_layout.addLayout(output_form)

        self.width_note = QLabel("", self)
        self.width_note.setWordWrap(True)
        self.width_note.setProperty("role", "hint")
        document_layout.addWidget(self.width_note)

        root.addWidget(self.document_group)
        root.addStretch(1)

        # --- wiring ----------------------------------------------------------
        for spin, field in (
            (self.x_spin, "x_mm"),
            (self.y_spin, "y_mm"),
            (self.w_spin, "width_mm"),
            (self.h_spin, "height_mm"),
        ):
            spin.valueChanged.connect(
                lambda value, field=field: self._live_element(field, value)
            )
            spin.editingFinished.connect(lambda: self.controller.commit("Change geometry"))
        for spin, field in (
            (self.width_spin, "width_mm"),
            (self.length_spin, "length_mm"),
            (self.gap_spin, "gap_mm"),
        ):
            spin.valueChanged.connect(
                lambda value, field=field: self._live_document(field, value)
            )
            spin.editingFinished.connect(lambda: self.controller.commit("Change label size"))

        self.text_edit.textChanged.connect(self._on_text_changed)
        self.text_edit.editingFinished.connect(
            lambda: self.controller.commit("Edit text")
        )
        self.font_combo.currentFontChanged.connect(self._on_font_changed)
        for spin in (self.font_size_spin, self.line_spacing_spin):
            spin.valueChanged.connect(self._on_text_style_changed)
            spin.editingFinished.connect(lambda: self.controller.commit("Change text style"))
        for button in (self.bold_button, self.italic_button, self.underline_button):
            button.toggled.connect(self._on_text_style_changed)
        for combo in (self.align_combo, self.valign_combo):
            combo.currentIndexChanged.connect(self._on_text_style_changed)

        self.fit_combo.currentIndexChanged.connect(self._on_image_fit_changed)
        self.replace_button.clicked.connect(self._request_replace_image)
        self.fit_size_button.clicked.connect(self._fit_image_height)

        self.duplicate_button.clicked.connect(self.duplicateRequested.emit)
        self.delete_button.clicked.connect(self.deleteRequested.emit)

        self.preset_combo.activated.connect(self._apply_preset)
        self.orientation_combo.currentIndexChanged.connect(self._on_orientation_changed)
        self.style_combo.currentIndexChanged.connect(self._on_document_style_changed)
        self.halftone_combo.currentIndexChanged.connect(self._on_output_changed)
        self.thermal_check.toggled.connect(self._on_output_changed)
        self.use_roll_button.clicked.connect(self._use_printer_roll)

        self.refresh_document()
        self.set_element(None)

    # --- page builders -------------------------------------------------------
    def _build_empty_page(self) -> QWidget:
        page = QWidget(self)
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        label = QLabel("Select an element on the canvas, or add one with the toolbar.", page)
        label.setWordWrap(True)
        label.setProperty("role", "hint")
        layout.addWidget(label)
        layout.addStretch(1)
        return page

    def _build_text_page(self) -> QWidget:
        page = QWidget(self)
        layout = QFormLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setHorizontalSpacing(8)
        layout.setVerticalSpacing(5)

        self.text_edit = CommitPlainTextEdit(page)
        self.text_edit.setPlaceholderText("Label text")
        self.text_edit.setFixedHeight(88)
        self.text_edit.setTabChangesFocus(True)
        layout.addRow("Text", self.text_edit)

        self.font_combo = QFontComboBox(page)
        layout.addRow("Font", self.font_combo)

        self.font_size_spin = QDoubleSpinBox(page)
        self.font_size_spin.setRange(3, 200)
        self.font_size_spin.setDecimals(1)
        self.font_size_spin.setSingleStep(0.5)
        self.font_size_spin.setSuffix(" pt")
        layout.addRow("Size", self.font_size_spin)

        style_row = QHBoxLayout()
        self.bold_button = QToolButton(page)
        self.bold_button.setText("B")
        self.bold_button.setCheckable(True)
        bold_font = self.bold_button.font()
        bold_font.setBold(True)
        self.bold_button.setFont(bold_font)
        self.italic_button = QToolButton(page)
        self.italic_button.setText("I")
        self.italic_button.setCheckable(True)
        italic_font = self.italic_button.font()
        italic_font.setItalic(True)
        self.italic_button.setFont(italic_font)
        self.underline_button = QToolButton(page)
        self.underline_button.setText("U")
        self.underline_button.setCheckable(True)
        underline_font = self.underline_button.font()
        underline_font.setUnderline(True)
        self.underline_button.setFont(underline_font)
        style_row.addWidget(self.bold_button)
        style_row.addWidget(self.italic_button)
        style_row.addWidget(self.underline_button)
        style_row.addStretch(1)
        layout.addRow("Style", style_row)

        self.align_combo = QComboBox(page)
        for align in (HorizontalAlign.LEFT, HorizontalAlign.CENTER, HorizontalAlign.RIGHT):
            self.align_combo.addItem(align.label, align)
        layout.addRow("Align", self.align_combo)

        self.valign_combo = QComboBox(page)
        for valign in (VerticalAlign.TOP, VerticalAlign.MIDDLE, VerticalAlign.BOTTOM):
            self.valign_combo.addItem(valign.label, valign)
        layout.addRow("Vertical", self.valign_combo)

        self.line_spacing_spin = QDoubleSpinBox(page)
        self.line_spacing_spin.setRange(0.5, 3.0)
        self.line_spacing_spin.setDecimals(1)
        self.line_spacing_spin.setSingleStep(0.1)
        self.line_spacing_spin.setSuffix("×")
        layout.addRow("Line spacing", self.line_spacing_spin)
        return page

    def _build_image_page(self) -> QWidget:
        page = QWidget(self)
        layout = QFormLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setHorizontalSpacing(8)
        layout.setVerticalSpacing(5)

        self.image_name = QLabel("", page)
        self.image_name.setWordWrap(True)
        self.image_name.setProperty("role", "info")
        layout.addRow("Image", self.image_name)

        self.replace_button = QPushButton("Choose image…", page)
        layout.addRow("", self.replace_button)

        self.fit_combo = QComboBox(page)
        for fit in (ImageFit.CONTAIN, ImageFit.STRETCH, ImageFit.COVER):
            self.fit_combo.addItem(fit.label, fit)
        layout.addRow("Scaling", self.fit_combo)

        self.fit_size_button = QPushButton("Fit height to image aspect", page)
        layout.addRow("", self.fit_size_button)

        hint = QLabel(
            "Images are embedded in the document, so templates stay portable.",
            page,
        )
        hint.setWordWrap(True)
        hint.setProperty("role", "hint")
        layout.addRow("", hint)
        return page

    # --- selection -----------------------------------------------------------
    def set_element(self, element: object | None) -> None:
        if element is None or not hasattr(element, "id"):
            self._element_id = ""
            self.selection_group.setVisible(False)
            return

        self._element_id = str(element.id)
        self.selection_group.setVisible(True)
        self._updating = True
        try:
            self._set_geometry_spins(element)
            if isinstance(element, TextElement):
                self.kind_label.setText("Text element")
                self.specific.setCurrentIndex(1)
                self._populate_text(element)
            elif isinstance(element, ImageElement):
                self.kind_label.setText("Image element")
                self.specific.setCurrentIndex(2)
                self._populate_image(element)
            else:
                self.kind_label.setText("Element")
                self.specific.setCurrentIndex(0)
        finally:
            self._updating = False

    def _element(self) -> TextElement | ImageElement | None:
        if not self._element_id:
            return None
        element = self.controller.document.find(self._element_id)
        if isinstance(element, (TextElement, ImageElement)):
            return element
        return None

    def _set_geometry_spins(self, element: TextElement | ImageElement) -> None:
        for spin, value in (
            (self.x_spin, element.x_mm),
            (self.y_spin, element.y_mm),
            (self.w_spin, element.width_mm),
            (self.h_spin, element.height_mm),
        ):
            spin.blockSignals(True)
            spin.setValue(value)
            spin.blockSignals(False)

    def sync_geometry(self) -> None:
        element = self._element()
        if element is None:
            return
        self._updating = True
        try:
            self._set_geometry_spins(element)
        finally:
            self._updating = False

    def _populate_text(self, element: TextElement) -> None:
        self.text_edit.blockSignals(True)
        self.text_edit.setPlainText(element.text)
        self.text_edit.blockSignals(False)
        self.font_combo.blockSignals(True)
        self.font_combo.setCurrentFont(QFont(element.font_family))
        self.font_combo.blockSignals(False)
        for spin, value in (
            (self.font_size_spin, element.font_size_pt),
            (self.line_spacing_spin, element.line_spacing),
        ):
            spin.blockSignals(True)
            spin.setValue(value)
            spin.blockSignals(False)
        for button, value in (
            (self.bold_button, element.bold),
            (self.italic_button, element.italic),
            (self.underline_button, element.underline),
        ):
            button.blockSignals(True)
            button.setChecked(value)
            button.blockSignals(False)
        for combo, value in (
            (self.align_combo, element.align),
            (self.valign_combo, element.valign),
        ):
            combo.blockSignals(True)
            combo.setCurrentIndex(max(0, combo.findData(value)))
            combo.blockSignals(False)

    def _populate_image(self, element: ImageElement) -> None:
        self.image_name.setText(element.source_name or "Embedded image")
        self.fit_combo.blockSignals(True)
        self.fit_combo.setCurrentIndex(max(0, self.fit_combo.findData(element.fit)))
        self.fit_combo.blockSignals(False)

    def focus_text_editor(self) -> None:
        if self.specific.currentIndex() == 1:
            self.text_edit.setFocus()

    # --- element edits -------------------------------------------------------
    def _page_size_mm(self) -> tuple[float, float]:
        return self.controller.document.page_size_mm(self._head_width_mm)

    def _live_element(self, field: str, value: float) -> None:
        if self._updating:
            return
        element = self._element()
        if element is None:
            return
        page_width, page_height = self._page_size_mm()
        if field == "x_mm":
            value = min(max(0.0, value), max(0.0, page_width - element.width_mm))
        elif field == "y_mm":
            value = min(max(0.0, value), max(0.0, page_height - element.height_mm))
        elif field == "width_mm":
            value = min(max(0.5, value), max(0.5, page_width - element.x_mm))
        elif field == "height_mm":
            value = min(max(0.5, value), max(0.5, page_height - element.y_mm))
        self.controller.live(lambda: setattr(element, field, value))
        if field in {"width_mm", "height_mm"}:
            self._set_geometry_spins(element)

    def _on_text_changed(self) -> None:
        if self._updating:
            return
        element = self._element()
        if isinstance(element, TextElement):
            text = self.text_edit.toPlainText()
            self.controller.live(lambda: setattr(element, "text", text))

    def _on_font_changed(self, font: QFont) -> None:
        if self._updating:
            return
        element = self._element()
        if isinstance(element, TextElement):
            family = font.family()
            self.controller.live(lambda: setattr(element, "font_family", family))
            self.controller.commit("Change font")

    def _on_text_style_changed(self, *_: object) -> None:
        if self._updating:
            return
        element = self._element()
        if not isinstance(element, TextElement):
            return
        align = _enum_value(self.align_combo, HorizontalAlign)
        valign = _enum_value(self.valign_combo, VerticalAlign)

        def mutate() -> None:
            element.bold = self.bold_button.isChecked()
            element.italic = self.italic_button.isChecked()
            element.underline = self.underline_button.isChecked()
            element.font_size_pt = self.font_size_spin.value()
            element.line_spacing = self.line_spacing_spin.value()
            if align is not None:
                element.align = align
            if valign is not None:
                element.valign = valign

        self.controller.edit(mutate, "Change text style")

    def _on_image_fit_changed(self, *_: object) -> None:
        if self._updating:
            return
        element = self._element()
        if not isinstance(element, ImageElement):
            return
        fit = _enum_value(self.fit_combo, ImageFit)
        if fit is not None:
            self.controller.edit(lambda: setattr(element, "fit", fit), "Change image scaling")

    def _request_replace_image(self) -> None:
        if self._element_id:
            self.replaceImageRequested.emit(self._element_id)

    def _fit_image_height(self) -> None:
        element = self._element()
        if not isinstance(element, ImageElement):
            return
        try:
            image = decode_image(element.png_base64)
        except RenderError:
            return
        if image.width() <= 0:
            return
        height = element.width_mm * image.height() / image.width()
        _, page_height = self._page_size_mm()
        height = min(max(0.5, height), page_height - element.y_mm)
        self.controller.edit(lambda: setattr(element, "height_mm", height), "Fit image")
        self.sync_geometry()

    # --- document edits ------------------------------------------------------
    def refresh_document(self) -> None:
        document = self.controller.document
        self._updating = True
        try:
            for spin, value in (
                (self.width_spin, document.width_mm),
                (self.length_spin, document.length_mm),
                (self.gap_spin, document.gap_mm),
            ):
                spin.blockSignals(True)
                spin.setValue(value)
                spin.blockSignals(False)
            self.style_combo.blockSignals(True)
            self.style_combo.setCurrentIndex(max(0, self.style_combo.findData(document.style)))
            self.style_combo.blockSignals(False)
            self.orientation_combo.blockSignals(True)
            self.orientation_combo.setCurrentIndex(
                max(0, self.orientation_combo.findData(document.orientation))
            )
            self.orientation_combo.blockSignals(False)
            self.halftone_combo.blockSignals(True)
            self.halftone_combo.setCurrentIndex(
                max(0, self.halftone_combo.findData(document.halftone))
            )
            self.halftone_combo.blockSignals(False)
            self.thermal_check.blockSignals(True)
            self.thermal_check.setChecked(document.thermal_correction)
            self.thermal_check.blockSignals(False)
            self._sync_preset_combo()
            self._update_width_note()
        finally:
            self._updating = False

    def _sync_preset_combo(self) -> None:
        document = self.controller.document
        self.preset_combo.blockSignals(True)
        index = 0
        for candidate in range(self.preset_combo.count()):
            preset = self.preset_combo.itemData(candidate)
            if isinstance(preset, LabelPreset) and document.matches_preset(preset):
                index = candidate
                break
        self.preset_combo.setCurrentIndex(index)
        self.preset_combo.blockSignals(False)

    def _live_document(self, field: str, value: float) -> None:
        if self._updating:
            return
        document = self.controller.document
        if field == "width_mm":
            value = min(max(1.0, value), 200.0)
        elif field == "length_mm":
            value = min(max(1.0, value), 500.0)
        elif field == "gap_mm":
            value = min(max(0.0, value), 50.0)
        self.controller.live(lambda: setattr(document, field, value))
        self._sync_preset_combo()
        self._update_width_note()

    def _on_orientation_changed(self, *_: object) -> None:
        if self._updating:
            return
        orientation = _enum_value(self.orientation_combo, Orientation)
        document = self.controller.document
        if orientation is None or orientation == document.orientation:
            return

        def mutate() -> None:
            document.orientation = orientation
            for element in document.elements:
                # Rotate the layout with the page.  Text and images remain
                # upright in the editor; the renderer rotates the whole label
                # for the printer's fixed-width head.
                element.x_mm, element.y_mm = element.y_mm, element.x_mm
                element.width_mm, element.height_mm = (
                    element.height_mm,
                    element.width_mm,
                )

        self.controller.edit(mutate, "Change orientation")
        self.refresh_document()
        self.sync_geometry()

    def _on_document_style_changed(self, *_: object) -> None:
        if self._updating:
            return
        style = _enum_value(self.style_combo, Style)
        if style is not None:
            self.controller.edit(
                lambda: setattr(self.controller.document, "style", style),
                "Change paper type",
            )
            self._sync_preset_combo()

    def _on_output_changed(self, *_: object) -> None:
        if self._updating:
            return
        document = self.controller.document
        halftone = _enum_value(self.halftone_combo, Halftone)
        thermal = self.thermal_check.isChecked()

        def mutate() -> None:
            if halftone is not None:
                document.halftone = halftone
            document.thermal_correction = thermal

        self.controller.edit(mutate, "Change output settings")

    def _apply_preset(self, index: int) -> None:
        preset = self.preset_combo.itemData(index)
        if not isinstance(preset, LabelPreset):
            return
        document = self.controller.document
        orientation = preset.orientation
        page_width, page_height = preset.width_mm, preset.length_mm
        if orientation == Orientation.LANDSCAPE:
            page_width, page_height = (
                preset.length_mm,
                min(preset.width_mm, self._head_width_mm),
            )
        else:
            page_width = min(preset.width_mm, self._head_width_mm)

        def mutate() -> None:
            if document.orientation != orientation:
                for element in document.elements:
                    element.x_mm, element.y_mm = element.y_mm, element.x_mm
                    element.width_mm, element.height_mm = (
                        element.height_mm,
                        element.width_mm,
                    )
            document.width_mm = preset.width_mm
            document.length_mm = preset.length_mm
            document.gap_mm = preset.gap_mm
            document.style = preset.style
            document.orientation = orientation
            for element in document.elements:
                element.x_mm = min(
                    element.x_mm, max(0.0, page_width - element.width_mm)
                )
                element.y_mm = min(
                    element.y_mm, max(0.0, page_height - element.height_mm)
                )

        self.controller.edit(mutate, "Change label preset")
        self.refresh_document()

    def _update_width_note(self) -> None:
        document = self.controller.document
        page_width, page_height = document.page_size_mm(self._head_width_mm)
        if document.orientation == Orientation.LANDSCAPE:
            note = (
                f"Printable area: {page_width:g} × {page_height:g} mm "
                f"({self._head_width_mm:g} mm print head)."
            )
        else:
            note = (
                f"Printable width: {page_width:g} mm "
                f"({self._head_width_mm:g} mm print head)."
            )
        if document.width_mm > self._head_width_mm:
            note += " Wider labels are clipped by the head."
        self.width_note.setText(note)

    def set_head_width(self, head_width_mm: float) -> None:
        self._head_width_mm = max(1.0, head_width_mm)
        self._update_width_note()

    def set_detected_label(self, label: LabelRoll | None) -> None:
        self._detected_label = label
        if label is not None and label.has_tag and label.width_mm and label.length_mm:
            self.roll_label.setText(
                f"Detected roll: {label.width_mm:g} × {label.length_mm:g} mm · "
                f"{label.style} · {label.color}"
            )
            self.use_roll_button.setEnabled(True)
        else:
            self.roll_label.setText("No readable roll tag on the printer.")
            self.use_roll_button.setEnabled(False)

    def _use_printer_roll(self) -> None:
        label = self._detected_label
        if label is None or not label.has_tag or not label.width_mm or not label.length_mm:
            return
        document = self.controller.document

        def mutate() -> None:
            document.width_mm = label.width_mm
            document.length_mm = label.length_mm
            document.gap_mm = label.gap_mm
            document.style = label.style

        self.controller.edit(mutate, "Use printer label size")
        self.refresh_document()

    def commit_pending(self) -> None:
        self.controller.commit("Edit")
