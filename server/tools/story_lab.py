#!/usr/bin/env python3
"""Story prompt lab (CONTENT_PIPELINE.md 9장). No emulator; run from the repository root:

    PYTHONPATH=server .local/server-venv/bin/python server/tools/story_lab.py <command> ...

  build-eval                      freeze the evaluation set: the facts of 5 demo regions -> server/eval/regions/*.json
                                  (reviewed demo-pack facts + TourAPI KOGL-1 overviews; run once, commit the files)
  generate --prompt-version X     write stories for every eval region -> server/eval/out/X/<region>.json (git-ignored)
  compare --a X --b Y             both versions side by side per region -> server/eval/out/compare-X-Y.md
  approve --pack P --story S01 --from X [--install]
                                  put one approved story into the pack (new story version, pack version + 1)

X is a prompt version: the legacy conversation prompt (petasos-v0.8-5) or the version that server/prompts/story.md
declares now. To compare a prompt edit: generate the current version, edit the prompt files and bump their version,
generate again, compare.
"""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from petasos_server.content_bundle import validate_bundle  # noqa: E402
from petasos_server.contracts import Failure  # noqa: E402
from petasos_server.local_env import load_environment  # noqa: E402
from petasos_server.pipeline.pack import _id, _source_row, pack_bytes, pack_sha256  # noqa: E402
from petasos_server.pipeline.scoring import StorySeedScorer  # noqa: E402
from petasos_server.pipeline.sources.text import sentences  # noqa: E402
from petasos_server.pipeline.story import (LEGACY_VERSION, BANNED, StoryDraft, StoryRejected, StoryRequest,  # noqa: E402
                                           StoryWriter, next_ending, target_window)

EVAL = ROOT / "server" / "eval"
EVAL_REGIONS_DIR = EVAL / "regions"
OUT = EVAL / "out"
PACKS = ROOT / "demo" / "packs"
APP_PACKS = ROOT / "app" / "src" / "main" / "assets" / "content" / "packs"
DEFAULT_STORIES = ROOT / "app" / "src" / "main" / "assets" / "content" / "p0-stories-v2.json"
# (eval region code, spoken name, si/gun/gu codes whose facts belong to it). 용인·천안 group their districts.
EVAL_REGIONS = (("11140", "서울 중구", ("11140",)), ("41460", "용인", ("41460", "41461", "41463", "41465")),
                ("41550", "안성", ("41550",)), ("44130", "천안", ("44130", "44131", "44133")),
                ("30200", "대전 유성구", ("30200",)))
TOURAPI_PLACES_PER_REGION = 3
LAB_TEMPERATURE = 0.4   # story generation in the lab; the grounding judge always runs at 0
TOURAPI_FACTS_PER_PLACE = 3
TOURAPI_DOCUMENTS_PER_REGION = 25
STORY_CATEGORIES = {"heritage": 1.6, "nature": 1.4, "culture": 1.3, "building": 1.2}
# Practical details are not story material and go stale.
PRACTICAL = ("운영", "시간", "요금", "휴관", "휴무", "전화", "주차", "문의", "예약", "입장", "관람료", "도보", "소요", "IC", "홈페이지")
CONNECTIVES = ("다만", "그러나", "하지만", "또한", "그리고", "이후", "이때", "이 ", "그 ", "따라서", "한편", "이곳", "이는")


def _json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def story_labels(pack):
    """S01.. in the pack's story order (the demo pack's script numbering)."""
    return {story["storyId"]: f"S{index:02d}" for index, story in enumerate(pack["stories"], 1)}


# ----------------------------------------------------------------------------------------------- build-eval

