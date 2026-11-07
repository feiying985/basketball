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
from app.services.phone import InvalidPhone, mask_phone, normalize_phone

SCRYPT_N = 2**14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_DKLEN = 32


class UsernameTaken(Exception):
    """Raised when a username is already registered."""


class PhoneTaken(Exception):
    """Raised when a phone number is already registered."""


class InvalidPassword(Exception):
    """Raised when the current password does not match."""


class SamePassword(Exception):
    """Raised when the new password equals the current password."""


class CodeAlreadyUsed(Exception):
    """Raised when a verification code was consumed or has expired."""


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


# Compared against for unknown accounts so login timing does not reveal
# whether an account exists.
_DUMMY_PASSWORD_HASH = hash_password("invalid-password")


class UserStore:
    def __init__(self, database: Database | None = None) -> None:
        self._database = database or default_database

    def create(self, username: str, password: str) -> User:
        """Create a legacy username-only account.

        Kept for tests and existing deployments.  New registrations go through
        :meth:`create_phone_user`.
        """

        user = User(
            id=uuid.uuid4().hex,
            phone=None,
            username=username,
            display_name=username,
            created_at=datetime.now(timezone.utc),
        )
        password_hash = hash_password(password)
        with self._database.write_lock, self._database.connect() as connection:
            try:
                connection.execute(
                    """
                    INSERT INTO users (
                        id, phone, phone_verified_at, username, username_key,
                        password_hash, created_at
                    )
                    VALUES (?, NULL, NULL, ?, ?, ?, ?)
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

    def create_phone_user(
        self,
        phone: str,
        password: str,
        username: str | None,
        *,
        code_id: str,
    ) -> User:
        """Create a phone-verified account and consume its SMS code atomically."""

        now = datetime.now(timezone.utc)
        user = User(
            id=uuid.uuid4().hex,
            phone=phone,
            username=username,
            display_name=username or mask_phone(phone),
            created_at=now,
        )
        password_hash = hash_password(password)
        with self._database.write_lock, self._database.connect() as connection:
            code_row = connection.execute(
                """
                SELECT id FROM sms_codes
                WHERE id = ? AND phone = ? AND purpose = 'register'
                  AND consumed_at IS NULL AND expires_at > ?
                """,
                (code_id, phone, now.isoformat()),
            ).fetchone()
            if code_row is None:
                raise CodeAlreadyUsed()
            try:
                connection.execute(
                    """
                    INSERT INTO users (
                        id, phone, phone_verified_at, username, username_key,
                        password_hash, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        user.id,
                        phone,
                        now.isoformat(),
                        username,
                        _username_key(username) if username else None,
                        password_hash,
                        now.isoformat(),
                    ),
                )
            except sqlite3.IntegrityError as exc:
                message = str(exc)
                if "users.phone" in message:
                    raise PhoneTaken(phone) from exc
                if "users.username_key" in message:
                    raise UsernameTaken(username or "") from exc
                raise
            consumed = connection.execute(
                """
                UPDATE sms_codes SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL
                """,
                (now.isoformat(), code_id),
            )
            if consumed.rowcount != 1:
                raise CodeAlreadyUsed()
            connection.commit()
        return user

    def find_by_account(self, account: str) -> User | None:
        """Find by username first, then by normalized phone."""

        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE username_key = ?",
                (_username_key(account),),
            ).fetchone()
        if row is not None:
            return _user_from_row(row)
        try:
            phone = normalize_phone(account)
        except InvalidPhone:
            return None
        return self.get_by_phone(phone)

    def get_by_phone(self, phone: str) -> User | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE phone = ?", (phone,)
            ).fetchone()
        return _user_from_row(row) if row is not None else None

    def get_by_username(self, username: str) -> User | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE username_key = ?",
                (_username_key(username),),
            ).fetchone()
        return _user_from_row(row) if row is not None else None

    def authenticate(self, account: str, password: str) -> User | None:
        user = self.find_by_account(account)
        if user is None:
            verify_password(password, _DUMMY_PASSWORD_HASH)
            return None
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT password_hash FROM users WHERE id = ?", (user.id,)
            ).fetchone()
        if row is None or not verify_password(password, row["password_hash"]):
            return None
        return user

    def get(self, user_id: str) -> User | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        return _user_from_row(row) if row is not None else None

    def change_password(
        self,
        user_id: str,
        current_password: str,
        new_password: str,
        *,
        keep_token_hash: str,
    ) -> None:
        with self._database.write_lock, self._database.connect() as connection:
            row = connection.execute(
                "SELECT password_hash FROM users WHERE id = ?", (user_id,)
            ).fetchone()
            if row is None or not verify_password(current_password, row["password_hash"]):
                raise InvalidPassword()
            if verify_password(new_password, row["password_hash"]):
                raise SamePassword()
            connection.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (hash_password(new_password), user_id),
            )
            connection.execute(
                "DELETE FROM sessions WHERE user_id = ? AND token_hash != ?",
                (user_id, keep_token_hash),
            )
            connection.commit()

    def reset_password(self, user_id: str, new_password: str, *, code_id: str) -> None:
        now = datetime.now(timezone.utc)
        with self._database.write_lock, self._database.connect() as connection:
            code_row = connection.execute(
                """
                SELECT id FROM sms_codes
                WHERE id = ? AND purpose = 'reset'
                  AND consumed_at IS NULL AND expires_at > ?
                """,
                (code_id, now.isoformat()),
            ).fetchone()
            if code_row is None:
                raise CodeAlreadyUsed()
            connection.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (hash_password(new_password), user_id),
            )
            connection.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))
            consumed = connection.execute(
                """
                UPDATE sms_codes SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL
                """,
                (now.isoformat(), code_id),
            )
            if consumed.rowcount != 1:
                raise CodeAlreadyUsed()
            connection.commit()

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

    def revoke_other_sessions(self, user_id: str, keep_token_hash: str) -> None:
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute(
                "DELETE FROM sessions WHERE user_id = ? AND token_hash != ?",
                (user_id, keep_token_hash),
            )
            connection.commit()

    def revoke_all_sessions(self, user_id: str) -> None:
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))
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
    username = row["username"]
    phone = row["phone"]
    return User(
        id=row["id"],
        phone=phone,
        username=username,
        display_name=username or mask_phone(phone) or "用户",
        created_at=row["created_at"],
    )


user_store = UserStore()
