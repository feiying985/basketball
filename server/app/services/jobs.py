"""SQLite-backed analysis job store.

Jobs keep their status and result across API restarts.  A task that was still
queued or processing when the process stopped cannot be resumed (the analysis
runs in-process), so it is marked as failed on the next startup.
"""

from __future__ import annotations

import sqlite3
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from app.schemas import AnalysisJob, AnalysisResult, JobStatus
from app.services.db import Database
from app.services.db import database as default_database

INTERRUPTED_ERROR = "服务重启导致任务中断，请重新上传视频"


@dataclass(frozen=True)
class JobRecord:
    job: AnalysisJob
    owner_id: str | None


class JobStore:
    def __init__(self, database: Database | None = None) -> None:
        self._database = database or default_database

    def create(self, filename: str, owner_id: str | None = None) -> AnalysisJob:
        now = datetime.now(timezone.utc)
        job = AnalysisJob(
            id=uuid.uuid4().hex,
            status=JobStatus.QUEUED,
            filename=filename,
            created_at=now,
        )
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute(
                """
                INSERT INTO jobs (id, owner_id, status, filename, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    job.id,
                    owner_id,
                    job.status.value,
                    job.filename,
                    now.isoformat(),
                    now.isoformat(),
                ),
            )
            connection.commit()
        return job

    def get(self, job_id: str) -> AnalysisJob | None:
        record = self.get_record(job_id)
        return record.job if record is not None else None

    def get_record(self, job_id: str) -> JobRecord | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM jobs WHERE id = ?", (job_id,)
            ).fetchone()
        if row is None:
            return None
        return JobRecord(job=_job_from_row(row), owner_id=row["owner_id"])

    def list(self, owner_id: str, limit: int = 50, offset: int = 0) -> list[AnalysisJob]:
        with self._database.connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM jobs
                WHERE owner_id = ?
                ORDER BY created_at DESC, rowid DESC
                LIMIT ? OFFSET ?
                """,
                (owner_id, limit, offset),
            ).fetchall()
        return [_job_from_row(row) for row in rows]

    def update(self, job_id: str, **changes: object) -> AnalysisJob:
        record = self.get_record(job_id)
        if record is None:
            raise KeyError(job_id)
        updated = record.job.model_copy(update=changes)
        result_json = (
            updated.result.model_dump_json() if updated.result is not None else None
        )
        now = datetime.now(timezone.utc)
        with self._database.write_lock, self._database.connect() as connection:
            connection.execute(
                """
                UPDATE jobs
                SET status = ?, filename = ?, result_json = ?, error = ?, stage = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    updated.status.value,
                    updated.filename,
                    result_json,
                    updated.error,
                    updated.stage,
                    now.isoformat(),
                    job_id,
                ),
            )
            connection.commit()
        return updated

    def fail_interrupted(self) -> int:
        """Mark tasks left queued/processing by a previous process as failed."""

        now = datetime.now(timezone.utc)
        with self._database.write_lock, self._database.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE jobs
                SET status = ?, error = ?, stage = NULL, updated_at = ?
                WHERE status IN (?, ?)
                """,
                (
                    JobStatus.FAILED.value,
                    INTERRUPTED_ERROR,
                    now.isoformat(),
                    JobStatus.QUEUED.value,
                    JobStatus.PROCESSING.value,
                ),
            )
            connection.commit()
            return cursor.rowcount


def _job_from_row(row: sqlite3.Row) -> AnalysisJob:
    result = (
        AnalysisResult.model_validate_json(row["result_json"])
        if row["result_json"]
        else None
    )
    return AnalysisJob(
        id=row["id"],
        status=JobStatus(row["status"]),
        filename=row["filename"],
        created_at=row["created_at"],
        result=result,
        error=row["error"],
        stage=row["stage"],
    )


job_store = JobStore()
