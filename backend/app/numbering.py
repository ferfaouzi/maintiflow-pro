from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from .models import NumberSequence


def next_intervention_number(db: Session, site_id: int, year: int) -> str:
    """Numéro unique BI-AAAA-NNNNNN, sans trou ni doublon même en accès concurrent.

    La ligne du compteur est verrouillée (SELECT ... FOR UPDATE) jusqu'au commit
    de la transaction qui crée le bon.
    """
    db.execute(
        insert(NumberSequence)
        .values(site_id=site_id, year=year, last_value=0)
        .on_conflict_do_nothing(index_elements=["site_id", "year"])
    )
    seq = db.execute(
        select(NumberSequence)
        .where(NumberSequence.site_id == site_id, NumberSequence.year == year)
        .with_for_update()
    ).scalar_one()
    seq.last_value += 1
    db.flush()
    return f"BI-{year}-{seq.last_value:06d}"
