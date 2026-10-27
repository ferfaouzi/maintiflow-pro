import hashlib
import os
import uuid
from pathlib import Path

from .config import get_settings

ALLOWED_IMAGE_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
MAGIC = {"image/jpeg": b"\xff\xd8\xff", "image/png": b"\x89PNG", "image/webp": b"RIFF"}


def root() -> Path:
    p = Path(get_settings().storage_dir).resolve()
    p.mkdir(parents=True, exist_ok=True)
    return p


def save_bytes(subdir: str, data: bytes, ext: str) -> tuple[str, str]:
    """Écrit le fichier hors de la racine web ; retourne (chemin relatif, sha256)."""
    rel = Path(subdir) / f"{uuid.uuid4()}{ext}"
    full = root() / rel
    full.parent.mkdir(parents=True, exist_ok=True)
    tmp = full.with_suffix(full.suffix + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, full)
    return str(rel), hashlib.sha256(data).hexdigest()


def resolve(rel: str) -> Path:
    full = (root() / rel).resolve()
    if root() not in full.parents:
        raise ValueError("Chemin de stockage invalide")
    return full