def _pack_rows_for(pack, codes):
    facts = {f["factRevisionId"]: f for f in pack["facts"]}
    sources = {s["sourceRevisionId"]: s for s in pack["sources"]}
    versions = {v["storyId"]: v for v in pack["versions"]}
    labels = story_labels(pack)
    chosen, existing = [], []
    for binding in pack["regionBindings"]:
        if binding["regionCode"] not in codes:
            continue
        version = versions[binding["storyId"]]
        cited = sorted({f for p in version["paragraphs"] for f in p["factRevisionIds"]})
        chosen += [binding["associationFactRevisionId"], *cited]
        story = next(s for s in pack["stories"] if s["storyId"] == binding["storyId"])
        existing.append({"label": labels[binding["storyId"]], "storyId": binding["storyId"], "title": story["title"],
                         "factRevisionIds": cited})
    for landmark in pack["landmarks"]:
        if landmark["regionCode"] in codes:
            chosen += landmark["factRevisionIds"]
    fact_rows = [facts[f] for f in dict.fromkeys(chosen)]
    source_rows = [sources[s] for s in dict.fromkeys(sid for f in fact_rows for sid in f["sourceRevisionIds"])]
    return source_rows, fact_rows, existing


def _tourapi_rows(adapter, codes, taken_names, verified_at, log):
    places = {}
    for code in codes:
        try:
            for place in adapter.places_in_area(code):
                places.setdefault(place.place_id, place)
        except Exception as error:  # noqa: BLE001 -- a district without an area list is skipped, not fatal
            log(f"    {code}: area list unavailable ({type(error).__name__})")
    usable = [p for p in places.values() if p.license.allows_generation and p.license.allows_modification
              and p.category in STORY_CATEGORIES and not any(name in p.name for name in taken_names)]
    usable.sort(key=lambda p: (-STORY_CATEGORIES[p.category], p.name))
    scored = []
    for place in usable[:TOURAPI_DOCUMENTS_PER_REGION]:
        document = adapter.document(place)
        if document is None:
            continue
        lines = [s for s in sentences(document.text) if 15 <= len(s) <= 200 and not s.startswith(CONNECTIVES)
                 and not any(word in s for word in PRACTICAL)][:TOURAPI_FACTS_PER_PLACE]
        if len(lines) >= 2:
            scored.append((STORY_CATEGORIES[place.category] + min(1.0, len(document.text) / 600.0), place, document, lines))
    scored.sort(key=lambda row: (-row[0], row[1].place_id))
    sources, facts = [], []
    for _score, place, document, lines in scored[:TOURAPI_PLACES_PER_REGION]:
        source = _source_row(document, verified_at)
        sources.append(source)
        for index, sentence in enumerate(lines):
            fact_id = _id("fact", document.source_id, index, sentence)
            facts.append({"factRevisionId": _id("fact-revision", fact_id, sentence), "factId": fact_id, "version": 1,
                          "statement": sentence, "kind": "verified_fact", "verifiedAt": verified_at,
                          "sourceRevisionIds": [source["sourceRevisionId"]]})
        log(f"    TourAPI {place.name} ({document.license.type}): {len(lines)} facts")
    return sources, facts


def build_eval(args):
    from petasos_server.pipeline.cache import SourceCache
    from petasos_server.pipeline.sources.tourapi import TourApiAdapter
    pack = _json(PACKS / f"{args.pack}.json")
    environment = load_environment(args.env_file) if args.env_file.exists() else {}
    key = environment.get("PETASOS_TOURAPI_SERVICE_KEY") or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY")
    adapter = TourApiAdapter(key or "offline", SourceCache(offline=args.offline or not key))
    verified_at = int(time.time() * 1000)
    for code, name, codes in EVAL_REGIONS:
        print(f"{code} {name}")
        pack_sources, pack_facts, existing = _pack_rows_for(pack, set(codes))
        taken = [s["title"] for s in pack_sources]
        tour_sources, tour_facts = _tourapi_rows(adapter, codes, taken, verified_at, print)
        document = {"regionCode": code, "regionName": name, "codes": list(codes), "pack": args.pack,
                    "createdAt": verified_at,
                    "note": "평가 세트(고정). 팩의 검토된 사실 + TourAPI 공공누리 제1유형 개요 문장. 바꾸면 이전 결과와 비교할 수 없다.",
                    "existingStories": existing, "sources": pack_sources + tour_sources, "facts": pack_facts + tour_facts}
        _write_json(EVAL_REGIONS_DIR / f"{code}.json", document)
        print(f"  -> {len(document['facts'])} facts ({len(pack_facts)} from the pack), {len(existing)} existing stories")


