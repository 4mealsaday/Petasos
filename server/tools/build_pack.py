#!/usr/bin/env python3
"""Build a demo scenario's Content Pack (docs/CONTENT_PIPELINE.md 4장).

    PYTHONPATH=server .local/server-venv/bin/python server/tools/build_pack.py --scenario seoul-daejeon

Reads demo/scenarios/<id>/scenario.json + route.json, collects places along the route (TourAPI + 국가유산청,
cached under .local/content/cache), writes demo/packs/<packId>.json, a review table
(demo/packs/<packId>.review.md), and copies the pack into the app assets (--install).

Stories are not regenerated: the existing reviewed story bundle (--stories) is carried over unchanged.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from petasos_server.content_bundle import validate_bundle  # noqa: E402
from petasos_server.local_env import load_environment  # noqa: E402
from petasos_server.pipeline import curated as curated_sources  # noqa: E402
from petasos_server.pipeline.cache import SourceCache  # noqa: E402
from petasos_server.pipeline.intro import IntroWriter, provider_from_config  # noqa: E402
from petasos_server.pipeline.pack import build_route_pack, pack_bytes, pack_sha256  # noqa: E402
from petasos_server.pipeline.regions import RegionIndex  # noqa: E402
from petasos_server.pipeline.sources.heritage import HeritageAdapter  # noqa: E402
from petasos_server.pipeline.sources.tourapi import TourApiAdapter  # noqa: E402

SCENARIOS = ROOT / "demo" / "scenarios"
PACKS = ROOT / "demo" / "packs"
APP_PACKS = ROOT / "app" / "src" / "main" / "assets" / "content" / "packs"
DEFAULT_STORIES = ROOT / "app" / "src" / "main" / "assets" / "content" / "p0-stories-v2.json"


def review_table(pack, report):
    lines = [f"# {pack['packId']} v{pack['version']} 랜드마크 검토표", "",
             f"promptVersion `{pack['promptVersion']}` · scoringVersion `{pack['scoringVersion']}` · 랜드마크 {len(report)}곳", "",
             "| # | 이름 | 지역 | 좌표(위도, 경도) | 보임 | 경로 기준 | intro | 방식 | 출처 · 라이선스 |",
             "|---|---|---|---|---|---|---|---|---|"]
    for index, row in enumerate(report, 1):
        lines.append(f"| {index} | {row['name']} | {row['regionCode'] or '-'} | {row['latitude']}, {row['longitude']} | "
                     f"{row['visibility']} | {row['side']} {row['routeDistanceM']} m · {row['alongKm']} km 지점 | "
                     f"{row['intro']} | {row['introMethod']} | {row['source']} · {row['license']} |")
    lines += ["", "## 근거 문장", ""]
    for index, row in enumerate(report, 1):
        lines.append(f"{index}. **{row['name']}** — [{row['source']}]({row['sourceUrl']})")
        lines += [f"   - {fact}" for fact in row["facts"]]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--version", type=int, default=1, help="pack version (bump when the content changes)")
    parser.add_argument("--stories", type=Path, default=DEFAULT_STORIES, help="reviewed story bundle carried over unchanged")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--server-config", type=Path, default=ROOT / ".local" / "server" / "config.json")
    parser.add_argument("--offline", action="store_true", help="use only cached source responses")
    parser.add_argument("--no-model", action="store_true", help="quote a source sentence instead of calling the model")
    parser.add_argument("--install", action="store_true", help="also copy the pack into app/src/main/assets/content/packs/")
    args = parser.parse_args()

    scenario_dir = SCENARIOS / args.scenario
    scenario = json.loads((scenario_dir / "scenario.json").read_text())
    route = json.loads((scenario_dir / scenario["route"]).read_text())
    base = json.loads(args.stories.read_text())
    validate_bundle(base)

    environment = load_environment(args.env_file) if args.env_file.exists() else {}
    cache = SourceCache(offline=args.offline)
    adapters = {"heritage": HeritageAdapter(cache)}
    key = environment.get("PETASOS_TOURAPI_SERVICE_KEY") or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY")
    if key:
        adapters["tourapi"] = TourApiAdapter(key, cache)
    else:
        print("PETASOS_TOURAPI_SERVICE_KEY missing: TourAPI skipped")
    provider = None if args.no_model or not args.server_config.exists() else provider_from_config(args.server_config, environment)
    print(f"Scenario {args.scenario}: {len(route['path'])} route points; model intros: {'on' if provider else 'off'}")

    # scenario.json "landmarks": the reviewed allowlist ("include": place ids), per-place editor notes for the intro
    # ("notes") and curated replacement sources ("curatedSources" file) -- CONTENT_PIPELINE.md 6장 후보 기준.
    review = scenario.get("landmarks", {})
    curated = curated_sources.load(scenario_dir / review["curatedSources"]) if review.get("curatedSources") else {}
    pack, report = build_route_pack(
        scenario_id=args.scenario, pack_id=scenario["packId"], version=args.version, route_points=route["path"],
        base_bundle=base, adapters=adapters, regions=RegionIndex(), intro_writer=IntroWriter(provider, judge=provider is not None),
        curated=curated, include=review.get("include"), notes=review.get("notes"))
    print(f"Cache: {cache.hits} hits, {cache.misses} fetched")

    # Stories must be byte-for-byte the reviewed ones (재생성하지 않음).
    for name in ("stories", "versions", "regionBindings"):
        assert [json.dumps(r, sort_keys=True) for r in pack[name] if "interestScores" not in r] == \
            [json.dumps(r, sort_keys=True) for r in base[name]], name

    PACKS.mkdir(parents=True, exist_ok=True)
    out = PACKS / f"{pack['packId']}.json"
    out.write_bytes(pack_bytes(pack))
    (PACKS / f"{pack['packId']}.review.md").write_text(review_table(pack, report))
    print(f"Wrote {out.relative_to(ROOT)} ({out.stat().st_size} bytes, sha256 {pack_sha256(pack)})")
    if args.install:
        APP_PACKS.mkdir(parents=True, exist_ok=True)
        (APP_PACKS / out.name).write_bytes(out.read_bytes())
        print(f"Installed {APP_PACKS.relative_to(ROOT) / out.name}; update BundledStoryCatalog.SHA256 to {pack_sha256(pack)}")


if __name__ == "__main__":
    main()
