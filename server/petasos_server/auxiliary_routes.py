"""Authenticated, memory-only auxiliary road forecasts; no native-route authority.

The async transport reuses the NAVER request builder and strict response parser.
No route result, coordinate, URL, credential or upstream exception is logged or
cached. Results survive only in the current request/response, never the generation
service's evidence, prompts, idempotency entries, or persistence layer.
"""
import asyncio
from collections import Counter, defaultdict, deque
import json
import math
import time

import httpx

from .contracts import Failure
from .naver_routes import (
    AuxiliaryNaverRoute, MAX_GUIDES, MAX_POINTS, MAX_RESPONSE_BYTES,
    NaverDirectionsClient, NaverRouteError, NaverRouteRequest, decode_naver_route,
)

MAX_WIRE_BYTES = 8 * 1024 * 1024


def serialize_auxiliary_route(route, request):
    """One bounded response; never truncate geometry or fill missing timing."""
    if (type(route) is not AuxiliaryNaverRoute or type(request) is not NaverRouteRequest or
            route.request != request or not 2 <= len(route.path) <= MAX_POINTS or
            len(route.timing_segments) > MAX_GUIDES):
        raise Failure("INVALID_ROUTE_RESPONSE", 502)
    try:
        value = {"schemaVersion": "0.8", "status": "ok", "provider": "naver_maps", "service": "directions5",
                 "sourceMode": "AUXILIARY", "coordinateReferenceSystem": "EPSG:4326",
                 "activePleosRouteVerified": False, "collectionEvidence": False,
                 "path": route.path,
                 "timingSegments": [{"startPointIndex": segment.start_point_index,
                                     "endPointIndex": segment.end_point_index,
                                     "durationMs": segment.duration_ms,
                                     "distanceMeters": segment.distance_meters}
                                    for segment in route.timing_segments],
                 "totalDistanceMeters": route.total_distance_meters, "totalDurationMs": route.total_duration_ms,
                 "timingCoverageComplete": route.timing_coverage_complete,
                 "timingTotalsMatch": route.timing_totals_match, "timingUsable": route.timing_usable}
        raw = json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("ascii")
    except (AttributeError, TypeError, ValueError, OverflowError, RecursionError):
        raise Failure("INVALID_ROUTE_RESPONSE", 502) from None
    if len(raw) > MAX_WIRE_BYTES:
        raise Failure("ROUTE_RESPONSE_TOO_LARGE", 502)
    return raw


class NaverDirectionsProvider:
    """Async socket cancellation, fixed origin, bounded decompressed response."""
    def __init__(self, key_id, key, *, transport=None):
        self._builder = NaverDirectionsClient(key_id, key)
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(10.0), follow_redirects=False,
                                       trust_env=False, transport=transport,
                                       limits=httpx.Limits(max_connections=16, max_keepalive_connections=4))

    def __repr__(self):
        return "NaverDirectionsProvider([redacted])"

    async def lookup(self, request):
        try:
            prepared = self._builder.prepare_request(request)
            async with self._client.stream("GET", prepared.full_url, headers=dict(prepared.header_items())) as response:
                if response.status_code == 429:
                    raise Failure("RATE_LIMITED", 429, True, 1000)
                if response.status_code != 200:
                    raise Failure("UPSTREAM_UNAVAILABLE", 503, response.status_code >= 500)
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(chunk) > MAX_RESPONSE_BYTES - len(raw):
                        raise Failure("ROUTE_RESPONSE_TOO_LARGE", 502)
                    raw.extend(chunk)
                return decode_naver_route(bytes(raw), request)
        except httpx.TimeoutException:
            raise Failure("ROUTE_TIMEOUT", 504, True) from None
        except httpx.HTTPError:
            raise Failure("UPSTREAM_UNAVAILABLE", 503, True) from None
        except NaverRouteError:
            raise Failure("INVALID_ROUTE_RESPONSE", 502) from None

    async def close(self):
        await self._client.aclose()


class AuxiliaryRouteService:
    """Separate bounded lookup lifecycle; no retry, result cache or shared waits."""
    def __init__(self, provider, *, timeout=12.0, clock=time.monotonic,
                 maximum_active=16, maximum_per_owner=2, maximum_starts_per_minute=60):
        if (type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 60 or
                type(maximum_active) is not int or not 1 <= maximum_active <= 16 or
                type(maximum_per_owner) is not int or not 1 <= maximum_per_owner <= 2 or
                type(maximum_starts_per_minute) is not int or not 1 <= maximum_starts_per_minute <= 60):
            raise ValueError("INVALID_ROUTE_CONFIGURATION")
        self.provider, self.timeout, self.clock = provider, timeout, clock
        self.maximum_active, self.maximum_per_owner = maximum_active, maximum_per_owner
        self.maximum_starts_per_minute = maximum_starts_per_minute
        self.active = Counter()
        self.starts = defaultdict(deque)
        self.tasks = set()
        self.lock = asyncio.Lock()
        self.closed = False

    def __repr__(self):
        return "AuxiliaryRouteService([redacted])"

    async def lookup(self, owner, request):
        if type(request) is not NaverRouteRequest:
            raise Failure("INVALID_REQUEST")
        current = asyncio.current_task()
        async with self.lock:
            if self.closed:
                raise Failure("AUXILIARY_ROUTE_UNAVAILABLE", 503)
            now = self.clock()
            for key, starts in list(self.starts.items()):
                while starts and starts[0] <= now - 60:
                    starts.popleft()
                if not starts:
                    del self.starts[key]
            if self.active[owner] >= self.maximum_per_owner or sum(self.active.values()) >= self.maximum_active:
                raise Failure("RATE_LIMITED", 429, True, 1000)
            starts = self.starts[owner]
            if len(starts) >= self.maximum_starts_per_minute:
                raise Failure("RATE_LIMITED", 429, True, max(1, math.ceil((starts[0] + 60 - now) * 1000)))
            starts.append(now)
            self.active[owner] += 1
            self.tasks.add(current)
        try:
            async with asyncio.timeout(self.timeout):
                route = await self.provider.lookup(request)
                return serialize_auxiliary_route(route, request)
        except TimeoutError:
            raise Failure("ROUTE_TIMEOUT", 504, True) from None
        except Failure:
            raise
        except Exception:
            raise Failure("UPSTREAM_UNAVAILABLE", 503, True) from None
        finally:
            async with self.lock:
                self.active[owner] -= 1
                if self.active[owner] == 0:
                    del self.active[owner]
                self.tasks.discard(current)

    async def close(self):
        async with self.lock:
            self.closed = True
            tasks = tuple(self.tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self.starts.clear()
        await self.provider.close()