def eval_regions(only=None):
    regions = [_json(path) for path in sorted(EVAL_REGIONS_DIR.glob("*.json"))]
    if not regions:
        raise SystemExit("No evaluation set: run build-eval first.")
    return [r for r in regions if only is None or r["regionCode"] in only]


# ----------------------------------------------------------------------------------------------- seeds

def build_seeds(region):
    """Story seeds = fact bundles: facts sharing a source, merged with the facts of the same existing story."""
    parent = {f["factRevisionId"]: f["factRevisionId"] for f in region["facts"]}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        parent[find(a)] = find(b)

    by_source = {}
    for fact in region["facts"]:
        for source in fact["sourceRevisionIds"]:
            by_source.setdefault(source, []).append(fact["factRevisionId"])
    for ids in by_source.values():
        for other in ids[1:]:
            union(ids[0], other)
    for story in region["existingStories"]:
        ids = [f for f in story["factRevisionIds"] if f in parent]
        for other in ids[1:]:
            union(ids[0], other)
    titles = {s["sourceRevisionId"]: s["title"] for s in region["sources"]}
    groups = {}
    for fact in region["facts"]:
        groups.setdefault(find(fact["factRevisionId"]), []).append(fact)
    seeds = []
    for facts in groups.values():
        seeds.append({"id": facts[0]["factRevisionId"], "title": titles.get(facts[0]["sourceRevisionIds"][0], "?"),
                      "facts": facts, "statements": [f["statement"] for f in facts]})
    return seeds


# ----------------------------------------------------------------------------------------------- generate

def lab_provider(config_path, environment):
    """The server's OpenRouter route, with room for a whole story (longer timeout and output budget than answers)."""
    import httpx
    from petasos_server.provider import OpenRouterConfig, OpenRouterProvider
    config = _json(config_path).get("provider")
    if not config or not environment.get("PETASOS_OPENROUTER_API_KEY"):
        raise SystemExit("Model unavailable: .local/server/config.json provider and PETASOS_OPENROUTER_API_KEY are required.")
    provider = OpenRouterProvider(OpenRouterConfig(
        api_key=environment["PETASOS_OPENROUTER_API_KEY"], model=config["model"], provider_slug=config["providerSlug"],
        accepted_provider_names=tuple(config["acceptedProviderNames"]), privacy_reviewed_until=config["privacyReviewedUntil"],
        account_content_logging_disabled=config["accountContentLoggingDisabled"],
        max_output_tokens=min(4096, config.get("maxOutputTokens", 2048)), accepted_model_ids=tuple(config.get("acceptedModelIds", [])),
        temperature=LAB_TEMPERATURE))
    provider.client = httpx.AsyncClient(timeout=httpx.Timeout(90.0), follow_redirects=False, trust_env=False)
    return provider


async def generate_region(writer, region, version, count, target_seconds, previous=None, use_seeds=True):
    """previous: the story played just before on the route (another region) -- its opening/ending and whether its seed
    was a religious facility matter. With seeds, StorySeedScorer picks the fact bundle; otherwise every fact is offered."""
    stories, existing = [], ([previous] if previous else [])
    scorer, used = StorySeedScorer(), set()
    previous_religious = bool(previous and previous.get("religious"))
    for _ in range(count):
        seed = None
        facts = region["facts"]
        if use_seeds:
            ranked = scorer.select(build_seeds(region), previous_religious=previous_religious, used=used)
            if not ranked:
                break
            seed = ranked[0]
            used.add(seed["id"])
            facts = seed["facts"]
            previous_religious = seed["religious"]
        info = None if seed is None else {key: seed[key] for key in ("id", "title", "score", "reasons", "rank", "candidates", "heavy", "religious")}
        ending = None
        if writer.guide_typed and version == writer.guide_version:
            ending = next_ending(existing[-1].get("ending") if existing else None, ENDINGS_USED)
        request = StoryRequest(region["regionCode"], region["regionName"], tuple(facts), target_seconds,
                               existing=tuple(existing), prompt_version=version, ending=ending)
        try:
            draft = await writer.write(request)
        except StoryRejected as rejected:
            stories.append({"status": "rejected", "rejectedAttempts": rejected.attempts, "seed": info})
            continue
        except Failure as failure:
            stories.append({"status": "error", "code": failure.code, "seed": info})
            continue
        facts = {f["factRevisionId"]: f["factId"] for f in region["facts"]}
        existing.append({"title": draft.title, "factIds": sorted({facts[f] for p in draft.paragraphs for f in p["factRevisionIds"]}),
                         "opening": draft.opening, "ending": draft.ending})
        stories.append({"status": "ok", **draft.to_dict(), "seed": info})
        if draft.ending:
            ENDINGS_USED.append(draft.ending)
    return stories


