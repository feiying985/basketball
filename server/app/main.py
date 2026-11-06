"""FastAPI application factory."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.auth import router as auth_router
from app.api.routes import router
from app.config import settings
from app.services.accounts import user_store
from app.services.clips import clip_service
from app.services.jobs import job_store

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # Analyses run in-process, so anything left queued/processing by a
    # previous process is stale.  Results already persisted stay readable.
    job_store.fail_interrupted()
    user_store.purge_expired_sessions()
    if not clip_service.available:
        logger.warning(
            "ffmpeg 不可用（%s），进球片段将不会生成；健康检查 clips=false",
            settings.ffmpeg_path,
        )
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="Basketball Server",
        version=settings.version,
        description="篮球进球识别后端（MVP 脚手架）",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(router, prefix="/api/v1")
    app.include_router(auth_router, prefix="/api/v1")

    @app.get("/", include_in_schema=False)
    def root() -> dict[str, str]:
        return {"service": settings.service_name, "docs": "/docs"}

    return app


app = create_app()
