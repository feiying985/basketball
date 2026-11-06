"""Tests for clip window calculation and ffmpeg clip generation."""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from app.config import Settings
from app.schemas import ClipStatus, ShotEvent
from app.services import clips

REAL_FFMPEG = shutil.which("ffmpeg") or os.getenv("BASKETBALL_TEST_FFMPEG")


def make_settings(tmp_path: Path, **overrides: object) -> Settings:
    values: dict[str, object] = {
        "upload_dir": tmp_path / "uploads",
        "clip_dir": tmp_path / "clips",
        "ffmpeg_path": "/fake/ffmpeg",
        "clip_enabled": True,
        "clip_pre_seconds": 5.0,
        "clip_post_seconds": 5.0,
        "clip_include_misses": False,
        "clip_max_per_job": 50,
        "clip_timeout_seconds": 120.0,
    }
    values.update(overrides)
    return Settings(**values)  # type: ignore[arg-type]


@pytest.fixture
def fake_ffmpeg(monkeypatch: pytest.MonkeyPatch) -> list[list[str]]:
    """Pretend ffmpeg exists, knows the duration and writes a small output."""

    calls: list[list[str]] = []

    def fake_run(command: list[str], **_: object) -> subprocess.CompletedProcess[str]:
        calls.append(command)
        Path(command[-1]).write_bytes(b"fake-mp4")
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: 60.0)
    monkeypatch.setattr(clips.subprocess, "run", fake_run)
    return calls


def test_compute_clip_window_uses_five_seconds_each_side() -> None:
    assert clips.compute_clip_window(10.0, 60.0, 5.0, 5.0) == (5.0, 15.0)


@pytest.mark.parametrize(
    ("timestamp", "duration", "pre", "post", "expected"),
    [
        (2.0, 60.0, 5.0, 5.0, (0.0, 7.0)),
        (58.0, 60.0, 5.0, 5.0, (53.0, 60.0)),
        (1.0, 2.0, 5.0, 5.0, (0.0, 2.0)),
        (0.2, 0.3, 0.1, 0.1, None),
        (60.0, 30.0, 5.0, 5.0, None),
        (10.0, 0.0, 5.0, 5.0, None),
    ],
)
def test_compute_clip_window_clamps_and_skips_short_windows(
    timestamp: float,
    duration: float,
    pre: float,
    post: float,
    expected: tuple[float, float] | None,
) -> None:
    assert clips.compute_clip_window(timestamp, duration, pre, post) == expected


def test_build_ffmpeg_command_is_android_compatible(tmp_path: Path) -> None:
    command = clips.build_ffmpeg_command(
        "ffmpeg",
        tmp_path / "input.mov",
        tmp_path / "0.mp4",
        12.5,
        10.0,
    )

    assert command[0] == "ffmpeg"
    assert command[command.index("-ss") + 1] == "12.500"
    assert command[command.index("-i") + 1].endswith("input.mov")
    assert command[command.index("-t") + 1] == "10.000"
    assert command[command.index("-map") + 1] == "0:v:0"
    assert "0:a:0?" in command
    assert "libx264" in command
    assert "yuv420p" in command
    assert "aac" in command
    assert "+faststart" in command


def test_attach_clips_sorts_shots_and_clips_made_shots(
    tmp_path: Path,
    fake_ffmpeg: list[list[str]],
) -> None:
    service = clips.ClipService(make_settings(tmp_path))
    shots = [
        ShotEvent(timestamp_seconds=20.0, frame_index=600, made=True),
        ShotEvent(timestamp_seconds=10.0, frame_index=300, made=True),
        ShotEvent(timestamp_seconds=30.0, frame_index=900, made=False),
    ]

    updated, note = service.attach_clips("job-1", tmp_path / "video.mp4", shots)

    assert note is None
    assert [shot.timestamp_seconds for shot in updated] == [10.0, 20.0, 30.0]
    first = updated[0].clip
    assert first is not None
    assert first.status is ClipStatus.READY
    assert (first.start_seconds, first.end_seconds, first.duration_seconds) == (5.0, 15.0, 10.0)
    assert first.url == "/api/v1/analyses/job-1/clips/0"
    assert first.size_bytes == len(b"fake-mp4")
    second = updated[1].clip
    assert second is not None
    assert second.url == "/api/v1/analyses/job-1/clips/1"
    assert updated[2].clip is None
    assert (tmp_path / "clips" / "job-1" / "0.mp4").is_file()
    assert (tmp_path / "clips" / "job-1" / "1.mp4").is_file()
    assert len(fake_ffmpeg) == 2