ENDINGS_USED = []   # endings of this generate run, for the even rotation


def generate(args):
    environment = load_environment(args.env_file) if args.env_file.exists() else {}
    writer = StoryWriter(lab_provider(args.server_config, environment), prompt_version=args.prompt_version, judge=True)
    order = [code for code, _name, _codes in EVAL_REGIONS]   # the demo route: 서울 → 용인 → 안성 → 천안 → 대전
    regions = sorted(eval_regions(set(args.region) if args.region else None), key=lambda r: order.index(r["regionCode"]))
    out = OUT / args.prompt_version

    async def run():
        try:
            previous = None
            for region in regions:
                started = time.monotonic()
                stories = await generate_region(writer, region, args.prompt_version, args.count, args.target_seconds, previous,
                                                use_seeds=not args.all_facts)
                last = next((s for s in reversed(stories) if s["status"] == "ok"), None)
                previous = {"title": last["title"], "factIds": [], "opening": last.get("opening"), "ending": last.get("ending"),
                            "religious": bool((last.get("seed") or {}).get("religious"))} if last else None
                _write_json(out / f"{region['regionCode']}.json", {
                    "promptVersion": args.prompt_version, "regionCode": region["regionCode"], "regionName": region["regionName"],
                    "targetSeconds": args.target_seconds, "targetChars": target_window(args.target_seconds),
                    "generatedAt": int(time.time() * 1000), "stories": stories})
                summary = ", ".join(s["status"] + (f"(재시도 {len(s['rejectedAttempts'])})" if s.get("rejectedAttempts") and s["status"] == "ok" else "")
                                    for s in stories)
                print(f"{region['regionCode']} {region['regionName']}: {summary} [{time.monotonic() - started:.1f}s]")
        finally:
            await writer.provider.close()
    asyncio.run(run())
    print(f"-> {out.relative_to(ROOT)}/")


# ----------------------------------------------------------------------------------------------- compare

def _cell(text):
    return text.replace("|", "\\|").replace("\n", " ")


def _status(story):
    if story is None:
        return "결과 없음"
    if story["status"] == "error":
        return f"오류 `{story['code']}`"
    attempts = story.get("rejectedAttempts") or []
    lines = []
    for number, attempt in enumerate(attempts, 1):
        lines.append(f"{number}차 거부: " + "; ".join(attempt["reasons"]))
    if story["status"] == "ok":
        return "통과" + (f" (재시도 후) — {' / '.join(lines)}" if lines else "")
    return "거부 — " + " / ".join(lines)


MARKS = {"supported": "✅", "insufficient": "⚠️불충분", "contradicted": "❌모순", "not_claim": "·"}
TYPE_MARKS = {"fact": "[F]", "bridge": "[B]", "bridge→fact": "[B→F]"}


