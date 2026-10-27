"""PDF « autocopiant » : un seul document, deux exemplaires identiques
(EXEMPLAIRE PRODUCTION / EXEMPLAIRE MAINTENANCE) partageant le même numéro,
la même révision et la même empreinte SHA-256 du contenu."""
import io
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import escape
from zoneinfo import ZoneInfo

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

from .models import Intervention, SignatureRole, Status
from .storage import resolve
from .timing import compute_durations, fmt_duration
from .workflow import active_signature, content_sha256

FONT_DIR = Path(__file__).parent / "fonts"
pdfmetrics.registerFont(TTFont("DejaVu", str(FONT_DIR / "DejaVuSans.ttf")))
pdfmetrics.registerFont(TTFont("DejaVu-Bold", str(FONT_DIR / "DejaVuSans-Bold.ttf")))

NAVY = colors.HexColor("#0B3D5C")
GREY = colors.HexColor("#5F6B73")
LIGHT = colors.HexColor("#EEF2F5")
COPY_COLORS = {"PRODUCTION": colors.HexColor("#1565C0"), "MAINTENANCE": colors.HexColor("#E65100")}

STATUS_FR = {
    Status.NOUVEAU: "Nouveau", Status.EN_ATTENTE: "En attente", Status.ACCEPTE: "Accepté",
    Status.EN_COURS: "En cours", Status.EN_ATTENTE_PIECE: "En attente de pièce", Status.REPARE: "Réparé",
    Status.VALIDATION_PRODUCTION: "Validation production", Status.CLOTURE: "Clôturé", Status.ANNULE: "Annulé",
}

EVENT_FR = {
    "CREATION": "Déclaration", "AFFECTATION": "Affectation", "PRISE_EN_CHARGE": "Prise en charge",
    "ARRIVEE": "Arrivée sur machine", "DEMARRAGE": "Début d'intervention", "PAUSE": "Suspension",
    "RESUME": "Reprise", "REPRISE": "Reprise", "ATTENTE_PIECE": "Attente de pièce",
    "REPARATION_TERMINEE": "Réparation terminée", "SIGNATURE": "Signature", "MODIFICATION": "Modification",
    "VALIDATION_MAINTENANCE": "Validation maintenance", "RENVOI_TECHNICIEN": "Renvoi au technicien",
    "CONFIRMATION_PRODUCTION": "Reprise production confirmée", "REFUS_PRODUCTION": "Refus production",
    "ANNULATION": "Annulation", "REOUVERTURE": "Réouverture", "PANNE_RECURRENTE": "Panne récurrente",
    "PIECE_AJOUTEE": "Pièce ajoutée", "PIECE_RETIREE": "Pièce retirée", "PHOTO_AJOUTEE": "Photo ajoutée",
}

base = ParagraphStyle("b", fontName="DejaVu", fontSize=8.5, leading=11)
small = ParagraphStyle("s", parent=base, fontSize=7, leading=9, textColor=GREY)
bold = ParagraphStyle("bb", parent=base, fontName="DejaVu-Bold")
h2 = ParagraphStyle("h2", parent=base, fontName="DejaVu-Bold", fontSize=9.5, textColor=colors.white)
title = ParagraphStyle("t", parent=base, fontName="DejaVu-Bold", fontSize=14, leading=17, textColor=NAVY)
center = ParagraphStyle("c", parent=base, alignment=TA_CENTER)


def P(text, style=base):
    return Paragraph(escape(str(text)) .replace("\n", "<br/>") if text not in (None, "") else "—", style)


def build_pdf(iv: Intervention, company: str, tz_name: str = "Africa/Tunis") -> bytes:
    tz = ZoneInfo(tz_name)

    def dt(x: datetime | None) -> str:
        return x.astimezone(tz).strftime("%d/%m/%Y %H:%M") if x else "—"

    sha = content_sha256(iv)
    generated = datetime.now(tz).strftime("%d/%m/%Y %H:%M")
    durations = compute_durations(iv)
    provisional = iv.status != Status.CLOTURE

    story = []
    for copy in ("PRODUCTION", "MAINTENANCE"):
        story += _copy(iv, copy, company, dt, sha, durations)
        story.append(PageBreak())
    story.pop()

    buf = io.BytesIO()

    def on_page(canvas, doc):
        canvas.saveState()
        canvas.setFont("DejaVu", 6.5)
        canvas.setFillColor(GREY)
        canvas.drawString(12 * mm, 8 * mm,
                          f"{iv.number} · rév. {iv.revision} · empreinte SHA-256 {sha} · généré le {generated}")
        canvas.drawRightString(198 * mm, 4.5 * mm, f"Page {doc.page}")
        if provisional:
            canvas.setFont("DejaVu-Bold", 44)
            canvas.setFillColor(colors.Color(0.8, 0.1, 0.1, alpha=0.12))
            canvas.translate(105 * mm, 150 * mm)
            canvas.rotate(35)
            label = "ANNULÉ" if iv.status == Status.ANNULE else "PROVISOIRE – NON CLÔTURÉ"
            canvas.drawCentredString(0, 0, label)
        canvas.restoreState()

    doc = SimpleDocTemplate(buf, pagesize=A4, leftMargin=12 * mm, rightMargin=12 * mm, topMargin=10 * mm,
                            bottomMargin=14 * mm, title=f"Bon d'intervention {iv.number}",
                            author="MaintiFlow Pro", subject=f"rev {iv.revision} sha256 {sha}")
    doc.build(story, onFirstPage=on_page, onLaterPages=on_page)
    return buf.getvalue()


