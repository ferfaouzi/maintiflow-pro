"""Règles métier du bon d'intervention : machine à états, autorisations, historique.

NOUVEAU -> EN_ATTENTE -> ACCEPTE -> EN_COURS -> (EN_ATTENTE_PIECE) -> REPARE
        -> VALIDATION_PRODUCTION -> CLOTURE
+ ANNULE (motif obligatoire), réouverture CLOTURE -> EN_ATTENTE (Responsable, motif obligatoire).

Validation Maintenance (signature technicien, puis Responsable si configuré) : REPARE -> VALIDATION_PRODUCTION.
Confirmation de reprise Production (signature chef d'équipe)          : VALIDATION_PRODUCTION -> CLOTURE.
Ce sont deux actions distinctes, faites par des rôles différents.
"""
import base64
import binascii
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .models import (
    Intervention,
    InterventionEvent,
    Role,
    Signature,
    SignatureRole,
    Status,
    User,
)
from .settings_store import get_setting

S = Status
OPEN_STATES = {S.NOUVEAU, S.EN_ATTENTE, S.ACCEPTE, S.EN_COURS, S.EN_ATTENTE_PIECE, S.REPARE, S.VALIDATION_PRODUCTION}
TECH_WORK_STATES = {S.ACCEPTE, S.EN_COURS, S.EN_ATTENTE_PIECE, S.REPARE}

CHEF_FIELDS = {"description", "category_id", "production_stopped", "stop_start_at"}
TECH_FIELDS = {"diagnosis", "root_cause", "corrective_action", "observations", "machine_final_state"}
RESP_FIELDS = {"priority", "category_id"} | TECH_FIELDS

MAX_SIGNATURE_BYTES = 200_000


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def conflict(msg: str) -> HTTPException:
    return HTTPException(status.HTTP_409_CONFLICT, msg)


def forbidden(msg: str = "Action non autorisée pour votre rôle") -> HTTPException:
    return HTTPException(status.HTTP_403_FORBIDDEN, msg)


def bad(msg: str) -> HTTPException:
    return HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, msg)


# ---------------------------------------------------------------- visibilité
def can_view(user: User, iv: Intervention) -> bool:
    # Toutes les personnes d'un site voient les bons du site (relève d'équipe) ; jamais un autre site.
    return user.site_id == iv.site_id


def is_assignee(user: User, iv: Intervention) -> bool:
    return user.role == Role.TECHNICIEN and iv.assigned_to_id == user.id


# ---------------------------------------------------------------- empreinte de contenu
def content_payload(iv: Intervention) -> dict:
    def d(x):
        return x.isoformat() if x else None

    return {
        "number": iv.number,
        "revision": iv.revision,
        "line": iv.line.code,
        "machine": iv.machine.code,
        "category": iv.category.code,
        "priority": iv.priority.value,
        "description": iv.description,
        "reported_by": iv.reported_by.username,
        "assigned_to": iv.assigned_to.username if iv.assigned_to else None,
        "declared_at": d(iv.declared_at),
        "production_stopped": iv.production_stopped,
        "stop_start_at": d(iv.stop_start_at),
        "accepted_at": d(iv.accepted_at),
        "arrived_at": d(iv.arrived_at),
        "started_at": d(iv.started_at),
        "repaired_at": d(iv.repaired_at),
        "diagnosis": iv.diagnosis,
        "root_cause": iv.root_cause,
        "corrective_action": iv.corrective_action,
        "observations": iv.observations,
        "machine_final_state": iv.machine_final_state.value if iv.machine_final_state else None,
        "parts": [[p.reference, p.designation, str(p.quantity), p.unit] for p in iv.parts],
    }


def content_sha256(iv: Intervention) -> str:
    raw = json.dumps(content_payload(iv), sort_keys=True, ensure_ascii=False).encode()
    return hashlib.sha256(raw).hexdigest()


