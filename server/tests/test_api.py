from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health() -> None:
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ok"
    assert payload["service"] == "basketball-server"
    assert payload["detector"] == "stub"


def test_analysis_rejects_unknown_format() -> None:
    response = client.post(
        "/api/v1/analyses",
        files={"file": ("notes.txt", b"hello", "text/plain")},
    )

    assert response.status_code == 415


def test_analysis_flow_returns_placeholder_result() -> None:
    response = client.post(
        "/api/v1/analyses",
        files={"file": ("clip.mp4", b"\x00\x00\x00\x18ftypmp42", "video/mp4")},
    )

    assert response.status_code == 202
    job = response.json()
    assert job["status"] == "queued"

    result = client.get(f"/api/v1/analyses/{job['id']}")
    assert result.status_code == 200
    payload = result.json()
    assert payload["status"] == "completed"
    assert payload["result"]["detector"] == "stub"
    assert payload["result"]["shots"] == []


def test_unknown_job_returns_404() -> None:
    assert client.get("/api/v1/analyses/does-not-exist").status_code == 404
