import hashlib
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Request, Response, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import case, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload
from sqlalchemy.orm.exc import StaleDataError

from ..audit import audit
from ..config import get_settings
from ..db import get_db
from ..deps import get_current_user
from ..models import (
    Attachment,
    FailureCategory,
    Intervention,
    InterventionEvent,
    Machine,
    PartUsed,
    Priority,
    Role,
    Signature,
    Site,
    Status,
    User,
)
from ..numbering import next_intervention_number
from ..pdf import build_pdf
from ..schemas import (
    ActionIn,
    InterventionCreate,
    InterventionDetail,
    InterventionPatch,
    InterventionSummary,
    Page,
    PartIn,
    SimilarOut,
)
from ..settings_store import get_setting
from ..storage import ALLOWED_IMAGE_TYPES, MAGIC, resolve, save_bytes
from ..timing import compute_durations
from ..workflow import (
    ACTIONS,
    SignatureRole,
    add_event,
    allowed_actions,
    can_view,
    check_version,
    content_sha256,
    detect_recurrence,
    editable_fields,
    ensure_not_locked,
    is_late,
    now_utc,
    perform,
    supersede_signatures,
)

router = APIRouter(prefix="/interventions", tags=["Bons d'intervention"])
files_router = APIRouter(tags=["Fichiers"])

LOAD = (
    selectinload(Intervention.line), selectinload(Intervention.machine), selectinload(Intervention.category),
    selectinload(Intervention.reported_by), selectinload(Intervention.assigned_to),
)
LOAD_FULL = LOAD + (
    selectinload(Intervention.events).selectinload(InterventionEvent.user),
    selectinload(Intervention.parts),
    selectinload(Intervention.signatures).selectinload(Signature.user),
    selectinload(Intervention.attachments),
)


def _person(u: User | None):
    return {"id": u.id, "full_name": u.full_name} if u else None


def summary(db: Session, iv: Intervention) -> InterventionSummary:
    return InterventionSummary(
        id=iv.id, number=iv.number, status=iv.status, priority=iv.priority,
        line_code=iv.line.code, line_name=iv.line.name, machine_code=iv.machine.code, machine_name=iv.machine.name,
        category_name=iv.category.name, description=iv.description, reported_by=_person(iv.reported_by),
        assigned_to=_person(iv.assigned_to), declared_at=iv.declared_at, production_stopped=iv.production_stopped,
        recurring=iv.recurring, paused=iv.paused, late=is_late(db, iv), version=iv.version,
    )


def detail(db: Session, user: User, iv: Intervention) -> InterventionDetail:
    base = summary(db, iv).model_dump()
    return InterventionDetail(
        **base,
        line_id=iv.line_id, machine_id=iv.machine_id, category_id=iv.category_id, client_uuid=iv.client_uuid,
        stop_start_at=iv.stop_start_at, stop_end_at=iv.stop_end_at, accepted_at=iv.accepted_at,
        arrived_at=iv.arrived_at, started_at=iv.started_at, repaired_at=iv.repaired_at,
        maintenance_validated_at=iv.maintenance_validated_at, production_confirmed_at=iv.production_confirmed_at,
        closed_at=iv.closed_at, cancelled_at=iv.cancelled_at, cancel_reason=iv.cancel_reason,
        diagnosis=iv.diagnosis, root_cause=iv.root_cause, corrective_action=iv.corrective_action,
        observations=iv.observations, machine_final_state=iv.machine_final_state,
        production_comment=iv.production_comment, reopen_count=iv.reopen_count, revision=iv.revision,
        locked=iv.locked, content_sha256=content_sha256(iv), durations=compute_durations(iv),
        parts=iv.parts,
        signatures=[{"id": s.id, "role": s.role, "user_name": s.user.full_name, "signed_at": s.signed_at,
                     "revision": s.revision, "content_sha256": s.content_sha256, "superseded": s.superseded,
                     "has_image": s.image_png is not None} for s in iv.signatures],
        attachments=iv.attachments,
        events=[{"id": e.id, "at": e.at, "type": e.type, "from_status": e.from_status, "to_status": e.to_status,
                 "comment": e.comment, "data": e.data, "user_name": e.user.full_name if e.user else None}
                for e in iv.events],
        allowed_actions=allowed_actions(db, user, iv),
        editable_fields=sorted(editable_fields(user, iv)),
    )