# ---------------------------------------------------------------- historique
def add_event(db: Session, iv: Intervention, user: User | None, type_: str, *, to: Status | None = None,
              comment: str | None = None, data: dict | None = None, at: datetime | None = None) -> InterventionEvent:
    ev = InterventionEvent(
        intervention=iv,
        at=at or now_utc(),
        user_id=user.id if user else None,
        type=type_,
        from_status=iv.status if to is not None else None,
        to_status=to,
        comment=comment,
        data=data,
    )
    db.add(ev)
    if to is not None:
        iv.status = to
    return ev


def supersede_signatures(iv: Intervention, roles: set[SignatureRole] | None = None) -> None:
    for sig in iv.signatures:
        if not sig.superseded and (roles is None or sig.role in roles):
            sig.superseded = True


def active_signature(iv: Intervention, role: SignatureRole) -> Signature | None:
    for sig in iv.signatures:
        if sig.role == role and not sig.superseded and sig.revision == iv.revision:
            return sig
    return None


def _decode_signature(b64: str | None) -> bytes | None:
    if not b64:
        return None
    try:
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError):
        raise bad("Signature invalide (base64)")
    if len(raw) > MAX_SIGNATURE_BYTES or not raw.startswith(b"\x89PNG"):
        raise bad("Signature invalide : image PNG de 200 Ko maximum attendue")
    return raw


def sign(db: Session, iv: Intervention, user: User, role: SignatureRole, image_b64: str | None) -> Signature:
    sig = Signature(
        intervention=iv,
        role=role,
        user_id=user.id,
        user=user,
        signed_at=now_utc(),
        revision=iv.revision,
        content_sha256=content_sha256(iv),
        image_png=_decode_signature(image_b64),
    )
    db.add(sig)
    add_event(db, iv, user, "SIGNATURE", data={"role": role.value, "sha256": sig.content_sha256})
    return sig


# ---------------------------------------------------------------- autorisations
@dataclass(frozen=True)
class Action:
    name: str
    states: frozenset
    roles: frozenset
    label: str


ACTIONS: dict[str, Action] = {a.name: a for a in [
    Action("assign", frozenset({S.NOUVEAU, S.EN_ATTENTE, S.ACCEPTE}), frozenset({Role.RESPONSABLE}), "Affecter"),
    Action("accept", frozenset({S.NOUVEAU, S.EN_ATTENTE}), frozenset({Role.TECHNICIEN}), "Prendre en charge"),
    Action("arrive", frozenset({S.ACCEPTE}), frozenset({Role.TECHNICIEN}), "Arrivé sur machine"),
    Action("start", frozenset({S.ACCEPTE}), frozenset({Role.TECHNICIEN}), "Démarrer"),
    Action("pause", frozenset({S.EN_COURS}), frozenset({Role.TECHNICIEN}), "Suspendre"),
    Action("resume", frozenset({S.EN_COURS, S.EN_ATTENTE_PIECE}), frozenset({Role.TECHNICIEN}), "Reprendre"),
    Action("wait_part", frozenset({S.EN_COURS}), frozenset({Role.TECHNICIEN}), "Attente pièce"),
    Action("repair", frozenset({S.EN_COURS}), frozenset({Role.TECHNICIEN}), "Terminer (réparé)"),
    Action("sign_maintenance", frozenset({S.REPARE}), frozenset({Role.TECHNICIEN}), "Signer le rapport maintenance"),
    Action("validate_maintenance", frozenset({S.REPARE}), frozenset({Role.RESPONSABLE}), "Valider (Responsable)"),
    Action("reject_maintenance", frozenset({S.REPARE}), frozenset({Role.RESPONSABLE}), "Renvoyer au technicien"),
    Action("confirm_production", frozenset({S.VALIDATION_PRODUCTION}), frozenset({Role.CHEF_EQUIPE}),
           "Confirmer la reprise de production"),
    Action("reject_production", frozenset({S.VALIDATION_PRODUCTION}), frozenset({Role.CHEF_EQUIPE}),
           "Refuser : problème non résolu"),
    Action("cancel", frozenset(OPEN_STATES), frozenset({Role.CHEF_EQUIPE, Role.RESPONSABLE}), "Annuler"),
    Action("reopen", frozenset({S.CLOTURE}), frozenset({Role.RESPONSABLE}), "Réouvrir"),
    Action("flag_recurring", frozenset(OPEN_STATES),
           frozenset({Role.CHEF_EQUIPE, Role.TECHNICIEN, Role.RESPONSABLE}), "Signaler panne récurrente"),
]}


