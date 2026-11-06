import dataclasses
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from httpx import Response

from app.api import routes
from app.main import app
from app.services.db import database
from tests.synthetic import write_shot_video

client = TestClient(app)

PASSWORD = "correct-horse-battery"


def _register(username: str | None = None) -> str:
    username = username or f"user{uuid.uuid4().hex[:8]}"
    response = client.post(
        "/api/v1/auth/register",
        json={"username": username, "password": PASSWORD},
    )
    assert response.status_code == 201, response.text
    return response.json()["access_token"]


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
    response = client.post(
        "/api/v1/auth/register",
        json={"username": "Alice", "password": PASSWORD},
    )

    assert response.status_code == 201
    payload = response.json()
    assert payload["token_type"] == "bearer"
    assert payload["user"]["username"] == "Alice"
    assert payload["access_token"]

    me = client.get("/api/v1/auth/me", headers=_auth(payload["access_token"]))
    assert me.status_code == 200
    assert me.json()["username"] == "Alice"


def test_duplicate_username_is_case_insensitive() -> None:
    _register("Alice")

    response = client.post(
        "/api/v1/auth/register",
        json={"username": "alice", "password": PASSWORD},
    )

    assert response.status_code == 409


@pytest.mark.parametrize(
    "payload",
    [
        {"username": "ab", "password": PASSWORD},
        {"username": "has space", "password": PASSWORD},
        {"username": "valid-name", "password": "short"},
    ],
)
def test_invalid_registration_payload_is_rejected(payload: dict[str, str]) -> None:
    assert client.post("/api/v1/auth/register", json=payload).status_code == 422


def test_login_with_wrong_password_is_rejected() -> None:
    _register("alice")

    response = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong-password"},
    )

    assert response.status_code == 401


def test_login_then_logout_revokes_only_the_current_session() -> None:
    first = _register("alice")
    second = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": PASSWORD},
    ).json()["access_token"]

    assert client.post("/api/v1/auth/logout", headers=_auth(first)).status_code == 204

    assert client.get("/api/v1/auth/me", headers=_auth(first)).status_code == 401
    assert client.get("/api/v1/auth/me", headers=_auth(second)).status_code == 200


def test_missing_or_invalid_token_is_rejected() -> None:
    assert client.get("/api/v1/auth/me").status_code == 401
    assert client.get("/api/v1/auth/me", headers=_auth("not-a-token")).status_code == 401


def test_expired_session_is_rejected() -> None:
    token = _register("alice")
    with database.write_lock, database.connect() as connection:
        connection.execute(
            "UPDATE sessions SET expires_at = ?",
            ("2000-01-01T00:00:00+00:00",),
        )
        connection.commit()

    assert client.get("/api/v1/auth/me", headers=_auth(token)).status_code == 401


def test_analysis_is_only_visible_to_its_owner(tmp_path: Path) -> None:
    alice = _register("alice")
    bob = _register("bob")

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
    token = _register("alice")
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

    token = _register("alice")
    assert client.get(f"/api/v1/analyses/{job.id}", headers=_auth(token)).status_code == 200
