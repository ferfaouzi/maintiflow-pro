import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from app.db import SessionLocal, engine

from .conftest import PWD, Api, new_ticket
from .helpers import tiny_png


def test_requires_authentication(client):
    assert client.get("/api/v1/interventions").status_code == 401
    assert client.get("/api/v1/interventions", headers={"Authorization": "Bearer abc"}).status_code == 401


def test_role_permissions(users, refs):
    body = {"machine_id": refs["machines"][0]["id"], "category_id": refs["categories"][0]["id"], "description": "abc"}
    for who in ("tech", "resp", "admin"):
        assert users[who].post("/interventions", json=body).status_code == 403
    iv = new_ticket(users["chef"], refs)
    users["chef"].act(iv, "accept", expect=403)
    users["admin"].act(iv, "accept", expect=403)
    users["resp"].act(iv, "accept", expect=403)
    iv = users["tech"].act(iv, "accept")
    users["tech2"].act(iv, "start", expect=403)
    users["tech"].act(iv, "assign", expect=403, technician_id=users["tech2"].user["id"])
    for who in ("chef", "tech", "resp"):
        assert users[who].get("/admin/users").status_code == 403
        assert users[who].get("/admin/audit").status_code == 403
        assert users[who].put("/admin/settings", {"require_manager_validation": True}).status_code == 403
    assert users["admin"].get("/admin/users").status_code == 200


def test_site_isolation(users, refs):
    iv = new_ticket(users["chef"], refs)
    assert users["chefo"].get(f"/interventions/{iv['id']}").status_code == 404
    assert users["chefo"].get(f"/interventions/{iv['id']}/pdf").status_code == 404
    assert users["chefo"].get("/interventions").json()["total"] == 0
    other_machine = users["chefo"].get("/referentials").json()["machines"][0]["id"]
    r = users["chef"].post("/interventions", json={"machine_id": other_machine,
                                                   "category_id": refs["categories"][0]["id"], "description": "abc"})
    assert r.status_code == 422


def test_logout_revokes_session(client, users):
    api = Api(client, "tech")
    assert api.get("/auth/me").status_code == 200
    assert api.post("/auth/logout").status_code == 204
    assert api.get("/auth/me").status_code == 401


def test_bruteforce_lockout(client, users):
    for _ in range(5):
        r = client.post("/api/v1/auth/login", json={"username": "tech2", "password": "mauvais1"})
        assert r.status_code == 401
    r = client.post("/api/v1/auth/login", json={"username": "tech2", "password": PWD})
    assert r.status_code == 423
    r = client.post("/api/v1/auth/login", json={"username": "inconnu", "password": "x"})
    assert r.status_code == 401
    audit = users["admin"].get("/admin/audit", params={"action": "LOGIN_FAILED"}).json()
    assert len(audit) >= 6


def test_admin_user_lifecycle_and_forced_password_change(client, users):
    admin = users["admin"]
    r = admin.post("/admin/users", json={"username": "Nouveau.Tech", "full_name": "Nouveau", "role": "TECHNICIEN",
                                         "password": "Initial123"})
    assert r.status_code == 201 and r.json()["username"] == "nouveau.tech"
    assert admin.post("/admin/users", json={"username": "nouveau.tech", "full_name": "x", "role": "TECHNICIEN",
                                            "password": "Initial123"}).status_code == 409
    assert admin.post("/admin/users", json={"username": "faible", "full_name": "x", "role": "TECHNICIEN",
                                            "password": "12345678"}).status_code == 422
    r = client.post("/api/v1/auth/login", json={"username": "nouveau.tech", "password": "Initial123"})
    h = {"Authorization": "Bearer " + r.json()["access_token"]}
    assert r.json()["user"]["must_change_password"] is True
    assert client.get("/api/v1/interventions", headers=h).status_code == 403
    assert client.post("/api/v1/auth/change-password", headers=h,
                       json={"current_password": "Initial123", "new_password": "Nouveau456"}).status_code == 204
    assert client.get("/api/v1/interventions", headers=h).status_code == 200
    uid = r.json()["user"]["id"]
    assert admin.patch(f"/admin/users/{uid}", {"active": False}).status_code == 200
    assert client.get("/api/v1/auth/me", headers=h).status_code == 401
    assert admin.patch(f"/admin/users/{admin.user['id']}", {"active": False}).status_code == 409


def test_audit_trail_records_actions(users, refs):
    iv = new_ticket(users["chef"], refs)
    users["tech"].act(iv, "accept")
    users["chef"].get(f"/interventions/{iv['id']}/pdf")
    actions = [a["action"] for a in users["admin"].get("/admin/audit", params={"entity_id": iv["number"]}).json()]
    assert {"INTERVENTION_CREATED", "ACTION_ACCEPT", "PDF_EXPORTED"} <= set(actions)


def test_database_forbids_silent_deletion_and_closed_edits(users, refs):
    from .test_workflow import run_to_repaired
    iv = run_to_repaired(users, refs)
    users["tech"].post(f"/interventions/{iv['id']}/parts", json={"reference": "R1", "designation": "D", "quantity": 1})
    iv = users["tech"].get(f"/interventions/{iv['id']}").json()
    iv = users["tech"].act(iv, "sign_maintenance")
    iv = users["chef"].act(iv, "confirm_production")
    for sql in (f"DELETE FROM interventions WHERE id = '{iv['id']}'",
                f"UPDATE interventions SET description = 'falsifié' WHERE id = '{iv['id']}'",
                f"DELETE FROM intervention_events WHERE intervention_id = '{iv['id']}'",
                "DELETE FROM audit_log",
                "UPDATE audit_log SET action = 'X'",
                f"DELETE FROM parts_used WHERE intervention_id = '{iv['id']}'"):
        with pytest.raises(DBAPIError):
            with engine.begin() as c:
                c.execute(text(sql))
    db = SessionLocal()
    assert db.execute(text(f"SELECT description FROM interventions WHERE id='{iv['id']}'")).scalar() != "falsifié"
    db.close()


def test_photo_upload_rules(users, refs):
    iv = new_ticket(users["chef"], refs)
    url = f"/interventions/{iv['id']}/attachments"
    png = tiny_png()
    r = users["chef"].post(url, files={"file": ("a.png", png, "image/png")}, data={"kind": "PROBLEME"})
    assert r.status_code == 201, r.text
    att = r.json()["attachments"][0]
    assert users["chef"].post(url, files={"file": ("a.exe", b"MZ...", "application/octet-stream")}).status_code == 415
    assert users["chef"].post(url, files={"file": ("a.png", b"pas une image", "image/png")}).status_code == 415
    assert users["chef"].post(url, files={"file": ("a.png", png, "image/png")}, data={"kind": "APRES"}).status_code == 403
    assert users["tech"].post(url, files={"file": ("a.png", png, "image/png")}, data={"kind": "AVANT"}).status_code == 403
    r = users["tech"].get(f"/attachments/{att['id']}")
    assert r.status_code == 200 and r.content == png
    assert users["chefo"].get(f"/attachments/{att['id']}").status_code == 404
