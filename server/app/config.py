"""Server configuration loaded from environment variables."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_UPLOAD_DIR = Path(tempfile.gettempdir()) / "basketball-uploads"
DEFAULT_DATA_DIR = Path(tempfile.gettempdir()) / "basketball-data"


def _env_float(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)))


def _env_int(name: str, default: int, *, minimum: int, maximum: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} 需要 {minimum}-{maximum} 之间的整数") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} 需要 {minimum}-{maximum} 之间的整数")
    return value


def _env_fraction(name: str, default: float) -> float:
    """Parse a 0-1 fraction; 0 is allowed (used to disable a threshold)."""

    raw = os.getenv(name, "").strip()
    try:
        value = float(raw) if raw else default
    except ValueError as exc:
        raise ValueError(f"{name} 需要 0-1 之间的小数") from exc
    if not 0.0 <= value <= 1.0:
        raise ValueError(f"{name} 需要 0-1 之间的小数")
    return value


def _env_ratio(name: str, default: float) -> float:
    value = _env_fraction(name, default)
    if value <= 0.0:
        raise ValueError(f"{name} 需要 0-1 之间的小数")
    return value


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    if raw in {"1", "true", "yes", "on"}:
        return True
    if raw in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} 需要布尔值: true/false")


def _env_database_path() -> Path:
    configured = os.getenv("BASKETBALL_DB_PATH", "").strip()
    if configured:
        return Path(configured)
    return DEFAULT_DATA_DIR / "basketball.db"


def parse_hoop_roi(
    raw: str,
    *,
    name: str = "BASKETBALL_HOOP_ROI",
) -> tuple[float, float, float, float]:
    """Parse a normalized ``x,y,width,height`` hoop region.

    The same parser serves the environment override and the per-request
    ``hoop_roi`` multipart field, so both accept exactly the same format and
    produce the same validation errors.
    """

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


def _env_roi(name: str) -> tuple[float, float, float, float] | None:
    """Parse a normalized ``x,y,width,height`` hoop region from the environment."""

    raw = os.getenv(name, "").strip()
    if not raw:
        return None
    return parse_hoop_roi(raw, name=name)


@dataclass(frozen=True)
class Settings:
    service_name: str = "basketball-server"
    version: str = "0.4.1"
    upload_dir: Path = Path(os.getenv("BASKETBALL_UPLOAD_DIR", str(DEFAULT_UPLOAD_DIR)))
    max_upload_bytes: int = int(os.getenv("BASKETBALL_MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))
    detector_backend: str = os.getenv("BASKETBALL_DETECTOR", "classical").strip().lower()
    sample_fps: float = max(_env_float("BASKETBALL_SAMPLE_FPS", 10.0), 1.0)
    shot_cooldown_seconds: float = max(_env_float("BASKETBALL_SHOT_COOLDOWN_SECONDS", 1.5), 0.0)
    hoop_roi: tuple[float, float, float, float] | None = _env_roi("BASKETBALL_HOOP_ROI")
    # Colour thresholds and radius limits from the real-video verification
    # report.  They keep the classical defaults, but can now be tuned per
    # deployment without editing code (night games need wider colour ranges).
    hue_min: int = _env_int("BASKETBALL_HUE_MIN", 0, minimum=0, maximum=179)
    hue_max: int = _env_int("BASKETBALL_HUE_MAX", 38, minimum=0, maximum=179)
    saturation_min: int = _env_int("BASKETBALL_SATURATION_MIN", 70, minimum=0, maximum=255)
    value_min: int = _env_int("BASKETBALL_VALUE_MIN", 50, minimum=0, maximum=255)
    ball_min_radius_ratio: float = _env_ratio("BASKETBALL_BALL_MIN_RADIUS_RATIO", 0.010)
    ball_max_radius_ratio: float = _env_ratio("BASKETBALL_BALL_MAX_RADIUS_RATIO", 0.120)
    static_persistence_threshold: float = _env_ratio(
        "BASKETBALL_STATIC_PERSISTENCE_THRESHOLD", 0.95
    )
    # Auto hoop detection must not silently pick a decorative orange object.
    # When the winning region only barely beats the runner-up the detection is
    # ambiguous, so analysis asks for a hand-drawn hoop instead of returning
    # events for the wrong target.  0 disables the check.
    hoop_min_confidence: float = _env_fraction("BASKETBALL_HOOP_MIN_CONFIDENCE", 0.25)
    database_path: Path = _env_database_path()
    require_auth: bool = _env_bool("BASKETBALL_REQUIRE_AUTH", False)
    token_ttl_days: float = _env_float("BASKETBALL_TOKEN_TTL_DAYS", 30.0)

    def __post_init__(self) -> None:
        if not 0 <= self.hue_min < self.hue_max <= 179:
            raise ValueError("BASKETBALL_HUE_MIN 必须小于 BASKETBALL_HUE_MAX，且都在 0-179 之间")
        if self.ball_min_radius_ratio >= self.ball_max_radius_ratio:
            raise ValueError("BASKETBALL_BALL_MIN_RADIUS_RATIO 必须小于最大值")
        if not 0.0 <= self.hoop_min_confidence <= 1.0:
            raise ValueError("BASKETBALL_HOOP_MIN_CONFIDENCE 需要 0-1 之间的小数")


settings = Settings()
