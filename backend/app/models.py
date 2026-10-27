"""Modèle de données MaintiFlow Pro.

Conventions :
- toutes les dates sont stockées en UTC (timestamptz) ;
- un bon d'intervention n'est jamais supprimé physiquement (annulation avec motif) ;
- chaque changement d'un bon produit une ligne dans intervention_events (historique)
  et les actions sensibles une ligne dans audit_log.
"""
import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Enum,
    ForeignKey,
    Integer,
    LargeBinary,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def _uuid() -> str:
    return str(uuid.uuid4())


class Role(str, enum.Enum):
    CHEF_EQUIPE = "CHEF_EQUIPE"
    TECHNICIEN = "TECHNICIEN"
    RESPONSABLE = "RESPONSABLE"
    ADMIN = "ADMIN"


class Status(str, enum.Enum):
    NOUVEAU = "NOUVEAU"
    EN_ATTENTE = "EN_ATTENTE"
    ACCEPTE = "ACCEPTE"
    EN_COURS = "EN_COURS"
    EN_ATTENTE_PIECE = "EN_ATTENTE_PIECE"
    REPARE = "REPARE"
    VALIDATION_PRODUCTION = "VALIDATION_PRODUCTION"
    CLOTURE = "CLOTURE"
    ANNULE = "ANNULE"


class Priority(str, enum.Enum):
    BASSE = "BASSE"
    NORMALE = "NORMALE"
    HAUTE = "HAUTE"
    CRITIQUE = "CRITIQUE"


class MachineState(str, enum.Enum):
    OPERATIONNELLE = "OPERATIONNELLE"
    DEGRADEE = "DEGRADEE"
    ARRETEE = "ARRETEE"


class SignatureRole(str, enum.Enum):
    MAINTENANCE = "MAINTENANCE"      # technicien
    RESPONSABLE = "RESPONSABLE"      # validation maintenance (si configurée)
    PRODUCTION = "PRODUCTION"        # chef d'équipe : confirmation de reprise


class Site(Base):
    __tablename__ = "sites"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(20), unique=True)
    name: Mapped[str] = mapped_column(String(120))
    timezone: Mapped[str] = mapped_column(String(64), default="Africa/Tunis")
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"))
    username: Mapped[str] = mapped_column(String(60), unique=True, index=True)
    full_name: Mapped[str] = mapped_column(String(120))
    role: Mapped[Role] = mapped_column(Enum(Role, name="role"))
    password_hash: Mapped[str] = mapped_column(String(100))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=True)
    failed_logins: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    site: Mapped[Site] = relationship()


class UserSession(Base):
    """Une session = un jeton émis. La déconnexion révoque la session côté serveur."""
    __tablename__ = "user_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    device: Mapped[str | None] = mapped_column(String(120))


