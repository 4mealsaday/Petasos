"""Compile explicitly reviewed public text into a deterministic TX-05 bundle.

This compiler does not fetch sources, approve pending reviews, infer regional
boundaries, verify media, or invent timestamps. Reviewed source text and every
permission are supplied explicitly. Android performs the transactional install.
"""

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import math
import re
from urllib.parse import urlsplit
from uuid import NAMESPACE_URL, uuid5


MAX_BUNDLE_BYTES = 256 * 1024
MAX_REVIEW_BYTES = 2 * 1024 * 1024
_LONG_MAX = 2**63 - 1
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")
_COORDINATE = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
_NAMESPACE = uuid5(NAMESPACE_URL, "urn:petasos:content:v0.8")
_TOP = {"formatVersion", "sources", "facts", "places", "stories", "versions"}
_SOURCE = {"sourceRevisionId", "sourceId", "provider", "sourceUrl", "title", "fetchedAt", "sourceUpdatedAt",
           "sourceUpdatedRaw", "verifiedAt", "contentHash", "license", "allowedUses", "attribution", "reviewStatus", "normalizedText"}
_FACT = {"factRevisionId", "factId", "version", "statement", "kind", "verifiedAt", "sourceRevisionIds"}
_PLACE = {"placeId", "name", "aliases", "latitude", "longitude", "coordinateSystem", "coordinateSourceRevisionId", "coordinateReviewBasis", "externalIds"}
_STORY = {"storyId", "topicId", "title", "coreQuestion", "interestTags", "placeId"}
_REGION_BINDING = {"storyId", "regionId", "regionRevisionId", "regionCode", "datasetVersion", "regionName",
                   "regionSourceRevisionId", "associationFactRevisionId", "verifiedAt", "reviewBasis"}
_VERSION = {"storyVersionId", "storyId", "version", "contentHash", "createdAt", "verifiedAt", "validUntil", "estimatedDurationMs", "status", "paragraphs"}
_PARAGRAPH = {"paragraphId", "ordinal", "required", "text", "audioRef", "estimatedDurationMs", "factRevisionIds"}
_CODES = frozenset({
    "INVALID_BUNDLE", "INVALID_REVIEW", "UNSUPPORTED_BUNDLE_VERSION", "BUNDLE_TOO_LARGE",
    "DUPLICATE_REVISION", "SOURCE_NOT_REVIEWED", "USE_NOT_ALLOWED", "UNSUPPORTED_ASSET_SCOPE",
    "SOURCE_HASH_MISMATCH", "INVALID_REVISION", "INVALID_FACT_KIND", "DUPLICATE_EXTERNAL_ID",
    "INVALID_COORDINATES", "UNREVIEWED_COORDINATES", "MISSING_REFERENCE", "UNREVIEWED_VERSION",
    "EXPIRED_VERSION", "INVALID_DURATION", "MISSING_REQUIRED_PARAGRAPH", "DUPLICATE_PARAGRAPH",
    "INVALID_ORDINAL", "UNVERIFIED_MEDIA", "DUPLICATE_STABLE_FACT", "STORY_HASH_MISMATCH",
    "INCOMPLETE_BUNDLE", "INVALID_SOURCE_URL", "DUPLICATE_ID", "INVALID_ID", "INVALID_TIMESTAMP",
    "INVALID_TEXT", "DUPLICATE_REVIEW_KEY", "INVALID_DEVICE_IDS",
    "INVALID_REGION_BINDING",
})


class ContentBundleError(ValueError):
    """Only a fixed code escapes; reviewed text and file contents never do."""

    def __init__(self, code):
        self.code = code if isinstance(code, str) and code in _CODES else "INVALID_BUNDLE"
        super().__init__(self.code)


def _need(condition, code="INVALID_BUNDLE"):
    if not condition:
        raise ContentBundleError(code)


def canonical_bytes(value) -> bytes:
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise ContentBundleError("INVALID_BUNDLE") from None


