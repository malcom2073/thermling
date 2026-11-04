import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication, QSettings  # noqa: E402

QCoreApplication.setOrganizationName("LabelwrightTests")
QCoreApplication.setApplicationName("LabelwrightTests")

import pytest  # noqa: E402


@pytest.fixture(autouse=True)
def clean_settings():
    QSettings().clear()
    yield
    QSettings().clear()
