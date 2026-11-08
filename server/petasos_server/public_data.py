"""Bounded official tourism API reads; every normalized record still needs review.

This module does not install content, resolve evidence, fetch media, or grant any
permission to display, distribute, or send provider text to a generation model.
"""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat

from .tourism_api import TourismApiError, TourismClient, decode_response

MAX_RESPONSE_BYTES = 512 * 1024
ENDPOINTS = {
    "odii": "https://apis.data.go.kr/B551011/Odii/storyBasedList",
    "tourapi_list": "https://apis.data.go.kr/B551011/KorService2/areaBasedList2",
    "tourapi_detail": "https://apis.data.go.kr/B551011/KorService2/detailCommon2",
}
OPERATIONS = {
    "odii": ("odii", "storyBasedList"),
    "tourapi_list": ("tourapi", "areaBasedList2"),
    "tourapi_detail": ("tourapi", "detailCommon2"),
}
CATALOG_URLS = {
    "odii": "https://www.data.go.kr/data/15101971/openapi.do",
    "tourapi": "https://www.data.go.kr/data/15101578/openapi.do",
}
KEY_NAMES = {"odii": "PETASOS_ODII_SERVICE_KEY", "tourapi": "PETASOS_TOURAPI_SERVICE_KEY"}
ID_PATTERN = re.compile(r"[0-9]{1,24}")
CODE_PATTERN = re.compile(r"(?:[0-9]{2}|[0-9]{4})")


class PublicDataError(ValueError):
    """Only fixed error codes and a validated numerical API result code escape."""

    def __init__(self, code, result_code=None):
        self.code = code
        self.result_code = result_code if isinstance(result_code, str) and CODE_PATTERN.fullmatch(result_code) else None
        super().__init__(code)


def _sample_request(endpoint_name, key, content_id=None):
    if endpoint_name not in OPERATIONS:
        raise PublicDataError("INVALID_ENDPOINT")
    if not isinstance(key, str) or not key or len(key) > 4096:
        raise PublicDataError("INVALID_SERVICE_KEY")
    parameters = {"numOfRows": 1, "pageNo": 1}
    if endpoint_name == "tourapi_list":
        parameters["contentTypeId"] = "12"
    elif endpoint_name == "tourapi_detail":
        if not isinstance(content_id, str) or not ID_PATTERN.fullmatch(content_id):
            raise PublicDataError("INVALID_RECORD_ID")
        parameters["contentId"] = content_id
    service, operation = OPERATIONS[endpoint_name]
    return service, operation, parameters


def request_url(endpoint_name, key, content_id=None):
    """Compatibility helper; the returned authenticated URL must never be logged."""
    service, operation, parameters = _sample_request(endpoint_name, key, content_id)
    try:
        return TourismClient(service, key).prepare_request(operation, parameters, response_format="json").full_url
    except TourismApiError as error:
        raise PublicDataError(error.code, error.result_code) from None


def _single_record(response):
    """The shared client supports lists; this bounded sampler deliberately does not."""
    if len(response.items) > 1:
        raise PublicDataError("INVALID_RESPONSE")
    return list(response.items)


def get_records(endpoint_name, key, content_id=None):
    """Use the official-contract client, retaining the one-row sampler boundary."""
    service, operation, parameters = _sample_request(endpoint_name, key, content_id)
    try:
        return _single_record(TourismClient(service, key, max_response_bytes=MAX_RESPONSE_BYTES).request(operation, parameters, response_format="json"))
    except TourismApiError as error:
        raise PublicDataError(error.code, error.result_code) from None


def decode_records(raw):
    """Compatibility wrapper for JSON/XML envelopes, limited to one candidate."""
    if not isinstance(raw, bytes):
        raise PublicDataError("INVALID_RESPONSE")
    if len(raw) > MAX_RESPONSE_BYTES:
        raise PublicDataError("RESPONSE_TOO_LARGE")
    try:
        return _single_record(decode_response(raw))
    except TourismApiError as error:
        raise PublicDataError(error.code, error.result_code) from None


def _text(record, key, limit, required=False):
    value = record.get(key, "")
    if value is None:
        value = ""
    if not isinstance(value, str) or len(value) > limit or "\x00" in value or any(0xD800 <= ord(char) <= 0xDFFF for char in value):
        raise PublicDataError("INVALID_RECORD_TEXT")
    if required and not value.strip():
        raise PublicDataError("INCOMPLETE_RECORD")
    return value


def _id(record, key, required=True):
    value = record.get(key, "")
    if isinstance(value, int) and not isinstance(value, bool):
        value = str(value)
    if value in (None, "") and not required:
        return None
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise PublicDataError("INVALID_RECORD_ID")
    return value


def _coordinates(record, longitude_field, latitude_field):
    values = [record.get(longitude_field), record.get(latitude_field)]
    result = {"status": "pending", "datum": "unverified", "longitude": None, "latitude": None}
    if all(value in (None, "") for value in values):
        result["status"] = "missing"
        return result
    try:
        if any(isinstance(value, bool) or not isinstance(value, (str, int, float)) for value in values):
            raise ValueError()
        longitude, latitude = map(float, values)
        if not all(math.isfinite(value) for value in (longitude, latitude)) or not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
            raise ValueError()
    except (ValueError, TypeError, OverflowError):
        result["status"] = "invalid"
        return result
    result.update(longitude=longitude, latitude=latitude)
    return result


