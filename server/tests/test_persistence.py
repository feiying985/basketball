from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from app.schemas import AnalysisResult, JobStatus, ShotEvent
from app.services.db import Database
from app.services.jobs import INTERRUPTED_ERROR, JobStore, job_store


def _store(path: Path) -> JobStore:
    return JobStore(Database(path))


def test_job_result_survives_store_recreation(tmp_path: Path) -> None:
    path = tmp_path / "jobs.db"
    store = _store(path)
    job = store.create("clip.mp4", owner_id="user-1")
    result = AnalysisResult(
        detector="classical",
        shots=[ShotEvent(timestamp_seconds=1.5, frame_index=15, made=True)],
    )
    store.update(job.id, status=JobStatus.COMPLETED, result=result)

    reopened = _store(path)
    record = reopened.get_record(job.id)

    assert record is not None
    assert record.owner_id == "user-1"
    assert record.job.status is JobStatus.COMPLETED
    assert record.job.result == result


def test_list_returns_only_the_owners_jobs(tmp_path: Path) -> None:
    store = _store(tmp_path / "jobs.db")
    alice_job = store.create("alice.mp4", owner_id="alice")
    store.create("bob.mp4", owner_id="bob")

    listed = store.list("alice")

    assert [job.id for job in listed] == [alice_job.id]


def test_fail_interrupted_marks_unfinished_jobs(tmp_path: Path) -> None:
    store = _store(tmp_path / "jobs.db")
    queued = store.create("queued.mp4")
    processing = store.create("processing.mp4")
    store.update(processing.id, status=JobStatus.PROCESSING)
    completed = store.create("completed.mp4")
    store.update(completed.id, status=JobStatus.COMPLETED)

    assert store.fail_interrupted() == 2

    assert store.get(queued.id).status is JobStatus.FAILED
    assert store.get(queued.id).error == INTERRUPTED_ERROR
    assert store.get(processing.id).status is JobStatus.FAILED
    assert store.get(completed.id).status is JobStatus.COMPLETED


def test_startup_marks_interrupted_jobs_failed() -> None:
    job = job_store.create("clip.mp4")
    job_store.update(job.id, status=JobStatus.PROCESSING)

    with TestClient(app) as client:
        payload = client.get(f"/api/v1/analyses/{job.id}").json()

    assert payload["status"] == "failed"
    assert payload["error"] == INTERRUPTED_ERROR
