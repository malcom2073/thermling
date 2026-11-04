"""Interactive label canvas: draggable/resizable text and image items."""

from __future__ import annotations

from PySide6.QtCore import QEvent, QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import (
    QFrame,
    QGraphicsItem,
    QGraphicsObject,
    QGraphicsRectItem,
    QGraphicsScene,
    QGraphicsView,
    QMenu,
    QStyleOptionGraphicsItem,
    QWidget,
)

from ..controller import DocumentController
from ..document import Element, ImageElement, Orientation, TextElement
from ..protocol import bitmap_width_dots, mm_to_dots
from ..render import (
    RenderError,
    draw_image_element,
    draw_text_element,
)
from ..theme import PAGE_WHITE, current_theme

HANDLE_SIZE = 11.0  # scene units (printer dots)
MIN_SIZE_MM = 0.5
SNAP_MM = 0.5
PAGE_MARGIN_MM = 8.0
CURSORS = {
    "tl": Qt.CursorShape.SizeFDiagCursor,
    "br": Qt.CursorShape.SizeFDiagCursor,
    "tr": Qt.CursorShape.SizeBDiagCursor,
    "bl": Qt.CursorShape.SizeBDiagCursor,
    "t": Qt.CursorShape.SizeVerCursor,
    "b": Qt.CursorShape.SizeVerCursor,
    "l": Qt.CursorShape.SizeHorCursor,
    "r": Qt.CursorShape.SizeHorCursor,
}


