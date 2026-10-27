from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..audit import audit
from ..db import get_db
from ..deps import get_current_user, require_admin
from ..models import DEFAULT_SETTINGS, AuditLog, User
from ..schemas import AuditOut, SettingsOut
from ..settings_store import all_settings, set_setting

router = APIRouter(prefix="/admin", tags=["Administration"])


@router.get("/settings", response_model=SettingsOut)
def get_settings_(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """Lecture autorisée à tous (le terminal adapte son comportement), modification réservée à l'admin."""
    return SettingsOut(values=all_settings(db, user.site_id))


@router.put("/settings", response_model=SettingsOut)
def put_settings(body: dict, request: Request, user: User = Depends(require_admin), db: Session = Depends(get_db)):
    unknown = [k for k in body if k not in DEFAULT_SETTINGS]
    if unknown:
        raise HTTPException(422, "Paramètres inconnus : " + ", ".join(unknown))
    for k, v in body.items():
        if type(v) is not type(DEFAULT_SETTINGS[k]):
            raise HTTPException(422, f"Type invalide pour {k}")
        set_setting(db, user.site_id, k, v)
    audit(db, "SETTINGS_UPDATED", user, "settings", None, body, request)
    db.commit()
    return SettingsOut(values=all_settings(db, user.site_id))


@router.get("/audit", response_model=list[AuditOut])
def audit_log(
    user: User = Depends(require_admin),
    db: Session = Depends(get_db),
    action: str | None = None,
    username: str | None = None,
    entity_id: str | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
):
    site_users = select(User.id).where(User.site_id == user.site_id)
    q = select(AuditLog).where((AuditLog.user_id.in_(site_users)) | (AuditLog.user_id.is_(None)))
    if action:
        q = q.where(AuditLog.action == action)
    if username:
        q = q.where(AuditLog.username == username)
    if entity_id:
        q = q.where(AuditLog.entity_id == entity_id)
    if date_from:
        q = q.where(AuditLog.at >= date_from)
    if date_to:
        q = q.where(AuditLog.at < date_to)
    return db.scalars(q.order_by(AuditLog.id.desc()).limit(limit).offset(offset)).all()
