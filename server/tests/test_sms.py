from __future__ import annotations

import dataclasses
import uuid

import pytest
from fastapi.testclient import TestClient

from app.config import _env_sms_debug_code, settings
from app.main import app
from app.services.db import database
from app.services.phone import normalize_phone
from app.services.sms import (
    DevSmsSender,
    InvalidVerificationCode,
    SmsCodeService,
    SmsSendError,
    sms_code_service,
)

client = TestClient(app)


def _phone() -> str:
    return f"139{uuid.uuid4().int % 100000000:08d}"


def _send(phone: str, purpose: str = "register") -> str:
    response = client.post(
        "/api/v1/auth/sms-code",
        json={"phone": phone, "purpose": purpose},
    )
    assert response.status_code == 200, response.text
    normalized = normalize_phone(phone)
    matches = [
        item
        for item in sms_code_service.sender.sent
        if item[0] == normalized and item[1] == purpose
    ]
    assert matches
    return matches[-1][2]


def _age_codes(phone: str) -> None:
    with database.write_lock, database.connect() as connection:
        connection.execute(
            "UPDATE sms_codes SET created_at = ? WHERE phone = ?",
            ("2000-01-01T00:00:00+00:00", normalize_phone(phone)),
        )
        connection.commit()


def _register(phone: str) -> None:
    code = _send(phone, "register")
    response = client.post(
        "/api/v1/auth/register",
        json={"phone": phone, "code": code, "password": "correct-horse-battery"},
    )
    assert response.status_code == 201, response.text


def test_invalid_phone_is_rejected() -> None:
    assert client.post(
        "/api/v1/auth/sms-code",
        json={"phone": "123", "purpose": "register"},
    ).status_code == 422


def test_code_is_hashed_in_database() -> None:
    phone = _phone()
    code = _send(phone)
    with database.connect() as connection:
        row = connection.execute(
            "SELECT code_hash FROM sms_codes WHERE phone = ?",
            ("+86" + phone,),
        ).fetchone()
    assert row is not None
    assert row["code_hash"] != code
    assert len(row["code_hash"]) == 64


def test_resend_cooldown_and_retry_after() -> None:
    phone = _phone()
    _send(phone)
    blocked = client.post(
        "/api/v1/auth/sms-code",
        json={"phone": phone, "purpose": "register"},
    )
    assert blocked.status_code == 429
    assert int(blocked.headers["retry-after"]) > 0


def test_wrong_code_invalidates_after_max_attempts() -> None:
    phone = _phone()
    code = _send(phone, "login")
    for _ in range(5):
        response = client.post(
            "/api/v1/auth/login/code",
            json={"phone": phone, "code": "000000" if code != "000000" else "111111"},
        )
        assert response.status_code in {400, 404}
    expired = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": code},
    )
    assert expired.status_code == 400


def test_new_code_invalidates_old_code() -> None:
    phone = _phone()
    _register(phone)
    _age_codes(phone)
    old_code = _send(phone, "login")
    _age_codes(phone)
    new_code = _send(phone, "login")

    old = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": old_code},
    )
    assert old.status_code == 400

    new = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": new_code},
    )
    assert new.status_code == 200


def test_expired_code_is_rejected() -> None:
    phone = _phone()
    _send(phone, "login")
    with database.write_lock, database.connect() as connection:
        connection.execute(
            "UPDATE sms_codes SET expires_at = ? WHERE phone = ?",
            ("2000-01-01T00:00:00+00:00", "+86" + phone),
        )
        connection.commit()
    response = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": "123456"},
    )
    assert response.status_code == 400


def test_code_is_bound_to_phone() -> None:
    phone_a = _phone()
    phone_b = _phone()
    code = _send(phone_a, "register")
    response = client.post(
        "/api/v1/auth/register",
        json={"phone": phone_b, "code": code, "password": "correct-horse-battery"},
    )
    assert response.status_code == 400


def test_phone_format_is_normalized() -> None:
    phone = _phone()
    code = _send(f"+86 {phone[:3]} {phone[3:]}")
    response = client.post(
        "/api/v1/auth/register",
        json={"phone": phone, "code": code, "password": "correct-horse-battery"},
    )
    assert response.status_code == 201, response.text


