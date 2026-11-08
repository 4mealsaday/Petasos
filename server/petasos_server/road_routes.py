"""Bounded, memory-only Kakao driving routes for an auxiliary route plan.

The returned road geometry is not the active Pleos navigation route, a measured
location, or collection evidence. No route/region authority is granted here.
Durations are the provider's integer-second estimates for each road; zero is
preserved, and neither total ETA nor road geometry is interpolated.

Contract: https://developers.kakaomobility.com/guide/navi-api/directions
Caching policy: https://developers.kakao.com/terms/ko/site-policies (5.20, 5.30).
This module has no disk cache or response export. Safe metadata excludes IDs,
coordinates, road names, upstream messages, credentials and request URLs.
"""
from dataclasses import dataclass
from http.client import HTTPException
import json
import math
import re
import ssl
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener

ENDPOINT = "https://apis-navi.kakaomobility.com/v1/directions"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_REQUEST_BYTES = 16 * 1024
MAX_POINTS = 200_000
MAX_ROADS = 50_000
# Local ingestion limits, not claims about provider timing precision.
MAX_DURATION_SECONDS = 7 * 24 * 60 * 60
MAX_DISTANCE_METERS = 1_500_000
_PRIORITIES = frozenset({"RECOMMEND", "TIME", "DISTANCE"})
_AVOID = frozenset({"ferries", "toll", "motorway", "schoolzone", "uturn"})
_ERRORS = frozenset({"INVALID_CONFIGURATION", "INVALID_REQUEST", "REQUEST_TOO_LARGE",
                     "NETWORK_ERROR", "HTTP_ERROR", "RESPONSE_TOO_LARGE", "INVALID_RESPONSE",
                     "UPSTREAM_ERROR", "INVALID_GEOMETRY", "GEOMETRY_LIMIT", "DISCONTINUOUS_GEOMETRY"})


class RoadRouteError(ValueError):
    """Fixed local codes only; no input, URL, token or upstream text."""
    def __init__(self, code):
        self.code = code if code in _ERRORS else "INVALID_RESPONSE"
        super().__init__(self.code)


def _need(condition, code="INVALID_RESPONSE"):
    if not condition:
        raise RoadRouteError(code)


def _coordinate(value, code="INVALID_GEOMETRY"):
    _need(type(value) in (tuple, list) and len(value) == 2, code)
    lon, lat = value
    # Korean driving API. This local acceptance envelope also rejects swapped
    # latitude/longitude and projected coordinates without guessing a CRS.
    _need(type(lon) in (int, float) and type(lat) in (int, float) and
          120 <= lon <= 135 and 30 <= lat <= 45, code)
    return (float(lon), float(lat))


def _integer(value, maximum, *, positive=False):
    _need(type(value) is int and (1 if positive else 0) <= value <= maximum)
    return value


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _need(key not in result)
        result[key] = value
    return result


def _reject_constant(_):
    raise RoadRouteError("INVALID_RESPONSE")


def _json(raw, maximum_bytes, *, request=False):
    invalid = "INVALID_REQUEST" if request else "INVALID_RESPONSE"
    _need(type(raw) is bytes, invalid)
    _need(len(raw) <= maximum_bytes, "REQUEST_TOO_LARGE" if request else "RESPONSE_TOO_LARGE")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (ValueError, TypeError, OverflowError, RecursionError):
        raise RoadRouteError(invalid) from None


@dataclass(frozen=True, repr=False)
class RoadRouteRequest:
    origin: tuple
    destination: tuple
    waypoints: tuple = ()
    priority: str = "RECOMMEND"
    avoid: tuple = ()
    origin_angle: int | None = None

    def __post_init__(self):
        object.__setattr__(self, "origin", _coordinate(self.origin, "INVALID_REQUEST"))
        object.__setattr__(self, "destination", _coordinate(self.destination, "INVALID_REQUEST"))
        _need(type(self.waypoints) in (tuple, list) and len(self.waypoints) <= 5, "INVALID_REQUEST")
        object.__setattr__(self, "waypoints", tuple(_coordinate(p, "INVALID_REQUEST") for p in self.waypoints))
        _need(type(self.priority) is str and self.priority in _PRIORITIES, "INVALID_REQUEST")
        _need(type(self.avoid) in (tuple, list) and len(self.avoid) <= len(_AVOID) and
              all(type(item) is str and item in _AVOID for item in self.avoid), "INVALID_REQUEST")
        _need(len(set(self.avoid)) == len(self.avoid), "INVALID_REQUEST")
        object.__setattr__(self, "avoid", tuple(self.avoid))
        _need(self.origin_angle is None or type(self.origin_angle) is int and 0 <= self.origin_angle <= 360,
              "INVALID_REQUEST")

    def __repr__(self):
        return "RoadRouteRequest([redacted])"


