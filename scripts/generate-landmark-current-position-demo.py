#!/usr/bin/env python3
"""Public synthetic geodesic traces around two reviewed official POI representative points.

No route, road access, measured accuracy, field driving or policy approval is inferred.
The fixed WGS84 Geod oracle is independent of the app's spherical curvature bounds.
"""
import argparse
import hashlib
import json
from pathlib import Path
import pyproj

ASSET_SHA = "c8f34431486e0581c2f2a9916eba2db17e6511259f47b4f7ee7a31d70cd7862b"
EVIDENCE_SHA = "9d6443850b13d84768ad7dd2497efabe7600d80df5cd4dc1375353e602554c8b"
LINKS = (
    ("f79e9b40-aa96-5972-b59f-aa99f7bc8522", "03849d89-1640-5603-be8b-9835d371ed02", "483cfc22-d83a-5346-9adc-69b756075edf"),
    ("f560a058-6fad-5f6f-9ac9-03ea8743ceb3", "666b77d3-aba4-5ea6-b9fc-1a4c6556eef5", "3ce2057c-bd6b-5e57-822a-3464a5ab4000"),
)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = (args.project_root / "app/src/main/assets/content/p0-stories-v2.json").read_bytes()
    evidence = (args.project_root / ".local/content/review/p0-landmark-coordinate-evidence-20261109-v1.json").read_bytes()
    assert hashlib.sha256(raw).hexdigest() == ASSET_SHA
    assert hashlib.sha256(evidence).hexdigest() == EVIDENCE_SHA
    bundle = json.loads(raw)
    sources = {s["sourceRevisionId"]: s for s in bundle["sources"]}
    facts = {f["factRevisionId"]: f for f in bundle["facts"]}
    places = {p["placeId"]: p for p in bundle["places"]}
    stories = {s["storyId"]: s for s in bundle["stories"]}
    geod = pyproj.Geod(ellps="WGS84")
    selected = []
    comparisons = 0
    for place_id, story_id, fact_id in LINKS:
        place, story, fact = places[place_id], stories[story_id], facts[fact_id]
        assert story["placeId"] is None and fact["kind"] == "verified_fact"
        versions = [v for v in bundle["versions"] if v["storyId"] == story_id]
        assert len(versions) == 1
        source = sources[place["coordinateSourceRevisionId"]]
        assert source["reviewStatus"] == "verified" and not source["allowedUses"]["allowGeneration"]
        for source_id in [place["coordinateSourceRevisionId"], *fact["sourceRevisionIds"]]:
            rights = sources[source_id]
            assert rights["reviewStatus"] == "verified" and rights["allowedUses"]["allowDisplay"] and rights["allowedUses"]["allowDistribution"]
        lat, lon = float(place["latitude"]), float(place["longitude"])
        metadata = dict(place_id=place_id, region_id=None, name=place["name"], aliases_json=canonical(place["aliases"]).decode(),
                        latitude=str(lat), longitude=str(lon), coordinate_source_revision_id=place["coordinateSourceRevisionId"])
        coordinates = []
        for signed_meters in range(-850, 851):
            x, y, _ = geod.fwd(lon, lat, 0 if signed_meters >= 0 else 180, abs(signed_meters))
            latitude_text, longitude_text = format(y, ".12f"), format(x, ".12f")
            _, _, inverse = geod.inv(lon, lat, float(longitude_text), float(latitude_text))
            assert abs(inverse - abs(signed_meters)) < 0.00001
            coordinates.append([signed_meters, latitude_text, longitude_text])
            comparisons += 1
        selected.append(dict(placeId=place_id, name=place["name"], latitude=place["latitude"], longitude=place["longitude"],
            coordinateSourceRevisionId=place["coordinateSourceRevisionId"], coordinateSourceContentHash=source["contentHash"],
            coordinateSourceVerifiedAt=source["verifiedAt"], placeMetadataSha256=hashlib.sha256(canonical(metadata)).hexdigest(),
            storyId=story_id, storyVersionId=versions[0]["storyVersionId"], storyVersionContentHash=versions[0]["contentHash"],
            paragraphIds=[p["paragraphId"] for p in versions[0]["paragraphs"]],
            associationFactRevisionId=fact_id, associationFactVerifiedAt=fact["verifiedAt"],
            associationSourceRevisionIds=fact["sourceRevisionIds"], coordinates=coordinates))
    document = dict(formatVersion=1, scope="PUBLIC_SYNTHETIC_CURRENT_POSITION_CANDIDATES_NOT_APPROVED",
        assetSha256=ASSET_SHA, coordinateReviewEvidenceSha256=EVIDENCE_SHA,
        generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        oracle="pyproj.Geod_WGS84_forward_inverse", oracleVersion=pyproj.__version__,
        coordinateRoundingDecimalPlaces=12, oracleInverseComparisons=comparisons,
        coordinateColumns=["signedGeodesicNorthMeters", "latitude", "longitude"],
        radiusCandidatesMeters=[150, 300, 500], cooldownCandidatesMs=[60000, 180000, 300000],
        speedCandidatesMetersPerSecond=[5, 15, 25], accuracyCandidatesMeters=[5, 15, 30], sampleRatesHz=[1, 5],
        navigationContext="explicitly_observed_INACTIVE_CurrentPosition_only",
        approvedPolicyCount=0, realDriving=False, realRoadGeometry=False, actualLocationSamples=False,
        officialCoordinateMeaning="Provider-assigned representative POI point; not an entrance, road access point, surveyed accuracy or visibility proof",
        syntheticTraceMeaning="Meridian cross-sections through a representative point; no assertion that a road or accessible vehicle path exists",
        places=selected)
    encoded = canonical(document) + b"\n"
    assert len(encoded) <= 262144
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as target:
        target.write(encoded)
    print(json.dumps({"fixtureBytes": len(encoded), "fixtureSha256": hashlib.sha256(encoded).hexdigest(),
                      "places": len(selected), "oracleComparisons": comparisons, "approvedPolicies": 0}))


if __name__ == "__main__":
    main()
