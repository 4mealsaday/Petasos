"""Memory-only NAVER Maps Directions 5 auxiliary-route adapter.

Official contract: https://api.ncloud-docs.com/docs/application-maps-directions5
Authentication: https://api.ncloud-docs.com/docs/application-maps-overview

Uses the new Maps origin, not the legacy naveropenapi.apigw.ntruss.com origin.
An external route is not the active Pleos route, measured location, or collection
proof. Guide durations describe spans between path indexes; section describes
only some roads and is never used to fill missing timing. No inferred tail,
uniform total-ETA redistribution, disk cache, response export or logging.
"""
from dataclasses import dataclass
from http.client import HTTPException
import json
import math
import ssl
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener

ENDPOINT = "https://maps.apigw.ntruss.com/map-direction/v1/driving"
MAX_REQUEST_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_POINTS = 200_000
MAX_GUIDES = 50_000
# Bounded ingestion limits, not statements about provider accuracy or precision.
MAX_DURATION_MS = 7 * 24 * 60 * 60 * 1000
MAX_DISTANCE_METERS = 10_000_000
_OPTIONS = frozenset({"trafast", "tracomfort", "traoptimal", "traavoidtoll", "traavoidcaronly"})
_ERRORS = frozenset({"INVALID_CONFIGURATION", "INVALID_REQUEST", "REQUEST_TOO_LARGE",
                     "NETWORK_ERROR", "HTTP_ERROR", "RESPONSE_TOO_LARGE", "INVALID_RESPONSE",
                     "UPSTREAM_ERROR", "INVALID_GEOMETRY", "GEOMETRY_LIMIT", "INVALID_TIMING"})


class NaverRouteError(ValueError):
    """Fixed local codes; never includes credentials, coordinates or upstream text."""
    def __init__(self, code):
        self.code = code if code in _ERRORS else "INVALID_RESPONSE"
        super().__init__(self.code)


def _need(condition, code="INVALID_RESPONSE"):
    if not condition:
        raise NaverRouteError(code)


def _coordinate(value, code="INVALID_GEOMETRY"):
    _need(type(value) in (list, tuple) and len(value) == 2, code)
    lon, lat = value
    # Explicit Korean API acceptance envelope: swapped axes/projected values
    # are rejected, never guessed or automatically reprojected.
    _need(type(lon) in (int, float) and type(lat) in (int, float) and
          120 <= lon <= 135 and 30 <= lat <= 45, code)
    return (float(lon), float(lat))


def _integer(value, maximum, *, positive=False, code="INVALID_RESPONSE"):
    _need(type(value) is int and (1 if positive else 0) <= value <= maximum, code)
    return value


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _need(key not in result)
        result[key] = value
    return result


def _reject_constant(_):
    raise NaverRouteError("INVALID_RESPONSE")


def _json(raw, maximum_bytes, *, request=False):
    invalid = "INVALID_REQUEST" if request else "INVALID_RESPONSE"
    _need(type(raw) is bytes, invalid)
    _need(len(raw) <= maximum_bytes, "REQUEST_TOO_LARGE" if request else "RESPONSE_TOO_LARGE")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (ValueError, TypeError, OverflowError, RecursionError):
        raise NaverRouteError(invalid) from None


@dataclass(frozen=True, repr=False)
class NaverRouteRequest:
    origin: tuple
    destination: tuple
    waypoints: tuple = ()
    option: str = "traoptimal"

    def __post_init__(self):
        object.__setattr__(self, "origin", _coordinate(self.origin, "INVALID_REQUEST"))
        object.__setattr__(self, "destination", _coordinate(self.destination, "INVALID_REQUEST"))
        _need(type(self.waypoints) in (tuple, list) and len(self.waypoints) <= 5, "INVALID_REQUEST")
        object.__setattr__(self, "waypoints", tuple(_coordinate(point, "INVALID_REQUEST") for point in self.waypoints))
        # One explicitly selected route option and one destination, not the API's
        # multi-option/multi-goal alternatives requiring a separate selection.
        _need(type(self.option) is str and self.option in _OPTIONS, "INVALID_REQUEST")

    def __repr__(self):
        return "NaverRouteRequest([redacted])"