def decode_route_request(raw: bytes) -> RoadRouteRequest:
    """Local CLI format; positions are [longitude, latitude] in EPSG:4326."""
    document = _json(raw, MAX_REQUEST_BYTES, request=True)
    try:
        _need(type(document) is dict and {"origin", "destination"} <= document.keys() and
              document.keys() <= {"origin", "destination", "waypoints", "priority", "avoid", "originAngle"},
              "INVALID_REQUEST")
        return RoadRouteRequest(document["origin"], document["destination"], document.get("waypoints", ()),
                                document.get("priority", "RECOMMEND"), document.get("avoid", ()),
                                document.get("originAngle"))
    except RoadRouteError:
        raise
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        raise RoadRouteError("INVALID_REQUEST") from None


@dataclass(frozen=True, repr=False)
class RoadLink:
    coordinates: tuple
    distance_meters: int
    duration_ms: int

    def __repr__(self):
        return "RoadLink([redacted])"


@dataclass(frozen=True, repr=False)
class RoadSection:
    roads: tuple
    distance_meters: int
    duration_ms: int

    def __repr__(self):
        return "RoadSection([redacted])"


@dataclass(frozen=True, repr=False)
class AuxiliaryRoadRoute:
    request: RoadRouteRequest
    provider_request_id: str
    provider_origin: tuple
    provider_destination: tuple
    provider_waypoints: tuple
    sections: tuple
    total_distance_meters: int
    total_duration_ms: int

    @property
    def source_mode(self):
        return "AUXILIARY"

    def __repr__(self):
        return "AuxiliaryRoadRoute([redacted])"

    def safe_metadata(self):
        roads = tuple(road for section in self.sections for road in section.roads)
        return {"status": "PASS", "provider": "kakao_mobility", "sourceMode": self.source_mode,
                "coordinateReferenceSystem": "EPSG:4326", "sectionCount": len(self.sections),
                "roadCount": len(roads), "pointCount": sum(len(road.coordinates) for road in roads),
                "zeroDurationRoadCount": sum(road.duration_ms == 0 for road in roads),
                "durationResolutionMs": 1000,
                "timingTotalsMatch": (sum(section.duration_ms for section in self.sections) == self.total_duration_ms
                    and all(sum(road.duration_ms for road in section.roads) == section.duration_ms
                            for section in self.sections)),
                "distanceTotalsMatch": (sum(section.distance_meters for section in self.sections) == self.total_distance_meters
                    and all(sum(road.distance_meters for road in section.roads) == section.distance_meters
                            for section in self.sections)),
                "storedGeometry": False, "reviewStatus": "not_reviewed", "productEnabled": False,
                "activePleosRouteVerified": False, "collectionEvidence": False}


