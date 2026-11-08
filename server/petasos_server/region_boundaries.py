"""Bounded, memory-only VWorld 2.0 legal si/gun/gu boundary lookup.

An API response is not a reviewed offline dataset. In particular, neither a
successful request nor the data.go.kr catalogue license authorizes caching this
API response, installing regions, granting collection cards, or publishing it.
"""
from dataclasses import dataclass, field
from http.client import HTTPException
import json
import math
import re
import ssl
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener

ENDPOINT = "https://api.vworld.kr/req/data"
LAYER = "LT_C_ADSIGG_INFO"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_POINTS = 200_000
_ERRORS = frozenset({"INVALID_CONFIGURATION", "INVALID_REGION_CODE", "NETWORK_ERROR", "HTTP_ERROR",
                     "RESPONSE_TOO_LARGE", "INVALID_RESPONSE", "UPSTREAM_ERROR", "REGION_NOT_FOUND",
                     "AMBIGUOUS_REGION", "UNSUPPORTED_CRS", "INVALID_GEOMETRY", "GEOMETRY_LIMIT"})


class BoundaryApiError(ValueError):
    """Only fixed local codes escape; never URLs, credentials or upstream text."""
    def __init__(self, code):
        self.code = code if code in _ERRORS else "INVALID_RESPONSE"
        super().__init__(self.code)


def _need(condition, code="INVALID_RESPONSE"):
    if not condition:
        raise BoundaryApiError(code)


def _code(value):
    _need(isinstance(value, str) and re.fullmatch(r"[0-9]{5}", value) is not None, "INVALID_REGION_CODE")


def _number(value):
    _need(type(value) is int or isinstance(value, str) and re.fullmatch(r"[0-9]{1,9}", value) is not None)
    result = int(value)
    _need(0 <= result <= 1_000_000_000)
    return result


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _need(key not in result)
        result[key] = value
    return result


def _reject_constant(_):
    raise BoundaryApiError("INVALID_RESPONSE")


def _text(value):
    _need(isinstance(value, str) and 0 < len(value.strip()) <= 500 and
          not any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value))
    return value


@dataclass(frozen=True, repr=False)
class BoundaryResponse:
    code: str
    name: str
    geometry_type: str
    # Immutable Polygon/MultiPolygon normalized to polygons -> rings -> lon/lat.
    polygons: tuple = field(repr=False)

    def __repr__(self):
        return "BoundaryResponse([redacted])"

    def safe_metadata(self):
        return {"status": "PASS", "layer": LAYER, "coordinateReferenceSystem": "EPSG:4326",
                "geometryType": self.geometry_type, "polygonCount": len(self.polygons),
                "ringCount": sum(len(polygon) for polygon in self.polygons),
                "pointCount": sum(len(ring) for polygon in self.polygons for ring in polygon),
                "storedGeometry": False, "reviewStatus": "not_reviewed", "productEnabled": False}


def _geometry(value):
    _need(isinstance(value, dict), "INVALID_GEOMETRY")
    kind, coordinates = value.get("type"), value.get("coordinates")
    _need(kind in ("Polygon", "MultiPolygon") and isinstance(coordinates, list), "INVALID_GEOMETRY")
    polygons = [coordinates] if kind == "Polygon" else coordinates
    _need(0 < len(polygons) <= 4096, "GEOMETRY_LIMIT")
    result, point_count = [], 0
    for polygon in polygons:
        _need(isinstance(polygon, list) and 0 < len(polygon) <= 4096, "INVALID_GEOMETRY")
        rings = []
        for ring in polygon:
            _need(isinstance(ring, list) and 4 <= len(ring) <= MAX_POINTS, "INVALID_GEOMETRY")
            point_count += len(ring)
            _need(point_count <= MAX_POINTS, "GEOMETRY_LIMIT")
            positions = []
            for point in ring:
                _need(isinstance(point, list) and len(point) == 2 and
                      all(type(n) in (int, float) and math.isfinite(n) for n in point), "INVALID_GEOMETRY")
                lon, lat = point
                _need(120 <= lon <= 135 and 30 <= lat <= 45, "INVALID_GEOMETRY")
                positions.append((lon, lat))
            _need(positions[0] == positions[-1] and len(set(positions[:-1])) >= 3, "INVALID_GEOMETRY")
            _need(all(a != b for a, b in zip(positions, positions[1:])), "INVALID_GEOMETRY")
            rings.append(tuple(positions))
        result.append(tuple(rings))
    # Structural validation only. ReviewedPolygonBoundaryLookup performs topology
    # checks later, after a separately licensed dataset has been reviewed.
    return kind, tuple(result)


