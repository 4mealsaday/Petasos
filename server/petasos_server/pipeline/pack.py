"""Content Pack assembly (CONTENT_PIPELINE.md 2-4장). build_route_pack serves build_pack.py (demo, once on a Mac);
build_region_pack serves GET /v1/packs/region/{code} (서비스, 2차). Both share collect -> select -> describe.

Steps for a route:
  1. collect: TourAPI around route samples (radius 3 km) + 국가유산청 lists of the 시도 the route crosses;
     keep places within CORRIDOR_M (~2 km) of the route line.
  2. visibility: drop what a passenger cannot see; keep only places within their visibility radius of the route
     (small 150 m / large 1 km), because the app could never announce the rest.
  3. facts: quote whole sentences of the official description (the source keeps its license).
  4. select: Scorer ranks; at most MAX_PER_REGION per si/gun/gu; same place from two sources is merged.
  5. describe: intro (model for KOGL-1 facts, verbatim quote otherwise), with the exact fact ids it uses.
  6. pack: existing stories copied unchanged + landmarks + metadata, validated like the app validates it.
"""
import hashlib
import json
import time
from uuid import NAMESPACE_URL, uuid5

from ..content_bundle import canonical_bytes, source_content_hash, validate_bundle
from .geo import Route, haversine_m, simplify
from .scoring import Candidate, NoInterestScorer, RuleScorer
from .sources.text import sentences, spoken_name
from .visibility import RADIUS_M, visibility

CORRIDOR_M = 2000.0
SAMPLE_SPACING_M = 3000.0
TOURAPI_RADIUS_M = 3000
MAX_PER_REGION = 3
MAX_FACTS_PER_LANDMARK = 4
SCOPE_TOLERANCE_M = 30.0
_NAMESPACE = uuid5(NAMESPACE_URL, "urn:petasos:content-pack:v1")
# 국가유산청 시도코드(ccbaCtcd) by the first two digits of a si/gun/gu code.
HERITAGE_PROVINCES = {"11": "11", "26": "21", "27": "22", "28": "23", "29": "24", "30": "25", "31": "26", "36": "45",
                      "41": "31", "51": "51", "42": "51", "43": "33", "44": "34", "52": "35", "45": "35", "46": "36",
                      "47": "37", "48": "38", "50": "50"}


def _id(*parts):
    return str(uuid5(_NAMESPACE, "\x1f".join(str(part) for part in parts)))


def _normal_name(name):
    name = spoken_name(name).replace(" ", "")
    for prefix in ("서울", "대전", "천안", "용인", "안성", "평택", "오산", "화성", "청주", "세종"):
        if name.startswith(prefix) and len(name) > len(prefix) + 1:
            name = name[len(prefix):]
    return name


# ----------------------------------------------------------------------------------------------- collect

def collect_route_candidates(route, *, tourapi, heritage, regions, log=print):
    """Places within CORRIDOR_M of the route that a passenger could see from it."""
    found = {}
    if tourapi is not None:
        # Regions the route crosses (and the ones within the corridor) by the app's own boundaries: full area lists.
        codes, uncovered = [], []
        for lon, lat in route.samples(500.0):
            code, _ = regions.containing_code(lon, lat)
            if code is None:
                uncovered.append((lon, lat))
            elif code not in codes:
                codes.append(code)
        for code in codes:
            for place in tourapi.places_in_area(code):
                found.setdefault(place.place_id, place)
        # Stretches outside the bundled boundaries fall back to the (sparser) location index.
        samples = [point for index, point in enumerate(uncovered) if index % int(SAMPLE_SPACING_M / 500.0) == 0]
        for lon, lat in samples:
            for place in tourapi.places_near(lon, lat, TOURAPI_RADIUS_M):
                found.setdefault(place.place_id, place)
        log(f"  TourAPI: {len(codes)} route regions + {len(samples)} uncovered samples -> {len(found)} places")
    if heritage is not None:
        provinces = set()
        for lon, lat in route.samples(1000.0):
            code, _name = regions.containing_code(lon, lat)
            if code and code[:2] in HERITAGE_PROVINCES:
                provinces.add(HERITAGE_PROVINCES[code[:2]])
        before = len(found)
        for province in sorted(provinces):
            for place in heritage.province(province):
                found.setdefault(place.place_id, place)
        log(f"  국가유산청: provinces {sorted(provinces)} -> {len(found) - before} places")
    candidates = []
    for place in found.values():
        nearest = route.nearest(place.lng, place.lat, CORRIDOR_M)
        if nearest is None:
            continue
        candidates.append((place, nearest))
    log(f"  within {CORRIDOR_M:.0f} m of the route: {len(candidates)}")
    return candidates


