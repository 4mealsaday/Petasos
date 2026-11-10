"""Common normalized rows (CONTENT_PIPELINE.md 2장). Adapters produce these; later stages never see raw API rows."""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class License:
    type: str          # e.g. KOGL-1, KOGL-3, KHS-OPENAPI-UNCONFIRMED
    url: str           # https page that states the terms
    allows_generation: bool  # may a model paraphrase it (KOGL-3 forbids modification)
    basis: str         # why this license applies (human-readable)
    # False for 변경금지 terms (KOGL-3) and for unconfirmed types: such text is never used as a landmark intro,
    # not even quoted verbatim (CONTENT_PIPELINE.md 6장 후보 기준).
    allows_modification: bool = True


@dataclass(frozen=True)
class SourceDocument:
    """One fetched source text (an overview/description). Facts quote it sentence by sentence."""
    source_id: str     # "<adapter>:<original id>"
    provider: str      # display name of the provider
    url: str           # https page or request URL without credentials
    title: str
    text: str
    license: License
    fetched_at: int    # epoch ms
    updated_raw: str | None = None
    attribution: str | None = None   # replaces the default "<provider> (<license type>)" line
    uses_basis: str | None = None    # replaces the default allowedUses review basis (curated sources)


@dataclass(frozen=True)
class Place:
    place_id: str                  # "<adapter>:<original id>"
    name: str
    lat: float
    lng: float
    region_code: str | None        # 5-digit si/gun/gu code
    category: str                  # heritage | culture | nature | building | leisure | other
    source: str                    # adapter name
    license: License
    source_url: str
    fetched_at: int
    attributes: dict = field(default_factory=dict)  # adapter-specific facts used by visibility/scoring rules


@dataclass(frozen=True)
class Fact:
    """One sentence quoted from a source document."""
    fact_id: str
    place_id: str | None
    region_code: str | None
    text: str
    source_id: str
    license: License