class _FailingSender:
    def send_code(self, phone: str, code: str, purpose: str) -> None:
        raise RuntimeError("provider down")


def test_provider_failure_does_not_leave_a_code() -> None:
    service = SmsCodeService(
        database=database,
        config=settings,
        sender=_FailingSender(),
    )
    phone = _phone()
    with pytest.raises(SmsSendError):
        service.issue(phone, "register", "127.0.0.1")
    with database.connect() as connection:
        row = connection.execute(
            "SELECT COUNT(*) FROM sms_codes WHERE phone = ?",
            ("+86" + phone,),
        ).fetchone()
    assert row[0] == 0


def test_dev_sender_records_code() -> None:
    sender = DevSmsSender()
    sender.send_code("+8613800138000", "123456", "login")
    assert sender.sent == [("+8613800138000", "login", "123456")]


def _production_settings(**overrides: object):
    """Production config with reviewed Aliyun values, for guard-rail tests."""

    values: dict[str, object] = {
        "env": "production",
        "sms_provider": "aliyun",
        "sms_access_key_id": "test-key",
        "sms_access_key_secret": "test-secret",
        "sms_sign_name": "测试签名",
        "sms_template_code": "SMS_000000",
        "sms_code_pepper": "test-pepper",
        "sms_debug_code": "",
    }
    values.update(overrides)
    return dataclasses.replace(settings, **values)


def test_debug_code_login_works_in_development() -> None:
    phone = _phone()
    _register(phone)
    _age_codes(phone)
    _send(phone, "login")

    response = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": "666666"},
    )

    assert response.status_code == 200, response.text


def test_debug_code_works_for_registration() -> None:
    phone = _phone()
    _send(phone, "register")

    response = client.post(
        "/api/v1/auth/register",
        json={
            "phone": phone,
            "code": "666666",
            "password": "correct-horse-battery",
        },
    )

    assert response.status_code == 201, response.text
    assert response.json()["user"]["phone"] == normalize_phone(phone)


def test_debug_code_still_requires_an_outstanding_code() -> None:
    phone = _phone()
    _register(phone)  # the register code was consumed and none was sent since

    response = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": "666666"},
    )

    assert response.status_code == 400


def test_debug_code_is_customizable() -> None:
    config = dataclasses.replace(settings, sms_debug_code="123456")
    service = SmsCodeService(database=database, config=config, sender=DevSmsSender())
    phone = _phone()
    service.issue(phone, "login", "127.0.0.1")

    assert service.verify(phone, "login", "123456").phone == normalize_phone(phone)
    with pytest.raises(InvalidVerificationCode):
        service.verify(phone, "login", "666666")


def test_debug_code_defaults_to_666666_only_in_development() -> None:
    development = dataclasses.replace(settings, sms_debug_code="")
    assert development.effective_sms_debug_code == "666666"
    assert _production_settings().effective_sms_debug_code is None
    assert dataclasses.replace(settings, sms_debug_code=None).effective_sms_debug_code is None


def test_production_rejects_a_fixed_debug_code() -> None:
    with pytest.raises(ValueError, match="生产环境"):
        _production_settings(sms_debug_code="666666")


def test_fixed_debug_code_requires_the_dev_sms_provider() -> None:
    with pytest.raises(ValueError, match="dev 短信"):
        _production_settings(env="staging", sms_debug_code="666666")


def test_fixed_debug_code_must_be_six_digits() -> None:
    with pytest.raises(ValueError, match="6 位数字"):
        dataclasses.replace(settings, sms_debug_code="12345")


def test_debug_code_can_be_disabled_with_off(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("BASKETBALL_SMS_DEBUG_CODE", "off")

    assert _env_sms_debug_code() is None
    assert dataclasses.replace(settings, sms_debug_code=None).effective_sms_debug_code is None


def test_production_verify_rejects_the_development_code() -> None:
    service = SmsCodeService(
        database=database,
        config=_production_settings(),
        sender=DevSmsSender(),
    )
    phone = _phone()
    service.issue(phone, "login", "127.0.0.1")

    with pytest.raises(InvalidVerificationCode):
        service.verify(phone, "login", "666666")
