"""Android runtime permissions for Bluetooth access."""

from __future__ import annotations

from collections.abc import Callable

from PySide6.QtCore import (
    QBluetoothPermission,
    QCoreApplication,
    QLocationPermission,
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


def _location_permission() -> QLocationPermission:
    permission = QLocationPermission()
    permission.setAccuracy(QLocationPermission.Accuracy.Precise)
    return permission


def bluetooth_permission_status() -> Qt.PermissionStatus:
    """Return the current Bluetooth permission status."""

    if not ANDROID:
        return Qt.PermissionStatus.Granted
    app = QCoreApplication.instance()
    if app is None:
        return Qt.PermissionStatus.Undetermined
    return app.checkPermission(_bluetooth_permission())


def location_permission_status() -> Qt.PermissionStatus:
    """Return the current precise-location permission status."""

    if not ANDROID:
        return Qt.PermissionStatus.Granted
    app = QCoreApplication.instance()
    if app is None:
        return Qt.PermissionStatus.Undetermined
    return app.checkPermission(_location_permission())


def permission_status() -> Qt.PermissionStatus:
    """Return the combined status of the permissions Bluetooth scanning needs."""

    statuses = (
        bluetooth_permission_status(),
        location_permission_status(),
    )
    if all(status == Qt.PermissionStatus.Granted for status in statuses):
        return Qt.PermissionStatus.Granted
    if any(status == Qt.PermissionStatus.Denied for status in statuses):
        return Qt.PermissionStatus.Denied
    return Qt.PermissionStatus.Undetermined


def _request_permission(
    app: QCoreApplication,
    permission: QPermission,
    context: QObject,
    callback: Callable[[bool], None],
) -> None:
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


def request_android_permissions(
    context: QObject, callback: Callable[[bool], None]
) -> None:
    """Request Bluetooth and location access, reporting whether both were granted.

    Android 12+ shows separate prompts for "Nearby devices" and "Location".
    QtBluetooth needs precise location as well when the manifest does not
    declare ``BLUETOOTH_SCAN`` with ``neverForLocation``.
    """

    if not ANDROID:
        callback(True)
        return
    app = QCoreApplication.instance()
    if app is None:
        callback(False)
        return

    def after_bluetooth(granted: bool) -> None:
        if not granted:
            callback(False)
            return
        _request_permission(app, _location_permission(), context, callback)

    _request_permission(app, _bluetooth_permission(), context, after_bluetooth)
