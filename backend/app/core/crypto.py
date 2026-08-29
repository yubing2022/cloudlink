"""Symmetric encryption for HA long-lived tokens at rest."""
from cryptography.fernet import Fernet, InvalidToken

from app.config import settings

_fernet: Fernet | None = None


def _get_fernet() -> Fernet:
    global _fernet
    if _fernet is None:
        if not settings.FERNET_KEY:
            raise RuntimeError("FERNET_KEY is not configured")
        _fernet = Fernet(settings.FERNET_KEY.encode())
    return _fernet


def encrypt_ha_token(plain: str) -> bytes:
    """Encrypt a HA long-lived token for storage."""
    return _get_fernet().encrypt(plain.encode())


def decrypt_ha_token(cipher: bytes) -> str:
    """Decrypt a stored HA token."""
    try:
        return _get_fernet().decrypt(cipher).decode()
    except InvalidToken as e:
        raise ValueError("Invalid or corrupted HA token") from e
