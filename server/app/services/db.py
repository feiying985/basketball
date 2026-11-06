"""SQLite persistence layer.

The MVP runs a single API process, so a file-backed SQLite database is enough
to keep analysis tasks and accounts across restarts.  A connection is opened
per operation and writes are serialized by a process-wide lock; swap this module
for PostgreSQL/Redis when running multiple API workers.
"""

from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from threading import Lock

from app.config import settings

SCHEMA = """
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    username_key TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    owner_id TEXT,
    status TEXT NOT NULL,
    stage TEXT,
    filename TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    result_json TEXT,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_jobs_owner_created ON jobs(owner_id, created_at DESC);
"""


class Database:
    """Small SQLite wrapper shared by the task and account stores."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.write_lock = Lock()
        path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=30.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self.connect() as connection:
            connection.executescript(SCHEMA)
            # Existing databases predate the clip stage column; add it in place
            # instead of asking operators to delete their task history.
            columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(jobs)")
            }
            if "stage" not in columns:
                connection.execute("ALTER TABLE jobs ADD COLUMN stage TEXT")
            connection.commit()


database = Database(settings.database_path)
