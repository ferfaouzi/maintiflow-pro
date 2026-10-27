from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..audit import audit
from ..db import get_db
from ..deps import get_current_user, require_admin
from ..models import FailureCategory, Line, Machine, Role, User
from ..schemas import (
    CategoryIn,
    CategoryOut,
    LineIn,
    LineOut,
    MachineIn,
    MachineOut,
    Referentials,
    UserCreateIn,
    UserOut,
    UserUpdateIn,
)
from ..security import check_password_policy, hash_password

router = APIRouter(tags=["Référentiels"])


@router.get("/referentials", response_model=Referentials)
def referentials(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """Tout ce dont le terminal a besoin pour créer un bon (mis en cache pour le mode hors ligne)."""
    lines = db.scalars(select(Line).where(Line.site_id == user.site_id, Line.active).order_by(Line.code)).all()
    machines = db.scalars(select(Machine).join(Line).where(Line.site_id == user.site_id, Machine.active,
                                                          Line.active).order_by(Machine.code)).all()
    cats = db.scalars(select(FailureCategory).where(FailureCategory.site_id == user.site_id,
                                                    FailureCategory.active).order_by(FailureCategory.name)).all()
    techs = db.scalars(select(User).where(User.site_id == user.site_id, User.role == Role.TECHNICIEN,
                                          User.active).order_by(User.full_name)).all()
    return Referentials(lines=lines, machines=machines, categories=cats, technicians=techs)


@router.get("/machines/by-qr/{token}", response_model=MachineOut)
def machine_by_qr(token: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    m = db.scalar(select(Machine).join(Line).where(Machine.qr_token == token, Line.site_id == user.site_id))
    if m is None:
        raise HTTPException(404, "Machine inconnue")
    return m


def _flush(db: Session, what: str):
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, f"{what} : code déjà utilisé")


def _commit(db: Session, what: str):
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, f"{what} : code déjà utilisé")


# ---------------- Administration des référentiels ----------------
admin = APIRouter(prefix="/admin", tags=["Administration"])