def decode_naver_request(raw: bytes) -> NaverRouteRequest:
    """CLI format {origin:[lon,lat],destination:[lon,lat],waypoints?:[],option?:...}."""
    document = _json(raw, MAX_REQUEST_BYTES, request=True)
    try:
        _need(type(document) is dict and {"origin", "destination"} <= document.keys() and
              document.keys() <= {"origin", "destination", "waypoints", "option"}, "INVALID_REQUEST")
        return NaverRouteRequest(document["origin"], document["destination"], document.get("waypoints", ()),
                                 document.get("option", "traoptimal"))
    except NaverRouteError:
        raise
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        raise NaverRouteError("INVALID_REQUEST") from None


@dataclass(frozen=True, repr=False)
class NaverTimingSegment:
    start_point_index: int
    end_point_index: int
    duration_ms: int
    distance_meters: int

    def __repr__(self):
        return "NaverTimingSegment([redacted])"


@dataclass(frozen=True, repr=False)
class AuxiliaryNaverRoute:
    request: NaverRouteRequest
    provider_origin: tuple
    provider_destination: tuple
    path: tuple
    timing_segments: tuple
    total_distance_meters: int
    total_duration_ms: int

    @property
    def source_mode(self):
        return "AUXILIARY"

    @property
    def timing_coverage_complete(self):
        return bool(self.timing_segments) and self.timing_segments[-1].end_point_index == len(self.path) - 1

    @property
    def timing_totals_match(self):
        return sum(segment.duration_ms for segment in self.timing_segments) == self.total_duration_ms

    @property
    def timing_usable(self):
        """Structural timing usability only; grants no active-route authority.

        An origin guide with index 0/duration 0 is harmless. A positive-length
        path span with 0 ms, or a timed wait on the same index, needs a separate
        timing interpretation and is not admitted to geometry interpolation.
        """
        return self.timing_coverage_complete and self.timing_totals_match and all(
            (segment.start_point_index == segment.end_point_index and segment.duration_ms == 0) or
            (segment.start_point_index < segment.end_point_index and segment.duration_ms > 0 and
             any(a != b for a, b in zip(self.path[segment.start_point_index:segment.end_point_index],
                                       self.path[segment.start_point_index + 1:segment.end_point_index + 1])))
            for segment in self.timing_segments)

    def __repr__(self):
        return "AuxiliaryNaverRoute([redacted])"

    def safe_metadata(self):
        last_index = self.timing_segments[-1].end_point_index if self.timing_segments else 0
        return {"status": "PASS", "provider": "naver_maps", "service": "directions5",
                "sourceMode": self.source_mode, "coordinateReferenceSystem": "EPSG:4326",
                "pointCount": len(self.path), "guideCount": len(self.timing_segments),
                "timingCoverageComplete": self.timing_coverage_complete,
                "timingTotalsMatch": self.timing_totals_match, "timingUsable": self.timing_usable,
                "distanceTotalsMatch": sum(s.distance_meters for s in self.timing_segments) == self.total_distance_meters,
                "zeroDurationSegmentCount": sum(s.duration_ms == 0 for s in self.timing_segments),
                "zeroDurationTravelSegmentCount": sum(s.duration_ms == 0 and s.end_point_index > s.start_point_index
                                                       for s in self.timing_segments),
                "stationaryTimedSegmentCount": sum(s.duration_ms > 0 and s.end_point_index == s.start_point_index
                                                    for s in self.timing_segments),
                "unknownTailPointCount": len(self.path) - 1 - last_index,
                "storedGeometry": False, "reviewStatus": "not_reviewed", "productEnabled": False,
                "activePleosRouteVerified": False, "collectionEvidence": False}