def source_content_hash(text: str) -> str:
    _text(text, 16000)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def version_content_hash(story_id: str, version: int, paragraphs: list) -> str:
    # Fact references are a set in the database; only their order is canonicalized.
    # Paragraph order and every other field remain part of the immutable hash.
    try:
        normalized = [{**paragraph, "factRevisionIds": sorted(paragraph["factRevisionIds"])} for paragraph in paragraphs]
    except (KeyError, TypeError, ValueError):
        raise ContentBundleError("INVALID_BUNDLE") from None
    return hashlib.sha256(canonical_bytes({"storyId": story_id, "version": version, "paragraphs": normalized})).hexdigest()


def _obj(value, keys, code="INVALID_BUNDLE"):
    _need(isinstance(value, dict) and set(value) == set(keys), code)
    return value


def _rows(value):
    _need(isinstance(value, list))
    return value


def _text(value, limit):
    _need(isinstance(value, str), "INVALID_TEXT")
    _need(bool(value.strip()) and not any(ord(c) < 32 and c not in "\n\r\t" or 0xD800 <= ord(c) <= 0xDFFF for c in value), "INVALID_TEXT")
    # Android String.length counts UTF-16 code units, including two per emoji.
    _need(len(value.encode("utf-16-le")) // 2 <= limit, "INVALID_TEXT")


def _id(value):
    _need(isinstance(value, str) and _ID.fullmatch(value) is not None, "INVALID_ID")


def _integer(value):
    _need(type(value) is int and -_LONG_MAX - 1 <= value <= _LONG_MAX)


def _timestamp(value):
    _integer(value)
    _need(value >= 0, "INVALID_TIMESTAMP")


def _revision(value):
    _integer(value)
    _need(value <= 2**31 - 1)
    _need(value >= 1, "INVALID_REVISION")


def _duration(value):
    _integer(value)
    _need(1 <= value <= 3_600_000, "INVALID_DURATION")


def _https(value):
    _text(value, 2048)
    try:
        url = urlsplit(value)
        valid = (url.scheme == "https" and bool(url.hostname) and url.username is None
                 and not re.search(r"[\x00-\x20\x7f]|%(?![0-9a-fA-F]{2})", value)
                 and not re.search(r"(?i)(servicekey|api[_-]?key|access[_-]?token|authorization)=", value))
    except ValueError:
        valid = False
    _need(valid, "INVALID_SOURCE_URL")


def _strings(values, maximum, limit):
    _rows(values)
    _need(len(values) <= maximum and all(isinstance(value, str) for value in values))
    _need(len(set(values)) == len(values))
    for value in values:
        _text(value, limit)


def _unique(rows, field):
    indexed = {}
    for row in rows:
        _need(isinstance(row, dict) and field in row)
        key = row[field]
        _id(key)
        _need(key not in indexed, "DUPLICATE_ID")
        indexed[key] = row
    return indexed


def _refs(values, keys):
    _rows(values)
    _need(bool(values) and all(isinstance(value, str) for value in values), "MISSING_REFERENCE")
    _need(len(set(values)) == len(values) and set(values) <= set(keys), "MISSING_REFERENCE")


def _coordinate(value, minimum, maximum):
    _need(isinstance(value, str) and len(value) <= 64 and _COORDINATE.fullmatch(value) is not None, "INVALID_COORDINATES")
    try:
        number = float(value)
        valid = math.isfinite(number) and minimum <= number <= maximum
    except (ValueError, OverflowError):
        valid = False
    _need(valid, "INVALID_COORDINATES")


def _validate_bundle(bundle, now_epoch_ms):
    _need(isinstance(bundle, dict))
    version = bundle.get("formatVersion")
    _need(type(version) is int and version in (1, 2), "UNSUPPORTED_BUNDLE_VERSION")
    _obj(bundle, _TOP | ({"regionBindings"} if version == 2 else set()))
    _timestamp(now_epoch_ms)
    for name, low, high in (("sources", 1, 64), ("facts", 1, 256), ("places", 0, 64), ("stories", 1, 32), ("versions", 1, 64)):
        _need(low <= len(_rows(bundle[name])) <= high)
    for name, keys in (("sources", _SOURCE), ("facts", _FACT), ("places", _PLACE), ("stories", _STORY), ("versions", _VERSION)):
        for row in bundle[name]:
            _obj(row, keys)
    sources = _unique(bundle["sources"], "sourceRevisionId")
    facts = _unique(bundle["facts"], "factRevisionId")
    places = _unique(bundle["places"], "placeId")
    stories = _unique(bundle["stories"], "storyId")
    _unique(bundle["versions"], "storyVersionId")
    for source in sources.values():
        _id(source["sourceId"])
        _text(source["provider"], 128)
        _https(source["sourceUrl"])
        _text(source["title"], 500)
        _timestamp(source["fetchedAt"])
        _timestamp(source["verifiedAt"])
        if source["sourceUpdatedAt"] is not None:
            _timestamp(source["sourceUpdatedAt"])
        if source["sourceUpdatedRaw"] is not None:
            _text(source["sourceUpdatedRaw"], 128)
        _need(source["reviewStatus"] == "verified", "SOURCE_NOT_REVIEWED")
        uses = _obj(source["allowedUses"], {"allowGeneration", "allowDisplay", "allowDistribution", "reviewBasis"})
        _need(all(type(uses[key]) is bool for key in ("allowGeneration", "allowDisplay", "allowDistribution")))
        _need(uses["allowDisplay"] and uses["allowDistribution"], "USE_NOT_ALLOWED")
        _text(uses["reviewBasis"], 4000)
        license = _obj(source["license"], {"type", "url", "scope", "reviewBasis"})
        _text(license["type"], 128)
        _https(license["url"])
        _need(license["scope"] == "text", "UNSUPPORTED_ASSET_SCOPE")
        _text(license["reviewBasis"], 4000)
        _text(source["attribution"], 1000)
        _need(source["contentHash"] == source_content_hash(source["normalizedText"]), "SOURCE_HASH_MISMATCH")
    for fact in facts.values():
        _id(fact["factId"])
        _revision(fact["version"])
        _text(fact["statement"], 4000)
        _timestamp(fact["verifiedAt"])
        _need(fact["kind"] in ("verified_fact", "tradition"), "INVALID_FACT_KIND")
        _refs(fact["sourceRevisionIds"], sources)
    external_ids = set()
    for place in places.values():
        _text(place["name"], 500)
        _strings(place["aliases"], 32, 200)
        for external in _rows(place["externalIds"]):
            _obj(external, {"provider", "externalId", "verifiedAt"})
            _text(external["provider"], 128)
            _text(external["externalId"], 128)
            _timestamp(external["verifiedAt"])
            pair = (external["provider"], external["externalId"])
            _need(pair not in external_ids, "DUPLICATE_EXTERNAL_ID")
            external_ids.add(pair)
        if place["latitude"] is None and place["longitude"] is None:
            _need(all(place[key] is None for key in ("coordinateSystem", "coordinateSourceRevisionId", "coordinateReviewBasis")), "INVALID_COORDINATES")
        else:
            _need(place["coordinateSystem"] == "WGS84" and isinstance(place["coordinateSourceRevisionId"], str)
                  and place["coordinateSourceRevisionId"] in sources, "UNREVIEWED_COORDINATES")
            _need(place["coordinateReviewBasis"] is not None, "UNREVIEWED_COORDINATES")
            _text(place["coordinateReviewBasis"], 4000)
            _coordinate(place["latitude"], -90, 90)
            _coordinate(place["longitude"], -180, 180)
    for story in stories.values():
        _id(story["topicId"])
        _text(story["title"], 500)
        _text(story["coreQuestion"], 1000)
        _strings(story["interestTags"], 20, 100)
        _need(story["placeId"] is None or isinstance(story["placeId"], str) and story["placeId"] in places, "MISSING_REFERENCE")
    if version == 2:
        _validate_region_bindings(bundle["regionBindings"], stories, facts, now_epoch_ms)
    paragraph_ids = set()
    for version in bundle["versions"]:
        _need(isinstance(version["storyId"], str) and version["storyId"] in stories, "MISSING_REFERENCE")
        _revision(version["version"])
        _timestamp(version["createdAt"])
        _timestamp(version["verifiedAt"])
        _need(version["status"] == "published", "UNREVIEWED_VERSION")
        if version["validUntil"] is not None:
            _integer(version["validUntil"])
            _need(version["validUntil"] > now_epoch_ms, "EXPIRED_VERSION")
        _duration(version["estimatedDurationMs"])
        paragraphs = _rows(version["paragraphs"])
        _need(1 <= len(paragraphs) <= 64, "MISSING_REQUIRED_PARAGRAPH")
        stable_facts = {}  # one fact revision may back several paragraphs; two revisions of one fact may not mix
        for index, paragraph in enumerate(paragraphs):
            _obj(paragraph, _PARAGRAPH)
            _id(paragraph["paragraphId"])
            _need(paragraph["paragraphId"] not in paragraph_ids, "DUPLICATE_PARAGRAPH")
            paragraph_ids.add(paragraph["paragraphId"])
            _integer(paragraph["ordinal"])
            _need(paragraph["ordinal"] == index, "INVALID_ORDINAL")
            _need(type(paragraph["required"]) is bool)
            _text(paragraph["text"], 4000)
            _need(paragraph["audioRef"] is None, "UNVERIFIED_MEDIA")
            _duration(paragraph["estimatedDurationMs"])
            _refs(paragraph["factRevisionIds"], facts)
            for fact_id in paragraph["factRevisionIds"]:
                stable_id = facts[fact_id]["factId"]
                _need(stable_facts.setdefault(stable_id, fact_id) == fact_id, "DUPLICATE_STABLE_FACT")
        _need(any(paragraph["required"] for paragraph in paragraphs), "MISSING_REQUIRED_PARAGRAPH")
        _need(version["estimatedDurationMs"] == sum(paragraph["estimatedDurationMs"] for paragraph in paragraphs), "INVALID_DURATION")
        _need(version["contentHash"] == version_content_hash(version["storyId"], version["version"], paragraphs), "STORY_HASH_MISMATCH")
    for rows, keys in ((bundle["sources"], ("sourceId", "contentHash")), (bundle["facts"], ("factId", "version")), (bundle["versions"], ("storyId", "version"))):
        _need(len({tuple(row[key] for key in keys) for row in rows}) == len(rows), "DUPLICATE_REVISION")
    _need(set(stories) == {version["storyId"] for version in bundle["versions"]}, "INCOMPLETE_BUNDLE")


def _validate_region_bindings(bindings, stories, facts, now_epoch_ms):
    """Validate explicit content association, without granting geometry or visit authority.

    The Android installer separately compares each region reference to its
    installed catalog. Association facts need not appear in spoken paragraphs.
    """
    _need(len(_rows(bindings)) <= 32, "INVALID_REGION_BINDING")
    seen_stories = set()
    for binding in bindings:
        _obj(binding, _REGION_BINDING)
        _need(all(isinstance(binding[field], str) for field in _REGION_BINDING - {"verifiedAt"}))
        for field in ("storyId", "regionId", "regionRevisionId", "regionSourceRevisionId", "associationFactRevisionId"):
            _id(binding[field])
        story_id = binding["storyId"]
        fact_id = binding["associationFactRevisionId"]
        _need(story_id in stories and story_id not in seen_stories and fact_id in facts, "INVALID_REGION_BINDING")
        seen_stories.add(story_id)
        _need(re.fullmatch(r"[0-9]{5}", binding["regionCode"]) is not None,
              "INVALID_REGION_BINDING")
        _text(binding["datasetVersion"], 128)
        _text(binding["regionName"], 500)
        _text(binding["reviewBasis"], 4000)
        _timestamp(binding["verifiedAt"])
        fact = facts[fact_id]
        _need(fact["kind"] == "verified_fact" and fact["verifiedAt"] <= binding["verifiedAt"] <= now_epoch_ms,
              "INVALID_REGION_BINDING")


def validate_bundle(bundle, now_epoch_ms: int | None = None) -> None:
    """Match the Android ContentBundleCodec contract, without altering input."""
    now = int(datetime.now(timezone.utc).timestamp() * 1000) if now_epoch_ms is None else now_epoch_ms
    try:
        _need(len(canonical_bytes(bundle)) <= MAX_BUNDLE_BYTES, "BUNDLE_TOO_LARGE")
        _validate_bundle(bundle, now)
    except ContentBundleError:
        raise
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        raise ContentBundleError("INVALID_BUNDLE") from None


def _stable_id(*parts) -> str:
    return str(uuid5(_NAMESPACE, canonical_bytes(list(parts)).decode("utf-8")))


def _review_rows(review, name, fields):
    result = []
    seen = set()
    for row in _rows(review[name]):
        _obj(row, fields, "INVALID_REVIEW")
        if "stableKey" in fields:
            key = row["stableKey"]
            _text(key, 512)
            _need(key not in seen, "DUPLICATE_REVIEW_KEY")
            seen.add(key)
        result.append(deepcopy(row))
    return result


def _take_refs(keys, index):
    _refs(keys, index)
    return [index[key] for key in keys]


def build_reviewed_bundle(review_document, now_epoch_ms: int | None = None) -> dict:
    """Compile the explicit review input; metadata never becomes permission.

    Stable keys determine UUIDv5 identities. Immutable timestamps are copied
    from review, so repeated builds preserve source/story revisions exactly.
    reviewMetadata is retained in the input review file, never in the bundle.
    """
    try:
        _need(isinstance(review_document, dict), "INVALID_REVIEW")
        format_version = review_document.get("formatVersion")
        _need(type(format_version) is int and format_version in (1, 2), "INVALID_REVIEW")
        keys = _TOP | {"kind"} | ({"regionBindings"} if format_version == 2 else set())
        if "reviewMetadata" in review_document:
            keys.add("reviewMetadata")
            _need(isinstance(review_document["reviewMetadata"], dict), "INVALID_REVIEW")
        _obj(review_document, keys, "INVALID_REVIEW")
        _need(review_document["kind"] == "petasos-content-source-review", "INVALID_REVIEW")
        _need(len(canonical_bytes(review_document)) <= MAX_REVIEW_BYTES, "INVALID_REVIEW")
        sources, facts, places, stories, versions = [], [], [], [], []
        source_ids, fact_ids, place_ids, story_ids = {}, {}, {}, {}
        for row in _review_rows(review_document, "sources", _SOURCE - {"sourceRevisionId", "sourceId", "contentHash"} | {"stableKey"}):
            key = row.pop("stableKey")
            source_id = _stable_id("source", row["provider"], key)
            digest = source_content_hash(row["normalizedText"])
            revision_id = _stable_id("source-revision", source_id, digest)
            source_ids[key] = revision_id
            sources.append({**row, "sourceId": source_id, "sourceRevisionId": revision_id, "contentHash": digest})
        for row in _review_rows(review_document, "facts", _FACT - {"factId", "factRevisionId", "sourceRevisionIds"} | {"stableKey", "sourceKeys"}):
            key = row.pop("stableKey")
            fact_id = _stable_id("fact", key)
            revision_id = _stable_id("fact-revision", fact_id, row["version"])
            fact_ids[key] = revision_id
            refs = _take_refs(row.pop("sourceKeys"), source_ids)
            facts.append({**row, "factId": fact_id, "factRevisionId": revision_id, "sourceRevisionIds": refs})
        for row in _review_rows(review_document, "places", _PLACE - {"placeId", "coordinateSourceRevisionId"} | {"stableKey", "coordinateSourceKey"}):
            key = row.pop("stableKey")
            place_id = _stable_id("place", key)
            place_ids[key] = place_id
            source_key = row.pop("coordinateSourceKey")
            _need(source_key is None or isinstance(source_key, str) and source_key in source_ids, "MISSING_REFERENCE")
            places.append({**row, "placeId": place_id, "coordinateSourceRevisionId": source_ids[source_key] if source_key is not None else None})
        for row in _review_rows(review_document, "stories", _STORY - {"storyId", "topicId", "placeId"} | {"stableKey", "topicKey", "placeKey"}):
            key = row.pop("stableKey")
            story_id = _stable_id("story", key)
            story_ids[key] = story_id
            topic_key = row.pop("topicKey")
            _text(topic_key, 512)
            place_key = row.pop("placeKey")
            _need(place_key is None or isinstance(place_key, str) and place_key in place_ids, "MISSING_REFERENCE")
            stories.append({**row, "storyId": story_id, "topicId": _stable_id("topic", topic_key), "placeId": place_ids[place_key] if place_key is not None else None})
        for row in _review_rows(review_document, "versions", _VERSION - {"storyVersionId", "storyId", "contentHash"} | {"storyKey"}):
            story_key = row.pop("storyKey")
            _need(isinstance(story_key, str) and story_key in story_ids, "MISSING_REFERENCE")
            story_id = story_ids[story_key]
            version_id = _stable_id("story-version", story_id, row["version"])
            paragraphs = []
            paragraph_keys = set()
            for paragraph in _rows(row["paragraphs"]):
                _obj(paragraph, _PARAGRAPH - {"paragraphId", "factRevisionIds"} | {"stableKey", "factKeys"}, "INVALID_REVIEW")
                paragraph = deepcopy(paragraph)
                key = paragraph.pop("stableKey")
                _text(key, 512)
                _need(key not in paragraph_keys, "DUPLICATE_REVIEW_KEY")
                paragraph_keys.add(key)
                refs = _take_refs(paragraph.pop("factKeys"), fact_ids)
                paragraphs.append({**paragraph, "paragraphId": _stable_id("paragraph", version_id, key), "factRevisionIds": refs})
            versions.append({**row, "storyId": story_id, "storyVersionId": version_id, "paragraphs": paragraphs,
                             "contentHash": version_content_hash(story_id, row["version"], paragraphs)})
        bundle = {"formatVersion": format_version, "sources": sources, "facts": facts, "places": places, "stories": stories, "versions": versions}
        if format_version == 2:
            bindings = []
            fields = _REGION_BINDING - {"storyId", "associationFactRevisionId"} | {"storyKey", "associationFactKey"}
            for row in _review_rows(review_document, "regionBindings", fields):
                story_key = row.pop("storyKey")
                fact_key = row.pop("associationFactKey")
                _need(isinstance(story_key, str) and story_key in story_ids
                      and isinstance(fact_key, str) and fact_key in fact_ids, "INVALID_REGION_BINDING")
                bindings.append({**row, "storyId": story_ids[story_key], "associationFactRevisionId": fact_ids[fact_key]})
            bundle["regionBindings"] = bindings
        validate_bundle(bundle, now_epoch_ms)
        return bundle
    except ContentBundleError:
        raise
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        raise ContentBundleError("INVALID_REVIEW") from None


def build_evidence_registry(bundle, allowed_device_ids: list[str], now_epoch_ms: int | None = None) -> dict:
    """Export only generation-permitted evidence for explicitly named devices."""
    validate_bundle(bundle, now_epoch_ms)
    _need(isinstance(allowed_device_ids, list) and bool(allowed_device_ids)
          and all(isinstance(value, str) and _ID.fullmatch(value) for value in allowed_device_ids)
          and len(set(allowed_device_ids)) == len(allowed_device_ids), "INVALID_DEVICE_IDS")
    sources = [
        {"sourceRevisionId": source["sourceRevisionId"], "text": source["normalizedText"],
         "allowedDeviceIds": list(allowed_device_ids), "reviewed": True}
        for source in bundle["sources"] if source["allowedUses"]["allowGeneration"]
    ]
    source_ids = {source["sourceRevisionId"] for source in sources}
    facts = [
        {"factRevisionId": fact["factRevisionId"], "factId": fact["factId"],
         "kind": "fact" if fact["kind"] == "verified_fact" else "tradition", "text": fact["statement"],
         "sourceRevisionIds": list(fact["sourceRevisionIds"])}
        for fact in bundle["facts"] if set(fact["sourceRevisionIds"]) <= source_ids
    ]
    return {"sources": sources, "facts": facts}
