from __future__ import annotations

import pytest

from thermling.controller import DocumentController
from thermling.document import ImageElement, LabelDocument, TextElement


@pytest.fixture()
def controller(qapp):
    return DocumentController(LabelDocument())


def text_element(text: str = "Hello") -> TextElement:
    return TextElement(text=text, x_mm=1, y_mm=1, width_mm=8, height_mm=5)


def test_initial_state(controller: DocumentController):
    assert controller.document.elements == []
    assert not controller.is_dirty
    assert not controller.undo_stack.canUndo()
    assert not controller.undo_stack.canRedo()


def test_live_edit_only_marks_dirty(controller: DocumentController):
    element = text_element()
    controller.live(lambda: controller.document.add(element))
    assert controller.document.elements == [element]
    assert controller.is_dirty
    assert not controller.undo_stack.canUndo()

    controller.commit("Add element")
    assert controller.undo_stack.canUndo()
    assert controller.undo_stack.undoText() == "Add element"


def test_commit_without_changes_pushes_nothing(controller: DocumentController):
    controller.commit("Nothing")
    assert not controller.undo_stack.canUndo()


def test_edit_can_be_undone_and_redone(controller: DocumentController):
    element = text_element()
    controller.add_element(element)
    assert controller.document.find(element.id) is element
    assert controller.is_dirty

    controller.undo()
    assert controller.document.elements == []
    assert not controller.is_dirty
    assert controller.undo_stack.canRedo()

    controller.redo()
    assert controller.document.find(element.id) is not None
    assert controller.is_dirty


def test_multiple_live_changes_group_into_one_undo_step(controller: DocumentController):
    element = text_element()
    controller.add_element(element)
    controller.live(lambda: setattr(controller.document.find(element.id), "x_mm", 2))
    controller.live(lambda: setattr(controller.document.find(element.id), "y_mm", 3))
    controller.commit("Move element")

    assert controller.document.find(element.id).x_mm == 2
    controller.undo()
    assert controller.document.find(element.id).x_mm == 1
    assert controller.document.find(element.id).y_mm == 1
    controller.redo()
    moved = controller.document.find(element.id)
    assert moved.x_mm == 2
    assert moved.y_mm == 3


def test_remove_and_duplicate(controller: DocumentController):
    element = text_element()
    controller.add_element(element)
    clone = controller.duplicate_element(element.id)
    assert clone is not None
    assert len(controller.document.elements) == 2
    assert controller.undo_stack.undoText() == "Duplicate element"

    controller.remove_element(element.id)
    assert controller.document.find(element.id) is None
    assert controller.undo_stack.undoText() == "Delete element"

    controller.undo()
    assert controller.document.find(element.id) is not None


def test_duplicate_missing_returns_none(controller: DocumentController):
    assert controller.duplicate_element("missing") is None
    assert not controller.is_dirty


def test_set_document_resets_history(controller: DocumentController):
    controller.add_element(text_element())
    assert controller.undo_stack.canUndo()

    document = LabelDocument(name="New")
    controller.set_document(document)
    assert controller.document is document
    assert not controller.undo_stack.canUndo()
    assert not controller.is_dirty


def test_mark_saved_and_dirty_tracking(controller: DocumentController):
    element = text_element()
    controller.add_element(element)
    controller.mark_saved()
    assert not controller.is_dirty

    controller.live(lambda: setattr(element, "x_mm", 5))
    assert controller.is_dirty
    controller.undo()
    assert not controller.is_dirty


def test_restore_emits_reset_and_changed(controller: DocumentController):
    element = text_element()
    controller.add_element(element)

    events: list[str] = []
    controller.reset.connect(lambda: events.append("reset"))
    controller.changed.connect(lambda: events.append("changed"))
    controller.undo()
    assert events == ["reset", "changed"]


def test_set_document_without_mark_saved_preserves_dirty(controller: DocumentController):
    controller.add_element(text_element())
    controller.mark_saved()
    controller.live(lambda: controller.document.add(ImageElement()))
    assert controller.is_dirty

    replacement = LabelDocument(name="Replacement")
    controller.set_document(replacement, mark_saved=False)
    assert controller.is_dirty


def test_edit_ignored_while_restoring(controller: DocumentController):
    element = text_element()
    controller.add_element(element)
    controller._restoring = True
    try:
        controller.live(lambda: controller.document.add(text_element("ignored")))
        controller.commit("ignored")
        controller.edit(lambda: controller.document.add(text_element("ignored")), "x")
    finally:
        controller._restoring = False
    assert len(controller.document.elements) == 1
