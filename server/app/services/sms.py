"""SMS verification codes and provider adapters.

Only an HMAC digest of each code is stored.  Codes are single-use, expire
quickly and are protected by per-phone, per-IP and global rate limits.  The
default ``dev`` provider logs the code instead of sending it; production uses
Aliyun SMS with reviewed signature/template values.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import secrets
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol

from app.config import Settings, settings
from app.services.db import Database
from app.services.db import database as default_database
from app.services.phone import normalize_phone

logger = logging.getLogger(__name__)


class SmsError(Exception):
    """Base class for SMS errors."""


class SmsSendError(SmsError):
    """Raised when the provider could not send a message."""


class SmsRateLimited(SmsError):
    def __init__(self, retry_after: int) -> None:
        super().__init__("验证码发送过于频繁")
        self.retry_after = max(retry_after, 1)


class InvalidVerificationCode(SmsError):
    """Raised when a code is unknown, expired, consumed or wrong."""


@dataclass(frozen=True)
class VerifiedCode:
    id: str
    phone: str
    purpose: str


class SmsSender(Protocol):
    def send_code(self, phone: str, code: str, purpose: str) -> None: ...


class DevSmsSender:
    """Local/test sender: records the code instead of calling a provider."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str, str]] = []

    def send_code(self, phone: str, code: str, purpose: str) -> None:
        self.sent.append((phone, purpose, code))
        logger.info("[dev-sms] phone=%s purpose=%s code=%s", phone, purpose, code)


class AliyunSmsSender:
    """Aliyun Dysmsapi adapter, imported lazily so dev/tests need no SDK."""

    def __init__(self, config: Settings) -> None:
        from alibabacloud_dysmsapi20170525.client import Client as DysmsapiClient
        from alibabacloud_tea_openapi import models as open_api_models

        client_config = open_api_models.Config(
            access_key_id=config.sms_access_key_id,
            access_key_secret=config.sms_access_key_secret,
        )
        client_config.endpoint = "dysmsapi.aliyuncs.com"
        client_config.region_id = config.sms_region
        self._client = DysmsapiClient(client_config)
        self._config = config

    def send_code(self, phone: str, code: str, purpose: str) -> None:
        from alibabacloud_dysmsapi20170525 import models as dysmsapi_models

        request = dysmsapi_models.SendSmsRequest(
            phone_numbers=phone.removeprefix("+86"),
            sign_name=self._config.sms_sign_name,
            template_code=self._config.sms_template_code,
            template_param=json.dumps({"code": code}, ensure_ascii=False),
        )
        try:
            response = self._client.send_sms(request)
        except Exception as exc:  # provider/network errors
            logger.warning("阿里云短信发送失败: %s", exc)
            raise SmsSendError("短信发送失败") from exc
        if getattr(response.body, "code", None) != "OK":
            logger.warning(
                "阿里云短信返回失败: code=%s message=%s",
                getattr(response.body, "code", None),
                getattr(response.body, "message", None),
            )
            raise SmsSendError("短信发送失败")


def build_sender(config: Settings) -> SmsSender:
    if config.sms_provider == "aliyun":
        return AliyunSmsSender(config)
    return DevSmsSender()


