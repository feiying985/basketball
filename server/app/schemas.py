"""Request and response models."""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field, field_validator


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


class User(BaseModel):
    id: str
    username: str
    created_at: datetime


class Credentials(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    password: str = Field(..., min_length=8, max_length=128)

    @field_validator("username")
    @classmethod
    def _normalize_username(cls, value: str) -> str:
        username = value.strip()
        if len(username) < 3:
            raise ValueError("用户名至少需要 3 个字符")
        if any(character.isspace() for character in username):
            raise ValueError("用户名不能包含空白字符")
        return username


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime | None = None
    user: User
