"""Small platform helpers shared by the UI and the transports."""

from __future__ import annotations

import os
import sys


def is_android() -> bool:
    """Return whether the app is running inside an Android Python runtime."""

    return sys.platform == "android" or bool(
        os.environ.get("ANDROID_ARGUMENT") or os.environ.get("P4A_BOOTSTRAP")
    )


ANDROID = is_android()
