from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from app.services.accounts import UserStore, hash_password, hash_token
from app.services.db import Database

PASSWORD = "correct-horse-battery"


def test_old_user_table_is_migrated_and_sessions_still_work(tmp_path: Path) -> None:
    path = tmp_path / "old.db"
    created = datetime.now(timezone.utc).isoformat()
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            username_key TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE sessions (
            token_hash TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL,
            expires_at TEXT
        );
        """
    )
    connection.execute(
        "INSERT INTO users (id, username, username_key, password_hash, created_at) "
        "VALUES (?, ?, ?, ?, ?)",
        ("legacy", "alice", "alice", hash_password(PASSWORD), created),
    )
    connection.execute(
        "INSERT INTO sessions (token_hash, user_id, created_at, expires_at) "
        "VALUES (?, ?, ?, NULL)",
        (hash_token("legacy-token"), "legacy", created),
    )
    connection.commit()
    connection.close()

    database = Database(path)
    user_store = UserStore(database)

    assert user_store.authenticate("alice", PASSWORD) is not None
    assert user_store.user_for_token("legacy-token") is not None

    with database.connect() as connection:
        columns = {
            row["name"]: row for row in connection.execute("PRAGMA table_info(users)")
        }
    assert "phone" in columns
    assert columns["username"]["notnull"] == 0
    # A new phone-only account can be created after the rebuild.
    expires = (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()
    with database.write_lock, database.connect() as connection:
        connection.execute(
            """
            INSERT INTO sms_codes (
                id, phone, purpose, code_hash, created_at, expires_at,
                attempts, max_attempts, consumed_at, request_ip
            ) VALUES (?, ?, 'register', 'unused', ?, ?, 0, 5, NULL, NULL)
            """,
            ("code-1", "+8613800138000", created, expires),
        )
        connection.commit()
    user = user_store.create_phone_user(
        "+8613800138000",
        PASSWORD,
        None,
        code_id="code-1",
    )
    assert user.phone == "+8613800138000"
    assert user.username is None
    token, _ = user_store.create_session(user.id)
    assert user_store.user_for_token(token) is not None