class ElementItem(QGraphicsObject):
    """Base canvas item for one document element."""

    editRequested = Signal(str)

    def __init__(
        self,
        element: Element,
        controller: DocumentController,
        view: CanvasView,
        dpi: int,
    ) -> None:
        super().__init__()
        self.element = element
        self.controller = controller
        self._view = view
        self._dpi = dpi
        self._width_dots = 1.0
        self._height_dots = 1.0
        self._syncing = False
        self._handle: str | None = None
        self._resizing = False
        self._start_rect = QRectF()
        self._start_scene = QPointF()
        self._start_pos = QPointF()

        self.setFlags(
            QGraphicsItem.GraphicsItemFlag.ItemIsSelectable
            | QGraphicsItem.GraphicsItemFlag.ItemIsMovable
            | QGraphicsItem.GraphicsItemFlag.ItemSendsGeometryChanges
        )
        self.setAcceptHoverEvents(True)
        self.setCursor(Qt.CursorShape.OpenHandCursor)
        self.sync()

    # --- geometry ------------------------------------------------------------
    @property
    def content_rect(self) -> QRectF:
        return QRectF(0, 0, self._width_dots, self._height_dots)

    def sync(self) -> None:
        dpm = self._dpi / 25.4
        width = max(1.0, self.element.width_mm * dpm)
        height = max(1.0, self.element.height_mm * dpm)
        if width != self._width_dots or height != self._height_dots:
            self.prepareGeometryChange()
            self._width_dots = width
            self._height_dots = height
        self._syncing = True
        try:
            self.setPos(self.element.x_mm * dpm, self.element.y_mm * dpm)
        finally:
            self._syncing = False
        self.update()

    def boundingRect(self) -> QRectF:
        return self.content_rect.adjusted(-HANDLE_SIZE, -HANDLE_SIZE, HANDLE_SIZE, HANDLE_SIZE)

    def shape(self) -> QPainterPath:
        path = QPainterPath()
        path.addRect(self.content_rect)
        if self.isSelected():
            for rect in self._handle_rects().values():
                path.addRect(rect)
        return path

    # --- painting ------------------------------------------------------------
    def paint(
        self,
        painter: QPainter,
        option: QStyleOptionGraphicsItem,
        widget: QWidget | None = None,
    ) -> None:
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, True)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        try:
            self.paint_content(painter)
        finally:
            painter.restore()

        painter.save()
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.setPen(QPen(QColor(0, 0, 0, 45), 0, Qt.PenStyle.DashLine))
        painter.drawRect(self.content_rect)
        painter.restore()

        if self.isSelected():
            painter.save()
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.setPen(QPen(QColor(current_theme().accent), 0))
            painter.drawRect(self.content_rect)
            painter.setBrush(QColor("white"))
            for rect in self._handle_rects().values():
                painter.drawRect(rect)
            painter.restore()

    def paint_content(self, painter: QPainter) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    # --- handles -------------------------------------------------------------
    def _handle_points(self) -> dict[str, QPointF]:
        rect = self.content_rect
        return {
            "tl": rect.topLeft(),
            "t": QPointF(rect.center().x(), rect.top()),
            "tr": rect.topRight(),
            "r": QPointF(rect.right(), rect.center().y()),
            "br": rect.bottomRight(),
            "b": QPointF(rect.center().x(), rect.bottom()),
            "bl": rect.bottomLeft(),
            "l": QPointF(rect.left(), rect.center().y()),
        }

    def _handle_rects(self) -> dict[str, QRectF]:
        half = HANDLE_SIZE / 2.0
        return {
            name: QRectF(point.x() - half, point.y() - half, HANDLE_SIZE, HANDLE_SIZE)
            for name, point in self._handle_points().items()
        }

    def _handle_at(self, position: QPointF) -> str | None:
        for name, rect in self._handle_rects().items():
            if rect.adjusted(-2, -2, 2, 2).contains(position):
                return name
        return None

    def hoverMoveEvent(self, event) -> None:
        handle = self._handle_at(event.pos()) if self.isSelected() else None
        self.setCursor(CURSORS.get(handle, Qt.CursorShape.OpenHandCursor))
        super().hoverMoveEvent(event)

    # --- interaction ---------------------------------------------------------
    def itemChange(self, change, value):
        if change == QGraphicsItem.GraphicsItemChange.ItemPositionChange and not self._syncing:
            dpm = self._dpi / 25.4
            page_width = self._view.page_width_dots
            page_height = self._view.page_height_dots
            x = value.x() / dpm
            y = value.y() / dpm
            max_x = max(0.0, (page_width - self._width_dots) / dpm)
            max_y = max(0.0, (page_height - self._height_dots) / dpm)
            x = min(max(0.0, x), max_x)
            y = min(max(0.0, y), max_y)
            if self._view.snap_enabled:
                x = round(x / SNAP_MM) * SNAP_MM
                y = round(y / SNAP_MM) * SNAP_MM
            return QPointF(x * dpm, y * dpm)
        if change == QGraphicsItem.GraphicsItemChange.ItemPositionHasChanged and not self._syncing:
            dpm = self._dpi / 25.4
            self.element.x_mm = self.pos().x() / dpm
            self.element.y_mm = self.pos().y() / dpm
            self.controller.live(lambda: None)
        return super().itemChange(change, value)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            handle = self._handle_at(event.pos()) if self.isSelected() else None
            if handle is not None:
                self._handle = handle
                self._resizing = True
                self._start_rect = QRectF(
                    self.pos().x(), self.pos().y(), self._width_dots, self._height_dots
                )
                self._start_scene = event.scenePos()
                event.accept()
                return
            self._start_pos = self.pos()
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:
        if self._resizing and self._handle is not None:
            self._apply_resize(event.scenePos())
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:
        if self._resizing:
            self._resizing = False
            self._handle = None
            self.controller.commit("Resize element")
            event.accept()
            return
        super().mouseReleaseEvent(event)
        self.controller.commit("Move element")

    def mouseDoubleClickEvent(self, event) -> None:
        self.editRequested.emit(self.element.id)
        event.accept()

    def _apply_resize(self, scene_position: QPointF) -> None:
        dpm = self._dpi / 25.4
        start = self._start_rect
        delta = scene_position - self._start_scene
        left, top, right, bottom = (
            start.left(),
            start.top(),
            start.right(),
            start.bottom(),
        )
        handle = self._handle or ""
        if "l" in handle:
            left += delta.x()
        if "r" in handle:
            right += delta.x()
        if "t" in handle:
            top += delta.y()
        if "b" in handle:
            bottom += delta.y()

        left_mm, right_mm = left / dpm, right / dpm
        top_mm, bottom_mm = top / dpm, bottom / dpm
        page_width_mm = self._view.page_width_mm
        page_height_mm = self._view.page_height_mm

        if self._view.snap_enabled:
            left_mm = round(left_mm / SNAP_MM) * SNAP_MM
            right_mm = round(right_mm / SNAP_MM) * SNAP_MM
            top_mm = round(top_mm / SNAP_MM) * SNAP_MM
            bottom_mm = round(bottom_mm / SNAP_MM) * SNAP_MM

        left_mm = min(max(0.0, left_mm), page_width_mm - MIN_SIZE_MM)
        top_mm = min(max(0.0, top_mm), page_height_mm - MIN_SIZE_MM)
        right_mm = min(max(left_mm + MIN_SIZE_MM, right_mm), page_width_mm)
        bottom_mm = min(max(top_mm + MIN_SIZE_MM, bottom_mm), page_height_mm)

        self.element.x_mm = left_mm
        self.element.y_mm = top_mm
        self.element.width_mm = max(MIN_SIZE_MM, right_mm - left_mm)
        self.element.height_mm = max(MIN_SIZE_MM, bottom_mm - top_mm)
        self.sync()
        self.controller.live(lambda: None)