def decode_boundary(raw: bytes, requested_code: str, *, maximum_bytes=MAX_RESPONSE_BYTES) -> BoundaryResponse:
    _code(requested_code)
    _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
    _need(isinstance(raw, bytes))
    _need(len(raw) <= maximum_bytes, "RESPONSE_TOO_LARGE")
    try:
        document = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        response = document["response"]
        _need(isinstance(response, dict))
        status = response.get("status")
        if status == "ERROR":
            raise BoundaryApiError("UPSTREAM_ERROR")
        if status == "NOT_FOUND":
            raise BoundaryApiError("REGION_NOT_FOUND")
        _need(status == "OK")
        _need(_number(response["record"]["total"]) == 1 and _number(response["record"]["current"]) == 1,
              "AMBIGUOUS_REGION")
        _need(_number(response["page"]["current"]) == 1 and _number(response["page"]["total"]) == 1)
        collection = response["result"]["featureCollection"]
        _need(collection["type"] == "FeatureCollection")
        # VWorld documents EPSG:4326 explicitly as [longitude, latitude]. Never
        # silently accept another CRS (such as EPSG:900913) or reproject uncertain input.
        crs = collection.get("crs")
        if crs is not None:
            _need(isinstance(crs, dict) and crs.get("type") == "name" and
                  crs.get("properties", {}).get("name") in
                  ("EPSG:4326", "urn:ogc:def:crs:EPSG::4326"), "UNSUPPORTED_CRS")
        features = collection["features"]
        _need(isinstance(features, list) and len(features) == 1, "AMBIGUOUS_REGION")
        feature = features[0]
        _need(feature["type"] == "Feature")
        properties = feature["properties"]
        _need(properties["sig_cd"] == requested_code, "AMBIGUOUS_REGION")
        name = _text(properties["sig_kor_nm"])
        kind, polygons = _geometry(feature["geometry"])
        return BoundaryResponse(requested_code, name, kind, polygons)
    except BoundaryApiError:
        raise
    except (ValueError, TypeError, KeyError, IndexError, AttributeError, OverflowError, RecursionError):
        raise BoundaryApiError("INVALID_RESPONSE") from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class VWorldBoundaryClient:
    """One exact code lookup. No device location, implicit paging, retry or disk cache."""
    def __init__(self, key: str, domain: str | None = None, *, timeout=10, maximum_bytes=MAX_RESPONSE_BYTES, opener=None):
        _need(isinstance(key, str) and 0 < len(key) <= 4096 and
              not any(ord(c) < 33 or ord(c) > 126 for c in key), "INVALID_CONFIGURATION")
        if domain is not None and domain != "":
            _need(isinstance(domain, str) and 0 < len(domain) <= 2048 and
                  not any(ord(c) < 33 or ord(c) > 126 for c in domain), "INVALID_CONFIGURATION")
            try:
                parsed = urlsplit(domain)
                _need(parsed.scheme in ("http", "https") and bool(parsed.hostname) and parsed.username is None and
                      parsed.password is None and not parsed.query and not parsed.fragment, "INVALID_CONFIGURATION")
                parsed.port
            except ValueError:
                raise BoundaryApiError("INVALID_CONFIGURATION") from None
        _need(type(timeout) in (int, float) and 0 < timeout <= 60 and math.isfinite(timeout), "INVALID_CONFIGURATION")
        _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
        self._key, self._domain = key, domain
        self._timeout, self._maximum_bytes = timeout, maximum_bytes
        self._opener = opener or build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())

    def __repr__(self):
        return "VWorldBoundaryClient([redacted])"

    def prepare_request(self, region_code):
        _code(region_code)
        query = {"service": "data", "version": "2.0", "request": "GetFeature", "data": LAYER,
                 "key": self._key, "format": "json", "errorFormat": "json",
                 "geometry": "true", "attribute": "true", "crs": "EPSG:4326", "size": "2", "page": "1",
                 "attrFilter": "sig_cd:=:" + region_code}
        if self._domain:
            query["domain"] = self._domain
        return Request(ENDPOINT + "?" + urlencode(query), method="GET",
                       headers={"Accept": "application/json", "User-Agent": "PetasosBoundaryReview/0.8"})

    def lookup(self, region_code) -> BoundaryResponse:
        request = self.prepare_request(region_code)
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                _need(response.status == 200, "HTTP_ERROR")
                raw = response.read(self._maximum_bytes + 1)
        except HTTPError as error:
            error.close()
            raise BoundaryApiError("HTTP_ERROR") from None
        except BoundaryApiError:
            raise
        except (URLError, TimeoutError, OSError, ValueError, HTTPException):
            raise BoundaryApiError("NETWORK_ERROR") from None
        return decode_boundary(raw, region_code, maximum_bytes=self._maximum_bytes)
