"""API tests for shot clips: metadata, ownership and HTTP Range streaming."""

from __future__ import annotations

import os
import shutil
from dataclasses import replace
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.api.routes as routes_module
import app.services.analysis as analysis_service
from app.config import Settings
from app.main import app
from app.schemas import AnalysisResult, ClipStatus, JobStatus, ShotClip, ShotEvent
from app.services.clips import ClipService
from app.services.jobs import job_store
from tests.synthetic import write_shot_video

REAL_FFMPEG = shutil.which("ffmpeg") or os.getenv("BASKETBALL_TEST_FFMPEG")

client = TestClient(app)

CLIP_BYTES = b"0123456789"


class FakeClipService:
    """Deterministic clip service: ready clips for made shots, files on disk."""

    def __init__(self, clip_dir: Path) -> None:
        self.clip_dir = clip_dir

    def attach_clips(
        self,
        job_id: str,
        video_path: Path,
        shots: list[ShotEvent],
    ) -> tuple[list[ShotEvent], str | None]:
        ordered = sorted(shots, key=lambda shot: shot.timestamp_seconds)
        updated: list[ShotEvent] = []
        for index, shot in enumerate(ordered):
            if not shot.made:
                updated.append(shot)
                continue
            output = self.clip_dir / job_id / f"{index}.mp4"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(CLIP_BYTES)
            updated.append(
                shot.model_copy(
                    update={
                        "clip": ShotClip(
                            status=ClipStatus.READY,
                            start_seconds=max(0.0, shot.timestamp_seconds - 5.0),
                            end_seconds=shot.timestamp_seconds + 5.0,
                            duration_seconds=10.0,
                            size_bytes=len(CLIP_BYTES),
                            url=f"/api/v1/analyses/{job_id}/clips/{index}",
                        )
                    }
                )
            )
        return updated, None


def _use_clip_dir(monkeypatch, clip_dir: Path) -> FakeClipService:
    fake = FakeClipService(clip_dir)
    monkeypatch.setattr(analysis_service, "clip_service", fake)
    monkeypatch.setattr(
        routes_module,
        "settings",
        replace(routes_module.settings, clip_dir=clip_dir),
    )
    return fake


