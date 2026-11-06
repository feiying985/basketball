"""Account registration, login and session routes."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.api.dependencies import AuthContext, require_auth
from app.schemas import Credentials, TokenResponse, User
from app.services.accounts import UsernameTaken, user_store

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/register",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
)
def register(payload: Credentials) -> TokenResponse:
    try:
        user = user_store.create(payload.username, payload.password)
    except UsernameTaken as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="用户名已被注册",
        ) from exc
    return _issue_token(user)


@router.post("/login", response_model=TokenResponse)
def login(payload: Credentials) -> TokenResponse:
    user = user_store.authenticate(payload.username, payload.password)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户名或密码错误",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return _issue_token(user)


@router.get("/me", response_model=User)
def me(auth: AuthContext = Depends(require_auth)) -> User:
    return auth.user


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(auth: AuthContext = Depends(require_auth)) -> Response:
    user_store.revoke_session_by_hash(auth.token_hash)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _issue_token(user: User) -> TokenResponse:
    token, expires_at = user_store.create_session(user.id)
    return TokenResponse(access_token=token, expires_at=expires_at, user=user)
