"""Operator-reviewed evidence; client URLs are never fetched or treated as authority."""
import copy
import hashlib
import json
import os
import stat
from dataclasses import dataclass, field
from pathlib import Path

from .contracts import Failure, ID, strict_json

MAX_EVIDENCE_BYTES = 8 * 1024 * 1024


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode("utf-8")).digest()


def _valid_id(value):
    return isinstance(value, str) and ID.fullmatch(value) is not None


def _unique_ids(value, *, nonempty=False):
    return (isinstance(value, list) and (bool(value) or not nonempty)
            and all(_valid_id(item) for item in value) and len(set(value)) == len(value))


@dataclass(frozen=True, repr=False)
class EvidenceContext:
    sources: dict = field(default_factory=dict)
    facts: dict = field(default_factory=dict)

    def model_input(self):
        # Neither callers nor provider adapters can change the registry's approved rows.
        return copy.deepcopy({"sources": list(self.sources.values()), "facts": list(self.facts.values())})

    def fingerprint(self):
        return _digest({"sources": self.sources, "facts": self.facts})


class EvidenceRegistry:
    def __init__(self, document=None):
        if document is None:
            document = {"sources": [], "facts": []}
        self.sources, self.facts = {}, {}
        if (not isinstance(document, dict) or set(document) != {"sources", "facts"}
                or not isinstance(document["sources"], list) or not isinstance(document["facts"], list)):
            raise ValueError("INVALID_EVIDENCE_REGISTRY")
        for source in document["sources"]:
            if (not isinstance(source, dict)
                or set(source) != {"sourceRevisionId", "text", "allowedDeviceIds", "reviewed"}
                or source["reviewed"] is not True or not _valid_id(source["sourceRevisionId"])
                or not isinstance(source["text"], str) or not source["text"].strip()
                or len(source["text"]) > 16000 or not _unique_ids(source["allowedDeviceIds"])
                or source["sourceRevisionId"] in self.sources):
                raise ValueError("INVALID_EVIDENCE_REGISTRY")
            self.sources[source["sourceRevisionId"]] = copy.deepcopy(source)
        for fact in document["facts"]:
            if (not isinstance(fact, dict)
                or set(fact) != {"factRevisionId", "factId", "kind", "text", "sourceRevisionIds"}
                or not _valid_id(fact["factRevisionId"]) or not _valid_id(fact["factId"])
                or fact["kind"] not in ("fact", "tradition") or not isinstance(fact["text"], str)
                or not fact["text"].strip() or len(fact["text"]) > 4000
                or not _unique_ids(fact["sourceRevisionIds"], nonempty=True)
                or not set(fact["sourceRevisionIds"]) <= self.sources.keys()
                or fact["factRevisionId"] in self.facts):
                raise ValueError("INVALID_EVIDENCE_REGISTRY")
            row = copy.deepcopy(fact)
            row["sourceRevisionIds"] = sorted(row["sourceRevisionIds"])
            self.facts[fact["factRevisionId"]] = row
        # Reject unencodable surrogates without exposing the operator's document.
        try:
            _digest({"sources": self.sources, "facts": self.facts})
        except (ValueError, UnicodeError):
            raise ValueError("INVALID_EVIDENCE_REGISTRY") from None

    def revision_fingerprints(self):
        # Device grants may change; source/fact revision content may not.
        sources = {sid: _digest({"sourceRevisionId": sid, "text": row["text"]})
                   for sid, row in self.sources.items()}
        facts = {fid: _digest(row) for fid, row in self.facts.items()}
        return sources, facts

    def resolve(self, owner, request):
        task, payload = request["task"], request["payload"]
        ids = set(payload.get("sourceRevisionIds", []))
        if task == "summary":
            ids = {sid for p in payload["completedResponses"] for sid in p["sourceRevisionIds"]}
        sources = {}
        for sid in sorted(ids):
            source = self.sources.get(sid)
            if source is None or owner not in source["allowedDeviceIds"]:
                raise Failure("NO_EVIDENCE", 422)
            sources[sid] = {"sourceRevisionId": sid, "text": source["text"]}
        excluded = set(payload.get("excludedFactIds", []))
        facts = {fid: copy.deepcopy(fact) for fid, fact in sorted(self.facts.items())
                 if set(fact["sourceRevisionIds"]) <= sources.keys() and fact["factId"] not in excluded}
        if task == "story" and not facts:
            raise Failure("NO_EVIDENCE", 422)
        return EvidenceContext(sources, facts)


