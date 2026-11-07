import dataclasses
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from httpx import Response

from app.api import routes
from app.main import app
from app.services.db import database
from app.services.phone import normalize_phone
from app.services.sms import sms_code_service
from tests.synthetic import write_shot_video

client = TestClient(app)

PASSWORD = "correct-horse-battery"
NEW_PASSWORD = "new-correct-horse-battery"


def _phone() -> str:
    return f"138{uuid.uuid4().int % 100000000:08d}"


def _send_code(phone: str, purpose: str = "register") -> str:
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
    assert matches, f"no dev SMS sent for {phone}"
    return matches[-1][2]


def _allow_resend(phone: str) -> None:
    """Test helper: age the last code so the 60s cooldown does not apply."""

    with database.write_lock, database.connect() as connection:
        connection.execute(
            "UPDATE sms_codes SET created_at = ? WHERE phone = ?",
            ("2000-01-01T00:00:00+00:00", normalize_phone(phone)),
        )
        connection.commit()


def _register(
    phone: str | None = None,
    username: str | None = None,
) -> tuple[str, str]:
    phone = phone or _phone()
    code = _send_code(phone, "register")
    payload: dict[str, str] = {
        "phone": phone,
        "code": code,
        "password": PASSWORD,
    }
    if username is not None:
        payload["username"] = username
    response = client.post("/api/v1/auth/register", json=payload)
    assert response.status_code == 201, response.text
    return response.json()["access_token"], phone


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _upload(
    tmp_path: Path,
    token: str | None = None,
) -> Response:
    video = tmp_path / "clip.avi"
    write_shot_video(video)
    headers = _auth(token) if token is not None else {}
    with video.open("rb") as uploaded:
        return client.post(
            "/api/v1/analyses",
            headers=headers,
            files={"file": ("clip.avi", uploaded, "video/x-msvideo")},
        )


def test_register_returns_a_token_and_user() -> None:
    phone = _phone()
    code = _send_code(phone)
    response = client.post(
        "/api/v1/auth/register",
        json={"phone": phone, "code": code, "password": PASSWORD},
    )

    assert response.status_code == 201
    payload = response.json()
    assert payload["token_type"] == "bearer"
    assert payload["user"]["phone"] == normalize_phone(phone)
    assert payload["user"]["username"] is None
    assert payload["user"]["display_name"].endswith("****" + phone[-4:])
    assert payload["access_token"]

    me = client.get("/api/v1/auth/me", headers=_auth(payload["access_token"]))
    assert me.status_code == 200
    assert me.json()["phone"] == normalize_phone(phone)


def test_duplicate_phone_is_rejected() -> None:
    phone = _phone()
    _register(phone)
    _allow_resend(phone)
    code = _send_code(phone, "register")

    response = client.post(
        "/api/v1/auth/register",
        json={"phone": phone, "code": code, "password": PASSWORD},
    )

    assert response.status_code == 409


def test_duplicate_username_is_case_insensitive() -> None:
    _register(username="Alice")
    phone = _phone()
    code = _send_code(phone)

    response = client.post(
        "/api/v1/auth/register",
        json={
            "phone": phone,
            "code": code,
            "password": PASSWORD,
            "username": "alice",
        },
    )

    assert response.status_code == 409


@pytest.mark.parametrize(
    "payload",
    [
        {"phone": "123", "code": "123456", "password": PASSWORD},
        {"phone": "13800138000", "code": "12345", "password": PASSWORD},
        {"phone": "13800138000", "code": "123456", "password": "short"},
        {"phone": "13800138000", "code": "123456", "password": PASSWORD, "username": "ab"},
        {"phone": "13800138000", "code": "123456", "password": PASSWORD, "username": "has space"},
        {"phone": "13800138000", "code": "123456", "password": PASSWORD, "username": "13800138001"},
    ],
)
def test_invalid_registration_payload_is_rejected(payload: dict[str, str]) -> None:
    assert client.post("/api/v1/auth/register", json=payload).status_code == 422


def test_old_username_only_registration_payload_is_rejected() -> None:
    response = client.post(
        "/api/v1/auth/register",
        json={"username": "alice", "password": PASSWORD},
    )
    assert response.status_code == 422


def test_login_with_wrong_password_is_rejected() -> None:
    _, phone = _register()

    response = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": "wrong-password"},
    )

    assert response.status_code == 401


def test_login_by_phone_and_by_username() -> None:
    _, phone = _register(username="alice")

    phone_login = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    )
    assert phone_login.status_code == 200

    username_login = client.post(
        "/api/v1/auth/login",
        json={"account": "ALICE", "password": PASSWORD},
    )
    assert username_login.status_code == 200

    legacy_username_login = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": PASSWORD},
    )
    assert legacy_username_login.status_code == 200


def test_legacy_username_account_can_still_login() -> None:
    from app.services.accounts import user_store

    user_store.create("legacy-user", PASSWORD)

    response = client.post(
        "/api/v1/auth/login",
        json={"account": "legacy-user", "password": PASSWORD},
    )

    assert response.status_code == 200
    assert response.json()["user"]["username"] == "legacy-user"
    assert response.json()["user"]["phone"] is None