@admin.get("/lines", response_model=list[LineOut])
def list_lines(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    return db.scalars(select(Line).where(Line.site_id == user.site_id).order_by(Line.code)).all()


@admin.post("/lines", response_model=LineOut, status_code=201)
def create_line(body: LineIn, request: Request, user: User = Depends(require_admin), db: Session = Depends(get_db)):
    line = Line(site_id=user.site_id, **body.model_dump())
    db.add(line)
    _flush(db, "Ligne")
    audit(db, "LINE_CREATED", user, "line", line.id, body.model_dump(), request)
    _commit(db, "Ligne")
    return line


@admin.put("/lines/{line_id}", response_model=LineOut)
def update_line(line_id: int, body: LineIn, request: Request, user: User = Depends(require_admin),
                db: Session = Depends(get_db)):
    line = db.get(Line, line_id)
    if line is None or line.site_id != user.site_id:
        raise HTTPException(404, "Ligne introuvable")
    for k, v in body.model_dump().items():
        setattr(line, k, v)
    audit(db, "LINE_UPDATED", user, "line", line.id, body.model_dump(), request)
    _commit(db, "Ligne")
    return line


@admin.get("/machines", response_model=list[MachineOut])
def list_machines(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    return db.scalars(select(Machine).join(Line).where(Line.site_id == user.site_id).order_by(Machine.code)).all()


def _own_line(db: Session, user: User, line_id: int) -> Line:
    line = db.get(Line, line_id)
    if line is None or line.site_id != user.site_id:
        raise HTTPException(422, "Ligne invalide")
    return line


@admin.post("/machines", response_model=MachineOut, status_code=201)
def create_machine(body: MachineIn, request: Request, user: User = Depends(require_admin),
                   db: Session = Depends(get_db)):
    _own_line(db, user, body.line_id)
    m = Machine(**body.model_dump())
    db.add(m)
    _flush(db, "Machine")
    audit(db, "MACHINE_CREATED", user, "machine", m.id, body.model_dump(), request)
    _commit(db, "Machine")
    return m


@admin.put("/machines/{machine_id}", response_model=MachineOut)
def update_machine(machine_id: int, body: MachineIn, request: Request, user: User = Depends(require_admin),
                   db: Session = Depends(get_db)):
    m = db.get(Machine, machine_id)
    if m is None or m.line.site_id != user.site_id:
        raise HTTPException(404, "Machine introuvable")
    _own_line(db, user, body.line_id)
    for k, v in body.model_dump().items():
        setattr(m, k, v)
    audit(db, "MACHINE_UPDATED", user, "machine", m.id, body.model_dump(), request)
    _commit(db, "Machine")
    return m


@admin.get("/categories", response_model=list[CategoryOut])
def list_categories(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    return db.scalars(select(FailureCategory).where(FailureCategory.site_id == user.site_id)
                      .order_by(FailureCategory.code)).all()


@admin.post("/categories", response_model=CategoryOut, status_code=201)
def create_category(body: CategoryIn, request: Request, user: User = Depends(require_admin),
                    db: Session = Depends(get_db)):
    c = FailureCategory(site_id=user.site_id, **body.model_dump())
    db.add(c)
    _flush(db, "Catégorie")
    audit(db, "CATEGORY_CREATED", user, "category", c.id, body.model_dump(mode="json"), request)
    _commit(db, "Catégorie")
    return c


@admin.put("/categories/{cat_id}", response_model=CategoryOut)
def update_category(cat_id: int, body: CategoryIn, request: Request, user: User = Depends(require_admin),
                    db: Session = Depends(get_db)):
    c = db.get(FailureCategory, cat_id)
    if c is None or c.site_id != user.site_id:
        raise HTTPException(404, "Catégorie introuvable")
    for k, v in body.model_dump().items():
        setattr(c, k, v)
    audit(db, "CATEGORY_UPDATED", user, "category", c.id, body.model_dump(mode="json"), request)
    _commit(db, "Catégorie")
    return c


@admin.get("/users", response_model=list[UserOut])
def list_users(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    return db.scalars(select(User).where(User.site_id == user.site_id).order_by(User.username)).all()


@admin.post("/users", response_model=UserOut, status_code=201)
def create_user(body: UserCreateIn, request: Request, user: User = Depends(require_admin),
                db: Session = Depends(get_db)):
    try:
        check_password_policy(body.password)
    except ValueError as e:
        raise HTTPException(422, str(e))
    u = User(site_id=user.site_id, username=body.username.lower(), full_name=body.full_name, role=body.role,
             password_hash=hash_password(body.password), must_change_password=True)
    db.add(u)
    _flush(db, "Utilisateur")
    audit(db, "USER_CREATED", user, "user", u.id, {"username": u.username, "role": u.role.value}, request)
    _commit(db, "Utilisateur")
    return u


@admin.patch("/users/{user_id}", response_model=UserOut)
def update_user(user_id: int, body: UserUpdateIn, request: Request, user: User = Depends(require_admin),
                db: Session = Depends(get_db)):
    u = db.get(User, user_id)
    if u is None or u.site_id != user.site_id:
        raise HTTPException(404, "Utilisateur introuvable")
    if u.id == user.id and (body.active is False or (body.role and body.role != Role.ADMIN)):
        raise HTTPException(409, "Un administrateur ne peut pas se désactiver ni retirer son propre rôle")
    detail = {}
    if body.full_name is not None:
        u.full_name = body.full_name
        detail["full_name"] = body.full_name
    if body.role is not None:
        detail["role"] = [u.role.value, body.role.value]
        u.role = body.role
    if body.active is not None:
        u.active = body.active
        detail["active"] = body.active
    if body.reset_password:
        try:
            check_password_policy(body.reset_password)
        except ValueError as e:
            raise HTTPException(422, str(e))
        u.password_hash = hash_password(body.reset_password)
        u.must_change_password = True
        u.failed_logins = 0
        u.locked_until = None
        detail["password_reset"] = True
    audit(db, "USER_UPDATED", user, "user", u.id, detail, request)
    _commit(db, "Utilisateur")
    return u
