"""Test configuration.

The server persists jobs and accounts to SQLite.  Point every test run at a
fresh temporary database so a developer's local data is never touched.
"""

from __future__ import annotations

import os
import tempfile
from collections.abc import Iterator
from pathlib import Path

import pytest

_TEST_DATA_DIR = Path(tempfile.mkdtemp(prefix="basketball-server-tests-"))
os.environ["BASKETBALL_DB_PATH"] = str(_TEST_DATA_DIR / "test.db")
# Tests must not depend on a real ffmpeg for the normal analysis flow, and they
# must not write clips into the developer's upload directory.  Clip tests build
# their own Settings/ClipService with an explicit ffmpeg path.
os.environ["BASKETBALL_CLIP_DIR"] = str(_TEST_DATA_DIR / "clips")
os.environ["BASKETBALL_FFMPEG_PATH"] = str(_TEST_DATA_DIR / "missing-ffmpeg")


@pytest.fixture(autouse=True)
def clean_database() -> Iterator[None]:
    from app.services.db import database

    with database.write_lock, database.connect() as connection:
        connection.execute("DELETE FROM sessions")
        connection.execute("DELETE FROM jobs")
        connection.execute("DELETE FROM users")
        connection.execute("DELETE FROM sms_codes")
        connection.commit()
    from app.api.auth import _login_limiter
    from app.services.sms import sms_code_service

    _login_limiter.clear()
    sender = sms_code_service.sender
    if hasattr(sender, "sent"):
        sender.sent.clear()
    yield