def collect_area_candidates(region_code, *, tourapi, heritage, regions, log=print):
    """Region scope: every place inside one si/gun/gu (no route, so no lateral-distance filter)."""
    entry = next((e for e in regions.entries if e["code"] == region_code), None)
    if entry is None:
        raise KeyError(region_code)
    min_lon, min_lat, max_lon, max_lat = entry["bounds"]
    centre = ((min_lon + max_lon) / 2, (min_lat + max_lat) / 2)
    radius = min(20000, int(haversine_m((min_lon, min_lat), (max_lon, max_lat)) / 2) + 500)
    places = {}
    if tourapi is not None:
        for place in tourapi.places_near(centre[0], centre[1], radius):
            places.setdefault(place.place_id, place)
    if heritage is not None and region_code[:2] in HERITAGE_PROVINCES:
        for place in heritage.province(HERITAGE_PROVINCES[region_code[:2]]):
            places.setdefault(place.place_id, place)
    inside = [p for p in places.values() if regions.containing_code(p.lng, p.lat)[0] == region_code
              or any(e["code"] == region_code for e in regions.containing(p.lng, p.lat))]
    log(f"  region {region_code}: {len(inside)} places")
    return [(place, (0.0, 0.0, 0)) for place in inside]


# ----------------------------------------------------------------------------------------------- facts

def _facts_for(document, place):
    rows = []
    for index, sentence in enumerate(sentences(document.text)[:MAX_FACTS_PER_LANDMARK]):
        fact_id = _id("fact", document.source_id, index, sentence)
        rows.append({"factId": fact_id, "factRevisionId": _id("fact-revision", fact_id, sentence), "statement": sentence,
                     "placeId": place.place_id, "regionCode": place.region_code})
    return rows


def describe_candidates(raw, *, adapters, regions, route_filter=True, log=print):
    """visibility + facts. raw = [(place, (distance, along, side))]."""
    kept, dropped = [], {"invisible": 0, "too_far": 0, "no_description": 0}
    for place, (distance, along, side) in raw:
        adapter = adapters[place.source]
        group = None
        if place.source == "heritage":
            # The cheap radius gate first: a heritage detail costs one request.
            if route_filter and distance > RADIUS_M["large"]:
                dropped["too_far"] += 1
                continue
            detail = adapter.details(place)
            group, kind = detail["gcodeName"], detail["mcodeName"]
        else:
            kind = None
        seen = visibility(place, group, kind)
        if seen is None:
            dropped["invisible"] += 1
            continue
        if route_filter and distance > RADIUS_M[seen]:
            dropped["too_far"] += 1
            continue
        document = adapter.document(place)
        facts = _facts_for(document, place) if document else []
        if not facts:
            dropped["no_description"] += 1
            continue
        code, _ = regions.containing_code(place.lng, place.lat)
        kept.append(Candidate(place=place, visibility=seen, route_distance_m=distance, along_m=along, side=side,
                              region_code=code or place.region_code, document=document, facts=facts,
                              heritage_grade=place.attributes.get("grade")))
    log(f"  visible within radius with a description: {len(kept)} (dropped {dropped})")
    return kept


# ----------------------------------------------------------------------------------------------- select

def select(candidates, scorer, *, max_per_region=MAX_PER_REGION, exclude=(), log=print):
    """Merge duplicates across sources, rank by scorer, keep the best per region."""
    ranked = sorted(candidates, key=lambda c: (-scorer.score(c), c.place.place_id))
    chosen, names = [], []
    for candidate in ranked:
        name = _normal_name(candidate.place.name)
        if candidate.place.place_id in exclude:
            continue
        duplicate = any((name == other_name or name in other_name or other_name in name) and
                        haversine_m((candidate.place.lng, candidate.place.lat), (other.place.lng, other.place.lat)) < 400
                        for other, other_name in zip(chosen, names))
        if duplicate:
            continue
        chosen.append(candidate)
        names.append(name)
    per_region, result = {}, []
    for candidate in chosen:
        key = candidate.region_code or "unknown"
        if per_region.get(key, 0) >= max_per_region:
            continue
        per_region[key] = per_region.get(key, 0) + 1
        result.append(candidate)
    result.sort(key=lambda c: c.along_m)
    log(f"  selected {len(result)} landmarks over {len(per_region)} regions (max {max_per_region} each)")
    return result


