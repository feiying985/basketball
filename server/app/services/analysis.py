"""Background analysis pipeline."""

from __future__ import annotations

from pathlib import Path

from app.schemas import JobStatus
from app.services.jobs import job_store
from app.services.shot_detection import HoopRoi, detector


def run_analysis(
    job_id: str,
    video_path: Path,
    hoop_roi: HoopRoi | None = None,
) -> None:
    job_store.update(job_id, status=JobStatus.PROCESSING)
    try:
        result = detector.analyze(video_path, hoop_roi=hoop_roi)
    except Exception as exc:  # noqa: BLE001 - any detector failure must fail the job
        job_store.update(job_id, status=JobStatus.FAILED, error=str(exc))
    else:
        job_store.update(job_id, status=JobStatus.COMPLETED, result=result)
