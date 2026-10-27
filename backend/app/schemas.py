from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field

from .models import MachineState, Priority, Role, SignatureRole, Status


class ORM(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------- Auth ----------
class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=60)
    password: str = Field(min_length=1, max_length=200)
    device: str | None = Field(default=None, max_length=120)


class UserOut(ORM):
    id: int
    username: str
    full_name: str
    role: Role
    site_id: int
    active: bool
    must_change_password: bool


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime
    user: UserOut


class ChangePasswordIn(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8, max_length=200)


# ---------- Référentiels ----------
class LineIn(BaseModel):
    code: str = Field(min_length=1, max_length=30)
    name: str = Field(min_length=1, max_length=120)
    active: bool = True


class LineOut(ORM):
    id: int
    code: str
    name: str
    active: bool


class MachineIn(BaseModel):
    line_id: int
    code: str = Field(min_length=1, max_length=40)
    name: str = Field(min_length=1, max_length=120)
    active: bool = True


class MachineOut(ORM):
    id: int
    line_id: int
    code: str
    name: str
    qr_token: str
    active: bool


class CategoryIn(BaseModel):
    code: str = Field(min_length=1, max_length=30)
    name: str = Field(min_length=1, max_length=120)
    default_priority: Priority = Priority.NORMALE
    active: bool = True


class CategoryOut(ORM):
    id: int
    code: str
    name: str
    default_priority: Priority
    active: bool


class UserCreateIn(BaseModel):
    username: str = Field(min_length=3, max_length=60, pattern=r"^[a-zA-Z0-9._-]+$")
    full_name: str = Field(min_length=1, max_length=120)
    role: Role
    password: str = Field(min_length=8, max_length=200)


class UserUpdateIn(BaseModel):
    full_name: str | None = Field(default=None, max_length=120)
    role: Role | None = None
    active: bool | None = None
    reset_password: str | None = Field(default=None, min_length=8, max_length=200)


class Referentials(BaseModel):
    lines: list[LineOut]
    machines: list[MachineOut]
    categories: list[CategoryOut]
    technicians: list[UserOut]


# ---------- Bons d'intervention ----------
class InterventionCreate(BaseModel):
    """Formulaire court du chef d'équipe : 4 champs obligatoires."""
    client_uuid: str | None = Field(default=None, max_length=36)
    machine_id: int
    category_id: int
    description: str = Field(min_length=3, max_length=4000)
    priority: Priority | None = None  # par défaut : priorité de la catégorie
    production_stopped: bool = False
    stop_start_at: datetime | None = None
    declared_at: datetime | None = None  # rempli par le terminal en mode hors ligne


class InterventionPatch(BaseModel):
    version: int  # verrou optimiste : la version lue par le client
    description: str | None = Field(default=None, min_length=3, max_length=4000)
    category_id: int | None = None
    priority: Priority | None = None
    production_stopped: bool | None = None
    stop_start_at: datetime | None = None
    diagnosis: str | None = Field(default=None, max_length=4000)
    root_cause: str | None = Field(default=None, max_length=4000)
    corrective_action: str | None = Field(default=None, max_length=4000)
    observations: str | None = Field(default=None, max_length=4000)
    machine_final_state: MachineState | None = None


class ActionIn(BaseModel):
    version: int | None = None
    comment: str | None = Field(default=None, max_length=2000)
    technician_id: int | None = None          # assign
    priority: Priority | None = None          # assign
    signature_png_base64: str | None = None   # sign_maintenance / validate_maintenance / confirm_production
    stop_end_at: datetime | None = None       # confirm_production
    # champs techniques envoyés en même temps que "repair"
    diagnosis: str | None = Field(default=None, max_length=4000)
    root_cause: str | None = Field(default=None, max_length=4000)
    corrective_action: str | None = Field(default=None, max_length=4000)
    machine_final_state: MachineState | None = None


class PartIn(BaseModel):
    reference: str = Field(min_length=1, max_length=60)
    designation: str = Field(min_length=1, max_length=160)
    quantity: Decimal = Field(gt=0, max_digits=10, decimal_places=2)
    unit: str = Field(default="pce", max_length=10)


class PartOut(ORM):
    id: int
    reference: str
    designation: str
    quantity: Decimal
    unit: str
    added_at: datetime


class EventOut(ORM):
    id: int
    at: datetime
    type: str
    from_status: Status | None
    to_status: Status | None
    comment: str | None
    data: dict | None
    user_name: str | None = None


class SignatureOut(ORM):
    id: int
    role: SignatureRole
    user_name: str
    signed_at: datetime
    revision: int
    content_sha256: str
    superseded: bool
    has_image: bool


class AttachmentOut(ORM):
    id: str
    kind: str
    content_type: str
    size_bytes: int
    uploaded_at: datetime


class PersonRef(BaseModel):
    id: int
    full_name: str


class InterventionSummary(BaseModel):
    id: str
    number: str
    status: Status
    priority: Priority
    line_code: str
    line_name: str
    machine_code: str
    machine_name: str
    category_name: str
    description: str
    reported_by: PersonRef
    assigned_to: PersonRef | None
    declared_at: datetime
    production_stopped: bool
    recurring: bool
    paused: bool
    late: bool
    version: int


class InterventionDetail(InterventionSummary):
    line_id: int
    machine_id: int
    category_id: int
    client_uuid: str | None
    stop_start_at: datetime | None
    stop_end_at: datetime | None
    accepted_at: datetime | None
    arrived_at: datetime | None
    started_at: datetime | None
    repaired_at: datetime | None
    maintenance_validated_at: datetime | None
    production_confirmed_at: datetime | None
    closed_at: datetime | None
    cancelled_at: datetime | None
    cancel_reason: str | None
    diagnosis: str | None
    root_cause: str | None
    corrective_action: str | None
    observations: str | None
    machine_final_state: MachineState | None
    production_comment: str | None
    reopen_count: int
    revision: int
    locked: bool
    content_sha256: str
    durations: dict
    parts: list[PartOut]
    signatures: list[SignatureOut]
    attachments: list[AttachmentOut]
    events: list[EventOut]
    allowed_actions: list[str]
    editable_fields: list[str]


class Page(BaseModel):
    total: int
    items: list[InterventionSummary]


class SimilarOut(BaseModel):
    """Interventions passées comparables. Ce sont des pistes, jamais un diagnostic certain."""
    disclaimer: str
    items: list[dict]


class SettingsOut(BaseModel):
    values: dict


class AuditOut(ORM):
    id: int
    at: datetime
    username: str | None
    action: str
    entity: str | None
    entity_id: str | None
    ip: str | None
    detail: dict | None
