"""Authentication dependencies for the HTTP routes."""

from __future__ import annotations

from dataclasses import dataclass

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.schemas import User
from app.services.accounts import hash_token, user_store

_bearer = HTTPBearer(auto_error=False)

_UNAUTHORIZED_HEADERS = {"WWW-Authenticate": "Bearer"}


@dataclass(frozen=True)
class AuthContext:
    user: User
    token_hash: str


def optional_auth(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> AuthContext | None:
    """Return the authenticated user, or ``None`` when no token was sent."""

    if credentials is None:
        return None
    user = user_store.user_for_token(credentials.credentials)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="登录状态已失效，请重新登录",
            headers=_UNAUTHORIZED_HEADERS,
        )
    return AuthContext(user=user, token_hash=hash_token(credentials.credentials))


def require_auth(
    auth: AuthContext | None = Depends(optional_auth),
) -> AuthContext:
    if auth is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="需要登录",
            headers=_UNAUTHORIZED_HEADERS,
        )
    return auth
