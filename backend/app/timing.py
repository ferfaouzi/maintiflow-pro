"""Chronométrage : toutes les durées sont recalculées à partir des horodatages réels
enregistrés dans l'historique (aucune durée n'est saisie à la main)."""
from datetime import datetime, timezone

from .models import Intervention, Status

WAITING_TAKEOVER = {Status.NOUVEAU, Status.EN_ATTENTE, Status.ACCEPTE}
WAITING_VALIDATION = {Status.REPARE, Status.VALIDATION_PRODUCTION}


def _secs(a: datetime | None, b: datetime | None) -> int | None:
    if a is None or b is None:
        return None
    return max(0, int((b - a).total_seconds()))


def compute_durations(iv: Intervention, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    end = iv.closed_at or iv.cancelled_at or now

    buckets = {
        "waiting_takeover_s": 0,   # déclaration -> début d'intervention (attente + déplacement)
        "active_maintenance_s": 0,  # EN_COURS hors pauses
        "paused_s": 0,             # suspensions pendant EN_COURS
        "waiting_parts_s": 0,      # EN_ATTENTE_PIECE
        "waiting_validation_s": 0,  # RÉPARÉ / VALIDATION PRODUCTION
    }

    status = Status.NOUVEAU
    paused = False
    cursor = iv.declared_at
    for ev in sorted(iv.events, key=lambda e: (e.at, e.id or 0)):
        _accumulate(buckets, status, paused, cursor, ev.at)
        cursor = max(cursor, ev.at)
        if ev.to_status is not None:
            status = ev.to_status
            if status != Status.EN_COURS:
                paused = False
        if ev.type == "PAUSE":
            paused = True
        elif ev.type == "RESUME":
            paused = False
    if status not in (Status.CLOTURE, Status.ANNULE):
        _accumulate(buckets, status, paused, cursor, end)

    stop_end = iv.stop_end_at or (None if iv.locked else now)
    downtime = _secs(iv.stop_start_at, stop_end) if iv.production_stopped else 0

    return {
        **buckets,
        "response_s": _secs(iv.declared_at, iv.accepted_at),       # délai de prise en charge
        "arrival_s": _secs(iv.declared_at, iv.arrived_at),          # délai d'arrivée sur machine
        "repair_window_s": _secs(iv.started_at, iv.repaired_at),    # début -> réparé (brut)
        "production_downtime_s": downtime,                          # arrêt production déclaré
        "total_s": _secs(iv.declared_at, end),
        "running": not iv.locked,
    }


def _accumulate(b: dict, status: Status, paused: bool, start: datetime, stop: datetime) -> None:
    d = _secs(start, stop) or 0
    if d == 0:
        return
    if status in WAITING_TAKEOVER:
        b["waiting_takeover_s"] += d
    elif status == Status.EN_COURS:
        b["paused_s" if paused else "active_maintenance_s"] += d
    elif status == Status.EN_ATTENTE_PIECE:
        b["waiting_parts_s"] += d
    elif status in WAITING_VALIDATION:
        b["waiting_validation_s"] += d


def fmt_duration(seconds: int | None) -> str:
    if seconds is None:
        return "—"
    h, rem = divmod(int(seconds), 3600)
    m, _ = divmod(rem, 60)
    return f"{h} h {m:02d} min" if h else f"{m} min"
