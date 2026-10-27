"""Workflow complet : du signalement par le chef d'équipe à la clôture."""
import threading
import uuid
from datetime import datetime, timedelta, timezone

from app.db import SessionLocal
from app.models import Intervention

from .conftest import Api, new_ticket
from .helpers import SIG


def run_to_repaired(u, refs, **kw):
    iv = new_ticket(u["chef"], refs, production_stopped=True, **kw)
    iv = u["tech"].act(iv, "accept")
    iv = u["tech"].act(iv, "start")
    return u["tech"].act(iv, "repair", diagnosis="Capteur inductif HS", root_cause="Câble écrasé",
                         corrective_action="Remplacement capteur + recâblage", machine_final_state="OPERATIONNELLE")


def test_full_workflow_signalement_a_cloture(users, refs):
    chef, tech, resp = users["chef"], users["tech"], users["resp"]
    year = datetime.now(timezone.utc).year

    iv = new_ticket(chef, refs, production_stopped=True)
    assert iv["number"] == f"BI-{year}-000001"
    assert iv["status"] == "NOUVEAU"
    assert iv["priority"] == "NORMALE"  # priorité par défaut de la catégorie
    assert iv["stop_start_at"] is not None
    assert "cancel" in iv["allowed_actions"]

    tech_id = next(t["id"] for t in refs["technicians"] if t["full_name"] == "TECH")
    iv = resp.act(iv, "assign", technician_id=tech_id, priority="HAUTE")
    assert iv["status"] == "EN_ATTENTE" and iv["priority"] == "HAUTE" and iv["assigned_to"]["id"] == tech_id

    assert users["tech2"].act(iv, "accept", expect=403)
    iv = tech.act(iv, "accept")
    assert iv["status"] == "ACCEPTE" and iv["accepted_at"]
    iv = tech.act(iv, "arrive")
    iv = tech.act(iv, "start")
    assert iv["status"] == "EN_COURS" and iv["started_at"]

    tech.act(iv, "pause", expect=422)  # motif obligatoire
    iv = tech.act(iv, "pause", comment="Pause sécurité – consignation")
    assert iv["paused"] is True
    iv = tech.act(iv, "resume")
    iv = tech.act(iv, "wait_part", comment="Capteur IFM IG5953 en commande")
    assert iv["status"] == "EN_ATTENTE_PIECE"
    iv = tech.act(iv, "resume")
    assert iv["status"] == "EN_COURS"

    r = tech.post(f"/interventions/{iv['id']}/parts",
                  json={"reference": "IG5953", "designation": "Capteur inductif M18", "quantity": 1})
    assert r.status_code == 201, r.text
    iv = r.json()
    assert len(iv["parts"]) == 1

    r = tech.post(f"/interventions/{iv['id']}/actions/repair", json={"diagnosis": "x"})
    assert r.status_code == 422 and "actions correctives" in r.text
    iv = tech.act(iv, "repair", diagnosis="Capteur inductif HS", root_cause="Câble écrasé par le carter",
                  corrective_action="Remplacement du capteur, protection du câble", machine_final_state="OPERATIONNELLE")
    assert iv["status"] == "REPARE"

    # La validation Maintenance n'est pas la confirmation Production
    chef.act(iv, "confirm_production", expect=409)
    iv = tech.act(iv, "sign_maintenance", signature_png_base64=SIG)
    assert iv["status"] == "VALIDATION_PRODUCTION"
    assert iv["maintenance_validated_at"] and not iv["production_confirmed_at"]
    tech.act(iv, "confirm_production", expect=403)

    iv = chef.act(iv, "confirm_production", signature_png_base64=SIG, comment="Production relancée OK")
    assert iv["status"] == "CLOTURE" and iv["locked"] is True
    assert iv["stop_end_at"] and iv["closed_at"]
    roles = sorted(s["role"] for s in iv["signatures"] if not s["superseded"])
    assert roles == ["MAINTENANCE", "PRODUCTION"]
    assert iv["allowed_actions"] == []

    d = iv["durations"]
    assert d["running"] is False
    assert d["production_downtime_s"] >= 0 and d["response_s"] is not None
    types = [e["type"] for e in iv["events"]]
    for t in ("CREATION", "AFFECTATION", "PRISE_EN_CHARGE", "ARRIVEE", "DEMARRAGE", "PAUSE", "RESUME",
              "ATTENTE_PIECE", "REPRISE", "PIECE_AJOUTEE", "REPARATION_TERMINEE", "SIGNATURE",
              "VALIDATION_MAINTENANCE", "CONFIRMATION_PRODUCTION"):
        assert t in types, t

    # Bon clôturé : protégé
    assert chef.patch(f"/interventions/{iv['id']}", {"version": iv["version"], "description": "modif"}).status_code == 409
    assert tech.post(f"/interventions/{iv['id']}/parts",
                     json={"reference": "A", "designation": "B", "quantity": 1}).status_code == 409
    assert resp.act(iv, "cancel", expect=409, comment="tentative d'annulation")


