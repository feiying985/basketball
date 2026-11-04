"""Request and response models."""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field


class JobStatus(str, Enum):
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"


class ShotEvent(BaseModel):
    timestamp_seconds: float = Field(..., ge=0, description="进球发生时间（秒）")
    frame_index: int = Field(..., ge=0)
    made: bool = Field(..., description="true=命中，false=未命中")


class AnalysisResult(BaseModel):
    detector: str
    shots: list[ShotEvent] = Field(default_factory=list)
    note: str | None = None


class AnalysisJob(BaseModel):
    id: str
    status: JobStatus
    filename: str
    created_at: datetime
    result: AnalysisResult | None = None
    error: str | None = None


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    detector: str
