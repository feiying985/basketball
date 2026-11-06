"""Background analysis pipeline."""

from __future__ import annotations

import logging
from pathlib import Path

from app.schemas import JobStatus
from app.services.clips import clip_service
from app.services.jobs import job_store
from app.services.shot_detection import HoopRoi, detector

logger = logging.getLogger(__name__)


def run_analysis(
    job_id: str,
    video_path: Path,
    hoop_roi: HoopRoi | None = None,
) -> None:
    job_store.update(job_id, status=JobStatus.PROCESSING, stage="detecting")
    try:
        result = detector.analyze(video_path, hoop_roi=hoop_roi)
    except Exception as exc:  # noqa: BLE001 - any detector failure must fail the job
        job_store.update(job_id, status=JobStatus.FAILED, error=str(exc), stage=None)
        return

    # Keep the array order stable before clips are indexed by position, and
    # persist detection results first so a crash during encoding still leaves a
    # readable analysis.
    result = result.model_copy(
        update={"shots": sorted(result.shots, key=lambda shot: shot.timestamp_seconds)}
    )
    job_store.update(job_id, result=result, stage="clipping")
    try:
        shots, clip_note = clip_service.attach_clips(job_id, video_path, result.shots)
        result = result.model_copy(update={"shots": shots})
        if clip_note is not None:
            result = result.model_copy(update={"clip_note": clip_note})
    except Exception as exc:  # noqa: BLE001 - clips must never fail an analysis
        logger.exception("clip generation failed for job %s", job_id)
        result = result.model_copy(update={"clip_note": f"片段生成失败：{exc}"})
    job_store.update(job_id, status=JobStatus.COMPLETED, result=result, stage=None)