class FileEvidenceRegistry:
    """Reload the private operator file at every authorization boundary, with no stale fallback.

    Only revision digests survive a refresh. They prevent an observed revision from being
    rewritten, including removal/re-addition, during this process lifetime. Deployment must
    retain the reviewed immutable source revisions across process restarts (DATA-03).
    """
    def __init__(self, path):
        self.path = Path(path)
        self.source_revisions, self.fact_revisions = {}, {}
        try:
            self._load()
        except (Failure, OSError, ValueError, TypeError):
            raise ValueError("INVALID_EVIDENCE_REGISTRY") from None

    def _load(self):
        before = os.lstat(self.path)
        if not stat.S_ISREG(before.st_mode):
            raise ValueError("INVALID_EVIDENCE_REGISTRY")
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
        descriptor = os.open(self.path, flags)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode)
                    or (before.st_dev, before.st_ino) != (info.st_dev, info.st_ino)
                    or (os.name == "posix" and (info.st_uid != os.geteuid()
                        or info.st_mode & 0o077 or not info.st_mode & stat.S_IRUSR))):
                raise ValueError("INVALID_EVIDENCE_REGISTRY")
            raw = stream.read(MAX_EVIDENCE_BYTES + 1)
            after = os.fstat(stream.fileno())
        current = os.lstat(self.path)
        if (len(raw) > MAX_EVIDENCE_BYTES
                or (info.st_dev, info.st_ino) != (current.st_dev, current.st_ino)
                or (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                   != (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                or (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                   != (current.st_size, current.st_mtime_ns, current.st_ctime_ns)):
            raise ValueError("INVALID_EVIDENCE_REGISTRY")
        document = strict_json(raw)
        if not isinstance(document, dict):
            raise ValueError("INVALID_EVIDENCE_REGISTRY")
        registry = EvidenceRegistry(document)
        source_revisions, fact_revisions = registry.revision_fingerprints()
        if (any(sid in self.source_revisions and self.source_revisions[sid] != digest
                for sid, digest in source_revisions.items())
                or any(fid in self.fact_revisions and self.fact_revisions[fid] != digest
                       for fid, digest in fact_revisions.items())):
            raise ValueError("IMMUTABLE_EVIDENCE_REVISION")
        # Commit observed revisions only after the entire candidate passes validation.
        self.source_revisions.update(source_revisions)
        self.fact_revisions.update(fact_revisions)
        return registry

    def resolve(self, owner, request):
        try:
            registry = self._load()
        except (Failure, OSError, ValueError, TypeError):
            # An invalid replacement must not silently reuse the last approved file, even
            # for a request without source IDs. No file paths or parser details escape.
            raise Failure("NO_EVIDENCE", 422) from None
        return registry.resolve(owner, request)


def validate_semantics(request, result, evidence):
    task, payload = request["task"], request["payload"]
    bad = Failure("INVALID_MODEL_OUTPUT", 422)
    if task in ("answer", "story"):
        story_fact_ids = set()
        for p in result["paragraphs"]:
            if not p["text"].strip() or not set(p["sourceRevisionIds"]) <= evidence.sources.keys():
                raise bad
            if not set(p["factRevisionIds"]) <= evidence.facts.keys():
                raise bad
            if p["kind"] in ("fact", "tradition"):
                if not p["factRevisionIds"] or not p["sourceRevisionIds"]:
                    raise bad
                required_sources = set()
                for fid in p["factRevisionIds"]:
                    fact = evidence.facts[fid]
                    if fact["kind"] != p["kind"] or not set(fact["sourceRevisionIds"]) <= set(p["sourceRevisionIds"]):
                        raise bad
                    if task == "story":
                        # Match TX-05: different revisions of one stable fact cannot
                        # duplicate that fact within or across public-story paragraphs.
                        if fact["factId"] in story_fact_ids:
                            raise bad
                        story_fact_ids.add(fact["factId"])
                        required_sources.update(fact["sourceRevisionIds"])
                if task == "story" and set(p["sourceRevisionIds"]) != required_sources:
                    raise bad
            elif task == "story" or p["factRevisionIds"]:
                raise bad
        # An answer request has no authorized content candidate list.
        if task == "answer" and result["targetContentId"] is not None:
            raise bad
    elif task == "resume":
        if result["action"] == "wait":
            if result["targetContentId"] is not None or result["paragraphId"] is not None:
                raise bad
        elif not any(c["contentId"] == result["targetContentId"] and c["paragraphId"] == result["paragraphId"]
                     for c in payload["candidates"]):
            raise bad
    elif task == "summary":
        completed = {p["paragraphKey"] for p in payload["completedResponses"]}
        if (set(result["completedResponseKeys"]) != completed
            or set(result["partialUnknownKeys"]) != set(payload["partialUnknownKeys"])
            or not set(result["verifiedFactRevisionIds"]) <= evidence.facts.keys()
            or any(evidence.facts[fid]["kind"] != "fact" for fid in result["verifiedFactRevisionIds"])):
            raise bad
