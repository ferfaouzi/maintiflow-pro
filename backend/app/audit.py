from fastapi import Request
from sqlalchemy.orm import Session

from .models import AuditLog, User


def audit(
    db: Session,
    action: str,
    user: User | None = None,
    entity: str | None = None,
    entity_id: str | int | None = None,
    detail: dict | None = None,
    request: Request | None = None,
    username: str | None = None,
) -> None:
    """Ajoute une entrée au journal d'audit (commitée avec la transaction appelante)."""
    db.add(
        AuditLog(
            user_id=user.id if user else None,
            username=user.username if user else username,
            action=action,
            entity=entity,
            entity_id=str(entity_id) if entity_id is not None else None,
            ip=request.client.host if request and request.client else None,
            detail=detail,
        )
    )
