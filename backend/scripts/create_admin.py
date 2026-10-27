"""Création du premier administrateur d'un site (installation pilote / production).

    python -m scripts.create_admin --site-code USINE1 --site-name "Usine pilote" --username admin.site

Le mot de passe est demandé de façon masquée, jamais passé en argument ni stocké en clair.
"""
import argparse
import getpass

from sqlalchemy import select

from app.db import SessionLocal
from app.models import Role, Site, User
from app.security import check_password_policy, hash_password


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site-code", required=True)
    ap.add_argument("--site-name", required=True)
    ap.add_argument("--username", required=True)
    ap.add_argument("--full-name", default="Administrateur")
    a = ap.parse_args()
    pwd = getpass.getpass("Mot de passe administrateur : ")
    if pwd != getpass.getpass("Confirmation : "):
        print("Les mots de passe ne correspondent pas.")
        return 1
    check_password_policy(pwd)
    db = SessionLocal()
    site = db.scalar(select(Site).where(Site.code == a.site_code))
    if site is None:
        site = Site(code=a.site_code, name=a.site_name)
        db.add(site)
        db.flush()
    db.add(User(site_id=site.id, username=a.username.lower(), full_name=a.full_name, role=Role.ADMIN,
                password_hash=hash_password(pwd), must_change_password=False))
    db.commit()
    print(f"Administrateur {a.username} créé sur le site {site.code}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