def _paragraphs(story, labels):
    """Each paragraph as its sentences with the grounding verdict (✅ 지지, ⚠️ 불충분, ❌ 모순, · 판정 제외)."""
    if story is None:
        return []
    if story["status"] == "ok":
        paragraphs, judgments = story["paragraphs"], story.get("judgments") or []
    elif story["status"] == "rejected" and story["rejectedAttempts"][-1].get("output", {}).get("paragraphs"):
        last = story["rejectedAttempts"][-1]   # last rejected output, shown for review
        paragraphs, judgments = last["output"]["paragraphs"], last.get("judgments") or []
    else:
        return []
    cells = []
    for number, p in enumerate(paragraphs, 1):
        rows = [r for r in judgments if r["paragraph"] == number]
        text = "<br>".join(f"{TYPE_MARKS.get(r.get('type'), '')}{MARKS[r['verdict']]} {r['sentence']}" + (f" _({r['reason']})_" if r["reason"] else "")
                             for r in rows) if rows else p.get("text") or " ".join(x["text"] for x in p.get("sentences", []))
        cells.append(f"{text} `[{', '.join(labels.get(f, '?') for f in p['factRevisionIds'])}]`")
    return cells


def _results(version):
    out = []
    for region in eval_regions():
        path = OUT / version / f"{region['regionCode']}.json"
        out += _json(path)["stories"] if path.exists() else []
    return out


def _final_rows(story):
    if story["status"] == "ok":
        return story.get("judgments") or []
    return (story.get("rejectedAttempts") or [{}])[-1].get("judgments") or []


def _seed(story):
    seed = (story or {}).get("seed")
    if not seed:
        return "전체 사실(소재 선택 없음)"
    return f"{seed['title']} — {seed['score']:g}점, 후보 {seed['candidates']}개 중 {seed['rank']}위 ({', '.join(seed['reasons'])})"


def _summary_bridges(version):
    ok = [s for s in _results(version) if s["status"] == "ok"]
    typed = [s for s in ok if any("sentences" in p for p in s["paragraphs"])]
    if not typed:
        return "type 없음(v2 이하)"
    good = 0
    for s in typed:
        rows = s.get("judgments") or []
        if rows and rows[0].get("type") == "bridge" and rows[-1].get("type") == "bridge":
            good += 1
    return f"{good}/{len(ok)}편"


def _summary_endings(version):
    endings = [s.get("ending") or "?" for s in _results(version) if s["status"] == "ok"]
    return f"{len(set(endings))}가지 ({', '.join(endings)})"


def _summary_topics(version):
    seeds = [s.get("seed") for s in _results(version) if s["status"] == "ok"]
    if not any(seeds):
        return "소재 선택 없음"
    return f"무거운 {sum(bool(x and x['heavy']) for x in seeds)}편 / 종교 {sum(bool(x and x['religious']) for x in seeds)}편"


def _summary_pass(version):
    stories = _results(version)
    return f"{sum(s['status'] == 'ok' for s in stories)}/{len(stories)}"


def _summary_location(version):
    """Accepted stories whose first paragraph still names a place other than the region as the current location."""
    from petasos_server.pipeline.grounding import location_reasons
    names = {r["regionCode"]: r["regionName"] for r in eval_regions()}
    count = 0
    for region in eval_regions():
        path = OUT / version / f"{region['regionCode']}.json"
        for story in (_json(path)["stories"] if path.exists() else []):
            if story["status"] == "ok" and location_reasons(story["paragraphs"], names[region["regionCode"]]):
                count += 1
    attempts = sum(any("현재 위치" in r for a in s.get("rejectedAttempts") or [] for r in a["reasons"]) for s in _results(version))
    return f"{count}건 (거부된 시도 {attempts}회)"


def _summary_bad(version):
    accepted = sum(r["verdict"] in ("insufficient", "contradicted") for s in _results(version) if s["status"] == "ok" for r in _final_rows(s))
    attempts = sum(r["verdict"] in ("insufficient", "contradicted") for s in _results(version)
                   for a in s.get("rejectedAttempts") or [] for r in a.get("judgments") or [])
    return f"통과본 {accepted}문장 (거부된 시도 {attempts}문장)"


def _summary_openings(version):
    names = {"place": "장소", "person": "사람", "scene": "장면", "question": "물음"}
    openings = [names.get(s.get("opening"), s.get("opening") or "?") for s in _results(version) if s["status"] == "ok"]
    passing = sum(s["paragraphs"][0]["text"].lstrip().split(".")[0].find("지나고 있") >= 0 for s in _results(version) if s["status"] == "ok")
    return f"{len(set(openings))}가지 ({', '.join(openings)}) · 첫 문장 '지나고 있' {passing}편"


