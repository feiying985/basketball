"""Request and response models."""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from app.services.phone import InvalidPhone, looks_like_phone, normalize_phone


class JobStatus(str, Enum):
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"


class ClipStatus(str, Enum):
    READY = "ready"
    FAILED = "failed"


class ShotClip(BaseModel):
    status: ClipStatus
    start_seconds: float = Field(..., ge=0, description="片段开始时间（秒）")
    end_seconds: float = Field(..., ge=0, description="片段结束时间（秒）")
    duration_seconds: float = Field(..., ge=0, description="片段时长（秒）")
    size_bytes: int | None = Field(default=None, ge=0)
    url: str | None = Field(default=None, description="相对接口路径，客户端拼接服务地址")
    error: str | None = None


class ShotEvent(BaseModel):
    timestamp_seconds: float = Field(..., ge=0, description="进球发生时间（秒）")
    frame_index: int = Field(..., ge=0)
    made: bool = Field(..., description="true=命中，false=未命中")
    clip: ShotClip | None = Field(default=None, description="进球前后片段；未生成为空")


class AnalysisResult(BaseModel):
    detector: str
    shots: list[ShotEvent] = Field(default_factory=list)
    note: str | None = None
    hoop_confidence: float | None = Field(
        default=None,
        ge=0,
        le=1,
        description="自动找筐置信度（0-1）；手动指定篮筐时为 1",
    )
    clip_note: str | None = Field(
        default=None,
        description="片段生成未执行或整体不可用时的说明",
    )


class AnalysisJob(BaseModel):
    id: str
    status: JobStatus
    filename: str
    created_at: datetime
    result: AnalysisResult | None = None
    error: str | None = None
    stage: str | None = Field(
        default=None,
        description="处理阶段：detecting / clipping；任务结束时为空",
    )


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    detector: str
    clips: bool = Field(default=False, description="服务端是否可用 ffmpeg 生成片段")


class User(BaseModel):
    id: str
    phone: str | None = None
    username: str | None = None
    display_name: str
    created_at: datetime


class SmsCodeRequest(BaseModel):
    phone: str
    purpose: Literal["register", "login", "reset"]

    @field_validator("phone")
    @classmethod
    def _normalize_phone(cls, value: str) -> str:
        try:
            return normalize_phone(value)
        except InvalidPhone as exc:
            raise ValueError(str(exc)) from exc


class SmsCodeResponse(BaseModel):
    expires_in: int
    resend_after: int


class _PasswordModel(BaseModel):
    password: str = Field(..., min_length=8, max_length=128)


class RegisterRequest(_PasswordModel):
    phone: str
    code: str = Field(..., pattern=r"^\d{6}$")
    username: str | None = Field(default=None, min_length=3, max_length=32)

    @field_validator("phone")
    @classmethod
    def _normalize_phone(cls, value: str) -> str:
        try:
            return normalize_phone(value)
        except InvalidPhone as exc:
            raise ValueError(str(exc)) from exc

    @field_validator("username")
    @classmethod
    def _normalize_username(cls, value: str | None) -> str | None:
        if value is None:
            return None
        username = value.strip()
        if not username:
            return None
        if len(username) < 3:
            raise ValueError("用户名至少需要 3 个字符")
        if any(character.isspace() for character in username):
            raise ValueError("用户名不能包含空白字符")
        if looks_like_phone(username):
            raise ValueError("用户名不能是手机号")
        return username


class LoginRequest(BaseModel):
    account: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=8, max_length=128)

    @model_validator(mode="before")
    @classmethod
    def _accept_legacy_username(cls, data: object) -> object:
        # Older clients sent ``username``; keep accepting it as the account.
        if isinstance(data, dict) and "account" not in data and "username" in data:
            return {**data, "account": data["username"]}
        return data

    @field_validator("account")
    @classmethod
    def _normalize_account(cls, value: str) -> str:
        account = value.strip()
        if not account:
            raise ValueError("请输入账号或手机号")
        return account


class CodeLoginRequest(BaseModel):
    phone: str
    code: str = Field(..., pattern=r"^\d{6}$")

    @field_validator("phone")
    @classmethod
    def _normalize_phone(cls, value: str) -> str:
        try:
            return normalize_phone(value)
        except InvalidPhone as exc:
            raise ValueError(str(exc)) from exc


class ChangePasswordRequest(BaseModel):
    current_password: str = Field(..., min_length=8, max_length=128)
    new_password: str = Field(..., min_length=8, max_length=128)


class ResetPasswordRequest(BaseModel):
    phone: str
    code: str = Field(..., pattern=r"^\d{6}$")
    new_password: str = Field(..., min_length=8, max_length=128)

    @field_validator("phone")
    @classmethod
    def _normalize_phone(cls, value: str) -> str:
        try:
            return normalize_phone(value)
        except InvalidPhone as exc:
            raise ValueError(str(exc)) from exc


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime | None = None
    user: User