class SmsCodeService:
    def __init__(
        self,
        database: Database | None = None,
        *,
        config: Settings | None = None,
        sender: SmsSender | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._database = database or default_database
        self._config = config or settings
        self.sender = sender or build_sender(self._config)
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def issue(self, phone: str, purpose: str, request_ip: str | None) -> tuple[int, int]:
        phone = normalize_phone(phone)
        now = self._clock()
        code = f"{secrets.randbelow(1_000_000):06d}"
        code_id = uuid.uuid4().hex
        expires_at = now + timedelta(seconds=self._config.sms_code_ttl_seconds)
        code_hash = self._hash(phone, purpose, code)
        with self._database.write_lock, self._database.connect() as connection:
            self._cleanup(connection, now)
            self._check_limits(connection, phone, request_ip, now)
            # A newer code always invalidates the previous one.
            connection.execute(
                """
                UPDATE sms_codes SET consumed_at = ?
                WHERE phone = ? AND purpose = ? AND consumed_at IS NULL
                """,
                (now.isoformat(), phone, purpose),
            )
            connection.execute(
                """
                INSERT INTO sms_codes (
                    id, phone, purpose, code_hash, created_at, expires_at,
                    attempts, max_attempts, consumed_at, request_ip
                )
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL, ?)
                """,
                (
                    code_id,
                    phone,
                    purpose,
                    code_hash,
                    now.isoformat(),
                    expires_at.isoformat(),
                    self._config.sms_max_attempts,
                    request_ip,
                ),
            )
            connection.commit()
        try:
            self.sender.send_code(phone, code, purpose)
        except Exception as exc:
            with self._database.write_lock, self._database.connect() as connection:
                connection.execute("DELETE FROM sms_codes WHERE id = ?", (code_id,))
                connection.commit()
            if isinstance(exc, SmsSendError):
                raise
            raise SmsSendError("短信发送失败") from exc
        return self._config.sms_code_ttl_seconds, self._config.sms_resend_seconds

    def verify(self, phone: str, purpose: str, code: str) -> VerifiedCode:
        phone = normalize_phone(phone)
        now = self._clock()
        with self._database.write_lock, self._database.connect() as connection:
            row = connection.execute(
                """
                SELECT * FROM sms_codes
                WHERE phone = ? AND purpose = ? AND consumed_at IS NULL
                  AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1
                """,
                (phone, purpose, now.isoformat()),
            ).fetchone()
            if row is None:
                raise InvalidVerificationCode()
            if row["attempts"] >= row["max_attempts"]:
                connection.execute(
                    "UPDATE sms_codes SET consumed_at = ? WHERE id = ?",
                    (now.isoformat(), row["id"]),
                )
                connection.commit()
                raise InvalidVerificationCode()
            if not hmac.compare_digest(row["code_hash"], self._hash(phone, purpose, code)):
                attempts = row["attempts"] + 1
                connection.execute(
                    "UPDATE sms_codes SET attempts = ? WHERE id = ?",
                    (attempts, row["id"]),
                )
                if attempts >= row["max_attempts"]:
                    connection.execute(
                        "UPDATE sms_codes SET consumed_at = ? WHERE id = ?",
                        (now.isoformat(), row["id"]),
                    )
                connection.commit()
                raise InvalidVerificationCode()
            connection.commit()
        return VerifiedCode(id=row["id"], phone=phone, purpose=purpose)

    def consume(self, code: VerifiedCode) -> None:
        now = self._clock()
        with self._database.write_lock, self._database.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE sms_codes SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL
                """,
                (now.isoformat(), code.id),
            )
            if cursor.rowcount != 1:
                raise InvalidVerificationCode()
            connection.commit()

    def cleanup(self) -> int:
        now = self._clock()
        with self._database.write_lock, self._database.connect() as connection:
            cursor = self._cleanup(connection, now)
            connection.commit()
            return cursor.rowcount if cursor is not None else 0

    def _cleanup(self, connection, now: datetime):
        cutoff = (now - timedelta(hours=24)).isoformat()
        return connection.execute(
            "DELETE FROM sms_codes WHERE created_at < ?", (cutoff,)
        )

    def _check_limits(
        self,
        connection,
        phone: str,
        request_ip: str | None,
        now: datetime,
    ) -> None:
        config = self._config
        latest = connection.execute(
            """
            SELECT created_at FROM sms_codes
            WHERE phone = ? ORDER BY created_at DESC LIMIT 1
            """,
            (phone,),
        ).fetchone()
        if latest is not None:
            last = datetime.fromisoformat(latest["created_at"])
            elapsed = (now - last).total_seconds()
            if elapsed < config.sms_resend_seconds:
                raise SmsRateLimited(int(config.sms_resend_seconds - elapsed) + 1)

        hour_ago = (now - timedelta(hours=1)).isoformat()
        day_ago = (now - timedelta(hours=24)).isoformat()
        if self._count(connection, phone, hour_ago) >= config.sms_phone_hourly_limit:
            raise SmsRateLimited(3600)
        if self._count(connection, phone, day_ago) >= config.sms_phone_daily_limit:
            raise SmsRateLimited(86400)
        if request_ip:
            ip_count = connection.execute(
                "SELECT COUNT(*) FROM sms_codes WHERE request_ip = ? AND created_at >= ?",
                (request_ip, hour_ago),
            ).fetchone()[0]
            if ip_count >= config.sms_ip_hourly_limit:
                raise SmsRateLimited(3600)
        global_count = connection.execute(
            "SELECT COUNT(*) FROM sms_codes WHERE created_at >= ?", (day_ago,)
        ).fetchone()[0]
        if global_count >= config.sms_daily_limit:
            raise SmsRateLimited(86400)

    @staticmethod
    def _count(connection, phone: str, cutoff: str) -> int:
        return connection.execute(
            "SELECT COUNT(*) FROM sms_codes WHERE phone = ? AND created_at >= ?",
            (phone, cutoff),
        ).fetchone()[0]

    def _hash(self, phone: str, purpose: str, code: str) -> str:
        pepper = self._config.sms_code_pepper or "dev-only-pepper"
        message = f"{phone}:{purpose}:{code}".encode("utf-8")
        return hmac.new(pepper.encode("utf-8"), message, hashlib.sha256).hexdigest()


sms_code_service = SmsCodeService()
