"""Tests for configuration parsing shared by env vars and the HTTP API."""

from __future__ import annotations

import pytest

from app.config import parse_hoop_roi


def test_parse_hoop_roi_accepts_normalized_region() -> None:
    assert parse_hoop_roi("0.465,0.305,0.055,0.05") == (0.465, 0.305, 0.055, 0.05)


def test_parse_hoop_roi_accepts_surrounding_whitespace() -> None:
    assert parse_hoop_roi(" 0.1 , 0.2 , 0.3 , 0.4 ") == (0.1, 0.2, 0.3, 0.4)


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "0.1,0.2,0.3",
        "0.1,0.2,0.3,0.4,0.5",
        "a,b,c,d",
        "0.5,0.5,0.6,0.1",
        "0.1,0.1,0,0.2",
        "1,0.1,0.1,0.1",
    ],
)
def test_parse_hoop_roi_rejects_invalid_regions(raw: str) -> None:
    with pytest.raises(ValueError):
        parse_hoop_roi(raw)
