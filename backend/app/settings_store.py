from sqlalchemy.orm import Session

from .models import DEFAULT_SETTINGS, AppSetting


def get_setting(db: Session, site_id: int, key: str):
    row = db.get(AppSetting, (site_id, key))
    if row is not None:
        return row.value.get("v")
    return DEFAULT_SETTINGS.get(key)


def all_settings(db: Session, site_id: int) -> dict:
    out = dict(DEFAULT_SETTINGS)
    for row in db.query(AppSetting).filter(AppSetting.site_id == site_id):
        out[row.key] = row.value.get("v")
    return out


def set_setting(db: Session, site_id: int, key: str, value) -> None:
    if key not in DEFAULT_SETTINGS:
        raise KeyError(key)
    row = db.get(AppSetting, (site_id, key))
    if row is None:
        db.add(AppSetting(site_id=site_id, key=key, value={"v": value}))
    else:
        row.value = {"v": value}