def test_manager_validation_when_configured(users, refs):
    assert users["admin"].put("/admin/settings", {"require_manager_validation": True}).status_code == 200
    iv = run_to_repaired(users, refs)
    users["resp"].act(iv, "validate_maintenance", expect=409)  # technicien doit signer d'abord
    iv = users["tech"].act(iv, "sign_maintenance", signature_png_base64=SIG)
    assert iv["status"] == "REPARE"
    users["chef"].act(iv, "confirm_production", expect=409)
    iv = users["resp"].act(iv, "validate_maintenance", signature_png_base64=SIG)
    assert iv["status"] == "VALIDATION_PRODUCTION"
    iv = users["chef"].act(iv, "confirm_production")
    assert iv["status"] == "CLOTURE"
    assert sorted(s["role"] for s in iv["signatures"]) == ["MAINTENANCE", "PRODUCTION", "RESPONSABLE"]


def test_production_refuses_restart(users, refs):
    iv = run_to_repaired(users, refs)
    iv = users["tech"].act(iv, "sign_maintenance")
    users["chef"].act(iv, "reject_production", expect=422)
    iv = users["chef"].act(iv, "reject_production", comment="Le défaut réapparaît après 10 pièces")
    assert iv["status"] == "EN_COURS"
    assert all(s["superseded"] for s in iv["signatures"])
    iv = users["tech"].act(iv, "repair")
    iv = users["tech"].act(iv, "sign_maintenance")
    assert iv["status"] == "VALIDATION_PRODUCTION"


def test_edit_after_signature_requires_new_signature(users, refs):
    iv = run_to_repaired(users, refs)
    users["admin"].put("/admin/settings", {"require_manager_validation": True})
    iv = users["tech"].act(iv, "sign_maintenance")
    r = users["tech"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "observations": "Prévoir graissage"})
    assert r.status_code == 200, r.text
    iv = r.json()
    assert iv["status"] == "REPARE" and all(s["superseded"] for s in iv["signatures"])
    assert "sign_maintenance" in iv["allowed_actions"]
    assert iv["events"][-1]["type"] == "MODIFICATION"
    assert iv["events"][-1]["data"]["observations"] == [None, "Prévoir graissage"]


def test_cancel_rules(users, refs):
    chef, resp = users["chef"], users["resp"]
    iv = new_ticket(chef, refs)
    chef.act(iv, "cancel", expect=422)                       # motif obligatoire
    users["chef2"].act(iv, "cancel", expect=403, comment="pas mon bon")
    iv2 = chef.act(iv, "cancel", comment="Déclaré par erreur sur la mauvaise machine")
    assert iv2["status"] == "ANNULE" and iv2["cancel_reason"]

    iv = new_ticket(chef, refs)
    iv = users["tech"].act(iv, "accept")
    chef.act(iv, "cancel", expect=403, comment="trop tard pour le chef")
    iv = resp.act(iv, "cancel", comment="Doublon du bon précédent")
    assert iv["status"] == "ANNULE"
    resp.act(iv, "reopen", expect=409, comment="un bon annulé ne se réouvre pas")


def test_reopen_creates_new_revision(users, refs):
    iv = run_to_repaired(users, refs)
    iv = users["tech"].act(iv, "sign_maintenance")
    iv = users["chef"].act(iv, "confirm_production")
    users["tech"].act(iv, "reopen", expect=403, comment="non autorisé")
    users["resp"].act(iv, "reopen", expect=422, comment="")
    iv = users["resp"].act(iv, "reopen", comment="Même panne le lendemain, analyse insuffisante")
    assert iv["status"] == "EN_ATTENTE" and iv["revision"] == 2 and iv["reopen_count"] == 1
    assert all(s["superseded"] for s in iv["signatures"])
    assert iv["events"][-1]["type"] == "REOUVERTURE" and iv["events"][-1]["data"]["revision"] == 1
    iv = users["tech"].act(iv, "accept")
    iv = users["tech"].act(iv, "start")
    iv = users["tech"].act(iv, "repair", corrective_action="Remplacement du câble complet")
    iv = users["tech"].act(iv, "sign_maintenance")
    iv = users["chef"].act(iv, "confirm_production")
    assert iv["status"] == "CLOTURE" and iv["revision"] == 2