def action_allowed(db: Session, user: User, iv: Intervention, name: str) -> tuple[bool, str]:
    a = ACTIONS.get(name)
    if a is None:
        return False, "Action inconnue"
    if not can_view(user, iv):
        return False, "Bon introuvable"
    if user.role not in a.roles:
        return False, "Action non autorisée pour votre rôle"
    if iv.status not in a.states:
        return False, f"Action « {a.label} » impossible à l'état {iv.status.value}"

    if name == "accept" and iv.assigned_to_id not in (None, user.id):
        return False, "Ce bon est affecté à un autre technicien"
    if name in {"arrive", "start", "pause", "resume", "wait_part", "repair", "sign_maintenance"} \
            and not is_assignee(user, iv):
        return False, "Seul le technicien affecté peut faire cette action"
    if name == "arrive" and iv.arrived_at is not None:
        return False, "Arrivée déjà enregistrée"
    if name == "pause" and iv.paused:
        return False, "Intervention déjà suspendue"
    if name == "resume" and iv.status == S.EN_COURS and not iv.paused:
        return False, "Intervention non suspendue"
    if name == "sign_maintenance" and active_signature(iv, SignatureRole.MAINTENANCE):
        return False, "Rapport déjà signé par le technicien"
    if name == "validate_maintenance":
        if not get_setting(db, iv.site_id, "require_manager_validation"):
            return False, "La validation Responsable n'est pas requise sur ce site"
        if not active_signature(iv, SignatureRole.MAINTENANCE):
            return False, "Le technicien doit d'abord signer le rapport"
    if name == "cancel" and user.role == Role.CHEF_EQUIPE:
        if iv.reported_by_id != user.id or iv.status not in {S.NOUVEAU, S.EN_ATTENTE}:
            return False, "Le chef d'équipe ne peut annuler que ses bons non encore pris en charge"
    if name == "flag_recurring" and iv.recurring:
        return False, "Déjà signalée comme récurrente"
    return True, ""


def allowed_actions(db: Session, user: User, iv: Intervention) -> list[str]:
    return [n for n in ACTIONS if action_allowed(db, user, iv, n)[0]]


def editable_fields(user: User, iv: Intervention) -> set[str]:
    if iv.locked or not can_view(user, iv):
        return set()
    if user.role == Role.CHEF_EQUIPE and iv.reported_by_id == user.id:
        if iv.status in {S.NOUVEAU, S.EN_ATTENTE}:
            return set(CHEF_FIELDS)
        if iv.status in OPEN_STATES - {S.VALIDATION_PRODUCTION}:
            return {"production_stopped", "stop_start_at"}
    if user.role == Role.TECHNICIEN and is_assignee(user, iv) and iv.status in TECH_WORK_STATES:
        return set(TECH_FIELDS)
    if user.role == Role.RESPONSABLE and iv.status in OPEN_STATES:
        return set(RESP_FIELDS)
    return set()


def check_version(iv: Intervention, version: int | None) -> None:
    if version is not None and version != iv.version:
        raise conflict(f"Le bon a été modifié entre-temps (version serveur {iv.version}). Rechargez-le.")


def ensure_not_locked(iv: Intervention) -> None:
    if iv.locked:
        raise conflict("Bon clôturé ou annulé : modification interdite (réouverture par le Responsable)")


