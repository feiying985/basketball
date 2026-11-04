from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from tests.synthetic import write_shot_video

client = TestClient(app)


def test_health() -> None:
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ok"
    assert payload["service"] == "basketball-server"
    assert payload["detector"] == "classical"


def test_analysis_rejects_unknown_format() -> None:
    response = client.post(
        "/api/v1/analyses",
        files={"file": ("notes.txt", b"hello", "text/plain")},
    )

    assert response.status_code == 415


def test_analysis_flow_returns_detected_shots(tmp_path: Path) -> None:
    video = tmp_path / "clip.avi"
    write_shot_video(video)

    with video.open("rb") as uploaded:
        response = client.post(
            "/api/v1/analyses",
            files={"file": ("clip.avi", uploaded, "video/x-msvideo")},
        )

    assert response.status_code == 202
    job = response.json()
    assert job["status"] == "queued"

    result = client.get(f"/api/v1/analyses/{job['id']}")
    assert result.status_code == 200
    payload = result.json()
    assert payload["status"] == "completed"
    assert payload["result"]["detector"] == "classical"
    assert payload["result"]["shots"]
    assert any(shot["made"] for shot in payload["result"]["shots"])


def test_unknown_job_returns_404() -> None:
    assert client.get("/api/v1/analyses/does-not-exist").status_code == 404