def decode_road_route(raw: bytes, request: RoadRouteRequest, *, maximum_bytes=MAX_RESPONSE_BYTES) -> AuxiliaryRoadRoute:
    _need(type(request) is RoadRouteRequest, "INVALID_REQUEST")
    _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
    document = _json(raw, maximum_bytes)
    try:
        _need(type(document) is dict)
        routes = document["routes"]
        # The request fixes alternatives=false. Never silently choose a different route.
        _need(type(routes) is list and len(routes) == 1)
        route = routes[0]
        _need(type(route) is dict and type(route.get("result_code")) is int)
        _need(route["result_code"] == 0, "UPSTREAM_ERROR")
        transaction_id = document["trans_id"]
        _need(type(transaction_id) is str and re.fullmatch(r"[\x21-\x7e]{1,128}", transaction_id) is not None)
        summary = route["summary"]
        _need(type(summary) is dict and summary.get("priority") == request.priority)
        provider_origin = _coordinate((summary["origin"]["x"], summary["origin"]["y"]))
        provider_destination = _coordinate((summary["destination"]["x"], summary["destination"]["y"]))
        waypoints = summary["waypoints"]
        _need(type(waypoints) is list and len(waypoints) == len(request.waypoints))
        provider_waypoints = tuple(_coordinate((point["x"], point["y"])) for point in waypoints)
        total_distance = _integer(summary["distance"], MAX_DISTANCE_METERS, positive=True)
        total_duration = _integer(summary["duration"], MAX_DURATION_SECONDS, positive=True) * 1000
        sections = route["sections"]
        _need(type(sections) is list and len(sections) == len(request.waypoints) + 1)
        result, point_count, road_count, previous_end = [], 0, 0, None
        for section in sections:
            _need(type(section) is dict)
            distance = _integer(section["distance"], MAX_DISTANCE_METERS)
            duration = _integer(section["duration"], MAX_DURATION_SECONDS) * 1000
            roads = section["roads"]
            _need(type(roads) is list and 0 < len(roads) <= MAX_ROADS, "GEOMETRY_LIMIT")
            road_count += len(roads)
            _need(road_count <= MAX_ROADS, "GEOMETRY_LIMIT")
            section_roads = []
            for road in roads:
                _need(type(road) is dict)
                road_distance = _integer(road["distance"], MAX_DISTANCE_METERS)
                # The API specifies Int seconds without a positive minimum. A
                # zero estimate is retained, never changed into a positive ETA.
                road_duration = _integer(road["duration"], MAX_DURATION_SECONDS) * 1000
                vertexes = road["vertexes"]
                _need(type(vertexes) is list and len(vertexes) >= 4 and len(vertexes) % 2 == 0,
                      "INVALID_GEOMETRY")
                point_count += len(vertexes) // 2
                _need(point_count <= MAX_POINTS, "GEOMETRY_LIMIT")
                coordinates = tuple(_coordinate(vertexes[index:index + 2]) for index in range(0, len(vertexes), 2))
                _need(any(a != b for a, b in zip(coordinates, coordinates[1:])), "INVALID_GEOMETRY")
                # Do not bridge missing shapes or silently snap provider points.
                _need(previous_end is None or previous_end == coordinates[0], "DISCONTINUOUS_GEOMETRY")
                previous_end = coordinates[-1]
                section_roads.append(RoadLink(coordinates, road_distance, road_duration))
            result.append(RoadSection(tuple(section_roads), distance, duration))
        # Aggregate values can differ through provider rounding; preserve all
        # levels and expose consistency instead of redistributing a total ETA.
        return AuxiliaryRoadRoute(request, transaction_id, provider_origin, provider_destination,
                                  provider_waypoints, tuple(result), total_distance, total_duration)
    except RoadRouteError:
        raise
    except (ValueError, TypeError, KeyError, IndexError, AttributeError, OverflowError, RecursionError):
        raise RoadRouteError("INVALID_RESPONSE") from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class KakaoRoadRouteClient:
    """One explicit auxiliary-route query; no retries, redirects, proxies or cache."""
    def __init__(self, key: str, *, timeout=10, maximum_bytes=MAX_RESPONSE_BYTES, opener=None):
        _need(type(key) is str and 0 < len(key) <= 4096 and
              all(33 <= ord(c) <= 126 for c in key), "INVALID_CONFIGURATION")
        _need(type(timeout) in (int, float) and 0 < timeout <= 60 and math.isfinite(timeout), "INVALID_CONFIGURATION")
        _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
        self._key, self._timeout, self._maximum_bytes = key, timeout, maximum_bytes
        self._opener = opener or build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())

    def __repr__(self):
        return "KakaoRoadRouteClient([redacted])"

    def prepare_request(self, request: RoadRouteRequest):
        _need(type(request) is RoadRouteRequest, "INVALID_REQUEST")
        # Python's shortest round-trip float representation avoids silently
        # trimming a caller's coordinate precision in the provider request.
        position = lambda point: repr(point[0]).removesuffix(".0") + "," + repr(point[1]).removesuffix(".0")
        origin = position(request.origin)
        if request.origin_angle is not None:
            origin += ",angle=" + str(request.origin_angle)
        query = {"origin": origin, "destination": position(request.destination), "priority": request.priority,
                 "summary": "false", "alternatives": "false", "road_details": "false"}
        if request.waypoints:
            query["waypoints"] = "|".join(position(point) for point in request.waypoints)
        if request.avoid:
            query["avoid"] = "|".join(request.avoid)
        return Request(ENDPOINT + "?" + urlencode(query), method="GET", headers={
            "Authorization": "KakaoAK " + self._key, "Content-Type": "application/json",
            "Accept": "application/json", "User-Agent": "PetasosAuxiliaryRouteReview/0.8"})

    def lookup(self, request: RoadRouteRequest) -> AuxiliaryRoadRoute:
        prepared = self.prepare_request(request)
        try:
            with self._opener.open(prepared, timeout=self._timeout) as response:
                _need(response.status == 200, "HTTP_ERROR")
                raw = response.read(self._maximum_bytes + 1)
        except HTTPError as error:
            error.close()
            raise RoadRouteError("HTTP_ERROR") from None
        except RoadRouteError:
            raise
        except (URLError, TimeoutError, OSError, ValueError, HTTPException):
            raise RoadRouteError("NETWORK_ERROR") from None
        return decode_road_route(raw, request, maximum_bytes=self._maximum_bytes)