# ---------------------------------------------------------------- exécution
def perform(db: Session, user: User, iv: Intervention, name: str, p) -> None:
    ok, why = action_allowed(db, user, iv, name)
    if not ok:
        code = status.HTTP_404_NOT_FOUND if why == "Bon introuvable" else (
            status.HTTP_403_FORBIDDEN if "rôle" in why or "Seul" in why or "autre technicien" in why
            or "chef d'équipe ne peut" in why else status.HTTP_409_CONFLICT)
        raise HTTPException(code, why)
    check_version(iv, p.version)
    t = now_utc()
    comment = (p.comment or "").strip() or None

    if name == "assign":
        tech = db.get(User, p.technician_id) if p.technician_id else None
        if tech is None or tech.role != Role.TECHNICIEN or not tech.active or tech.site_id != iv.site_id:
            raise bad("Technicien invalide")
        data = {"technician": tech.username}
        if p.priority and p.priority != iv.priority:
            data["priority"] = [iv.priority.value, p.priority.value]
            iv.priority = p.priority
        changed = tech.id != iv.assigned_to_id
        if iv.status == S.ACCEPTE and not changed:
            raise conflict("Ce technicien a déjà pris en charge le bon")
        to = S.EN_ATTENTE if (iv.status in {S.NOUVEAU, S.EN_ATTENTE} or changed) else None
        if iv.status == S.ACCEPTE:
            iv.accepted_at = None
            iv.arrived_at = None
        iv.assigned_to_id = tech.id
        iv.assigned_to = tech
        add_event(db, iv, user, "AFFECTATION", to=to, comment=comment, data=data)

    elif name == "accept":
        iv.assigned_to_id = user.id
        iv.assigned_to = user
        iv.accepted_at = t
        add_event(db, iv, user, "PRISE_EN_CHARGE", to=S.ACCEPTE, comment=comment, at=t)

    elif name == "arrive":
        iv.arrived_at = t
        add_event(db, iv, user, "ARRIVEE", comment=comment, at=t)

    elif name == "start":
        iv.arrived_at = iv.arrived_at or t
        iv.started_at = t
        add_event(db, iv, user, "DEMARRAGE", to=S.EN_COURS, comment=comment, at=t)

    elif name == "pause":
        if not comment:
            raise bad("Motif de suspension obligatoire")
        iv.paused = True
        add_event(db, iv, user, "PAUSE", comment=comment, at=t)

    elif name == "resume":
        if iv.status == S.EN_ATTENTE_PIECE:
            add_event(db, iv, user, "REPRISE", to=S.EN_COURS, comment=comment, at=t)
        else:
            iv.paused = False
            add_event(db, iv, user, "RESUME", comment=comment, at=t)

    elif name == "wait_part":
        if not comment:
            raise bad("Indiquez la pièce attendue")
        iv.paused = False
        add_event(db, iv, user, "ATTENTE_PIECE", to=S.EN_ATTENTE_PIECE, comment=comment, at=t)

    elif name == "repair":
        for f in ("diagnosis", "root_cause", "corrective_action", "machine_final_state"):
            v = getattr(p, f)
            if v is not None:
                setattr(iv, f, v.strip() if isinstance(v, str) else v)
        missing = [lbl for f, lbl in (("diagnosis", "diagnostic"), ("corrective_action", "actions correctives"),
                                       ("machine_final_state", "état final de la machine")) if not getattr(iv, f)]
        if missing:
            raise bad("Champs obligatoires avant de terminer : " + ", ".join(missing))
        iv.paused = False
        iv.repaired_at = t
        add_event(db, iv, user, "REPARATION_TERMINEE", to=S.REPARE, comment=comment, at=t)

    elif name == "sign_maintenance":
        sign(db, iv, user, SignatureRole.MAINTENANCE, p.signature_png_base64)
        if not get_setting(db, iv.site_id, "require_manager_validation"):
            iv.maintenance_validated_at = t
            add_event(db, iv, user, "VALIDATION_MAINTENANCE", to=S.VALIDATION_PRODUCTION, comment=comment, at=t)

    elif name == "validate_maintenance":
        sign(db, iv, user, SignatureRole.RESPONSABLE, p.signature_png_base64)
        iv.maintenance_validated_at = t
        add_event(db, iv, user, "VALIDATION_MAINTENANCE", to=S.VALIDATION_PRODUCTION, comment=comment, at=t)

    elif name == "reject_maintenance":
        if not comment:
            raise bad("Motif obligatoire")
        supersede_signatures(iv, {SignatureRole.MAINTENANCE, SignatureRole.RESPONSABLE})
        iv.repaired_at = None
        add_event(db, iv, user, "RENVOI_TECHNICIEN", to=S.EN_COURS, comment=comment, at=t)

    elif name == "confirm_production":
        stop_end = None
        if iv.production_stopped:
            stop_end = p.stop_end_at or iv.stop_end_at or t
            if stop_end.tzinfo is None:
                raise bad("Heure de reprise : fuseau horaire obligatoire")
            if stop_end > t + timedelta(minutes=5) or (iv.stop_start_at and stop_end < iv.stop_start_at):
                raise bad("Heure de reprise de production incohérente")
            iv.stop_end_at = stop_end
        iv.production_comment = comment
        sign(db, iv, user, SignatureRole.PRODUCTION, p.signature_png_base64)
        iv.production_confirmed_at = t
        iv.closed_at = t
        add_event(db, iv, user, "CONFIRMATION_PRODUCTION", to=S.CLOTURE, comment=comment, at=t,
                  data={"stop_end_at": stop_end.isoformat() if stop_end else None})

    elif name == "reject_production":
        if not comment:
            raise bad("Décrivez le problème constaté")
        supersede_signatures(iv, {SignatureRole.MAINTENANCE, SignatureRole.RESPONSABLE})
        iv.repaired_at = None
        iv.maintenance_validated_at = None
        add_event(db, iv, user, "REFUS_PRODUCTION", to=S.EN_COURS, comment=comment, at=t)

    elif name == "cancel":
        if not comment or len(comment) < 5:
            raise bad("Motif d'annulation obligatoire (5 caractères minimum)")
        iv.cancel_reason = comment
        iv.cancelled_at = t
        iv.paused = False
        add_event(db, iv, user, "ANNULATION", to=S.ANNULE, comment=comment, at=t)

    elif name == "reopen":
        if not comment or len(comment) < 5:
            raise bad("Motif de réouverture obligatoire (5 caractères minimum)")
        old = {"revision": iv.revision, "closed_at": iv.closed_at.isoformat() if iv.closed_at else None,
               "archived_pdf_sha256": iv.archived_pdf_sha256}
        supersede_signatures(iv)
        iv.revision += 1
        iv.reopen_count += 1
        iv.closed_at = None
        iv.production_confirmed_at = None
        iv.maintenance_validated_at = None
        iv.repaired_at = None
        iv.accepted_at = None
        iv.archived_pdf_path = None
        iv.archived_pdf_sha256 = None
        if iv.production_stopped:
            iv.stop_end_at = None
        add_event(db, iv, user, "REOUVERTURE", to=S.EN_ATTENTE, comment=comment, at=t, data=old)

    elif name == "flag_recurring":
        iv.recurring = True
        add_event(db, iv, user, "PANNE_RECURRENTE", comment=comment, at=t, data={"source": "manuel"})

    iv.updated_at = t  # force l'incrément de version (verrou optimiste) à chaque action


def detect_recurrence(db: Session, iv: Intervention) -> int:
    window = int(get_setting(db, iv.site_id, "recurrence_window_days"))
    threshold = int(get_setting(db, iv.site_id, "recurrence_threshold"))
    since = iv.declared_at - timedelta(days=window)
    count = db.scalar(
        select(func.count(Intervention.id)).where(
            Intervention.machine_id == iv.machine_id,
            Intervention.category_id == iv.category_id,
            Intervention.status != S.ANNULE,
            Intervention.declared_at >= since,
            Intervention.id != iv.id,
        )
    ) or 0
    return count if count + 1 >= threshold else 0


def is_late(db: Session, iv: Intervention, now: datetime | None = None) -> bool:
    if iv.status not in {S.NOUVEAU, S.EN_ATTENTE}:
        return False
    limits = get_setting(db, iv.site_id, "late_acceptance_minutes") or {}
    limit = limits.get(iv.priority.value)
    if limit is None:
        return False
    return (now or now_utc()) - iv.declared_at > timedelta(minutes=int(limit))
