from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..audit import audit
from ..config import get_settings
from ..db import get_db
from ..deps import get_current_session
from ..models import User, UserSession
from ..schemas import ChangePasswordIn, LoginIn, TokenOut, UserOut
from ..security import check_password_policy, create_access_token, hash_password, verify_password

router = APIRouter(prefix="/auth", tags=["Authentification"])

# Hash factice : même coût de calcul que l'utilisateur existe ou non (anti-énumération).
_DUMMY_HASH = hash_password("dummy-password-0")


@router.post("/login", response_model=TokenOut)
def login(body: LoginIn, request: Request, db: Session = Depends(get_db)):
    s = get_settings()
    now = datetime.now(timezone.utc)
    user = db.scalar(select(User).where(User.username == body.username.strip().lower()))
    generic = HTTPException(status.HTTP_401_UNAUTHORIZED, "Identifiant ou mot de passe incorrect")

    if user is None:
        verify_password(body.password, _DUMMY_HASH)
        audit(db, "LOGIN_FAILED", username=body.username[:60], request=request, detail={"reason": "unknown"})
        db.commit()
        raise generic
    if user.locked_until and user.locked_until > now:
        audit(db, "LOGIN_LOCKED", user=user, request=request)
        db.commit()
        raise HTTPException(status.HTTP_423_LOCKED, "Compte temporairement verrouillé après plusieurs échecs")
    if not verify_password(body.password, user.password_hash) or not user.active:
        user.failed_logins += 1
        if user.failed_logins >= s.login_max_attempts:
            user.locked_until = now + timedelta(minutes=s.login_lock_minutes)
            user.failed_logins = 0
        audit(db, "LOGIN_FAILED", user=user, request=request,
              detail={"reason": "inactive" if not user.active else "password"})
        db.commit()
        raise generic

    user.failed_logins = 0
    user.locked_until = None
    sess = UserSession(user_id=user.id, expires_at=now + timedelta(hours=s.access_token_hours),
                       device=(body.device or request.headers.get("user-agent", ""))[:120])
    db.add(sess)
    db.flush()
    token, expires = create_access_token(user.id, sess.id, user.role.value)
    sess.expires_at = expires
    audit(db, "LOGIN", user=user, request=request, detail={"session": sess.id})
    db.commit()
    return TokenOut(access_token=token, expires_at=expires, user=UserOut.model_validate(user))


@router.post("/logout", status_code=204)
def logout(request: Request, ctx=Depends(get_current_session), db: Session = Depends(get_db)):
    user, sess = ctx
    sess.revoked_at = datetime.now(timezone.utc)
    db.merge(sess)
    audit(db, "LOGOUT", user=user, request=request)
    db.commit()


@router.get("/me", response_model=UserOut)
def me(ctx=Depends(get_current_session)):
    return ctx[0]


@router.post("/change-password", status_code=204)
def change_password(body: ChangePasswordIn, request: Request, ctx=Depends(get_current_session),
                    db: Session = Depends(get_db)):
    user, current = ctx
    if not verify_password(body.current_password, user.password_hash):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Mot de passe actuel incorrect")
    try:
        check_password_policy(body.new_password)
    except ValueError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e))
    user.password_hash = hash_password(body.new_password)
    user.must_change_password = False
    # Révoque les autres sessions de l'utilisateur.
    now = datetime.now(timezone.utc)
    for s in db.scalars(select(UserSession).where(UserSession.user_id == user.id, UserSession.revoked_at.is_(None),
                                                  UserSession.id != current.id)):
        s.revoked_at = now
    audit(db, "PASSWORD_CHANGED", user=user, request=request)
    db.commit()
