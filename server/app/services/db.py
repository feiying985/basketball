"""SQLite persistence layer.

The MVP runs a single API process, so a file-backed SQLite database is enough
to keep analysis tasks, accounts and SMS verification codes across restarts.
A connection is opened per operation and writes are serialized by a
process-wide lock; swap this module for PostgreSQL/Redis when running multiple
API workers.
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
    phone TEXT UNIQUE,
    phone_verified_at TEXT,
    username TEXT,
    username_key TEXT UNIQUE,
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

CREATE TABLE IF NOT EXISTS sms_codes (
    id TEXT PRIMARY KEY,
    phone TEXT NOT NULL,
    purpose TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    consumed_at TEXT,
    request_ip TEXT
);

CREATE INDEX IF NOT EXISTS idx_sms_codes_phone
    ON sms_codes(phone, purpose, created_at DESC);

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

_USERS_TABLE = """
CREATE TABLE users_new (
    id TEXT PRIMARY KEY,
    phone TEXT UNIQUE,
    phone_verified_at TEXT,
    username TEXT,
    username_key TEXT UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
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
            user_columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(users)")
            }
            if "phone" not in user_columns:
                # 0.3.0 stored only username/password.  New accounts may be
                # phone-only, so ``username`` becomes nullable via a table
                # rebuild; existing users and sessions are copied unchanged.
                self._migrate_users(connection)
            # Existing databases predate the clip stage column; add it in place
            # instead of asking operators to delete their task history.
            columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(jobs)")
            }
            if "stage" not in columns:
                connection.execute("ALTER TABLE jobs ADD COLUMN stage TEXT")
            connection.commit()

    @staticmethod
    def _migrate_users(connection: sqlite3.Connection) -> None:
        connection.execute("PRAGMA foreign_keys = OFF")
        try:
            connection.executescript(
                f"""
                BEGIN;
                {_USERS_TABLE}
                INSERT INTO users_new (
                    id, phone, phone_verified_at, username, username_key,
                    password_hash, created_at
                )
                SELECT id, NULL, NULL, username, username_key,
                       password_hash, created_at
                FROM users;
                DROP TABLE users;
                ALTER TABLE users_new RENAME TO users;
                COMMIT;
                """
            )
        finally:
            connection.execute("PRAGMA foreign_keys = ON")


database = Database(settings.database_path)