def test_numbering_is_unique_under_concurrency(client, users, refs):
    year = datetime.now(timezone.utc).year
    numbers, errors = [], []

    def worker():
        try:
            api = Api(client, "chef")
            for _ in range(5):
                numbers.append(new_ticket(api, refs)["number"])
        except Exception as e:  # pragma: no cover
            errors.append(e)

    threads = [threading.Thread(target=worker) for _ in range(4)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not errors
    assert sorted(numbers) == [f"BI-{year}-{i:06d}" for i in range(1, 21)]


def test_client_uuid_makes_creation_idempotent(users, refs):
    cu = str(uuid.uuid4())
    a = new_ticket(users["chef"], refs, client_uuid=cu)
    b = new_ticket(users["chef"], refs, client_uuid=cu)
    assert a["id"] == b["id"] and a["number"] == b["number"]
    assert users["chef"].get("/interventions").json()["total"] == 1
    r = users["chef2"].post("/interventions", json={"machine_id": refs["machines"][0]["id"],
                                                     "category_id": refs["categories"][0]["id"],
                                                     "description": "abc", "client_uuid": cu})
    assert r.status_code == 409


def test_offline_declared_at_is_kept_and_bounded(users, refs):
    t = (datetime.now(timezone.utc) - timedelta(hours=2)).replace(microsecond=0)
    iv = new_ticket(users["chef"], refs, declared_at=t.isoformat())
    assert datetime.fromisoformat(iv["declared_at"]) == t
    r = users["chef"].post("/interventions", json={"machine_id": refs["machines"][0]["id"],
                                                    "category_id": refs["categories"][0]["id"], "description": "abc",
                                                    "declared_at": (t + timedelta(days=1)).isoformat()})
    assert r.status_code == 422


def test_recurrence_detection_and_similar(users, refs):
    first = run_to_repaired(users, refs)
    first = users["tech"].act(first, "sign_maintenance")
    users["chef"].act(first, "confirm_production")
    iv = new_ticket(users["chef"], refs)
    assert iv["recurring"] is True
    assert any(e["type"] == "PANNE_RECURRENTE" for e in iv["events"])
    sim = users["tech"].get(f"/interventions/{iv['id']}/similar").json()
    assert "indicatif" in sim["disclaimer"]
    assert sim["items"][0]["number"] == first["number"] and sim["items"][0]["same_machine"]
    other = new_ticket(users["chef"], refs, machine_id=refs["machines"][1]["id"])
    assert other["recurring"] is False
    other = users["tech"].act(other, "flag_recurring", comment="Déjà vu la semaine dernière")
    assert other["recurring"] is True


def test_optimistic_locking(users, refs):
    iv = new_ticket(users["chef"], refs)
    r1 = users["chef"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "description": "Bourrage + bruit"})
    assert r1.status_code == 200
    r2 = users["chef"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "description": "autre"})
    assert r2.status_code == 409
    r = users["tech"].post(f"/interventions/{iv['id']}/actions/accept", json={"version": iv["version"]})
    assert r.status_code == 409


def test_field_permissions(users, refs):
    iv = new_ticket(users["chef"], refs)
    r = users["chef"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "diagnosis": "x"})
    assert r.status_code == 403
    iv = users["tech"].act(iv, "accept")
    r = users["chef"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "description": "changement"})
    assert r.status_code == 403  # description figée après prise en charge
    r = users["tech"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "description": "texte technicien"})
    assert r.status_code == 403
    r = users["tech"].patch(f"/interventions/{iv['id']}", {"version": iv["version"], "diagnosis": "Vérin bloqué"})
    assert r.status_code == 200


def test_search_filters(users, refs):
    a = new_ticket(users["chef"], refs)
    b = new_ticket(users["chef2"], refs, machine_id=refs["machines"][1]["id"], category_id=refs["categories"][1]["id"])
    users["tech"].act(b, "accept")
    chef = users["chef"]
    assert chef.get("/interventions", params={"q": a["number"]}).json()["items"][0]["id"] == a["id"]
    assert chef.get("/interventions", params={"q": "M2"}).json()["total"] == 1
    assert chef.get("/interventions", params={"mine": True}).json()["total"] == 1
    assert chef.get("/interventions", params={"status": ["ACCEPTE"]}).json()["items"][0]["id"] == b["id"]
    tech_id = users["tech"].user["id"]
    assert users["resp"].get("/interventions", params={"technician_id": tech_id}).json()["total"] == 1
    home = users["tech"].get("/interventions", params={"open_only": True}).json()["items"]
    assert home[0]["priority"] == "CRITIQUE"  # catégorie Sécurité en tête
    assert chef.get(f"/interventions/by-number/{a['number']}").status_code == 200


def test_durations_from_timestamps(users, refs):
    iv = run_to_repaired(users, refs)
    db = SessionLocal()
    obj = db.get(Intervention, iv["id"])
    from app.timing import compute_durations
    base = obj.declared_at
    # Rejoue une chronologie connue en décalant les horodatages
    offsets = {"CREATION": 0, "PRISE_EN_CHARGE": 300, "DEMARRAGE": 900, "REPARATION_TERMINEE": 2700}
    for e in obj.events:
        e.at = base + timedelta(seconds=offsets[e.type])
    obj.accepted_at = base + timedelta(seconds=300)
    obj.started_at = base + timedelta(seconds=900)
    obj.repaired_at = base + timedelta(seconds=2700)
    d = compute_durations(obj, now=base + timedelta(seconds=3000))
    db.rollback()
    db.close()
    assert d["response_s"] == 300
    assert d["waiting_takeover_s"] == 900
    assert d["active_maintenance_s"] == 1800
    assert d["waiting_validation_s"] == 300
    assert d["production_downtime_s"] == 3000
