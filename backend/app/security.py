from datetime import datetime, timedelta, timezone

import bcrypt
import jwt

from .config import get_settings

MIN_PASSWORD_LENGTH = 8


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode(), password_hash.encode())
    except ValueError:
        return False


def check_password_policy(password: str) -> None:
    if len(password) < MIN_PASSWORD_LENGTH:
        raise ValueError(f"Le mot de passe doit contenir au moins {MIN_PASSWORD_LENGTH} caractères")
    if password.isdigit() or password.isalpha():
        raise ValueError("Le mot de passe doit mélanger lettres et chiffres")


def create_access_token(user_id: int, session_id: str, role: str) -> tuple[str, datetime]:
    s = get_settings()
    expires = datetime.now(timezone.utc) + timedelta(hours=s.access_token_hours)
    payload = {"sub": str(user_id), "sid": session_id, "role": role, "exp": expires}
    return jwt.encode(payload, s.jwt_secret, algorithm=s.jwt_algorithm), expires


def decode_token(token: str) -> dict:
    s = get_settings()
    return jwt.decode(token, s.jwt_secret, algorithms=[s.jwt_algorithm])
