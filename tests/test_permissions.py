from __future__ import annotations

from PySide6.QtCore import Qt

from thermling import permissions


class _Result:
    def __init__(self, status: Qt.PermissionStatus) -> None:
        self._status = status

    def status(self) -> Qt.PermissionStatus:
        return self._status


class _FakeApplication:
    def __init__(
        self,
        statuses: dict[str, Qt.PermissionStatus],
        answers: dict[str, Qt.PermissionStatus] | None = None,
    ) -> None:
        self.statuses = statuses
        self.answers = answers or {}
        self.requested: list[str] = []

    def checkPermission(self, permission: str) -> Qt.PermissionStatus:
        return self.statuses[permission]

    def requestPermission(self, permission, context, callback) -> None:
        del context
        self.requested.append(permission)
        callback(_Result(self.answers[permission]))


def _install_fake_app(monkeypatch, app: _FakeApplication) -> None:
    class _FakeCoreApplication:
        @staticmethod
        def instance() -> _FakeApplication:
            return app

    monkeypatch.setattr(permissions, "ANDROID", True)
    monkeypatch.setattr(permissions, "QCoreApplication", _FakeCoreApplication)
    monkeypatch.setattr(permissions, "_bluetooth_permission", lambda: "bluetooth")
    monkeypatch.setattr(permissions, "_location_permission", lambda: "location")


def test_permission_status_requires_bluetooth_and_location(monkeypatch):
    statuses = {
        "bluetooth": Qt.PermissionStatus.Granted,
        "location": Qt.PermissionStatus.Undetermined,
    }
    _install_fake_app(monkeypatch, _FakeApplication(statuses))

    assert permissions.permission_status() == Qt.PermissionStatus.Undetermined

    statuses["location"] = Qt.PermissionStatus.Denied

    assert permissions.permission_status() == Qt.PermissionStatus.Denied

    statuses["location"] = Qt.PermissionStatus.Granted

    assert permissions.permission_status() == Qt.PermissionStatus.Granted


def test_request_android_permissions_requests_both(monkeypatch):
    app = _FakeApplication(
        {
            "bluetooth": Qt.PermissionStatus.Undetermined,
            "location": Qt.PermissionStatus.Undetermined,
        },
        {
            "bluetooth": Qt.PermissionStatus.Granted,
            "location": Qt.PermissionStatus.Granted,
        },
    )
    _install_fake_app(monkeypatch, app)
    results: list[bool] = []

    permissions.request_android_permissions(None, results.append)

    assert app.requested == ["bluetooth", "location"]
    assert results == [True]


def test_request_android_permissions_stops_when_bluetooth_is_denied(monkeypatch):
    app = _FakeApplication(
        {
            "bluetooth": Qt.PermissionStatus.Undetermined,
            "location": Qt.PermissionStatus.Undetermined,
        },
        {"bluetooth": Qt.PermissionStatus.Denied},
    )
    _install_fake_app(monkeypatch, app)
    results: list[bool] = []

    permissions.request_android_permissions(None, results.append)

    assert app.requested == ["bluetooth"]
    assert results == [False]


def test_request_android_permissions_reports_denied_location(monkeypatch):
    app = _FakeApplication(
        {
            "bluetooth": Qt.PermissionStatus.Undetermined,
            "location": Qt.PermissionStatus.Undetermined,
        },
        {
            "bluetooth": Qt.PermissionStatus.Granted,
            "location": Qt.PermissionStatus.Denied,
        },
    )
    _install_fake_app(monkeypatch, app)
    results: list[bool] = []

    permissions.request_android_permissions(None, results.append)

    assert app.requested == ["bluetooth", "location"]
    assert results == [False]


def test_request_android_permissions_skips_granted_permissions(monkeypatch):
    app = _FakeApplication(
        {
            "bluetooth": Qt.PermissionStatus.Granted,
            "location": Qt.PermissionStatus.Granted,
        }
    )
    _install_fake_app(monkeypatch, app)
    results: list[bool] = []

    permissions.request_android_permissions(None, results.append)

    assert app.requested == []
    assert results == [True]