def _section(label: str):
    t = Table([[Paragraph(escape(label), h2)]], colWidths=[186 * mm])
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), NAVY), ("TOPPADDING", (0, 0), (-1, -1), 2),
                           ("BOTTOMPADDING", (0, 0), (-1, -1), 2)]))
    return t


def _grid(rows, widths):
    t = Table(rows, colWidths=widths)
    t.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#B0BEC5")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 2), ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    for r in range(len(rows)):
        for c in range(0, len(rows[r]), 2):
            t.setStyle(TableStyle([("BACKGROUND", (c, r), (c, r), LIGHT)]))
    return t


def _copy(iv: Intervention, copy: str, company: str, dt, sha: str, durations: dict):
    W = 186 * mm
    band = Table([[Paragraph(f"EXEMPLAIRE {copy}", ParagraphStyle("x", parent=h2, alignment=TA_CENTER))]],
                 colWidths=[W])
    band.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), COPY_COLORS[copy])]))

    head = Table([[
        [Paragraph("MaintiFlow Pro", bold), P(company, small)],
        Paragraph("BON D'INTERVENTION MAINTENANCE", title),
        [Paragraph(escape(iv.number), ParagraphStyle("n", parent=title, fontSize=12)),
         P(f"Révision {iv.revision} · {STATUS_FR[iv.status]}", small)],
    ]], colWidths=[45 * mm, 90 * mm, 51 * mm])
    head.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE")]))

    c4 = [30 * mm, 63 * mm, 30 * mm, 63 * mm]
    out = [head, Spacer(1, 2 * mm), band, Spacer(1, 3 * mm)]

    out += [_section("1. Déclaration (Production)"), _grid([
        [P("Déclaré le", bold), P(dt(iv.declared_at)), P("Chef d'équipe", bold), P(iv.reported_by.full_name)],
        [P("Ligne", bold), P(f"{iv.line.code} – {iv.line.name}"), P("Machine", bold),
         P(f"{iv.machine.code} – {iv.machine.name}")],
        [P("Catégorie", bold), P(iv.category.name), P("Priorité", bold), P(iv.priority.value)],
        [P("Arrêt production", bold), P("Oui" if iv.production_stopped else "Non"), P("Début / fin d'arrêt", bold),
         P(f"{dt(iv.stop_start_at)} → {dt(iv.stop_end_at)}" if iv.production_stopped else "—")],
        [P("Description", bold), P(iv.description), P("Panne récurrente", bold), P("Oui" if iv.recurring else "Non")],
    ], c4), Spacer(1, 2 * mm)]

    out += [_section("2. Intervention (Maintenance)"), _grid([
        [P("Technicien", bold), P(iv.assigned_to.full_name if iv.assigned_to else None), P("Prise en charge", bold),
         P(dt(iv.accepted_at))],
        [P("Arrivée", bold), P(dt(iv.arrived_at)), P("Début intervention", bold), P(dt(iv.started_at))],
        [P("Fin (réparé)", bold), P(dt(iv.repaired_at)), P("État final machine", bold),
         P(iv.machine_final_state.value if iv.machine_final_state else None)],
    ], c4), Spacer(1, 1 * mm), _grid([
        [P("Diagnostic", bold), P(iv.diagnosis)],
        [P("Cause identifiée", bold), P(iv.root_cause)],
        [P("Actions correctives", bold), P(iv.corrective_action)],
        [P("Observations", bold), P(iv.observations)],
    ], [30 * mm, 156 * mm]), Spacer(1, 2 * mm)]

    parts = [[P("Référence", bold), P("Désignation", bold), P("Quantité", bold)]]
    parts += [[P(p.reference), P(p.designation), P(f"{p.quantity} {p.unit}")] for p in iv.parts] or []
    if len(parts) == 1:
        parts.append([P("Aucune pièce"), P(""), P("")])
    out += [_section("3. Pièces de rechange utilisées"),
            _grid(parts, [40 * mm, 116 * mm, 30 * mm]), Spacer(1, 2 * mm)]

    d = durations
    out += [_section("4. Temps (calculés à partir des horodatages)"), _grid([
        [P("Délai de prise en charge", bold), P(fmt_duration(d["response_s"])),
         P("Temps d'intervention effectif", bold), P(fmt_duration(d["active_maintenance_s"]))],
        [P("Attente avant intervention", bold), P(fmt_duration(d["waiting_takeover_s"])),
         P("Attente pièces + suspensions", bold), P(fmt_duration(d["waiting_parts_s"] + d["paused_s"]))],
        [P("Arrêt de production", bold), P(fmt_duration(d["production_downtime_s"])),
         P("Durée totale du bon", bold), P(fmt_duration(d["total_s"]))],
    ], [45 * mm, 48 * mm, 45 * mm, 48 * mm]), Spacer(1, 2 * mm)]

    if iv.status == Status.ANNULE:
        out += [_grid([[P("Motif d'annulation", bold), P(iv.cancel_reason)]], [40 * mm, 146 * mm]),
                Spacer(1, 2 * mm)]
    if iv.production_comment:
        out += [_grid([[P("Commentaire production", bold), P(iv.production_comment)]], [40 * mm, 146 * mm]),
                Spacer(1, 2 * mm)]

    out.append(KeepTogether([_section("5. Validations et signatures"), _signatures(iv, dt), Spacer(1, 2 * mm)]))

    photos = _photos(iv)
    if photos:
        out += [_section(f"6. Photos ({len(iv.attachments)})"), photos, Spacer(1, 2 * mm)]

    hist = [[P("Date", bold), P("Événement", bold), P("Par", bold), P("Commentaire", bold)]]
    for e in iv.events:
        label = EVENT_FR.get(e.type, e.type)
        if e.type == "SIGNATURE" and e.data:
            label += f" ({e.data.get('role', '').lower()})"
        elif e.type in ("PIECE_AJOUTEE", "PIECE_RETIREE") and e.data:
            label += f" {e.data.get('reference', '')}"
        hist.append([P(dt(e.at), small), P(label, small), P(e.user.full_name if e.user else "système", small),
                     P(e.comment or "", small)])
    out += [_section("7. Historique horodaté"), _grid(hist, [28 * mm, 52 * mm, 36 * mm, 70 * mm])]
    out.append(Spacer(1, 2 * mm))
    out.append(P("Les deux exemplaires de ce document sont identiques et portent la même empreinte SHA-256. "
                 "Toute modification après clôture nécessite une réouverture tracée et produit une nouvelle révision.",
                 small))
    return out


