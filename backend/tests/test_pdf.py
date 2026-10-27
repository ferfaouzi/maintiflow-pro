import io

from pypdf import PdfReader

from app.db import SessionLocal
from app.models import Intervention
from app.storage import resolve

from .conftest import new_ticket
from .helpers import SIG
from .test_workflow import run_to_repaired


def _text(pdf: bytes) -> list[str]:
    return [p.extract_text() for p in PdfReader(io.BytesIO(pdf)).pages]


def test_provisional_pdf_has_two_identical_copies(users, refs):
    iv = new_ticket(users["chef"], refs, description="Fuite d'air sur vérin – éjecteur bloqué")
    r = users["chef"].get(f"/interventions/{iv['id']}/pdf")
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    assert r.headers["x-document-kind"] == "provisoire"
    pages = _text(r.content)
    joined = "\n".join(pages)
    assert "EXEMPLAIRE PRODUCTION" in joined and "EXEMPLAIRE MAINTENANCE" in joined
    assert joined.count(iv["number"]) >= 2
    assert "PROVISOIRE" in joined
    assert "éjecteur bloqué" in joined
    assert joined.count(iv["content_sha256"]) >= 2  # même empreinte sur les deux exemplaires


def test_closed_pdf_is_archived_and_immutable(users, refs):
    iv = run_to_repaired(users, refs)
    users["tech"].post(f"/interventions/{iv['id']}/parts",
                       json={"reference": "VER-32", "designation": "Joint de vérin Ø32", "quantity": 2})
    iv = users["tech"].get(f"/interventions/{iv['id']}").json()
    iv = users["tech"].act(iv, "sign_maintenance", signature_png_base64=SIG)
    iv = users["chef"].act(iv, "confirm_production", signature_png_base64=SIG)
    r1 = users["chef"].get(f"/interventions/{iv['id']}/pdf")
    r2 = users["resp"].get(f"/interventions/{iv['id']}/pdf")
    assert r1.headers["x-document-kind"] == "archive"
    assert r1.content == r2.content
    text = "\n".join(_text(r1.content))
    assert "PROVISOIRE" not in text and "Joint de vérin" in text and "Clôturé" in text

    db = SessionLocal()
    path = resolve(db.get(Intervention, iv["id"]).archived_pdf_path)
    db.close()
    path.write_bytes(r1.content + b"tampered")
    assert users["chef"].get(f"/interventions/{iv['id']}/pdf").status_code == 500