def compare(args):
    lines = [f"# 이야기 비교: `{args.a}` ↔ `{args.b}`", "",
             f"평가 세트 `server/eval/regions/` · 목표 길이는 각 결과의 targetChars · 검증 기준: 근거 연결, 근거 밖 숫자, "
             f"금지 표현({', '.join(label for _, label in BANNED)}), 문단·전체 길이, 반복", "",
             "문단 끝 `[F#]`은 그 문단이 인용한 근거 사실 번호다. 거부된 결과는 마지막 시도의 문장을 보여 준다.",
             "문장 앞 표시는 근거 판정이다: ✅ 지지 · ⚠️불충분 · ❌모순 · `·` 판정 제외(물음, 숫자 없는 상상·여운, 현재 위치 문장 — "
             "`server/petasos_server/pipeline/grounding.py`). 판정이 없는 결과(판정 전 규칙 검사에서 거부)는 문단 원문만 보인다.",
             "문장 type(v3+): `[F]` 사실 · `[B]` 연결(판정 제외) · `[B→F]` 숫자·장소 이름이 든 연결 문장(사실로 보고 판정). "
             "소재 = StorySeedScorer(story-seed-rules-v0)가 고른 사실 묶음, 점수와 이유.", ""]
    for region in eval_regions():
        code = region["regionCode"]
        results = {}
        for version in (args.a, args.b):
            path = OUT / version / f"{code}.json"
            results[version] = _json(path)["stories"] if path.exists() else []
        labels = {f["factRevisionId"]: f"F{i}" for i, f in enumerate(region["facts"], 1)}
        lines += [f"## {code} {region['regionName']}", "", "<details><summary>근거 사실 "
                  f"{len(region['facts'])}개</summary>", ""]
        lines += [f"- F{i}: {f['statement']}" for i, f in enumerate(region["facts"], 1)]
        lines += ["", "</details>", ""]
        count = max(len(results[args.a]), len(results[args.b]), 1)
        for index in range(count):
            a = results[args.a][index] if index < len(results[args.a]) else None
            b = results[args.b][index] if index < len(results[args.b]) else None
            if count > 1:
                lines += [f"### 이야기 {index + 1}", ""]
            lines += [f"| | `{args.a}` | `{args.b}` |", "|---|---|---|",
                      f"| 검증 | {_cell(_status(a))} | {_cell(_status(b))} |",
                      f"| 제목 | {_cell((a or {}).get('title', '-'))} | {_cell((b or {}).get('title', '-'))} |",
                      f"| 소재 | {_cell(_seed(a))} | {_cell(_seed(b))} |",
                      f"| 시작·끝 | {(a or {}).get('opening') or '-'} / {(a or {}).get('ending') or '-'} | {(b or {}).get('opening') or '-'} / {(b or {}).get('ending') or '-'} |",
                      f"| 글자 수 | {(a or {}).get('chars', '-')} | {(b or {}).get('chars', '-')} |"]
            pa, pb = _paragraphs(a, labels), _paragraphs(b, labels)
            for number in range(max(len(pa), len(pb))):
                left = pa[number] if number < len(pa) else ""
                right = pb[number] if number < len(pb) else ""
                lines.append(f"| 문단 {number + 1} | {_cell(left)} | {_cell(right)} |")
            lines.append("")
    lines += ["## 요약", "", "| | " + " | ".join(f"`{v}`" for v in (args.a, args.b)) + " |", "|---|---|---|"]
    for label, fn in (("통과", _summary_pass), ("현재 위치 지어내기", _summary_location), ("판정 불충분·모순 문장", _summary_bad),
                      ("시작 방식", _summary_openings), ("연결 문장으로 시작·끝", _summary_bridges), ("끝맺음", _summary_endings),
                      ("무거운 소재 / 종교 시설 소재", _summary_topics)):
        lines.append(f"| {label} | " + " | ".join(fn(v) for v in (args.a, args.b)) + " |")
    lines.append("")
    path = OUT / f"compare-{args.a}-{args.b}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"-> {path.relative_to(ROOT)}")


