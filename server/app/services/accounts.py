"""User accounts and bearer-token sessions.

Passwords are hashed with scrypt (stdlib) and never stored in plain text.
Session tokens are random, returned to the client once and stored only as a
SHA-256 digest, so a database leak does not expose usable tokens.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone

from app.config import settings
from app.schemas import User
from app.services.db import Database
from app.services.db import database as default_database

SCRYPT_N = 2**14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_DKLEN = 32


class UsernameTaken(Exception):
    """Raised when a username is already registered."""


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=SCRYPT_N,
        r=SCRYPT_R,
        p=SCRYPT_P,
        dklen=SCRYPT_DKLEN,
    )
    return (
        f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}"
        f"${salt.hex()}${digest.hex()}"
    )


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, n, r, p, salt_hex, digest_hex = encoded.split("$")
        if algorithm != "scrypt":
            return False
        expected = bytes.fromhex(digest_hex)
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=bytes.fromhex(salt_hex),
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
    except (TypeError, ValueError):
        return False
    return hmac.compare_digest(actual, expected)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


# Compared against for unknown usernames so login timing does not reveal
# whether an account exists.
_DUMMY_PASSWORD_HASH = hash_password("invalid-password")


class UserStore:
    def __init__(self, database: Database | None = None) -> None:
        self._database = database or default_database

    def create(self, username: str, password: str) -> User:
        user = User(
            id=uuid.uuid4().hex,
            username=username,
            created_at=datetime.now(timezone.utc),
        )
        password_hash = hash_password(password)
        with self._database.write_lock, self._database.connect() as connection:
            try:
                connection.execute(
                    """
                    INSERT INTO users (id, username, username_key, password_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        user.id,
                        user.username,
                        _username_key(username),
                        password_hash,
                        user.created_at.isoformat(),
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise UsernameTaken(username) from exc
            connection.commit()
        return user

    def authenticate(self, username: str, password: str) -> User | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE username_key = ?",
                (_username_key(username),),
            ).fetchone()
        if row is None:
            verify_password(password, _DUMMY_PASSWORD_HASH)
            return None
        if not verify_password(password, row["password_hash"]):
            return None
        return _user_from_row(row)

    def get(self, user_id: str) -> User | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        return _user_from_row(row) if row is not None else None

    def create_session(self, user_id: str) -> tuple[str, datetime | None]:
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc)
        expires_at = (
            None
            if settings.token_ttl_days <= 0
            else now + timedelta(days=settings.token_ttl_days)
        )
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute(
                """
                INSERT INTO sessions (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                (
                    hash_token(token),
                    user_id,
                    now.isoformat(),
                    expires_at.isoformat() if expires_at is not None else None,
                ),
            )
            connection.commit()
        return token, expires_at

    def user_for_token(self, token: str) -> User | None:
        now = datetime.now(timezone.utc).isoformat()
        with self._database.connect() as connection:
            row = connection.execute(
                """
                SELECT users.*
                FROM sessions
                JOIN users ON users.id = sessions.user_id
                WHERE sessions.token_hash = ?
                  AND (sessions.expires_at IS NULL OR sessions.expires_at > ?)
                """,
                (hash_token(token), now),
            ).fetchone()
        return _user_from_row(row) if row is not None else None

    def revoke_session(self, token: str) -> None:
        self.revoke_session_by_hash(hash_token(token))

    def revoke_session_by_hash(self, token_hash: str) -> None:
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute(
                "DELETE FROM sessions WHERE token_hash = ?", (token_hash,)
            )
            connection.commit()

    def purge_expired_sessions(self) -> int:
        now = datetime.now(timezone.utc).isoformat()
        with self._database.write_lock, self._database.connect() as connection:
            cursor = connection.execute(
                "DELETE FROM sessions WHERE expires_at IS NOT NULL AND expires_at <= ?",
                (now,),
            )
            connection.commit()
            return cursor.rowcount


def _username_key(username: str) -> str:
    return username.casefold()


def _user_from_row(row: sqlite3.Row) -> User:
    return User(
        id=row["id"],
        username=row["username"],
        created_at=row["created_at"],
    )


user_store = UserStore()
