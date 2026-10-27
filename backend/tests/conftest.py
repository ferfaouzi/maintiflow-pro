import os
import secrets
import tempfile

os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL", "postgresql+psycopg://maintiflow:dev_only_change_me@localhost:5432/maintiflow_test")
os.environ.setdefault("JWT_SECRET", secrets.token_urlsafe(48))
os.environ["APP_ENV"] = "development"
os.environ["STORAGE_DIR"] = tempfile.mkdtemp(prefix="mf-test-")

import pytest  # noqa: E402
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import text  # noqa: E402

from app.db import SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.models import FailureCategory, Line, Machine, Priority, Role, Site, User  # noqa: E402
from app.security import hash_password  # noqa: E402

PWD = "Test1234pass"
_HASH = hash_password(PWD)
HERE = os.path.dirname(os.path.dirname(__file__))


@pytest.fixture(scope="session", autouse=True)
def schema():
    cfg = Config(os.path.join(HERE, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(HERE, "alembic"))
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
    yield


@pytest.fixture(autouse=True)
def data(schema):
    with engine.begin() as c:
        tables = ", ".join(t for t in ("audit_log", "signatures", "attachments", "parts_used", "intervention_events",
                                       "interventions", "number_sequences", "user_sessions", "users", "machines",
                                       "lines", "failure_categories", "app_settings", "sites"))
        c.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))
    db = SessionLocal()
    site = Site(code="T", name="Site test")
    other = Site(code="O", name="Autre site")
    db.add_all([site, other])
    db.flush()
    line = Line(site_id=site.id, code="L1", name="Ligne 1")
    oline = Line(site_id=other.id, code="X1", name="Ligne autre site")
    db.add_all([line, oline])
    db.flush()
    db.add_all([Machine(line_id=line.id, code="M1", name="Presse 1"), Machine(line_id=line.id, code="M2", name="Convoyeur"),
                Machine(line_id=oline.id, code="XM", name="Machine autre site")])
    db.add_all([FailureCategory(site_id=site.id, code="MECA", name="Mécanique", default_priority=Priority.NORMALE),
                FailureCategory(site_id=site.id, code="SECU", name="Sécurité", default_priority=Priority.CRITIQUE)])
    for u, r, s in [("chef", Role.CHEF_EQUIPE, site), ("chef2", Role.CHEF_EQUIPE, site), ("tech", Role.TECHNICIEN, site),
                    ("tech2", Role.TECHNICIEN, site), ("resp", Role.RESPONSABLE, site), ("admin", Role.ADMIN, site),
                    ("chefo", Role.CHEF_EQUIPE, other)]:
        db.add(User(site_id=s.id, username=u, full_name=u.upper(), role=r, password_hash=_HASH,
                    must_change_password=False))
    db.commit()
    db.close()
    yield


@pytest.fixture
def client():
    return TestClient(app)


class Api:
    def __init__(self, client, username):
        r = client.post("/api/v1/auth/login", json={"username": username, "password": PWD})
        assert r.status_code == 200, r.text
        self.c = client
        self.h = {"Authorization": f"Bearer {r.json()['access_token']}"}
        self.user = r.json()["user"]

    def get(self, url, **kw):
        return self.c.get("/api/v1" + url, headers=self.h, **kw)

    def post(self, url, json=None, **kw):
        return self.c.post("/api/v1" + url, json=json, headers=self.h, **kw)

    def patch(self, url, json=None):
        return self.c.patch("/api/v1" + url, json=json, headers=self.h)

    def put(self, url, json=None):
        return self.c.put("/api/v1" + url, json=json, headers=self.h)

    def delete(self, url):
        return self.c.delete("/api/v1" + url, headers=self.h)

    def act(self, iv, name, expect=200, **body):
        r = self.post(f"/interventions/{iv['id']}/actions/{name}", json=body)
        assert r.status_code == expect, f"{name}: {r.status_code} {r.text}"
        return r.json()


@pytest.fixture
def users(client):
    return {u: Api(client, u) for u in ("chef", "chef2", "tech", "tech2", "resp", "admin", "chefo")}


@pytest.fixture
def refs(users):
    return users["chef"].get("/referentials").json()


def new_ticket(api, refs, **kw):
    body = {"machine_id": refs["machines"][0]["id"], "category_id": refs["categories"][0]["id"],
            "description": "Bourrage récurrent en sortie de presse", **kw}
    r = api.post("/interventions", json=body)
    assert r.status_code in (200, 201), r.text
    return r.json()
