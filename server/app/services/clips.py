"""Server-side shot-clip generation with ffmpeg.

After detection finishes, the analysis pipeline asks this module to cut a short
MP4 around every made shot.  Clips are written under ``BASKETBALL_CLIP_DIR``
(``<upload_dir>/clips`` by default) and their metadata is stored in the job
result.  A single clip failure is recorded on that shot and never fails the
analysis itself; ffmpeg is optional and its availability is exposed by
``GET /api/v1/health``.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import threading
from pathlib import Path

from app.config import Settings
from app.config import settings as default_settings
from app.schemas import ClipStatus, ShotClip, ShotEvent

MIN_CLIP_SECONDS = 0.5
ERROR_TAIL_LINES = 3
ERROR_MAX_CHARS = 500
CLIP_URL_PREFIX = "/api/v1/analyses"

# Encoding is the most CPU-hungry part of the pipeline.  The MVP is a single
# process, so one global permit keeps concurrent uploads from saturating a
# small 2 vCPU server.
_FFMPEG_SEMAPHORE = threading.BoundedSemaphore(1)

_FFMPEG_MISSING_NOTE = "服务端未安装 ffmpeg，未生成进球片段"
_UNREADABLE_DURATION_ERROR = "无法读取视频时长，未生成片段"
_TOO_SHORT_ERROR = "视频边界内不足 0.5 秒，无法生成片段"


def resolve_executable(value: str) -> str | None:
    """Resolve a configured executable name or path, or return ``None``."""

    candidate = Path(value)
    if candidate.is_file():
        return str(candidate)
    return shutil.which(value)


def ffmpeg_available(config: Settings | None = None) -> bool:
    target = config or default_settings
    return target.clip_enabled and resolve_executable(target.ffmpeg_path) is not None


def default_ffprobe_path(ffmpeg: str | None) -> str | None:
    """Prefer ffprobe next to the configured ffmpeg, then PATH."""

    if ffmpeg:
        suffix = ".exe" if os.name == "nt" else ""
        sibling = Path(ffmpeg).with_name(f"ffprobe{suffix}")
        if sibling.is_file():
            return str(sibling)
    return shutil.which("ffprobe")


def compute_clip_window(
    timestamp_seconds: float,
    duration_seconds: float,
    pre_seconds: float,
    post_seconds: float,
) -> tuple[float, float] | None:
    """Return the ``[start, end]`` window for one shot, clamped to the video.

    ``None`` means the visible part is shorter than :data:`MIN_CLIP_SECONDS`.
    """

    if duration_seconds <= 0:
        return None
    start = max(0.0, timestamp_seconds - pre_seconds)
    end = min(duration_seconds, timestamp_seconds + post_seconds)
    if end - start < MIN_CLIP_SECONDS:
        return None
    return start, end


def build_ffmpeg_command(
    ffmpeg_path: str,
    source: Path,
    output: Path,
    start_seconds: float,
    duration_seconds: float,
) -> list[str]:
    """Build the re-encoding command used for every clip.

    ``-ss`` sits before ``-i`` for fast seeking; modern ffmpeg still decodes to
    the target frame, which is accurate enough for a five-second window.  Source
    HEVC is converted to H.264 so Android can play it.
    """

    return [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-ss",
        f"{start_seconds:.3f}",
        "-i",
        str(source),
        "-t",
        f"{duration_seconds:.3f}",
        "-map",
        "0:v:0",
        "-map",
        "0:a:0?",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "23",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-b:a",
        "128k",
        "-movflags",
        "+faststart",
        "-y",
        str(output),
    ]


def probe_duration(video_path: Path, ffprobe_path: str | None = None) -> float | None:
    """Return the video duration in seconds using ffprobe, then OpenCV."""

    if ffprobe_path:
        try:
            completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
                [
                    ffprobe_path,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    str(video_path),
                ],
                capture_output=True,
                text=True,
                errors="replace",
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            completed = None
        if completed is not None and completed.returncode == 0:
            try:
                value = float(completed.stdout.strip())
            except ValueError:
                pass
            else:
                if value > 0:
                    return value

    try:
        import cv2  # Imported lazily: ffprobe normally answers first.
    except Exception:  # noqa: BLE001 - OpenCV is optional in this fallback
        return None

    capture = cv2.VideoCapture(str(video_path))
    try:
        if not capture.isOpened():
            return None
        frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
    finally:
        capture.release()
    if frames <= 0 or fps <= 0:
        return None
    return frames / fps


class ClipService:
    """Compute clip windows and produce MP4 clips for an analysis job."""

    def __init__(self, config: Settings | None = None) -> None:
        self._config = config or default_settings

    @property
    def config(self) -> Settings:
        return self._config

    @property
    def available(self) -> bool:
        return ffmpeg_available(self._config)

    def attach_clips(
        self,
        job_id: str,
        video_path: Path,
        shots: list[ShotEvent],
    ) -> tuple[list[ShotEvent], str | None]:
        """Return timestamp-sorted shots with clip metadata attached.

        The second value is a job-wide note for cases where no clip work ran
        (ffmpeg disabled or missing).  The returned list keeps a stable index:
        ``GET /analyses/{job_id}/clips/{index}`` indexes into it.
        """

        ordered = sorted(shots, key=lambda shot: shot.timestamp_seconds)
        if not self._config.clip_enabled:
            return ordered, None

        ffmpeg = resolve_executable(self._config.ffmpeg_path)
        if ffmpeg is None:
            return ordered, _FFMPEG_MISSING_NOTE

        indexes = [
            index
            for index, shot in enumerate(ordered)
            if shot.made or self._config.clip_include_misses
        ][: self._config.clip_max_per_job]
        if not indexes:
            return ordered, None

        duration = probe_duration(video_path, default_ffprobe_path(ffmpeg))
        if duration is None:
            updated = list(ordered)
            for index in indexes:
                updated[index] = ordered[index].model_copy(
                    update={
                        "clip": self._failed_clip(
                            ordered[index].timestamp_seconds,
                            0.0,
                            _UNREADABLE_DURATION_ERROR,
                        )
                    }
                )
            return updated, None

        target_dir = self._config.clip_output_dir / job_id
        target_dir.mkdir(parents=True, exist_ok=True)

        updated = list(ordered)
        for index in indexes:
            shot = ordered[index]
            window = compute_clip_window(
                shot.timestamp_seconds,
                duration,
                self._config.clip_pre_seconds,
                self._config.clip_post_seconds,
            )
            if window is None:
                updated[index] = shot.model_copy(
                    update={
                        "clip": self._failed_clip(
                            shot.timestamp_seconds, duration, _TOO_SHORT_ERROR
                        )
                    }
                )
                continue

            start, end = window
            output = target_dir / f"{index}.mp4"
            clip = self._existing_clip(job_id, index, output, start, end)
            if clip is None:
                clip = self._generate_clip(
                    ffmpeg,
                    job_id,
                    index,
                    video_path,
                    output,
                    start,
                    end,
                )
            updated[index] = shot.model_copy(update={"clip": clip})
        return updated, None

    def _existing_clip(
        self,
        job_id: str,
        index: int,
        output: Path,
        start: float,
        end: float,
    ) -> ShotClip | None:
        """Reuse a clip generated by an earlier attempt; clips are idempotent."""

        try:
            size = output.stat().st_size
        except OSError:
            return None
        if size <= 0:
            return None
        return self._ready_clip(job_id, index, output, start, end)

    def _generate_clip(
        self,
        ffmpeg: str,
        job_id: str,
        index: int,
        source: Path,
        output: Path,
        start: float,
        end: float,
    ) -> ShotClip:
        duration = end - start
        # Keep the .mp4 suffix so ffmpeg can pick the muxer from the filename.
        temp = output.with_name(f"{output.stem}.part{output.suffix}")
        error: str | None = None
        try:
            temp.unlink(missing_ok=True)
            command = build_ffmpeg_command(ffmpeg, source, temp, start, duration)
            with _FFMPEG_SEMAPHORE:
                completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
                    command,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=self._config.clip_timeout_seconds,
                    check=False,
                )
            if completed.returncode != 0:
                error = _tail(completed.stderr) or f"ffmpeg 退出码 {completed.returncode}"
            elif not temp.is_file() or temp.stat().st_size <= 0:
                error = "ffmpeg 未生成有效片段"
            else:
                os.replace(temp, output)
        except subprocess.TimeoutExpired:
            error = f"片段生成超时（>{self._config.clip_timeout_seconds:.0f} 秒）"
        except OSError as exc:
            error = str(exc)
        finally:
            temp.unlink(missing_ok=True)

        if error is not None:
            return self._failed_span(start, end, error)
        return self._ready_clip(job_id, index, output, start, end)

    def _ready_clip(
        self,
        job_id: str,
        index: int,
        output: Path,
        start: float,
        end: float,
    ) -> ShotClip:
        return ShotClip(
            status=ClipStatus.READY,
            start_seconds=_round(start),
            end_seconds=_round(end),
            duration_seconds=_round(end - start),
            size_bytes=output.stat().st_size,
            url=f"{CLIP_URL_PREFIX}/{job_id}/clips/{index}",
        )

    def _failed_clip(
        self,
        timestamp_seconds: float,
        duration_seconds: float,
        error: str,
    ) -> ShotClip:
        if duration_seconds > 0:
            start = max(0.0, timestamp_seconds - self._config.clip_pre_seconds)
            end = min(duration_seconds, timestamp_seconds + self._config.clip_post_seconds)
        else:
            start = 0.0
            end = 0.0
        return self._failed_span(start, end, error)

    def _failed_span(self, start: float, end: float, error: str) -> ShotClip:
        return ShotClip(
            status=ClipStatus.FAILED,
            start_seconds=_round(start),
            end_seconds=_round(end),
            duration_seconds=_round(max(0.0, end - start)),
            error=error[:ERROR_MAX_CHARS],
        )


def _round(value: float) -> float:
    return round(max(0.0, value), 3)


def _tail(value: str | None) -> str | None:
    if not value:
        return None
    lines = [line.strip() for line in value.strip().splitlines() if line.strip()]
    if not lines:
        return None
    return "\n".join(lines[-ERROR_TAIL_LINES:])[-ERROR_MAX_CHARS:]


clip_service = ClipService()
