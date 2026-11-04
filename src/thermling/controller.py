"""Document controller with snapshot-based undo/redo."""

from __future__ import annotations

from collections.abc import Callable

from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QUndoCommand, QUndoStack

from .document import Element, LabelDocument


class _SnapshotCommand(QUndoCommand):
    def __init__(
        self,
        controller: DocumentController,
        before: dict,
        after: dict,
        text: str,
    ) -> None:
        super().__init__(text)
        self._controller = controller
        self._before = before
        self._after = after

    def undo(self) -> None:
        self._controller._restore(self._before)

    def redo(self) -> None:
        if self._controller.document.to_dict() != self._after:
            self._controller._restore(self._after)


class DocumentController(QObject):
    changed = Signal()
    reset = Signal()
    dirtyChanged = Signal(bool)

    def __init__(self, document: LabelDocument | None = None) -> None:
        super().__init__()
        self._document = document or LabelDocument()
        self._baseline = self._document.to_dict()
        self._saved_state = self._baseline
        self._dirty = False
        self._restoring = False
        self.undo_stack = QUndoStack(self)

    @property
    def document(self) -> LabelDocument:
        return self._document

    @property
    def is_dirty(self) -> bool:
        return self._dirty

    # --- document lifecycle --------------------------------------------------
    def set_document(self, document: LabelDocument, *, mark_saved: bool = True) -> None:
        self._document = document
        self._baseline = document.to_dict()
        if mark_saved:
            self._saved_state = self._baseline
        self.undo_stack.clear()
        self._set_dirty(False if mark_saved else self._baseline != self._saved_state)
        self.reset.emit()
        self.changed.emit()

    def mark_saved(self) -> None:
        self._saved_state = self._document.to_dict()
        self._set_dirty(False)

    # --- editing -------------------------------------------------------------
    def live(self, mutate: Callable[[], None]) -> None:
        """Apply an edit without pushing an undo step (e.g. during a drag)."""
        if self._restoring:
            return
        mutate()
        self._set_dirty(self._document.to_dict() != self._saved_state)
        self.changed.emit()

    def commit(self, text: str) -> None:
        """Push everything since the last commit as one undo step."""
        if self._restoring:
            return
        after = self._document.to_dict()
        if after != self._baseline:
            command = _SnapshotCommand(self, self._baseline, after, text)
            self._baseline = after
            self.undo_stack.push(command)
        self._set_dirty(after != self._saved_state)

    def edit(self, mutate: Callable[[], None], text: str) -> None:
        self.live(mutate)
        self.commit(text)

    def add_element(self, element: Element, text: str = "Add element") -> None:
        self.edit(lambda: self._document.add(element), text)

    def remove_element(self, element_id: str, text: str = "Delete element") -> None:
        self.edit(lambda: self._document.remove(element_id), text)

    def duplicate_element(self, element_id: str) -> Element | None:
        holder: dict[str, Element | None] = {"clone": None}

        def mutate() -> None:
            holder["clone"] = self._document.duplicate(element_id)

        self.edit(mutate, "Duplicate element")
        return holder["clone"]

    def undo(self) -> None:
        self.commit("Edit")
        self.undo_stack.undo()

    def redo(self) -> None:
        self.undo_stack.redo()

    def _restore(self, state: dict) -> None:
        self._restoring = True
        try:
            self._document = LabelDocument.from_dict(state)
            self._baseline = self._document.to_dict()
        finally:
            self._restoring = False
        self._set_dirty(self._document.to_dict() != self._saved_state)
        self.reset.emit()
        self.changed.emit()

    def _set_dirty(self, dirty: bool) -> None:
        if dirty != self._dirty:
            self._dirty = dirty
            self.dirtyChanged.emit(dirty)