# ----------------------------------------------------------------------------------------------- rows

def _coordinate(value):
    return f"{value:.7f}"


def _source_row(document, verified_at):
    return {
        "sourceRevisionId": _id("source-revision", document.source_id, source_content_hash(document.text)),
        "sourceId": document.source_id, "provider": document.provider, "sourceUrl": document.url, "title": document.title[:500],
        "fetchedAt": document.fetched_at, "sourceUpdatedAt": None, "sourceUpdatedRaw": document.updated_raw,
        "verifiedAt": verified_at, "contentHash": source_content_hash(document.text),
        "license": {"type": document.license.type, "url": document.license.url, "scope": "text",
                    "reviewBasis": document.license.basis},
        "allowedUses": {"allowGeneration": document.license.allows_generation, "allowDisplay": True, "allowDistribution": True,
                        "reviewBasis": "build_pack: 공식 API 원문을 문장 단위로 인용. 팩 검토표로 사람 확인"},
        "attribution": f"{document.provider} ({document.license.type})",
        "reviewStatus": "verified", "normalizedText": document.text[:16000],
    }


def landmark_rows(selected, *, intro_writer, existing_places, verified_at, log=print):
    """-> (sources, facts, places, landmarks, report). existing_places maps kto-tourapi content id -> place row."""
    sources, facts, places, landmarks, report = [], [], [], [], []
    prepared, requests = [], []
    for candidate in selected:
        source = _source_row(candidate.document, verified_at)
        fact_rows = [{"factRevisionId": f["factRevisionId"], "factId": f["factId"], "version": 1, "statement": f["statement"],
                      "kind": "verified_fact", "verifiedAt": verified_at, "sourceRevisionIds": [source["sourceRevisionId"]]}
                     for f in candidate.facts]
        requests.append((spoken_name(candidate.place.name), candidate.place.category,
                         [{**f, "sourceRevisionId": source["sourceRevisionId"]} for f in fact_rows],
                         candidate.document.license.allows_generation))
        prepared.append((candidate, source, fact_rows))
    intros = intro_writer.write_many(requests)
    for (candidate, source, fact_rows), written in zip(prepared, intros):
        if written is None:
            log(f"  skip {candidate.place.name}: no intro")
            continue
        intro, fact_ids, method = written
        place = candidate.place
        existing = existing_places.get(place.attributes.get("contentId")) if place.source == "tourapi" else None
        if existing is not None:
            place_id, lat, lng = existing["placeId"], existing["latitude"], existing["longitude"]
        else:
            place_id, lat, lng = place.place_id, _coordinate(place.lat), _coordinate(place.lng)
            external = place.attributes.get("contentId") if place.source == "tourapi" else place.place_id.split(":", 1)[1]
            places.append({"placeId": place_id, "name": place.name[:500], "aliases": [], "latitude": lat, "longitude": lng,
                           "coordinateSystem": "WGS84", "coordinateSourceRevisionId": source["sourceRevisionId"],
                           "coordinateReviewBasis": f"{source['provider']} 좌표(WGS84) 그대로",
                           "externalIds": [{"provider": "kto-tourapi" if place.source == "tourapi" else "khs-heritage",
                                            "externalId": external, "verifiedAt": verified_at}]})
        landmarks.append({"placeId": place_id, "name": spoken_name(place.name), "latitude": lat, "longitude": lng,
                          "regionCode": candidate.region_code, "visibility": candidate.visibility,
                          "category": place.category, "intro": intro, "factRevisionIds": fact_ids})
        report.append({"name": spoken_name(place.name), "regionCode": candidate.region_code, "latitude": lat, "longitude": lng,
                       "visibility": candidate.visibility, "side": "오른쪽" if candidate.side > 0 else "왼쪽",
                       "routeDistanceM": round(candidate.route_distance_m), "alongKm": round(candidate.along_m / 1000, 1),
                       "intro": intro, "introMethod": method, "source": source["provider"], "license": source["license"]["type"],
                       "sourceUrl": source["sourceUrl"], "facts": [f["statement"] for f in fact_rows if f["factRevisionId"] in fact_ids]})
        sources.append(source)
        facts.extend(fact_rows)
    return sources, facts, places, landmarks, report


# ----------------------------------------------------------------------------------------------- pack