def test_attach_clips_reuses_existing_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = clips.ClipService(make_settings(tmp_path))
    output = tmp_path / "clips" / "job-1" / "0.mp4"
    output.parent.mkdir(parents=True)
    output.write_bytes(b"already-here")

    def fail_run(*_: object, **__: object) -> subprocess.CompletedProcess[str]:
        raise AssertionError("ffmpeg must not run for an existing clip")

    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: 60.0)
    monkeypatch.setattr(clips.subprocess, "run", fail_run)

    updated, _ = service.attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.READY
    assert clip.size_bytes == len(b"already-here")


def test_attach_clips_records_ffmpeg_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: 60.0)
    monkeypatch.setattr(
        clips.subprocess,
        "run",
        lambda command, **_: subprocess.CompletedProcess(
            command, 1, "", "encoder exploded\n"
        ),
    )

    updated, note = clips.ClipService(make_settings(tmp_path)).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    assert note is None
    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.FAILED
    assert "encoder exploded" in (clip.error or "")
    assert not (tmp_path / "clips" / "job-1" / "0.mp4").exists()


def test_attach_clips_records_timeout(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: 60.0)

    def timeout_run(command: list[str], **_: object) -> None:
        raise subprocess.TimeoutExpired(command, 120)

    monkeypatch.setattr(clips.subprocess, "run", timeout_run)

    updated, _ = clips.ClipService(make_settings(tmp_path)).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.FAILED
    assert "超时" in (clip.error or "")


def test_attach_clips_records_empty_output(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: 60.0)
    monkeypatch.setattr(
        clips.subprocess,
        "run",
        lambda command, **_: subprocess.CompletedProcess(command, 0, "", ""),
    )

    updated, _ = clips.ClipService(make_settings(tmp_path)).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.FAILED
    assert "有效片段" in (clip.error or "")


def test_attach_clips_without_ffmpeg_returns_note(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(clips, "resolve_executable", lambda value: None)

    updated, note = clips.ClipService(make_settings(tmp_path)).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    assert note is not None and "ffmpeg" in note
    assert updated[0].clip is None


def test_attach_clips_can_be_disabled(tmp_path: Path) -> None:
    updated, note = clips.ClipService(
        make_settings(tmp_path, clip_enabled=False)
    ).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    assert note is None
    assert updated[0].clip is None


def test_attach_clips_marks_failed_when_duration_unknown(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(clips, "resolve_executable", lambda value: str(value))
    monkeypatch.setattr(clips, "probe_duration", lambda path, ffprobe=None: None)

    updated, _ = clips.ClipService(make_settings(tmp_path)).attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True)],
    )

    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.FAILED
    assert "时长" in (clip.error or "")


def test_attach_clips_can_include_misses_and_limit_count(
    tmp_path: Path,
    fake_ffmpeg: list[list[str]],
) -> None:
    service = clips.ClipService(
        make_settings(
            tmp_path,
            clip_include_misses=True,
            clip_max_per_job=2,
        )
    )

    updated, _ = service.attach_clips(
        "job-1",
        tmp_path / "video.mp4",
        [
            ShotEvent(timestamp_seconds=10.0, frame_index=1, made=True),
            ShotEvent(timestamp_seconds=20.0, frame_index=2, made=False),
            ShotEvent(timestamp_seconds=30.0, frame_index=3, made=True),
        ],
    )

    assert len(fake_ffmpeg) == 2
    assert updated[0].clip is not None
    assert updated[1].clip is not None
    assert updated[2].clip is None


@pytest.mark.skipif(REAL_FFMPEG is None, reason="ffmpeg is not installed")
def test_real_ffmpeg_generates_a_playable_clip(tmp_path: Path) -> None:
    from tests.synthetic import write_shot_video

    video = tmp_path / "input.avi"
    write_shot_video(video, duration_seconds=4.0)
    settings = make_settings(
        tmp_path,
        ffmpeg_path=REAL_FFMPEG or "ffmpeg",
        clip_pre_seconds=1.0,
        clip_post_seconds=1.0,
    )

    updated, note = clips.ClipService(settings).attach_clips(
        "job-real",
        video,
        [ShotEvent(timestamp_seconds=2.0, frame_index=60, made=True)],
    )

    assert note is None
    clip = updated[0].clip
    assert clip is not None
    assert clip.status is ClipStatus.READY, clip.error
    output = settings.clip_output_dir / "job-real" / "0.mp4"
    assert output.is_file()
    assert output.stat().st_size > 0
    duration = clips.probe_duration(
        output,
        clips.default_ffprobe_path(REAL_FFMPEG),
    )
    assert duration is not None
    assert 1.5 <= duration <= 2.6
