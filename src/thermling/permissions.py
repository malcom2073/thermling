"""Android runtime permissions for Bluetooth access."""

from __future__ import annotations

from collections.abc import Callable

from PySide6.QtCore import (
    QBluetoothPermission,
    QCoreApplication,
    QObject,
    QPermission,
    Qt,
)

from .runtime import ANDROID


def _bluetooth_permission() -> QBluetoothPermission:
    permission = QBluetoothPermission()
    permission.setCommunicationModes(
        QBluetoothPermission.CommunicationMode.Access
    )
    return permission


def bluetooth_permission_status() -> Qt.PermissionStatus:
    """Return the current status, or ``Granted`` off Android."""

    if not ANDROID:
        return Qt.PermissionStatus.Granted
    app = QCoreApplication.instance()
    if app is None:
        return Qt.PermissionStatus.Undetermined
    return app.checkPermission(_bluetooth_permission())


def request_bluetooth_permission(
    context: QObject, callback: Callable[[bool], None]
) -> None:
    """Request Android Bluetooth access and report the result asynchronously."""

    if not ANDROID:
        callback(True)
        return
    app = QCoreApplication.instance()
    if app is None:
        callback(False)
        return
    permission = _bluetooth_permission()
    status = app.checkPermission(permission)
    if status == Qt.PermissionStatus.Granted:
        callback(True)
        return
    if status == Qt.PermissionStatus.Denied:
        callback(False)
        return

    def finished(result: QPermission) -> None:
        callback(result.status() == Qt.PermissionStatus.Granted)

    app.requestPermission(permission, context, finished)