# ----------------------------------------------------------------------------------------------- judge-examples

def parse_example(path):
    """examples/NN-*.md -> (region name, facts, paragraphs with typed sentences)."""
    import re
    text = Path(path).read_text(encoding="utf-8")
    region = re.search(r"^### 예시: ([^(\n]+?) \(", text, re.M).group(1).strip()
    facts, paragraphs = {}, []
    for line in text.splitlines():
        fact = re.match(r"- (F\d+) \((fact|tradition)\): (.+)$", line)
        head = re.match(r"문단 \d+ \(([^)]*)\)", line)
        unit = re.match(r"- \[(fact|bridge)\] (.+)$", line)
        if fact:
            facts[fact.group(1)] = {"factRevisionId": f"example-{fact.group(1)}", "factId": f"example-{fact.group(1)}",
                                    "kind": fact.group(2), "statement": fact.group(3), "sourceRevisionIds": ["example-source"]}
        elif head:
            ids = [f"example-{x.strip()}" for x in head.group(1).split(",")]
            paragraphs.append({"factRevisionIds": ids, "sentences": []})
        elif unit and paragraphs:
            paragraphs[-1]["sentences"].append({"text": unit.group(2).strip(), "type": unit.group(1)})
    return region, list(facts.values()), paragraphs


def judge_examples(args):
    """Run the grounding stage on the prompt examples (same judge as generate)."""
    from petasos_server.pipeline.grounding import GroundingJudge, VERDICT_LABELS, fact_names
    from petasos_server.pipeline.story import evidence_from_facts, structure_reasons
    from petasos_server.prompts import DIRECTORY, example_names
    environment = load_environment(args.env_file) if args.env_file.exists() else {}
    provider = lab_provider(args.server_config, environment)
    judge = GroundingJudge(provider)

    async def run():
        try:
            for name in example_names():
                region, facts, paragraphs = parse_example(DIRECTORY / f"{name}.md")
                rows = await judge.judge(paragraphs, evidence_from_facts(facts), region)
                evidence = evidence_from_facts(facts)
                structure = structure_reasons({"paragraphs": paragraphs}, region, fact_names(evidence))
                print(f"\n## {name} ({region})" + (f" — 구조: {'; '.join(structure)}" if structure else ""))
                for row in rows:
                    print(f"- {TYPE_MARKS.get(row.get('type'), '')} {VERDICT_LABELS[row['verdict']]}: {row['sentence']}"
                          + (f" — {row['reason']}" if row["reason"] else ""))
        finally:
            await provider.close()
    asyncio.run(run())


# ----------------------------------------------------------------------------------------------- approve

def apply_story(bundle, story_id, draft, region, now_ms):
    """Add the facts/sources the story cites (from the eval set) and replace the story's version with version + 1."""
    eval_facts = {f["factRevisionId"]: f for f in region["facts"]}
    eval_sources = {s["sourceRevisionId"]: s for s in region["sources"]}
    have_facts = {f["factRevisionId"] for f in bundle["facts"]}
    have_sources = {s["sourceRevisionId"] for s in bundle["sources"]}
    for fact_id in sorted({f for p in draft.paragraphs for f in p["factRevisionIds"]}):
        if fact_id in have_facts:
            continue
        fact = eval_facts[fact_id]
        for source_id in fact["sourceRevisionIds"]:
            if source_id not in have_sources:
                bundle["sources"].append(eval_sources[source_id])
                have_sources.add(source_id)
        bundle["facts"].append(fact)
        have_facts.add(fact_id)
    index = next(i for i, v in enumerate(bundle["versions"]) if v["storyId"] == story_id)
    old = bundle["versions"][index]
    bundle["versions"][index] = draft.bundle_version(story_id, old["version"] + 1, now_ms)
    return old["version"], old["version"] + 1