def canonical_bytes(document):
    return json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def normalize_record(service, record, fetched_at):
    """Whitelist text/identity metadata, but never upgrade it to verified evidence."""
    if service not in CATALOG_URLS or not isinstance(record, dict):
        raise PublicDataError("INVALID_RECORD")
    if service == "odii":
        identifiers = {key: _id(record, key, required=key in {"stid", "stlid"}) for key in ("tid", "tlid", "stid", "stlid")}
        fields = {"title": _text(record, "title", 500, required=True), "audioTitle": _text(record, "audioTitle", 500),
                  "script": _text(record, "script", 64000), "langCode": _text(record, "langCode", 16),
                  "createdtime": _text(record, "createdtime", 32), "modifiedtime": _text(record, "modifiedtime", 32)}
        coordinates = _coordinates(record, "mapX", "mapY")
        missing = [] if fields["script"].strip() else ["script"]
        endpoint = ENDPOINTS["odii"]
    else:
        identifiers = {"contentid": _id(record, "contentid"), "contenttypeid": _id(record, "contenttypeid", required=False)}
        fields = {"title": _text(record, "title", 500, required=True), "overview": _text(record, "overview", 64000),
                  "addr1": _text(record, "addr1", 1000), "addr2": _text(record, "addr2", 1000),
                  "createdtime": _text(record, "createdtime", 32), "modifiedtime": _text(record, "modifiedtime", 32),
                  "cpyrhtDivCd": _text(record, "cpyrhtDivCd", 32)}
        fields.update({key: _text(record, key, 32) for key in ("lDongRegnCd", "lDongSignguCd", "lclsSystm1", "lclsSystm2", "lclsSystm3")})
        coordinates = _coordinates(record, "mapx", "mapy")
        missing = [] if fields["overview"].strip() else ["overview"]
        endpoint = ENDPOINTS["tourapi_detail"]
    source_record = {"service": service, "identifiers": identifiers, "fields": fields, "coordinates": coordinates}
    return {
        **source_record, "recordRevisionSha256": hashlib.sha256(canonical_bytes(source_record)).hexdigest(),
        "source": {"catalogUrl": CATALOG_URLS[service], "endpoint": endpoint, "fetchedAtUTC": fetched_at},
        "review_status": "pending", "allowGeneration": False, "allowDisplay": False, "allowDistribution": False,
        "completeness": {"status": "incomplete" if missing else "complete", "missingFields": missing},
    }


def collect_candidates(environment, fetched_at, services=("odii", "tourapi"), fetch=get_records):
    """No retries: all services together make at most three allowlisted GETs."""
    if len(services) != len(set(services)) or not set(services) <= KEY_NAMES.keys():
        raise PublicDataError("INVALID_SERVICE")
    records, results = [], []
    for service in services:
        key = environment.get(KEY_NAMES[service]) or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY")
        if not key:
            results.append({"service": service, "status": "MISSING_KEY", "recordCount": 0})
            continue
        try:
            rows = fetch("odii" if service == "odii" else "tourapi_list", key)
            if service == "tourapi" and rows:
                content_id = _id(rows[0], "contentid")
                rows = fetch("tourapi_detail", key, content_id)
                if rows and _id(rows[0], "contentid") != content_id:
                    raise PublicDataError("RECORD_ID_MISMATCH")
            if not rows:
                results.append({"service": service, "status": "NO_DATA", "recordCount": 0})
                continue
            if len(rows) != 1:
                raise PublicDataError("INVALID_RESPONSE")
            record = normalize_record(service, rows[0], fetched_at)
            records.append(record)
            results.append({"service": service, "status": "PENDING_REVIEW" if record["completeness"]["status"] == "complete" else "INCOMPLETE_PENDING_REVIEW", "recordCount": 1})
        except PublicDataError as error:
            result = {"service": service, "status": error.code, "recordCount": 0}
            if error.result_code is not None:
                result["resultCode"] = error.result_code
            results.append(result)
    return {"formatVersion": 1, "kind": "petasos-public-data-review-candidates", "records": records}, results


def write_private_candidates(directory, filename, document, private_root=None):
    """Create a private directory/file, reject symlinks and existing output files."""
    directory = Path(directory)
    private_root = Path(private_root) if private_root is not None else directory
    if not re.fullmatch(r"[a-zA-Z0-9_-]+\.json", filename):
        raise PublicDataError("INVALID_OUTPUT_NAME")
    try:
        relative = directory.relative_to(private_root)
        if ".." in relative.parts:
            raise PublicDataError("OUTPUT_DIRECTORY_NOT_PRIVATE")
        # Every ancestor is checked for redirects before any writes are made.
        if any(parent.is_symlink() for parent in (directory, *directory.parents)):
            raise PublicDataError("OUTPUT_DIRECTORY_NOT_PRIVATE")
        chain = [private_root]
        for part in relative.parts:
            chain.append(chain[-1] / part)
        for component in chain:
            try:
                component.mkdir(mode=0o700)
            except FileExistsError:
                pass
            info = component.lstat()
            if not stat.S_ISDIR(info.st_mode) or (os.name == "posix" and (info.st_uid != os.geteuid() or info.st_mode & 0o077)):
                raise PublicDataError("OUTPUT_DIRECTORY_NOT_PRIVATE")
        path = directory / filename
        data = canonical_bytes(document) + b"\n"
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        return path, hashlib.sha256(data).hexdigest()
    except PublicDataError:
        raise
    except FileExistsError:
        raise PublicDataError("OUTPUT_ALREADY_EXISTS") from None
    except (OSError, ValueError, TypeError):
        raise PublicDataError("OUTPUT_WRITE_FAILED") from None
