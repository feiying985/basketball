"""Small in-process login attempt limiter.

The API runs as one process, so an in-memory sliding window is enough for the
MVP.  Production Nginx limits add a second layer.  Restarting the process
clears these counters; the SMS and session tables remain persistent.
"""

from __future__ import annotations

import time
from collections import defaultdict
from collections.abc import Callable


class LoginRateLimited(Exception):
    def __init__(self, retry_after: int) -> None:
        super().__init__("登录尝试过于频繁")
        self.retry_after = max(retry_after, 1)


class LoginAttemptLimiter:
    def __init__(
        self,
        *,
        max_failures: int,
        window_seconds: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._max_failures = max_failures
        self._window_seconds = window_seconds
        self._clock = clock
        self._failures: dict[str, list[float]] = defaultdict(list)

    def _key(self, account: str, ip: str) -> str:
        return f"{account.casefold()}|{ip}"

    def _recent(self, key: str, now: float) -> list[float]:
        cutoff = now - self._window_seconds
        values = [value for value in self._failures.get(key, []) if value > cutoff]
        if values:
            self._failures[key] = values
        else:
            self._failures.pop(key, None)
        return values

    def check(self, account: str, ip: str) -> None:
        now = self._clock()
        recent = self._recent(self._key(account, ip), now)
        if len(recent) >= self._max_failures:
            retry_after = max(1, int(self._window_seconds - (now - recent[0])) + 1)
            raise LoginRateLimited(retry_after)

    def record_failure(self, account: str, ip: str) -> None:
        now = self._clock()
        key = self._key(account, ip)
        self._recent(key, now)
        self._failures[key].append(now)

    def reset(self, account: str, ip: str) -> None:
        self._failures.pop(self._key(account, ip), None)

    def clear(self) -> None:
        self._failures.clear()


def client_ip(request) -> str:
    """Best-effort client IP; Nginx forwards the original address."""

    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client is not None else "unknown"
