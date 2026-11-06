from __future__ import annotations

from thermling.worker import PrinterWorker


def test_worker_defers_reentrant_print(qtbot):
    worker = PrinterWorker()
    results: list[tuple[bool, str]] = []
    worker.printFinished.connect(lambda ok, message: results.append((ok, message)))

    worker._busy = True
    worker.print_job(b"payload")

    assert results == []
    assert len(worker._pending) == 1

    worker._finish_operation()

    assert results == [(False, "Not connected to the printer")]
    assert not worker._busy
    assert not worker._pending


def test_worker_defers_reentrant_refresh(qtbot):
    worker = PrinterWorker()
    errors: list[str] = []
    worker.error.connect(errors.append)

    worker._busy = True
    worker.refresh()
    assert errors == []

    worker._finish_operation()

    assert errors == ["Not connected to the printer"]


def test_worker_disconnect_during_operation_is_reported_afterwards(qtbot):
    worker = PrinterWorker()
    events: list[str] = []
    worker.disconnected.connect(lambda: events.append("disconnected"))
    worker._pending.append(lambda: events.append("queued"))

    worker._busy = True
    worker.disconnect_from()

    assert events == []
    assert not worker._pending

    worker._finish_operation()

    assert events == ["disconnected"]
    assert not worker._busy


def test_worker_disconnect_cancels_queued_print(qtbot):
    worker = PrinterWorker()
    results: list[tuple[bool, str]] = []
    worker.printFinished.connect(lambda ok, message: results.append((ok, message)))

    worker._busy = True
    worker.print_job(b"payload")
    worker.disconnect_from()

    assert results == [(False, "Print cancelled")]
    assert not worker._pending
    assert worker._queued_prints == 0


def test_worker_disconnect_when_idle_emits_immediately(qtbot):
    worker = PrinterWorker()
    events: list[str] = []
    worker.disconnected.connect(lambda: events.append("disconnected"))

    worker.disconnect_from()

    assert events == ["disconnected"]


def test_worker_connect_failure_resets_busy_flag(qtbot):
    worker = PrinterWorker()
    failures: list[str] = []
    worker.connectionFailed.connect(failures.append)

    worker.connect_to(
        {"mac": "nope", "backend": "socket", "device": "/dev/rfcomm0", "take_over": False}
    )

    assert failures and "Invalid Bluetooth address" in failures[0]
    assert not worker._busy