def test_completed_job_lists_clips_and_serves_range(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _use_clip_dir(monkeypatch, tmp_path / "clips")
    video = tmp_path / "clip.avi"
    write_shot_video(video)

    with video.open("rb") as uploaded:
        response = client.post(
            "/api/v1/analyses",
            files={"file": ("clip.avi", uploaded, "video/x-msvideo")},
        )

    assert response.status_code == 202
    job_id = response.json()["id"]
    payload = client.get(f"/api/v1/analyses/{job_id}").json()
    assert payload["status"] == "completed"
    assert payload["stage"] is None

    made_index = next(
        index
        for index, shot in enumerate(payload["result"]["shots"])
        if shot["made"] and shot["clip"] is not None
    )
    clip = payload["result"]["shots"][made_index]["clip"]
    assert clip["status"] == "ready"
    assert clip["url"] == f"/api/v1/analyses/{job_id}/clips/{made_index}"
    assert clip["duration_seconds"] == 10.0

    full = client.get(f"/api/v1/analyses/{job_id}/clips/{made_index}")
    assert full.status_code == 200
    assert full.headers["content-type"] == "video/mp4"
    assert full.headers["accept-ranges"] == "bytes"
    assert "inline" in full.headers["content-disposition"]
    assert full.headers["cache-control"] == "private, max-age=3600"
    assert full.content == CLIP_BYTES

    partial = client.get(
        f"/api/v1/analyses/{job_id}/clips/{made_index}",
        headers={"Range": "bytes=2-5"},
    )
    assert partial.status_code == 206
    assert partial.content == b"2345"
    assert partial.headers["content-range"] == f"bytes 2-5/{len(CLIP_BYTES)}"


def test_clip_index_out_of_range_returns_404() -> None:
    job = job_store.create("clip.mp4")
    job_store.update(
        job.id,
        status=JobStatus.COMPLETED,
        result=AnalysisResult(
            detector="stub",
            shots=[ShotEvent(timestamp_seconds=4.0, frame_index=1, made=True)],
        ),
    )

    assert client.get(f"/api/v1/analyses/{job.id}/clips/1").status_code == 404
    assert client.get(f"/api/v1/analyses/{job.id}/clips/-1").status_code == 404


def test_clip_not_ready_returns_409() -> None:
    job = job_store.create("clip.mp4")
    job_store.update(
        job.id,
        status=JobStatus.COMPLETED,
        result=AnalysisResult(
            detector="stub",
            shots=[
                ShotEvent(
                    timestamp_seconds=4.0,
                    frame_index=1,
                    made=True,
                    clip=ShotClip(
                        status=ClipStatus.FAILED,
                        start_seconds=0.0,
                        end_seconds=9.0,
                        duration_seconds=9.0,
                        error="boom",
                    ),
                )
            ],
        ),
    )

    response = client.get(f"/api/v1/analyses/{job.id}/clips/0")
    assert response.status_code == 409
    assert "尚未生成或生成失败" in response.json()["detail"]


def test_missing_clip_file_returns_404(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        routes_module,
        "settings",
        replace(routes_module.settings, clip_dir=tmp_path / "empty-clips"),
    )
    job = job_store.create("clip.mp4")
    job_store.update(
        job.id,
        status=JobStatus.COMPLETED,
        result=AnalysisResult(
            detector="stub",
            shots=[
                ShotEvent(
                    timestamp_seconds=4.0,
                    frame_index=1,
                    made=True,
                    clip=ShotClip(
                        status=ClipStatus.READY,
                        start_seconds=0.0,
                        end_seconds=9.0,
                        duration_seconds=9.0,
                        size_bytes=len(CLIP_BYTES),
                        url=f"/api/v1/analyses/{job.id}/clips/0",
                    ),
                )
            ],
        ),
    )

    response = client.get(f"/api/v1/analyses/{job.id}/clips/0")
    assert response.status_code == 404
    assert "清理" in response.json()["detail"]


def test_other_users_clip_returns_404() -> None:
    job = job_store.create("clip.mp4", owner_id="someone-else")
    job_store.update(
        job.id,
        status=JobStatus.COMPLETED,
        result=AnalysisResult(
            detector="stub",
            shots=[
                ShotEvent(
                    timestamp_seconds=4.0,
                    frame_index=1,
                    made=True,
                    clip=ShotClip(
                        status=ClipStatus.READY,
                        start_seconds=0.0,
                        end_seconds=9.0,
                        duration_seconds=9.0,
                        size_bytes=1,
                        url=f"/api/v1/analyses/{job.id}/clips/0",
                    ),
                )
            ],
        ),
    )

    assert client.get(f"/api/v1/analyses/{job.id}/clips/0").status_code == 404


def test_health_reports_clip_availability() -> None:
    payload = client.get("/api/v1/health").json()

    assert payload["version"] == "0.6.0"
    assert payload["clips"] is False


def test_analysis_without_ffmpeg_reports_clip_note(tmp_path: Path) -> None:
    video = tmp_path / "no-ffmpeg.avi"
    write_shot_video(video)

    with video.open("rb") as uploaded:
        response = client.post(
            "/api/v1/analyses",
            files={"file": ("no-ffmpeg.avi", uploaded, "video/x-msvideo")},
        )

    payload = client.get(f"/api/v1/analyses/{response.json()['id']}").json()
    assert payload["status"] == "completed"
    assert "ffmpeg" in (payload["result"]["clip_note"] or "")
    assert all(shot["clip"] is None for shot in payload["result"]["shots"])


@pytest.mark.skipif(REAL_FFMPEG is None, reason="ffmpeg is not installed")
def test_real_ffmpeg_clip_is_served_end_to_end(
    tmp_path: Path,
    monkeypatch,
) -> None:
    clip_dir = tmp_path / "real-clips"
    settings = Settings(
        upload_dir=tmp_path / "uploads",
        clip_dir=clip_dir,
        ffmpeg_path=REAL_FFMPEG or "ffmpeg",
        clip_enabled=True,
        clip_pre_seconds=1.0,
        clip_post_seconds=1.0,
    )
    monkeypatch.setattr(
        analysis_service,
        "clip_service",
        ClipService(settings),
    )
    monkeypatch.setattr(
        routes_module,
        "settings",
        replace(routes_module.settings, clip_dir=clip_dir),
    )
    video = tmp_path / "real.avi"
    write_shot_video(video, duration_seconds=4.0)

    with video.open("rb") as uploaded:
        response = client.post(
            "/api/v1/analyses",
            files={"file": ("real.avi", uploaded, "video/x-msvideo")},
        )

    job_id = response.json()["id"]
    payload = client.get(f"/api/v1/analyses/{job_id}").json()
    ready = [
        (index, shot)
        for index, shot in enumerate(payload["result"]["shots"])
        if shot["made"] and shot["clip"] is not None
    ]
    assert ready, payload
    index, shot = ready[0]
    assert shot["clip"]["status"] == "ready", shot["clip"]
    assert 1.0 <= shot["clip"]["duration_seconds"] <= 3.0

    clip = client.get(f"/api/v1/analyses/{job_id}/clips/{index}")
    assert clip.status_code == 200
    assert clip.headers["content-type"] == "video/mp4"
    assert clip.content

    partial = client.get(
        f"/api/v1/analyses/{job_id}/clips/{index}",
        headers={"Range": "bytes=0-1023"},
    )
    assert partial.status_code == 206
    assert partial.content
    assert partial.headers["content-range"].startswith("bytes 0-1023/")
