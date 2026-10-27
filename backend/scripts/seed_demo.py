"""Jeu de données de démonstration 100 % FICTIF (aucune donnée réelle d'usine).

Usage :
    python -m scripts.seed_demo                 # mots de passe aléatoires -> demo_credentials.txt
    DEMO_PASSWORD='Demo2026!x' python -m scripts.seed_demo   # même mot de passe pour tous (poste de test)

Refuse de s'exécuter si APP_ENV vaut pilot ou production.
"""
import os
import secrets
import string
import sys
from pathlib import Path

from sqlalchemy import select

from app.config import get_settings
from app.db import SessionLocal
from app.models import FailureCategory, Line, Machine, Priority, Role, Site, User
from app.security import check_password_policy, hash_password

SITE = ("DEMO", "Usine de démonstration – Faisceaux automobiles")
LINES = [
    ("L1", "Ligne 1 – Coupe et sertissage", [
        ("L1-COUP-01", "Coupeuse-dénudeuse automatique"), ("L1-SERT-01", "Presse de sertissage 1"),
        ("L1-SERT-02", "Presse de sertissage 2"), ("L1-ULTRA-01", "Soudeuse à ultrasons")]),
    ("L2", "Ligne 2 – Assemblage faisceaux", [
        ("L2-PLAN-01", "Planche d'assemblage rotative"), ("L2-CONV-01", "Convoyeur principal"),
        ("L2-RUBAN-01", "Rubaneuse automatique")]),
    ("L3", "Ligne 3 – Contrôle électrique", [
        ("L3-TEST-01", "Banc de test électrique 1"), ("L3-TEST-02", "Banc de test électrique 2"),
        ("L3-VISION-01", "Poste de contrôle vision")]),
]
CATEGORIES = [
    ("MECA", "Mécanique", Priority.NORMALE), ("ELEC", "Électrique", Priority.HAUTE),
    ("PNEU", "Pneumatique", Priority.NORMALE), ("AUTO", "Automatisme / capteur", Priority.HAUTE),
    ("OUTIL", "Outillage / applicateur", Priority.NORMALE), ("QUAL", "Défaut qualité produit", Priority.HAUTE),
    ("SECU", "Sécurité machine", Priority.CRITIQUE), ("AUTRE", "Autre", Priority.BASSE),
]
USERS = [  # noms fictifs
    ("chef.l1", "Amine Chef L1 (fictif)", Role.CHEF_EQUIPE),
    ("chef.l2", "Nadia Chef L2 (fictif)", Role.CHEF_EQUIPE),
    ("tech.1", "Karim Technicien (fictif)", Role.TECHNICIEN),
    ("tech.2", "Youssef Technicien (fictif)", Role.TECHNICIEN),
    ("resp.maint", "Leila Responsable Maintenance (fictif)", Role.RESPONSABLE),
    ("admin", "Administrateur démo", Role.ADMIN),
]


def random_password() -> str:
    alphabet = string.ascii_letters + string.digits
    while True:
        p = "".join(secrets.choice(alphabet) for _ in range(10)) + secrets.choice("23456789")
        if any(c.isalpha() for c in p):
            return p


def main() -> int:
    if get_settings().is_production:
        print("Refusé : le jeu de démonstration ne doit pas être chargé en pilote/production.", file=sys.stderr)
        return 2
    shared = os.environ.get("DEMO_PASSWORD")
    if shared:
        check_password_policy(shared)
    db = SessionLocal()
    site = db.scalar(select(Site).where(Site.code == SITE[0]))
    if site:
        print("Jeu de démonstration déjà présent : rien à faire.")
        return 0
    site = Site(code=SITE[0], name=SITE[1])
    db.add(site)
    db.flush()
    for code, name, machines in LINES:
        line = Line(site_id=site.id, code=code, name=name)
        db.add(line)
        db.flush()
        for mcode, mname in machines:
            db.add(Machine(line_id=line.id, code=mcode, name=mname))
    for code, name, prio in CATEGORIES:
        db.add(FailureCategory(site_id=site.id, code=code, name=name, default_priority=prio))
    creds = []
    for username, full_name, role in USERS:
        pwd = shared or random_password()
        db.add(User(site_id=site.id, username=username, full_name=full_name, role=role,
                    password_hash=hash_password(pwd), must_change_password=False))
        creds.append((username, role.value, pwd))
    db.commit()

    out = Path("demo_credentials.txt")
    out.write_text("# Comptes de DÉMONSTRATION – ne jamais utiliser en production\n" +
                   "".join(f"{u:12} {r:12} {p}\n" for u, r, p in creds))
    out.chmod(0o600)
    print(f"Jeu de démonstration créé. Identifiants écrits dans {out.resolve()} (droits 600).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