def _signatures(iv: Intervention, dt):
    cells = []
    for role, label in ((SignatureRole.MAINTENANCE, "Technicien maintenance"),
                        (SignatureRole.RESPONSABLE, "Responsable maintenance"),
                        (SignatureRole.PRODUCTION, "Chef d'équipe – reprise production")):
        s = active_signature(iv, role)
        block = [Paragraph(escape(label), bold)]
        if s:
            block.append(P(f"{s.user.full_name}\n{dt(s.signed_at)}"))
            if s.image_png:
                try:
                    block.append(Image(io.BytesIO(s.image_png), width=40 * mm, height=16 * mm, kind="proportional"))
                except Exception:  # image illisible : la signature électronique reste tracée
                    pass
            block.append(Paragraph(escape(f"empreinte {s.content_sha256[:16]}…"), small))
        else:
            na = role == SignatureRole.RESPONSABLE and iv.maintenance_validated_at and not s
            block.append(P("Non requise" if na else "En attente", small))
        cells.append(block)
    t = Table([cells], colWidths=[62 * mm] * 3, rowHeights=[34 * mm])
    t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#B0BEC5")),
                           ("VALIGN", (0, 0), (-1, -1), "TOP")]))
    return t


def _photos(iv: Intervention):
    imgs = []
    for a in iv.attachments[:4]:
        try:
            imgs.append([Image(str(resolve(a.storage_path)), width=42 * mm, height=32 * mm, kind="proportional"),
                         P(a.kind, small)])
        except Exception:
            continue
    if not imgs:
        return None
    t = Table([imgs], colWidths=[46.5 * mm] * len(imgs))
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP")]))
    return t
