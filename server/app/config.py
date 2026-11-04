"""Server configuration loaded from environment variables."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_UPLOAD_DIR = Path(tempfile.gettempdir()) / "basketball-uploads"


def _env_float(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)))


def _env_roi(name: str) -> tuple[float, float, float, float] | None:
    """Parse a normalized ``x,y,width,height`` hoop region from the environment."""

    raw = os.getenv(name, "").strip()
    if not raw:
        return None
    parts = [part.strip() for part in raw.split(",")]
    if len(parts) != 4:
        raise ValueError(f"{name} 需要 4 个 0-1 之间的数值: x,y,width,height")
    try:
        x, y, width, height = (float(part) for part in parts)
    except ValueError as exc:
        raise ValueError(f"{name} 需要 4 个 0-1 之间的数值: x,y,width,height") from exc
    if not (0 <= x < 1 and 0 <= y < 1 and 0 < width <= 1 and 0 < height <= 1):
        raise ValueError(f"{name} 的取值范围是 0-1，且宽高必须大于 0")
    if x + width > 1 or y + height > 1:
        raise ValueError(f"{name} 不能超出画面范围")
    return x, y, width, height


@dataclass(frozen=True)
class Settings:
    service_name: str = "basketball-server"
    version: str = "0.2.0"
    upload_dir: Path = Path(os.getenv("BASKETBALL_UPLOAD_DIR", str(DEFAULT_UPLOAD_DIR)))
    max_upload_bytes: int = int(os.getenv("BASKETBALL_MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))
    detector_backend: str = os.getenv("BASKETBALL_DETECTOR", "classical").strip().lower()
    sample_fps: float = max(_env_float("BASKETBALL_SAMPLE_FPS", 10.0), 1.0)
    shot_cooldown_seconds: float = max(_env_float("BASKETBALL_SHOT_COOLDOWN_SECONDS", 1.5), 0.0)
    hoop_roi: tuple[float, float, float, float] | None = _env_roi("BASKETBALL_HOOP_ROI")


settings = Settings()
