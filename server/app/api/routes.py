"""HTTP routes."""

from __future__ import annotations

from pathlib import Path

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    UploadFile,
    status,
)
from fastapi.responses import FileResponse

from app.api.dependencies import AuthContext, optional_auth, require_auth
from app.config import parse_hoop_roi, settings
from app.schemas import AnalysisJob, ClipStatus, HealthResponse
from app.services.analysis import run_analysis
from app.services.clips import clip_service
from app.services.jobs import JobRecord, job_store
from app.services.shot_detection import detector

router = APIRouter()

ALLOWED_SUFFIXES = {".mp4", ".mov", ".m4v", ".webm", ".avi", ".mkv"}


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        service=settings.service_name,
        version=settings.version,
        detector=detector.name,
        clips=clip_service.available,
    )


@router.post(
    "/analyses",
    response_model=AnalysisJob,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_analysis(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    hoop_roi: str | None = Form(default=None),
    auth: AuthContext | None = Depends(optional_auth),
) -> AnalysisJob:
    if settings.require_auth and auth is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="需要登录后才能上传视频",
            headers={"WWW-Authenticate": "Bearer"},
        )

    filename = file.filename or "upload"
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"不支持的视频格式: {suffix or '未知'}",
        )

    parsed_hoop_roi = None
    if hoop_roi is not None and hoop_roi.strip():
        try:
            parsed_hoop_roi = parse_hoop_roi(hoop_roi, name="hoop_roi")
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=str(exc),
            ) from exc

    owner_id = auth.user.id if auth is not None else None
    job = job_store.create(filename=filename, owner_id=owner_id)
    video_path = _save_upload(file, job.id, suffix)
    background_tasks.add_task(run_analysis, job.id, video_path, parsed_hoop_roi)
    return job


@router.get("/analyses", response_model=list[AnalysisJob])
def list_analyses(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    auth: AuthContext = Depends(require_auth),
) -> list[AnalysisJob]:
    return job_store.list(auth.user.id, limit=limit, offset=offset)


@router.get("/analyses/{job_id}", response_model=AnalysisJob)
def get_analysis(
    job_id: str,
    auth: AuthContext | None = Depends(optional_auth),
) -> AnalysisJob:
    return _owned_record(job_id, auth).job


@router.get("/analyses/{job_id}/clips/{index}")
def get_analysis_clip(
    job_id: str,
    index: int,
    auth: AuthContext | None = Depends(optional_auth),
) -> FileResponse:
    """Stream a generated shot clip with HTTP Range support for seeking."""

    record = _owned_record(job_id, auth)
    shots = record.job.result.shots if record.job.result is not None else []
    if index < 0 or index >= len(shots):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="片段不存在")

    clip = shots[index].clip
    if clip is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="该投篮没有生成片段",
        )
    if clip.status is not ClipStatus.READY:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="片段尚未生成或生成失败",
        )

    path = settings.clip_output_dir / job_id / f"{index}.mp4"
    if not path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="片段文件已清理",
        )
    return FileResponse(
        path,
        media_type="video/mp4",
        headers={
            "Content-Disposition": f'inline; filename="shot-{index + 1}.mp4"',
            "Cache-Control": "private, max-age=3600",
        },
    )


def _owned_record(job_id: str, auth: AuthContext | None) -> JobRecord:
    """Load a job applying the same ownership rules as the result endpoint."""

    record = job_store.get_record(job_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="分析任务不存在")
    if record.owner_id is not None:
        # Hide other users' tasks instead of revealing that the ID exists.
        if auth is None or auth.user.id != record.owner_id:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="分析任务不存在"
            )
    elif settings.require_auth and auth is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="需要登录",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return record


def _save_upload(file: UploadFile, job_id: str, suffix: str) -> Path:
    settings.upload_dir.mkdir(parents=True, exist_ok=True)
    destination = settings.upload_dir / f"{job_id}{suffix}"
    size = 0
    try:
        with destination.open("wb") as buffer:
            while chunk := file.file.read(1024 * 1024):
                size += len(chunk)
                if size > settings.max_upload_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail="视频超过大小限制",
                    )
                buffer.write(chunk)
    except HTTPException:
        destination.unlink(missing_ok=True)
        raise
    return destination
