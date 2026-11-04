"""In-memory job store.

This is intentionally process-local: the MVP runs a single API process.
Replace it with a database/queue (e.g. Redis + Celery) before scaling out.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from threading import Lock

from app.schemas import AnalysisJob, JobStatus


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, AnalysisJob] = {}
        self._lock = Lock()

    def create(self, filename: str) -> AnalysisJob:
        job = AnalysisJob(
            id=uuid.uuid4().hex,
            status=JobStatus.QUEUED,
            filename=filename,
            created_at=datetime.now(timezone.utc),
        )
        with self._lock:
            self._jobs[job.id] = job
        return job

    def get(self, job_id: str) -> AnalysisJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def update(self, job_id: str, **changes: object) -> AnalysisJob:
        with self._lock:
            job = self._jobs[job_id]
            updated = job.model_copy(update=changes)
            self._jobs[job_id] = updated
            return updated


job_store = JobStore()