def assemble_pack(base_bundle, *, pack_id, version, scope, sources, facts, places, landmarks,
                  prompt_version, scorer, interest_scorer=None, created_at=None):
    """The v2 story bundle unchanged + landmark rows + pack metadata (formatVersion 3)."""
    interest_scorer = interest_scorer or NoInterestScorer()
    stories = []
    for story in base_bundle["stories"]:
        scores = interest_scorer.interest_scores(story)
        stories.append({**story, "interestScores": scores} if scores else dict(story))
    pack = {
        "formatVersion": 3, "packId": pack_id, "version": version,
        "createdAt": created_at if created_at is not None else int(time.time() * 1000),
        "promptVersion": prompt_version, "scoringVersion": scorer.version, "scope": scope,
        "sources": list(base_bundle["sources"]) + sources, "facts": list(base_bundle["facts"]) + facts,
        "places": list(base_bundle["places"]) + places, "stories": stories,
        "versions": list(base_bundle["versions"]), "regionBindings": list(base_bundle.get("regionBindings", [])),
        "landmarks": landmarks,
    }
    validate_bundle(pack)
    return pack


def route_scope(scenario_id, route_points, regions):
    path = [[round(lon, 6), round(lat, 6)] for lon, lat in simplify(route_points, SCOPE_TOLERANCE_M)]
    codes = []
    route = Route(route_points)
    for lon, lat in route.samples(500.0):
        code, _ = regions.containing_code(lon, lat)
        if code and code not in codes:
            codes.append(code)
    return {"type": "route", "scenarioId": scenario_id, "path": path, "regionCodes": codes}


def _existing_places(base_bundle):
    out = {}
    for place in base_bundle["places"]:
        for external in place["externalIds"]:
            if external["provider"] == "kto-tourapi":
                out[external["externalId"]] = place
    return out


def build_route_pack(*, scenario_id, pack_id, version, route_points, base_bundle, adapters, regions,
                     intro_writer, scorer=None, log=print):
    scorer = scorer or RuleScorer()
    route = Route(route_points)
    verified_at = int(time.time() * 1000)
    raw = collect_route_candidates(route, tourapi=adapters.get("tourapi"), heritage=adapters.get("heritage"),
                                   regions=regions, log=log)
    candidates = describe_candidates(raw, adapters=adapters, regions=regions, log=log)
    selected = select(candidates, scorer, log=log)
    sources, facts, places, landmarks, report = landmark_rows(
        selected, intro_writer=intro_writer, existing_places=_existing_places(base_bundle), verified_at=verified_at, log=log)
    pack = assemble_pack(base_bundle, pack_id=pack_id, version=version, scope=route_scope(scenario_id, route_points, regions),
                         sources=sources, facts=facts, places=places, landmarks=landmarks,
                         prompt_version=intro_writer.version, scorer=scorer)
    return pack, report


def build_region_pack(region_code, *, adapters, regions, intro_writer, scorer=None, log=print):
    """GET /v1/packs/region/{code} (뼈대): landmarks only, no stories yet (2차 generates the story pool)."""
    scorer = scorer or RuleScorer()
    verified_at = int(time.time() * 1000)
    raw = collect_area_candidates(region_code, tourapi=adapters.get("tourapi"), heritage=adapters.get("heritage"),
                                  regions=regions, log=log)
    candidates = describe_candidates(raw, adapters=adapters, regions=regions, route_filter=False, log=log)
    selected = select(candidates, scorer, log=log)
    sources, facts, places, landmarks, report = landmark_rows(
        selected, intro_writer=intro_writer, existing_places={}, verified_at=verified_at, log=log)
    empty = {"sources": [], "facts": [], "places": [], "stories": [], "versions": [], "regionBindings": []}
    if not sources:
        raise LookupError("NO_LANDMARKS")
    pack = assemble_pack(empty, pack_id=f"region-{region_code}", version=1,
                         scope={"type": "region", "regionCode": region_code, "regionCodes": [region_code]},
                         sources=sources, facts=facts, places=places, landmarks=landmarks,
                         prompt_version=intro_writer.version, scorer=scorer)
    return pack, report


def pack_sha256(pack):
    return hashlib.sha256(pack_bytes(pack)).hexdigest()


def pack_bytes(pack):
    """The exact bytes written to disk and the app asset (pretty JSON keeps reviews readable in git)."""
    return (json.dumps(pack, ensure_ascii=False, indent=1, sort_keys=False) + "\n").encode("utf-8")
