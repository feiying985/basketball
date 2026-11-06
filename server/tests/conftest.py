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


@pytest.fixture(autouse=True)
def clean_database() -> Iterator[None]:
    from app.services.db import database

    with database.write_lock, database.connect() as connection:
        connection.execute("DELETE FROM sessions")
        connection.execute("DELETE FROM jobs")
        connection.execute("DELETE FROM users")
        connection.commit()
    yield