class TextItem(ElementItem):
    element: TextElement

    def paint_content(self, painter: QPainter) -> None:
        if not self.element.text:
            painter.setPen(QColor(130, 130, 130))
            font = QFont(self.element.font_family)
            font.setPixelSize(max(10, min(20, int(self._height_dots * 0.35))))
            painter.setFont(font)
            painter.drawText(
                self.content_rect,
                Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap,
                "Double-click to add text",
            )
            return
        draw_text_element(painter, self.element, self.content_rect, self._dpi)


class ImageItem(ElementItem):
    element: ImageElement

    def paint_content(self, painter: QPainter) -> None:
        try:
            draw_image_element(painter, self.element, self.content_rect)
        except RenderError:
            painter.fillRect(self.content_rect, QColor(245, 245, 245))
            painter.setPen(QColor(160, 160, 160))
            painter.drawText(self.content_rect, Qt.AlignmentFlag.AlignCenter, "No image")


class CanvasView(QGraphicsView):
    selectionChanged = Signal(str)
    editRequested = Signal(str)
    deleteRequested = Signal()
    duplicateRequested = Signal()
    zoomChanged = Signal(float)

    def __init__(
        self, controller: DocumentController, parent: QWidget | None = None
    ) -> None:
        super().__init__(parent)
        self.controller = controller
        self._scene = QGraphicsScene(self)
        self.setScene(self._scene)
        self._items: dict[str, ElementItem] = {}
        self._page: QGraphicsRectItem | None = None
        self._shadow: QGraphicsRectItem | None = None
        self._dpi = 203
        self._printable_width_mm = 12.0
        self._page_width_dots = 1.0
        self._page_height_dots = 1.0
        self._page_width_mm = 1.0
        self._page_height_mm = 1.0
        self._snap_enabled = True
        self._grid_visible = True
        self._fit_pending = True
        self._rebuilding = False

        self.setRenderHints(
            QPainter.RenderHint.Antialiasing
            | QPainter.RenderHint.TextAntialiasing
            | QPainter.RenderHint.SmoothPixmapTransform
        )
        self.setDragMode(QGraphicsView.DragMode.RubberBandDrag)
        self.setTransformationAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setResizeAnchor(QGraphicsView.ViewportAnchor.AnchorViewCenter)
        self.setViewportUpdateMode(QGraphicsView.ViewportUpdateMode.BoundingRectViewportUpdate)
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setBackgroundBrush(QColor(current_theme().canvas))
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self._scene.selectionChanged.connect(self._on_selection_changed)
        self.rebuild()

    def apply_theme(self) -> None:
        """Refresh colours that are painted outside Qt's palette system."""
        theme = current_theme()
        self.setBackgroundBrush(QColor(theme.canvas))
        if self._shadow is not None:
            self._shadow.setBrush(QColor(0, 0, 0, theme.shadow_alpha))
        if self._page is not None:
            self._page.setBrush(QColor(PAGE_WHITE))
            self._page.setPen(QPen(QColor(theme.page_edge), 0))
        self.viewport().update()

    def changeEvent(self, event) -> None:
        if event.type() == QEvent.Type.PaletteChange:
            self.apply_theme()
        super().changeEvent(event)

    # --- document synchronisation -------------------------------------------
    def configure(self, dpi: int, head_width_mm: float) -> None:
        dpi = max(50, min(1200, dpi))
        if dpi == self._dpi and abs(head_width_mm - self._printable_width_mm) < 1e-6:
            return
        self._dpi = dpi
        self._printable_width_mm = head_width_mm
        self.rebuild()

    def rebuild(self) -> None:
        selected = self.selected_id()
        self._rebuilding = True
        try:
            self._items.clear()
            self._scene.clear()
            self._page = None
            self._shadow = None
            self._build_page()
            for element in self.controller.document.elements:
                self._create_item(element)
            self._update_scene_rect()
            if selected and selected in self._items:
                self._items[selected].setSelected(True)
        finally:
            self._rebuilding = False
        self.viewport().update()

    def sync_from_document(self) -> None:
        document = self.controller.document
        page_width, page_height = document.page_size_mm(self._printable_width_mm)
        if (
            abs(page_width - self._page_width_mm) > 1e-6
            or abs(page_height - self._page_height_mm) > 1e-6
        ):
            self._update_page_geometry()
        wanted = {element.id: element for element in document.elements}
        for element_id in list(self._items):
            if element_id not in wanted:
                item = self._items.pop(element_id)
                self._scene.removeItem(item)
        for element_id, element in wanted.items():
            item = self._items.get(element_id)
            if item is None:
                item = self._create_item(element)
            elif item.element is not element:
                item.element = element  # type: ignore[assignment]
            item.sync()
        self._update_scene_rect()

    # --- page and items ------------------------------------------------------
    def _build_page(self) -> None:
        theme = current_theme()
        self._update_page_geometry()
        self._shadow = QGraphicsRectItem(self._page_rect().translated(2.5, 2.5))
        self._shadow.setBrush(QColor(0, 0, 0, theme.shadow_alpha))
        self._shadow.setPen(QPen(Qt.PenStyle.NoPen))
        self._shadow.setZValue(-2)
        self._scene.addItem(self._shadow)

        self._page = QGraphicsRectItem(self._page_rect())
        self._page.setBrush(QColor(PAGE_WHITE))
        self._page.setPen(QPen(QColor(theme.page_edge), 0))
        self._page.setZValue(-1)
        self._scene.addItem(self._page)

    def _update_page_geometry(self) -> None:
        document = self.controller.document
        dpm = self._dpi / 25.4
        page_width, page_height = document.page_size_mm(self._printable_width_mm)
        self._page_width_mm = page_width
        self._page_height_mm = page_height
        if document.orientation == Orientation.LANDSCAPE:
            self._page_width_dots = max(1.0, mm_to_dots(page_width, self._dpi))
            self._page_height_dots = float(
                bitmap_width_dots(page_height, self._dpi)
            )
        else:
            self._page_width_dots = float(bitmap_width_dots(page_width, self._dpi))
            self._page_height_dots = max(1.0, page_height * dpm)
        if self._page is not None:
            self._page.setRect(self._page_rect())
        if self._shadow is not None:
            self._shadow.setRect(self._page_rect().translated(2.5, 2.5))

    def _page_rect(self) -> QRectF:
        return QRectF(0, 0, self._page_width_dots, self._page_height_dots)

    def _update_scene_rect(self) -> None:
        margin = PAGE_MARGIN_MM * self._dpi / 25.4
        self.setSceneRect(
            QRectF(
                -margin,
                -margin,
                self._page_width_dots + 2 * margin,
                self._page_height_dots + 2 * margin,
            )
        )

    def _create_item(self, element: Element) -> ElementItem:
        item: ElementItem
        if isinstance(element, TextElement):
            item = TextItem(element, self.controller, self, self._dpi)
        elif isinstance(element, ImageElement):
            item = ImageItem(element, self.controller, self, self._dpi)
        else:  # pragma: no cover - the model only has these two types
            raise TypeError(f"Unsupported element: {element!r}")
        item.editRequested.connect(self.editRequested.emit)
        item.setZValue(1)
        self._scene.addItem(item)
        self._items[element.id] = item
        return item

    # --- selection -----------------------------------------------------------
    def selected_id(self) -> str:
        for item in self._scene.selectedItems():
            if isinstance(item, ElementItem):
                return item.element.id
        return ""

    def selected_item(self) -> ElementItem | None:
        for item in self._scene.selectedItems():
            if isinstance(item, ElementItem):
                return item
        return None

    def select_element(self, element_id: str) -> None:
        item = self._items.get(element_id)
        if item is None:
            self._scene.clearSelection()
            return
        self._scene.clearSelection()
        item.setSelected(True)
        self.ensureVisible(item)

    def clear_selection(self) -> None:
        self._scene.clearSelection()

    def _on_selection_changed(self) -> None:
        if not self._rebuilding:
            self.selectionChanged.emit(self.selected_id())

    # --- view settings -------------------------------------------------------
    @property
    def snap_enabled(self) -> bool:
        return self._snap_enabled

    def set_snap_enabled(self, enabled: bool) -> None:
        self._snap_enabled = enabled

    @property
    def grid_visible(self) -> bool:
        return self._grid_visible

    def set_grid_visible(self, visible: bool) -> None:
        self._grid_visible = visible
        self.viewport().update()

    @property
    def dots_per_mm(self) -> float:
        return self._dpi / 25.4

    @property
    def page_width_dots(self) -> float:
        return self._page_width_dots

    @property
    def page_height_dots(self) -> float:
        return self._page_height_dots

    @property
    def page_width_mm(self) -> float:
        return self._page_width_mm

    @property
    def page_height_mm(self) -> float:
        return self._page_height_mm

    @property
    def dpi(self) -> int:
        return self._dpi

    def zoom(self) -> float:
        return self.transform().m11()

    def set_zoom(self, value: float) -> None:
        value = max(0.15, min(12.0, value))
        current = self.zoom()
        if abs(current - value) > 1e-4:
            self.scale(value / current, value / current)
            self.zoomChanged.emit(self.zoom())

    def zoom_by(self, factor: float) -> None:
        self.set_zoom(self.zoom() * factor)

    def fit_to_view(self) -> None:
        self.fitInView(self._scene.sceneRect(), Qt.AspectRatioMode.KeepAspectRatio)
        self._fit_pending = False
        self.zoomChanged.emit(self.zoom())

    # --- events --------------------------------------------------------------
    def showEvent(self, event) -> None:
        super().showEvent(event)
        if self._fit_pending:
            self.fit_to_view()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        if self._fit_pending:
            self.fit_to_view()

    def wheelEvent(self, event) -> None:
        if event.modifiers() & Qt.KeyboardModifier.ControlModifier:
            self.zoom_by(1.15 if event.angleDelta().y() > 0 else 1 / 1.15)
            event.accept()
            return
        super().wheelEvent(event)

    def keyPressEvent(self, event) -> None:
        if event.key() in (Qt.Key.Key_Delete, Qt.Key.Key_Backspace):
            self.deleteRequested.emit()
            event.accept()
            return
        if event.key() == Qt.Key.Key_Escape:
            self.clear_selection()
            event.accept()
            return
        if event.key() == Qt.Key.Key_D and event.modifiers() & Qt.KeyboardModifier.ControlModifier:
            self.duplicateRequested.emit()
            event.accept()
            return
        super().keyPressEvent(event)

    def contextMenuEvent(self, event) -> None:
        item = self.itemAt(event.pos())
        if not isinstance(item, ElementItem):
            return
        if not item.isSelected():
            self.select_element(item.element.id)
        menu = QMenu(self)
        duplicate = menu.addAction("Duplicate")
        delete = menu.addAction("Delete")
        chosen = menu.exec(event.globalPos())
        if chosen is duplicate:
            self.duplicateRequested.emit()
        elif chosen is delete:
            self.deleteRequested.emit()

    def drawBackground(self, painter: QPainter, rect: QRectF) -> None:
        super().drawBackground(painter, rect)
        if not self._grid_visible:
            return
        page = self._page_rect()
        clip = rect.intersected(page)
        if clip.isEmpty():
            return
        dpm = self.dots_per_mm
        theme = current_theme()
        painter.save()
        painter.setClipRect(clip)
        minor_color = QColor(theme.grid)
        minor_color.setAlpha(theme.grid_minor_alpha)
        major_color = QColor(theme.grid)
        major_color.setAlpha(theme.grid_major_alpha)
        minor = QPen(minor_color, 0)
        major = QPen(major_color, 0)
        start_x = max(0, int(clip.left() / dpm))
        end_x = min(int(page.width() / dpm) + 1, int(clip.right() / dpm) + 1)
        for mm in range(start_x, end_x + 1):
            x = mm * dpm
            painter.setPen(major if mm % 5 == 0 else minor)
            painter.drawLine(QPointF(x, clip.top()), QPointF(x, clip.bottom()))
        start_y = max(0, int(clip.top() / dpm))
        end_y = min(int(page.height() / dpm) + 1, int(clip.bottom() / dpm) + 1)
        for mm in range(start_y, end_y + 1):
            y = mm * dpm
            painter.setPen(major if mm % 5 == 0 else minor)
            painter.drawLine(QPointF(clip.left(), y), QPointF(clip.right(), y))
        painter.restore()

    def drawForeground(self, painter: QPainter, rect: QRectF) -> None:
        super().drawForeground(painter, rect)
        if self.controller.document.elements:
            return
        painter.save()
        painter.setPen(QColor(120, 128, 140))
        font = QFont()
        font.setPixelSize(max(12, int(3.0 * self.dots_per_mm)))
        painter.setFont(font)
        painter.drawText(
            self._page_rect(),
            Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap,
            "Add text or an image\nto start your label",
        )
        painter.restore()
