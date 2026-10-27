"""Scénario de démonstration de bout en bout contre un serveur en marche (données fictives).

    python -m scripts.demo_scenario --base http://localhost:8000 --credentials demo_credentials.txt

Crée 4 bons à différents états, dont un clôturé avec PDF archivé (enregistré dans ./demo_output/).
"""
import argparse
import base64
import struct
import zlib
from pathlib import Path

import httpx


def signature_png() -> str:
    w, h = 120, 40
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            on = abs((y - 20) - int(12 * __import__("math").sin(x / 9))) < 2
            row += bytes([20, 40, 120] if on else [255, 255, 255])
        rows.append(bytes(row))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
    return base64.b64encode(png).decode()


class Client:
    def __init__(self, base, username, password):
        self.c = httpx.Client(base_url=base + "/api/v1", timeout=30)
        r = self.c.post("/auth/login", json={"username": username, "password": password, "device": "demo"})
        r.raise_for_status()
        self.c.headers["Authorization"] = "Bearer " + r.json()["access_token"]

    def act(self, iv, name, **body):
        r = self.c.post(f"/interventions/{iv['id']}/actions/{name}", json=body)
        if r.status_code != 200:
            raise SystemExit(f"{name}: {r.status_code} {r.text}")
        return r.json()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8000")
    ap.add_argument("--credentials", default="demo_credentials.txt")
    a = ap.parse_args()
    pw = dict(line.split()[0::2] for line in Path(a.credentials).read_text().splitlines()
              if line and not line.startswith("#"))
    chef, tech, tech2, resp = (Client(a.base, u, pw[u]) for u in ("chef.l1", "tech.1", "tech.2", "resp.maint"))
    refs = chef.c.get("/referentials").json()
    m = {x["code"]: x["id"] for x in refs["machines"]}
    c = {x["name"]: x["id"] for x in refs["categories"]}
    sig = signature_png()

    def create(machine, cat, desc, stopped=True):
        r = chef.c.post("/interventions", json={"machine_id": m[machine], "category_id": c[cat],
                                                 "description": desc, "production_stopped": stopped})
        r.raise_for_status()
        return r.json()

    iv = create("L1-SERT-01", "Outillage / applicateur",
                "Hauteur de sertissage hors tolérance sur terminal 2,8 mm, pièces rejetées au contrôle.")
    iv = tech.act(iv, "accept")
    iv = tech.act(iv, "start")
    tech.c.post(f"/interventions/{iv['id']}/parts", json={"reference": "APP-28-STD", "designation":
                                                          "Applicateur terminal 2,8 mm (fictif)", "quantity": 1})
    iv = tech.c.get(f"/interventions/{iv['id']}").json()
    iv = tech.act(iv, "repair", diagnosis="Usure du poinçon de l'applicateur",
                  root_cause="Dépassement du nombre de cycles préconisé",
                  corrective_action="Remplacement de l'applicateur, réglage et contrôle de 5 pièces (OK)",
                  machine_final_state="OPERATIONNELLE")
    iv = tech.act(iv, "sign_maintenance", signature_png_base64=sig)
    iv = chef.act(iv, "confirm_production", signature_png_base64=sig, comment="Production relancée, 1re pièce OK")
    out = Path("demo_output")
    out.mkdir(exist_ok=True)
    pdf = chef.c.get(f"/interventions/{iv['id']}/pdf")
    (out / f"{iv['number']}.pdf").write_bytes(pdf.content)
    print("Clôturé :", iv["number"], "->", out / f"{iv['number']}.pdf")

    iv2 = create("L2-CONV-01", "Mécanique", "Bruit anormal et à-coups du convoyeur principal.")
    iv2 = resp.act(iv2, "assign", technician_id=next(t["id"] for t in refs["technicians"]
                                                     if "Youssef" in t["full_name"]), priority="HAUTE")
    iv2 = tech2.act(iv2, "accept")
    iv2 = tech2.act(iv2, "start")
    iv2 = tech2.act(iv2, "wait_part", comment="Roulement 6204-2RS en commande magasin")
    print("En attente de pièce :", iv2["number"])

    iv3 = create("L3-TEST-01", "Sécurité machine", "Barrière immatérielle ne coupe plus le cycle.")
    print("Nouveau (critique) :", iv3["number"])
    iv4 = create("L1-COUP-01", "Pneumatique", "Fuite d'air au niveau de la pince d'amenée.", stopped=False)
    iv4 = tech.act(iv4, "accept")
    print("Accepté :", iv4["number"])


if __name__ == "__main__":
    main()
