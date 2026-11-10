"""Normalized-store cache (CONTENT_PIPELINE.md 1장): one JSON file per (adapter, request key).

A source id that has been fetched once is never fetched again; delete the file to force a refresh.
Credentials are never part of a key or a stored file.
"""
import hashlib
import json
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[3] / ".local" / "content" / "cache"


class SourceCache:
    def __init__(self, root=DEFAULT_ROOT, offline=False):
        self.root = Path(root)
        self.offline = offline
        self.hits = 0
        self.misses = 0

    def _path(self, adapter, key):
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
        return self.root / adapter / f"{digest}.json"

    def get_or_fetch(self, adapter, key, fetch):
        """fetch() returns a JSON-serializable value. In offline mode a miss raises KeyError."""
        path = self._path(adapter, key)
        if path.exists():
            self.hits += 1
            return json.loads(path.read_text(encoding="utf-8"))["value"]
        if self.offline:
            raise KeyError(key)
        value = fetch()
        self.misses += 1
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps({"key": key, "value": value}, ensure_ascii=False), encoding="utf-8")
        temporary.replace(path)
        return value