class Line(Base):
    __tablename__ = "lines"
    __table_args__ = (UniqueConstraint("site_id", "code"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"))
    code: Mapped[str] = mapped_column(String(30))
    name: Mapped[str] = mapped_column(String(120))
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class Machine(Base):
    __tablename__ = "machines"
    __table_args__ = (UniqueConstraint("line_id", "code"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    line_id: Mapped[int] = mapped_column(ForeignKey("lines.id"), index=True)
    code: Mapped[str] = mapped_column(String(40))
    name: Mapped[str] = mapped_column(String(120))
    # Jeton opaque imprimé dans le QR Code (phase 2) : ne révèle pas l'identifiant interne.
    qr_token: Mapped[str] = mapped_column(String(36), unique=True, default=_uuid)
    active: Mapped[bool] = mapped_column(Boolean, default=True)

    line: Mapped[Line] = relationship()


class FailureCategory(Base):
    __tablename__ = "failure_categories"
    __table_args__ = (UniqueConstraint("site_id", "code"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"))
    code: Mapped[str] = mapped_column(String(30))
    name: Mapped[str] = mapped_column(String(120))
    default_priority: Mapped[Priority] = mapped_column(Enum(Priority, name="priority"), default=Priority.NORMALE)
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class NumberSequence(Base):
    """Compteur annuel par site pour les numéros BI-AAAA-NNNNNN (verrou de ligne)."""
    __tablename__ = "number_sequences"
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"), primary_key=True)
    year: Mapped[int] = mapped_column(Integer, primary_key=True)
    last_value: Mapped[int] = mapped_column(Integer, default=0)


class Intervention(Base):
    __tablename__ = "interventions"
    __table_args__ = (UniqueConstraint("site_id", "number"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    number: Mapped[str] = mapped_column(String(30), index=True)
    # Identifiant généré par le terminal : rend la création idempotente (hors ligne / ré-essais).
    client_uuid: Mapped[str | None] = mapped_column(String(36), unique=True)
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"), index=True)
    line_id: Mapped[int] = mapped_column(ForeignKey("lines.id"), index=True)
    machine_id: Mapped[int] = mapped_column(ForeignKey("machines.id"), index=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("failure_categories.id"))
    priority: Mapped[Priority] = mapped_column(Enum(Priority, name="priority"))
    status: Mapped[Status] = mapped_column(Enum(Status, name="status"), index=True)
    description: Mapped[str] = mapped_column(Text)

    reported_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    assigned_to_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)

    production_stopped: Mapped[bool] = mapped_column(Boolean, default=False)
    stop_start_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    stop_end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    declared_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    arrived_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    repaired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    maintenance_validated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    production_confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    cancel_reason: Mapped[str | None] = mapped_column(Text)

    paused: Mapped[bool] = mapped_column(Boolean, default=False)

    diagnosis: Mapped[str | None] = mapped_column(Text)
    root_cause: Mapped[str | None] = mapped_column(Text)
    corrective_action: Mapped[str | None] = mapped_column(Text)
    observations: Mapped[str | None] = mapped_column(Text)
    machine_final_state: Mapped[MachineState | None] = mapped_column(Enum(MachineState, name="machine_state"))
    production_comment: Mapped[str | None] = mapped_column(Text)

    recurring: Mapped[bool] = mapped_column(Boolean, default=False)
    reopen_count: Mapped[int] = mapped_column(Integer, default=0)
    revision: Mapped[int] = mapped_column(Integer, default=1)  # révision du rapport (incrémentée à la réouverture)
    version: Mapped[int] = mapped_column(Integer, default=1)   # verrou optimiste (synchronisation)

    archived_pdf_sha256: Mapped[str | None] = mapped_column(String(64))
    archived_pdf_path: Mapped[str | None] = mapped_column(String(300))

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __mapper_args__ = {"version_id_col": version}

    line: Mapped[Line] = relationship()
    machine: Mapped[Machine] = relationship()
    category: Mapped[FailureCategory] = relationship()
    reported_by: Mapped[User] = relationship(foreign_keys=[reported_by_id])
    assigned_to: Mapped[User | None] = relationship(foreign_keys=[assigned_to_id])
    events: Mapped[list["InterventionEvent"]] = relationship(
        back_populates="intervention", order_by="InterventionEvent.id"
    )
    parts: Mapped[list["PartUsed"]] = relationship(back_populates="intervention", order_by="PartUsed.id")
    signatures: Mapped[list["Signature"]] = relationship(back_populates="intervention", order_by="Signature.id")
    attachments: Mapped[list["Attachment"]] = relationship(back_populates="intervention", order_by="Attachment.id")

    @property
    def locked(self) -> bool:
        return self.status in (Status.CLOTURE, Status.ANNULE)


class InterventionEvent(Base):
    """Historique horodaté (append-only) : changements d'état, pauses, modifications, signatures."""
    __tablename__ = "intervention_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    intervention_id: Mapped[str] = mapped_column(ForeignKey("interventions.id"), index=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    type: Mapped[str] = mapped_column(String(40))
    from_status: Mapped[Status | None] = mapped_column(Enum(Status, name="status"))
    to_status: Mapped[Status | None] = mapped_column(Enum(Status, name="status"))
    comment: Mapped[str | None] = mapped_column(Text)
    data: Mapped[dict | None] = mapped_column(JSON)

    intervention: Mapped[Intervention] = relationship(back_populates="events")
    user: Mapped[User | None] = relationship()


class PartUsed(Base):
    __tablename__ = "parts_used"
    id: Mapped[int] = mapped_column(primary_key=True)
    intervention_id: Mapped[str] = mapped_column(ForeignKey("interventions.id"), index=True)
    reference: Mapped[str] = mapped_column(String(60))
    designation: Mapped[str] = mapped_column(String(160))
    quantity: Mapped[float] = mapped_column(Numeric(10, 2))
    unit: Mapped[str] = mapped_column(String(10), default="pce")
    added_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    added_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    intervention: Mapped[Intervention] = relationship(back_populates="parts")


class Signature(Base):
    __tablename__ = "signatures"
    id: Mapped[int] = mapped_column(primary_key=True)
    intervention_id: Mapped[str] = mapped_column(ForeignKey("interventions.id"), index=True)
    role: Mapped[SignatureRole] = mapped_column(Enum(SignatureRole, name="signature_role"))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    signed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revision: Mapped[int] = mapped_column(Integer)
    # Empreinte SHA-256 du contenu du bon au moment de la signature.
    content_sha256: Mapped[str] = mapped_column(String(64))
    image_png: Mapped[bytes | None] = mapped_column(LargeBinary)
    superseded: Mapped[bool] = mapped_column(Boolean, default=False)  # vrai après réouverture

    intervention: Mapped[Intervention] = relationship(back_populates="signatures")
    user: Mapped[User] = relationship()


class Attachment(Base):
    __tablename__ = "attachments"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    intervention_id: Mapped[str] = mapped_column(ForeignKey("interventions.id"), index=True)
    kind: Mapped[str] = mapped_column(String(20))  # PROBLEME | AVANT | APRES
    content_type: Mapped[str] = mapped_column(String(40))
    size_bytes: Mapped[int] = mapped_column(Integer)
    sha256: Mapped[str] = mapped_column(String(64))
    storage_path: Mapped[str] = mapped_column(String(300))
    uploaded_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    uploaded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    intervention: Mapped[Intervention] = relationship(back_populates="attachments")


class AuditLog(Base):
    __tablename__ = "audit_log"
    id: Mapped[int] = mapped_column(primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), index=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    username: Mapped[str | None] = mapped_column(String(60))
    action: Mapped[str] = mapped_column(String(60), index=True)
    entity: Mapped[str | None] = mapped_column(String(40))
    entity_id: Mapped[str | None] = mapped_column(String(40))
    ip: Mapped[str | None] = mapped_column(String(45))
    detail: Mapped[dict | None] = mapped_column(JSON)


class AppSetting(Base):
    __tablename__ = "app_settings"
    site_id: Mapped[int] = mapped_column(ForeignKey("sites.id"), primary_key=True)
    key: Mapped[str] = mapped_column(String(60), primary_key=True)
    value: Mapped[dict] = mapped_column(JSON)


DEFAULT_SETTINGS: dict[str, object] = {
    # Si vrai, le Responsable Maintenance doit valider après la signature du technicien.
    "require_manager_validation": False,
    # Fenêtre (jours) pour détecter une panne récurrente sur la même machine + catégorie.
    "recurrence_window_days": 30,
    "recurrence_threshold": 2,
    # Délai (minutes) au-delà duquel un bon non pris en charge est signalé en retard.
    "late_acceptance_minutes": {"CRITIQUE": 5, "HAUTE": 15, "NORMALE": 60, "BASSE": 240},
    "company_display_name": "Usine de démonstration",
}
