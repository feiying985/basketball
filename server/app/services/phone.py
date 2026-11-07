"""Mainland China mobile number helpers.

Numbers are stored in E.164 form (``+86`` + 11 digits) so the same phone can
never create two accounts through different formatting.
"""

from __future__ import annotations

import re

_MOBILE = re.compile(r"^1[3-9]\d{9}$")


class InvalidPhone(ValueError):
    """Raised when a phone number is not a valid mainland mobile number."""


def normalize_phone(raw: str) -> str:
    value = raw.strip()
    for character in (" ", "-", "(", ")"):
        value = value.replace(character, "")
    if value.startswith("+86"):
        value = value[3:]
    elif value.startswith("0086"):
        value = value[4:]
    elif value.startswith("86") and len(value) == 13:
        value = value[2:]
    if not _MOBILE.fullmatch(value):
        raise InvalidPhone("请输入正确的手机号")
    return f"+86{value}"


def mask_phone(phone: str | None) -> str:
    if not phone:
        return ""
    digits = phone[3:] if phone.startswith("+86") else phone
    if len(digits) < 7:
        return phone
    return f"{digits[:3]}****{digits[-4:]}"


def looks_like_phone(raw: str) -> bool:
    try:
        normalize_phone(raw)
    except InvalidPhone:
        return False
    return True