def load(db: Session, user: User, iv_id: str, full: bool = True, lock: bool = False) -> Intervention:
    q = select(Intervention).where(Intervention.id == iv_id).options(*(LOAD_FULL if full else LOAD))
    if lock:
        q = q.with_for_update(of=Intervention)
    iv = db.scalar(q)
    if iv is None or not can_view(user, iv):
        raise HTTPException(404, "Bon introuvable")
    return iv


def reload(db: Session, user: User, iv_id: str) -> InterventionDetail:
    db.expire_all()
    return detail(db, user, load(db, user, iv_id))


def commit_or_conflict(db: Session):
    try:
        db.commit()
    except StaleDataError:
        db.rollback()
        raise HTTPException(409, "Le bon a été modifié simultanément par un autre utilisateur. Rechargez-le.")


# ------------------------------------------------------------------ liste / recherche
@router.get("", response_model=Page)
def search(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
    q: str | None = Query(None, description="Numéro de bon, code machine ou texte de la description"),
    status: list[Status] | None = Query(None),
    open_only: bool = False,
    mine: bool = Query(False, description="Chef : mes déclarations ; Technicien : mes interventions"),
    line_id: int | None = None,
    machine_id: int | None = None,
    category_id: int | None = None,
    technician_id: int | None = None,
    priority: Priority | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    cond = [Intervention.site_id == user.site_id]
    if q:
        like = f"%{q.strip()}%"
        cond.append(or_(Intervention.number.ilike(like), Intervention.description.ilike(like),
                        Machine.code.ilike(like), Machine.name.ilike(like)))
    if status:
        cond.append(Intervention.status.in_(status))
    if open_only:
        cond.append(Intervention.status.not_in([Status.CLOTURE, Status.ANNULE]))
    if mine:
        cond.append(Intervention.assigned_to_id == user.id if user.role == Role.TECHNICIEN
                    else Intervention.reported_by_id == user.id)
    for col, val in ((Intervention.line_id, line_id), (Intervention.machine_id, machine_id),
                     (Intervention.category_id, category_id), (Intervention.assigned_to_id, technician_id),
                     (Intervention.priority, priority)):
        if val is not None:
            cond.append(col == val)
    if date_from:
        cond.append(Intervention.declared_at >= date_from)
    if date_to:
        cond.append(Intervention.declared_at < date_to)

    base = select(Intervention).join(Machine, Intervention.machine_id == Machine.id).where(*cond)
    total = db.scalar(select(func.count()).select_from(base.subquery()))
    order = [Intervention.declared_at.desc()]
    if open_only:  # écran d'accueil : les plus prioritaires puis les plus anciens d'abord
        order = [case({Priority.CRITIQUE: 0, Priority.HAUTE: 1, Priority.NORMALE: 2, Priority.BASSE: 3},
                      value=Intervention.priority),
                 Intervention.declared_at.asc()]
    rows = db.scalars(base.options(*LOAD).order_by(*order).limit(limit).offset(offset)).all()
    return Page(total=total, items=[summary(db, iv) for iv in rows])


@router.get("/by-number/{number}", response_model=InterventionDetail)
def by_number(number: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    iv_id = db.scalar(select(Intervention.id).where(Intervention.number == number.upper(),
                                                    Intervention.site_id == user.site_id))
    if iv_id is None:
        raise HTTPException(404, "Bon introuvable")
    return detail(db, user, load(db, user, iv_id))


# ------------------------------------------------------------------ création
@router.post("", response_model=InterventionDetail, status_code=201)
def create(body: InterventionCreate, request: Request, response: Response,
           user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if user.role != Role.CHEF_EQUIPE:
        raise HTTPException(403, "Seul un chef d'équipe production peut déclarer un bon")

    if body.client_uuid:
        existing = db.scalar(select(Intervention).where(Intervention.client_uuid == body.client_uuid))
        if existing is not None:
            if existing.reported_by_id != user.id:
                raise HTTPException(409, "Identifiant client déjà utilisé")
            response.status_code = 200  # ré-envoi (hors ligne / réseau instable) : pas de doublon
            return detail(db, user, load(db, user, existing.id))

    machine = db.get(Machine, body.machine_id)
    if machine is None or not machine.active or machine.line.site_id != user.site_id or not machine.line.active:
        raise HTTPException(422, "Machine invalide")
    cat = db.get(FailureCategory, body.category_id)
    if cat is None or not cat.active or cat.site_id != user.site_id:
        raise HTTPException(422, "Catégorie invalide")

    now = now_utc()
    declared = body.declared_at or now
    if declared.tzinfo is None:
        raise HTTPException(422, "declared_at doit contenir un fuseau horaire")
    if declared > now + timedelta(minutes=5) or declared < now - timedelta(days=7):
        raise HTTPException(422, "Date de déclaration incohérente")
    stop_start = None
    if body.production_stopped:
        stop_start = body.stop_start_at or declared
        if stop_start.tzinfo is None or stop_start > now + timedelta(minutes=5):
            raise HTTPException(422, "Heure d'arrêt de production incohérente")

    site = db.get(Site, user.site_id)
    iv = Intervention(
        number=next_intervention_number(db, site.id, declared.year),
        client_uuid=body.client_uuid, site_id=site.id, line_id=machine.line_id, machine_id=machine.id,
        category_id=cat.id, priority=body.priority or cat.default_priority, status=Status.NOUVEAU,
        description=body.description.strip(), reported_by_id=user.id, production_stopped=body.production_stopped,
        stop_start_at=stop_start, declared_at=declared,
    )
    db.add(iv)
    db.flush()
    ev = add_event(db, iv, user, "CREATION", to=Status.NOUVEAU, at=declared,
                   data={"offline": body.declared_at is not None})
    ev.from_status = None
    previous = detect_recurrence(db, iv)
    if previous:
        iv.recurring = True
        add_event(db, iv, None, "PANNE_RECURRENTE", at=declared,
                  comment=f"{previous} bon(s) similaire(s) sur cette machine pendant la période de référence",
                  data={"source": "auto", "previous": previous})
    audit(db, "INTERVENTION_CREATED", user, "intervention", iv.number, {"machine": machine.code}, request)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "Conflit lors de la création (doublon). Réessayez.")
    return reload(db, user, iv.id)


@router.get("/{iv_id}", response_model=InterventionDetail)
def get_one(iv_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return detail(db, user, load(db, user, iv_id))


# ------------------------------------------------------------------ modification tracée
@router.patch("/{iv_id}", response_model=InterventionDetail)
def patch(iv_id: str, body: InterventionPatch, request: Request, user: User = Depends(get_current_user),
          db: Session = Depends(get_db)):
    iv = load(db, user, iv_id, lock=True)
    ensure_not_locked(iv)
    check_version(iv, body.version)
    allowed = editable_fields(user, iv)
    changes = body.model_dump(exclude_unset=True, exclude={"version"})
    refused = [k for k in changes if k not in allowed]
    if refused:
        raise HTTPException(403, "Champs non modifiables par vous à cet état : " + ", ".join(refused))

    diff = {}
    for k, v in changes.items():
        if k == "category_id":
            cat = db.get(FailureCategory, v)
            if cat is None or cat.site_id != iv.site_id or not cat.active:
                raise HTTPException(422, "Catégorie invalide")
        if k == "stop_start_at" and v is not None and v.tzinfo is None:
            raise HTTPException(422, "stop_start_at doit contenir un fuseau horaire")
        old = getattr(iv, k)
        if isinstance(v, str):
            v = v.strip() or None
        if old != v:
            diff[k] = [_j(old), _j(v)]
            setattr(iv, k, v)
    if iv.production_stopped and iv.stop_start_at is None:
        iv.stop_start_at = now_utc()
        diff["stop_start_at"] = [None, _j(iv.stop_start_at)]
    if not iv.production_stopped:
        iv.stop_start_at = None
    if not diff:
        return detail(db, user, iv)
    if iv.status == Status.REPARE:
        # Le contenu signé a changé : la signature maintenance doit être refaite.
        supersede_signatures(iv, {SignatureRole.MAINTENANCE, SignatureRole.RESPONSABLE})
    add_event(db, iv, user, "MODIFICATION", data=diff)
    audit(db, "INTERVENTION_EDITED", user, "intervention", iv.number, diff, request)
    commit_or_conflict(db)
    return reload(db, user, iv.id)


def _j(v):
    if isinstance(v, datetime):
        return v.isoformat()
    if hasattr(v, "value"):
        return v.value
    return v


# ------------------------------------------------------------------ transitions du workflow
@router.post("/{iv_id}/actions/{name}", response_model=InterventionDetail)
def action(iv_id: str, name: str, body: ActionIn, request: Request, user: User = Depends(get_current_user),
           db: Session = Depends(get_db)):
    if name not in ACTIONS:
        raise HTTPException(404, "Action inconnue")
    iv = load(db, user, iv_id, lock=True)
    before = iv.status
    perform(db, user, iv, name, body)
    db.flush()
    if name == "confirm_production":
        _archive_pdf(db, iv)
    audit(db, f"ACTION_{name.upper()}", user, "intervention", iv.number,
          {"from": before.value, "to": iv.status.value, "comment": body.comment}, request)
    commit_or_conflict(db)
    return reload(db, user, iv.id)


def _archive_pdf(db: Session, iv: Intervention) -> None:
    """À la clôture, le PDF est figé : stocké une fois, empreinte enregistrée, puis servi tel quel."""
    db.flush()
    db.refresh(iv)
    site = db.get(Site, iv.site_id)
    data = build_pdf(iv, get_setting(db, iv.site_id, "company_display_name"), site.timezone)
    rel, sha = save_bytes(f"pdf/{iv.declared_at.year}", data, ".pdf")
    iv.archived_pdf_path = rel
    iv.archived_pdf_sha256 = sha


# ------------------------------------------------------------------ pièces
@router.post("/{iv_id}/parts", response_model=InterventionDetail, status_code=201)
def add_part(iv_id: str, body: PartIn, request: Request, user: User = Depends(get_current_user),
             db: Session = Depends(get_db)):
    iv = load(db, user, iv_id, lock=True)
    ensure_not_locked(iv)
    if "corrective_action" not in editable_fields(user, iv):
        raise HTTPException(403, "Seul le technicien affecté (ou le Responsable) peut ajouter des pièces")
    p = PartUsed(intervention=iv, added_by_id=user.id, **body.model_dump())
    db.add(p)
    if iv.status == Status.REPARE:
        supersede_signatures(iv, {SignatureRole.MAINTENANCE, SignatureRole.RESPONSABLE})
    iv.updated_at = now_utc()
    add_event(db, iv, user, "PIECE_AJOUTEE", data={"reference": p.reference, "quantity": str(p.quantity)})
    audit(db, "PART_ADDED", user, "intervention", iv.number, {"reference": p.reference}, request)
    commit_or_conflict(db)
    return reload(db, user, iv.id)


@router.delete("/{iv_id}/parts/{part_id}", response_model=InterventionDetail)
def delete_part(iv_id: str, part_id: int, request: Request, user: User = Depends(get_current_user),
                db: Session = Depends(get_db)):
    iv = load(db, user, iv_id, lock=True)
    ensure_not_locked(iv)
    if "corrective_action" not in editable_fields(user, iv):
        raise HTTPException(403, "Non autorisé")
    p = next((x for x in iv.parts if x.id == part_id), None)
    if p is None:
        raise HTTPException(404, "Pièce introuvable")
    add_event(db, iv, user, "PIECE_RETIREE", data={"reference": p.reference, "quantity": str(p.quantity)})
    db.delete(p)
    if iv.status == Status.REPARE:
        supersede_signatures(iv, {SignatureRole.MAINTENANCE, SignatureRole.RESPONSABLE})
    iv.updated_at = now_utc()
    audit(db, "PART_REMOVED", user, "intervention", iv.number, {"reference": p.reference}, request)
    commit_or_conflict(db)
    return reload(db, user, iv.id)


# ------------------------------------------------------------------ historique intelligent (pistes)
@router.get("/{iv_id}/similar", response_model=SimilarOut)
def similar(iv_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    iv = load(db, user, iv_id, full=False)
    rows = db.scalars(
        select(Intervention).options(*LOAD).where(
            Intervention.site_id == iv.site_id, Intervention.id != iv.id, Intervention.status == Status.CLOTURE,
            or_(Intervention.machine_id == iv.machine_id, Intervention.category_id == iv.category_id),
        ).order_by((Intervention.machine_id == iv.machine_id).desc(),
                   (Intervention.category_id == iv.category_id).desc(), Intervention.closed_at.desc()).limit(5)
    ).all()
    return SimilarOut(
        disclaimer="Interventions passées comparables, à titre indicatif uniquement : "
                   "elles ne remplacent pas le diagnostic du technicien.",
        items=[{"id": r.id, "number": r.number, "machine": r.machine.code, "category": r.category.name,
                "closed_at": r.closed_at, "same_machine": r.machine_id == iv.machine_id,
                "diagnosis": r.diagnosis, "root_cause": r.root_cause, "corrective_action": r.corrective_action}
               for r in rows],
    )


# ------------------------------------------------------------------ PDF autocopiant
@router.get("/{iv_id}/pdf", response_class=Response,
            responses={200: {"content": {"application/pdf": {}}}})
def pdf(iv_id: str, request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    iv = load(db, user, iv_id)
    if iv.status == Status.CLOTURE and iv.archived_pdf_path:
        data = resolve(iv.archived_pdf_path).read_bytes()
        if hashlib.sha256(data).hexdigest() != iv.archived_pdf_sha256:
            audit(db, "PDF_INTEGRITY_ERROR", user, "intervention", iv.number, None, request)
            db.commit()
            raise HTTPException(500, "Intégrité du PDF archivé non vérifiée : contactez l'administrateur")
        kind = "archive"
    else:
        site = db.get(Site, iv.site_id)
        data = build_pdf(iv, get_setting(db, iv.site_id, "company_display_name"), site.timezone)
        kind = "provisoire"
    audit(db, "PDF_EXPORTED", user, "intervention", iv.number, {"kind": kind}, request)
    db.commit()
    return Response(data, media_type="application/pdf", headers={
        "Content-Disposition": f'inline; filename="{iv.number}_rev{iv.revision}.pdf"',
        "X-Document-Kind": kind,
        "Cache-Control": "no-store",
    })


# ------------------------------------------------------------------ photos
@router.post("/{iv_id}/attachments", response_model=InterventionDetail, status_code=201)
async def upload(iv_id: str, request: Request, kind: str = Form("PROBLEME"), file: UploadFile = File(...),
                 user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    kind = kind.upper()
    if kind not in {"PROBLEME", "AVANT", "APRES"}:
        raise HTTPException(422, "Type de photo invalide")
    iv = load(db, user, iv_id, lock=True)
    ensure_not_locked(iv)
    may = (user.role == Role.CHEF_EQUIPE and kind == "PROBLEME") or \
          (user.role in (Role.TECHNICIEN, Role.RESPONSABLE) and "diagnosis" in editable_fields(user, iv))
    if not may:
        raise HTTPException(403, "Ajout de photo non autorisé")
    ctype = (file.content_type or "").lower()
    if ctype not in ALLOWED_IMAGE_TYPES:
        raise HTTPException(415, "Formats acceptés : JPEG, PNG, WEBP")
    max_bytes = get_settings().max_upload_mb * 1024 * 1024
    data = await file.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise HTTPException(413, f"Photo trop volumineuse (max {get_settings().max_upload_mb} Mo)")
    if not data.startswith(MAGIC[ctype]):
        raise HTTPException(415, "Le contenu du fichier ne correspond pas à son type")
    rel, sha = save_bytes(f"photos/{iv.declared_at.year}/{iv.number}", data, ALLOWED_IMAGE_TYPES[ctype])
    db.add(Attachment(intervention=iv, kind=kind, content_type=ctype, size_bytes=len(data), sha256=sha,
                      storage_path=rel, uploaded_by_id=user.id))
    iv.updated_at = now_utc()
    add_event(db, iv, user, "PHOTO_AJOUTEE", data={"kind": kind, "sha256": sha})
    audit(db, "ATTACHMENT_ADDED", user, "intervention", iv.number, {"kind": kind, "size": len(data)}, request)
    commit_or_conflict(db)
    return reload(db, user, iv.id)


@files_router.get("/attachments/{att_id}")
def download(att_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    a = db.get(Attachment, att_id)
    if a is None or not can_view(user, a.intervention):
        raise HTTPException(404, "Fichier introuvable")
    return FileResponse(resolve(a.storage_path), media_type=a.content_type, headers={"Cache-Control": "private"})


@files_router.get("/signatures/{sig_id}/image")
def signature_image(sig_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    s = db.get(Signature, sig_id)
    if s is None or s.image_png is None or not can_view(user, s.intervention):
        raise HTTPException(404, "Signature introuvable")
    return Response(s.image_png, media_type="image/png", headers={"Cache-Control": "private"})
