"""Curated sources (CONTENT_PIPELINE.md 6장 후보 기준): a reviewed replacement for a place whose API text may not be
used as an intro (변경금지 / unconfirmed license). demo/scenarios/<id>/curated_sources.json maps a place id to
{"document": {...}, "coordinates": {...}}; the document's text is a few independent fact sentences written from an
official page (simple facts only, no copied wording), checked by a person against `url` before the pack is built."""
import json
from pathlib import Path

from .model import License, SourceDocument


def _document(row, fetched_at):
    license = License(row["license"]["type"], row["license"]["url"], bool(row["license"]["allowsGeneration"]),
                      row["license"]["basis"], allows_modification=True)
    return SourceDocument(
        source_id=row["sourceId"], provider=row["provider"], url=row["url"], title=row["title"], text=row["text"],
        license=license, fetched_at=fetched_at, attribution=row.get("attribution"), uses_basis=row.get("usesBasis"))


def load(path):
    path = Path(path)
    if not path.exists():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    out = {}
    for place_id, entry in value["places"].items():
        fetched = int(entry["fetchedAt"])
        out[place_id] = {"document": _document(entry["document"], fetched),
                         "coordinates": _document(entry["coordinates"], fetched) if entry.get("coordinates") else None}
    return out