def test_code_login_and_unregistered_phone() -> None:
    _, phone = _register()
    _allow_resend(phone)
    code = _send_code(phone, "login")

    response = client.post(
        "/api/v1/auth/login/code",
        json={"phone": phone, "code": code},
    )
    assert response.status_code == 200

    unregistered = _phone()
    unregistered_code = _send_code(unregistered, "login")
    response = client.post(
        "/api/v1/auth/login/code",
        json={"phone": unregistered, "code": unregistered_code},
    )
    assert response.status_code == 404


def test_login_then_logout_revokes_only_the_current_session() -> None:
    first, phone = _register()
    second = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    ).json()["access_token"]

    assert client.post("/api/v1/auth/logout", headers=_auth(first)).status_code == 204

    assert client.get("/api/v1/auth/me", headers=_auth(first)).status_code == 401
    assert client.get("/api/v1/auth/me", headers=_auth(second)).status_code == 200


def test_missing_or_invalid_token_is_rejected() -> None:
    assert client.get("/api/v1/auth/me").status_code == 401
    assert client.get("/api/v1/auth/me", headers=_auth("not-a-token")).status_code == 401


def test_expired_session_is_rejected() -> None:
    token, _ = _register()
    with database.write_lock, database.connect() as connection:
        connection.execute(
            "UPDATE sessions SET expires_at = ?",
            ("2000-01-01T00:00:00+00:00",),
        )
        connection.commit()

    assert client.get("/api/v1/auth/me", headers=_auth(token)).status_code == 401


def test_change_password_keeps_current_session_and_revokes_others() -> None:
    token, phone = _register()
    other = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    ).json()["access_token"]

    response = client.post(
        "/api/v1/auth/password",
        headers=_auth(token),
        json={"current_password": PASSWORD, "new_password": NEW_PASSWORD},
    )
    assert response.status_code == 204

    assert client.get("/api/v1/auth/me", headers=_auth(token)).status_code == 200
    assert client.get("/api/v1/auth/me", headers=_auth(other)).status_code == 401
    assert client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    ).status_code == 401
    assert client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": NEW_PASSWORD},
    ).status_code == 200


def test_change_password_rejects_wrong_and_same_password() -> None:
    token, _ = _register()

    wrong = client.post(
        "/api/v1/auth/password",
        headers=_auth(token),
        json={"current_password": "wrong-password", "new_password": NEW_PASSWORD},
    )
    assert wrong.status_code == 401

    same = client.post(
        "/api/v1/auth/password",
        headers=_auth(token),
        json={"current_password": PASSWORD, "new_password": PASSWORD},
    )
    assert same.status_code == 400


def test_reset_password_revokes_all_sessions() -> None:
    token, phone = _register()
    other = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    ).json()["access_token"]
    _allow_resend(phone)
    code = _send_code(phone, "reset")

    response = client.post(
        "/api/v1/auth/password/reset",
        json={"phone": phone, "code": code, "new_password": NEW_PASSWORD},
    )
    assert response.status_code == 204

    assert client.get("/api/v1/auth/me", headers=_auth(token)).status_code == 401
    assert client.get("/api/v1/auth/me", headers=_auth(other)).status_code == 401
    assert client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    ).status_code == 401
    assert client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": NEW_PASSWORD},
    ).status_code == 200


def test_login_attempts_are_rate_limited() -> None:
    _, phone = _register()
    for _ in range(10):
        response = client.post(
            "/api/v1/auth/login",
            json={"account": phone, "password": "wrong-password"},
        )
        assert response.status_code == 401

    blocked = client.post(
        "/api/v1/auth/login",
        json={"account": phone, "password": PASSWORD},
    )
    assert blocked.status_code == 429
    assert blocked.headers["retry-after"]


def test_analysis_is_only_visible_to_its_owner(tmp_path: Path) -> None:
    alice, _ = _register()
    bob, _ = _register()

    response = _upload(tmp_path, token=alice)
    assert response.status_code == 202
    job_id = response.json()["id"]

    assert client.get(f"/api/v1/analyses/{job_id}", headers=_auth(alice)).status_code == 200
    assert client.get(f"/api/v1/analyses/{job_id}", headers=_auth(bob)).status_code == 404
    assert client.get(f"/api/v1/analyses/{job_id}").status_code == 404

    assert [job["id"] for job in client.get(
        "/api/v1/analyses", headers=_auth(alice)
    ).json()] == [job_id]
    assert client.get("/api/v1/analyses", headers=_auth(bob)).json() == []


def test_anonymous_analysis_still_works_without_require_auth(tmp_path: Path) -> None:
    response = _upload(tmp_path)

    assert response.status_code == 202
    assert client.get(f"/api/v1/analyses/{response.json()['id']}").status_code == 200
    assert client.get("/api/v1/analyses").status_code == 401


def test_require_auth_setting_blocks_anonymous_uploads(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    token, _ = _register()
    monkeypatch.setattr(
        routes,
        "settings",
        dataclasses.replace(routes.settings, require_auth=True),
    )

    assert _upload(tmp_path).status_code == 401
    assert _upload(tmp_path, token=token).status_code == 202


def test_require_auth_blocks_anonymous_task_lookup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.services.jobs import job_store

    job = job_store.create("clip.mp4")
    monkeypatch.setattr(
        routes,
        "settings",
        dataclasses.replace(routes.settings, require_auth=True),
    )

    assert client.get(f"/api/v1/analyses/{job.id}").status_code == 401

    token, _ = _register()
    assert client.get(f"/api/v1/analyses/{job.id}", headers=_auth(token)).status_code == 200
