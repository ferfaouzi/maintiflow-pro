"""Contrôle après restauration : nombre de bons, chaîne d'historique, intégrité des PDF archivés."""
import hashlib

from sqlalchemy import func, select

from app.db import SessionLocal
from app.models import AuditLog, Intervention, InterventionEvent, Status
from app.storage import resolve


def main() -> int:
    db = SessionLocal()
    n = db.scalar(select(func.count(Intervention.id)))
    ev = db.scalar(select(func.count(InterventionEvent.id)))
    au = db.scalar(select(func.count(AuditLog.id)))
    bad = 0
    closed = db.scalars(select(Intervention).where(Intervention.status == Status.CLOTURE)).all()
    for iv in closed:
        if not iv.archived_pdf_path:
            continue
        data = resolve(iv.archived_pdf_path).read_bytes()
        if hashlib.sha256(data).hexdigest() != iv.archived_pdf_sha256:
            bad += 1
            print("PDF altéré :", iv.number)
    print(f"bons={n} événements={ev} audit={au} clôturés={len(closed)} pdf_invalides={bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