def approve(args):
    pack_path = PACKS / f"{args.pack}.json"
    pack = _json(pack_path)
    by_label = {label: story_id for story_id, label in story_labels(pack).items()}
    if args.story not in by_label:
        raise SystemExit(f"Unknown story {args.story}; the pack has {', '.join(sorted(by_label))}.")
    story_id = by_label[args.story]
    region = next((r for r in eval_regions() if any(s["label"] == args.story for s in r["existingStories"])), None)
    if region is None:
        raise SystemExit(f"{args.story} is not in any evaluation region.")
    out_path = OUT / args.from_version / f"{region['regionCode']}.json"
    result = _json(out_path)
    story = result["stories"][args.index - 1]
    if story["status"] != "ok":
        raise SystemExit(f"{out_path.relative_to(ROOT)} story {args.index} did not pass the checks.")
    draft = StoryDraft.from_dict(story)
    now = int(time.time() * 1000)
    old, new = apply_story(pack, story_id, draft, region, now)
    pack["version"] += 1
    pack["createdAt"] = now
    validate_bundle(pack)
    # The reviewed story bundle that build_pack.py carries over must hold the same version, or a rebuild reverts it.
    base = None
    if args.stories:
        base = _json(args.stories)
        apply_story(base, story_id, draft, region, now)
        validate_bundle(base)
    pack_path.write_bytes(pack_bytes(pack))
    if base is not None:
        args.stories.write_text(json.dumps(base, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    story["reviewStatus"] = "approved"
    story["approvedInto"] = {"pack": args.pack, "packVersion": pack["version"], "story": args.story, "storyVersion": new}
    _write_json(out_path, result)
    print(f"{args.story}: story version {old} -> {new} from {args.from_version}; pack {args.pack} v{pack['version']} "
          f"(sha256 {pack_sha256(pack)})")
    if args.install:
        APP_PACKS.mkdir(parents=True, exist_ok=True)
        (APP_PACKS / pack_path.name).write_bytes(pack_path.read_bytes())
        print(f"Installed {(APP_PACKS / pack_path.name).relative_to(ROOT)}; update BundledStoryCatalog.SHA256 to {pack_sha256(pack)}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--server-config", type=Path, default=ROOT / ".local" / "server" / "config.json")
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build-eval")
    build.add_argument("--pack", default="seoul-daejeon")
    build.add_argument("--offline", action="store_true", help="use only cached TourAPI responses")
    gen = commands.add_parser("generate")
    gen.add_argument("--prompt-version", required=True, help=f"{LEGACY_VERSION} or the version in server/prompts/story.md")
    gen.add_argument("--region", action="append", help="eval region code (repeatable); default: all")
    gen.add_argument("--count", type=int, default=1, help="stories per region")
    gen.add_argument("--target-seconds", type=int, default=35)
    gen.add_argument("--all-facts", action="store_true", help="offer every region fact instead of the scored story seed")
    commands.add_parser("judge-examples", help="grounding-judge the prompt examples")
    cmp_ = commands.add_parser("compare")
    cmp_.add_argument("--a", required=True)
    cmp_.add_argument("--b", required=True)
    app = commands.add_parser("approve")
    app.add_argument("--pack", required=True)
    app.add_argument("--story", required=True, help="S01..S10 in the pack's story order")
    app.add_argument("--from", dest="from_version", required=True, help="prompt version whose lab output to take")
    app.add_argument("--index", type=int, default=1, help="which story of that region's output (1-based)")
    app.add_argument("--stories", type=Path, default=DEFAULT_STORIES,
                     help="reviewed story bundle that build_pack.py carries over; updated too (empty string = skip)")
    app.add_argument("--install", action="store_true", help="also copy the pack into the app assets")
    args = parser.parse_args(argv)
    if getattr(args, "stories", None) is not None and str(args.stories) in ("", "."):
        args.stories = None
    {"build-eval": build_eval, "generate": generate, "compare": compare, "approve": approve,
     "judge-examples": judge_examples}[args.command](args)


if __name__ == "__main__":
    main()
