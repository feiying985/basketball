"""Account registration, SMS login, password and session routes."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.api.dependencies import AuthContext, require_auth
from app.config import settings
from app.schemas import (
    ChangePasswordRequest,
    CodeLoginRequest,
    LoginRequest,
    RegisterRequest,
    ResetPasswordRequest,
    SmsCodeRequest,
    SmsCodeResponse,
    TokenResponse,
    User,
)
from app.services.accounts import (
    CodeAlreadyUsed,
    InvalidPassword,
    PhoneTaken,
    SamePassword,
    UsernameTaken,
    user_store,
)
from app.services.rate_limit import (
    LoginAttemptLimiter,
    LoginRateLimited,
    client_ip,
)
from app.services.sms import (
    InvalidVerificationCode,
    SmsRateLimited,
    SmsSendError,
    VerifiedCode,
    sms_code_service,
)

router = APIRouter(prefix="/auth", tags=["auth"])

_login_limiter = LoginAttemptLimiter(
    max_failures=settings.login_max_failures,
    window_seconds=settings.login_window_seconds,
)


@router.post("/sms-code", response_model=SmsCodeResponse)
def send_sms_code(payload: SmsCodeRequest, request: Request) -> SmsCodeResponse:
    try:
        expires_in, resend_after = sms_code_service.issue(
            payload.phone,
            payload.purpose,
            client_ip(request),
        )
    except SmsRateLimited as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="验证码发送过于频繁，请稍后再试",
            headers={"Retry-After": str(exc.retry_after)},
        ) from exc
    except SmsSendError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="短信发送失败，请稍后重试",
        ) from exc
    return SmsCodeResponse(expires_in=expires_in, resend_after=resend_after)


@router.post(
    "/register",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
)
def register(payload: RegisterRequest) -> TokenResponse:
    verified = _verify_code(payload.phone, "register", payload.code)
    try:
        user = user_store.create_phone_user(
            payload.phone,
            payload.password,
            payload.username,
            code_id=verified.id,
        )
    except CodeAlreadyUsed as exc:
        raise _invalid_code() from exc
    except PhoneTaken as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="该手机号已注册",
        ) from exc
    except UsernameTaken as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="用户名已被占用",
        ) from exc
    return _issue_token(user)


@router.post("/login", response_model=TokenResponse)
def login(payload: LoginRequest, request: Request) -> TokenResponse:
    ip = client_ip(request)
    try:
        _login_limiter.check(payload.account, ip)
    except LoginRateLimited as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="登录尝试过于频繁，请稍后再试",
            headers={"Retry-After": str(exc.retry_after)},
        ) from exc

    user = user_store.authenticate(payload.account, payload.password)
    if user is None:
        _login_limiter.record_failure(payload.account, ip)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="账号或密码错误",
            headers={"WWW-Authenticate": "Bearer"},
        )
    _login_limiter.reset(payload.account, ip)
    return _issue_token(user)


@router.post("/login/code", response_model=TokenResponse)
def login_with_code(payload: CodeLoginRequest) -> TokenResponse:
    verified = _verify_code(payload.phone, "login", payload.code)
    user = user_store.get_by_phone(payload.phone)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="该手机号尚未注册，请先注册",
        )
    _consume_code(verified)
    return _issue_token(user)


@router.post("/password", status_code=status.HTTP_204_NO_CONTENT)
def change_password(
    payload: ChangePasswordRequest,
    auth: AuthContext = Depends(require_auth),
) -> Response:
    try:
        user_store.change_password(
            auth.user.id,
            payload.current_password,
            payload.new_password,
            keep_token_hash=auth.token_hash,
        )
    except InvalidPassword as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="当前密码错误",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc
    except SamePassword as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="新密码不能与当前密码相同",
        ) from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/password/reset", status_code=status.HTTP_204_NO_CONTENT)
def reset_password(payload: ResetPasswordRequest) -> Response:
    verified = _verify_code(payload.phone, "reset", payload.code)
    user = user_store.get_by_phone(payload.phone)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="该手机号尚未注册，请先注册",
        )
    try:
        user_store.reset_password(
            user.id,
            payload.new_password,
            code_id=verified.id,
        )
    except CodeAlreadyUsed as exc:
        raise _invalid_code() from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", response_model=User)
def me(auth: AuthContext = Depends(require_auth)) -> User:
    return auth.user


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(auth: AuthContext = Depends(require_auth)) -> Response:
    user_store.revoke_session_by_hash(auth.token_hash)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _verify_code(phone: str, purpose: str, code: str) -> VerifiedCode:
    try:
        return sms_code_service.verify(phone, purpose, code)
    except InvalidVerificationCode as exc:
        raise _invalid_code() from exc


def _consume_code(verified: VerifiedCode) -> None:
    try:
        sms_code_service.consume(verified)
    except InvalidVerificationCode as exc:
        raise _invalid_code() from exc


def _invalid_code() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail="验证码错误或已过期",
    )


def _issue_token(user: User) -> TokenResponse:
    token, expires_at = user_store.create_session(user.id)
    return TokenResponse(access_token=token, expires_at=expires_at, user=user)