def decode_naver_route(raw: bytes, request: NaverRouteRequest, *, maximum_bytes=MAX_RESPONSE_BYTES) -> AuxiliaryNaverRoute:
    _need(type(request) is NaverRouteRequest, "INVALID_REQUEST")
    _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
    document = _json(raw, maximum_bytes)
    try:
        _need(type(document) is dict and type(document.get("code")) is int)
        _need(document["code"] == 0, "UPSTREAM_ERROR")
        routes = document["route"]
        _need(type(routes) is dict and set(routes) == {request.option})
        selected = routes[request.option]
        _need(type(selected) is list and len(selected) == 1)
        route = selected[0]
        _need(type(route) is dict)
        summary = route["summary"]
        _need(type(summary) is dict)
        provider_origin = _coordinate(summary["start"]["location"])
        provider_destination = _coordinate(summary["goal"]["location"])
        total_distance = _integer(summary["distance"], MAX_DISTANCE_METERS, positive=True)
        total_duration = _integer(summary["duration"], MAX_DURATION_MS, positive=True)
        supplied_path = route["path"]
        _need(type(supplied_path) is list and 2 <= len(supplied_path) <= MAX_POINTS, "GEOMETRY_LIMIT")
        path = tuple(_coordinate(point) for point in supplied_path)
        _need(any(a != b for a, b in zip(path, path[1:])), "INVALID_GEOMETRY")
        guides = route.get("guide", [])
        _need(type(guides) is list and len(guides) <= MAX_GUIDES, "INVALID_TIMING")
        segments, previous = [], 0
        for guide in guides:
            _need(type(guide) is dict, "INVALID_TIMING")
            index = _integer(guide["pointIndex"], len(path) - 1, code="INVALID_TIMING")
            _need(index >= previous, "INVALID_TIMING")
            duration = _integer(guide["duration"], MAX_DURATION_MS, code="INVALID_TIMING")
            distance = _integer(guide["distance"], MAX_DISTANCE_METERS, code="INVALID_TIMING")
            segments.append(NaverTimingSegment(previous, index, duration, distance))
            previous = index
        # Do not invent an arrival guide, a missing final duration, or an ETA
        # from summary/partial section.speed values. Preserve provider estimates.
        return AuxiliaryNaverRoute(request, provider_origin, provider_destination, path, tuple(segments),
                                   total_distance, total_duration)
    except NaverRouteError:
        raise
    except (ValueError, TypeError, KeyError, IndexError, AttributeError, OverflowError, RecursionError):
        raise NaverRouteError("INVALID_RESPONSE") from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class NaverDirectionsClient:
    """One explicit request to the new Maps service; no retries or disk storage."""
    def __init__(self, key_id: str, key: str, *, timeout=10, maximum_bytes=MAX_RESPONSE_BYTES, opener=None):
        for credential in (key_id, key):
            _need(type(credential) is str and 0 < len(credential) <= 4096 and
                  all(33 <= ord(char) <= 126 for char in credential), "INVALID_CONFIGURATION")
        _need(type(timeout) in (int, float) and 0 < timeout <= 60 and math.isfinite(timeout), "INVALID_CONFIGURATION")
        _need(type(maximum_bytes) is int and 0 < maximum_bytes <= MAX_RESPONSE_BYTES, "INVALID_CONFIGURATION")
        self._key_id, self._key = key_id, key
        self._timeout, self._maximum_bytes = timeout, maximum_bytes
        self._opener = opener or build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())

    def __repr__(self):
        return "NaverDirectionsClient([redacted])"

    def prepare_request(self, request: NaverRouteRequest):
        _need(type(request) is NaverRouteRequest, "INVALID_REQUEST")
        position = lambda point: repr(point[0]).removesuffix(".0") + "," + repr(point[1]).removesuffix(".0")
        query = {"start": position(request.origin), "goal": position(request.destination),
                 "option": request.option, "lang": "ko"}
        if request.waypoints:
            query["waypoints"] = "|".join(position(point) for point in request.waypoints)
        return Request(ENDPOINT + "?" + urlencode(query), method="GET", headers={
            "x-ncp-apigw-api-key-id": self._key_id, "x-ncp-apigw-api-key": self._key,
            "Accept": "application/json", "User-Agent": "PetasosAuxiliaryRouteReview/0.8"})

    def lookup(self, request: NaverRouteRequest) -> AuxiliaryNaverRoute:
        prepared = self.prepare_request(request)
        try:
            with self._opener.open(prepared, timeout=self._timeout) as response:
                _need(response.status == 200, "HTTP_ERROR")
                raw = response.read(self._maximum_bytes + 1)
        except HTTPError as error:
            error.close()
            raise NaverRouteError("HTTP_ERROR") from None
        except NaverRouteError:
            raise
        except (URLError, TimeoutError, OSError, ValueError, HTTPException):
            raise NaverRouteError("NETWORK_ERROR") from None
        return decode_naver_route(raw, request, maximum_bytes=self._maximum_bytes)
