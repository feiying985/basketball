"""Server configuration loaded from environment variables."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_UPLOAD_DIR = Path(tempfile.gettempdir()) / "basketball-uploads"


@dataclass(frozen=True)
class Settings:
    service_name: str = "basketball-server"
    version: str = "0.1.0"
    upload_dir: Path = Path(os.getenv("BASKETBALL_UPLOAD_DIR", str(DEFAULT_UPLOAD_DIR)))
    max_upload_bytes: int = int(os.getenv("BASKETBALL_MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))


settings = Settings()
