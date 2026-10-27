from datetime import datetime, timezone

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .db import get_db
from .models import Role, User, UserSession
from .security import decode_token

bearer = HTTPBearer(auto_error=False)


def get_current_session(
    creds: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(get_db)
) -> tuple[User, UserSession]:
    unauthorized = HTTPException(status.HTTP_401_UNAUTHORIZED, "Session invalide ou expirée",
                                 headers={"WWW-Authenticate": "Bearer"})
    if creds is None:
        raise unauthorized
    try:
        payload = decode_token(creds.credentials)
    except jwt.PyJWTError:
        raise unauthorized
    sess = db.get(UserSession, payload.get("sid"))
    now = datetime.now(timezone.utc)
    if sess is None or sess.revoked_at is not None or sess.expires_at <= now:
        raise unauthorized
    user = db.get(User, int(payload["sub"]))
    if user is None or not user.active or user.id != sess.user_id:
        raise unauthorized
    return user, sess


def get_current_user(ctx: tuple[User, UserSession] = Depends(get_current_session)) -> User:
    user = ctx[0]
    if user.must_change_password:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED")
    return user


def require_roles(*roles: Role):
    def checker(user: User = Depends(get_current_user)) -> User:
        if user.role not in roles:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Action non autorisée pour votre rôle")
        return user

    return checker


require_admin = require_roles(Role.ADMIN)
