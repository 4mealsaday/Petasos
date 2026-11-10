"""국가유산청 국가유산 검색 OPEN API adapter (no key).

Official guide: https://www.khs.go.kr/html/HtmlPage.do?pg=/publicinfo/pbinfo3_0202.jsp&mn=NS_04_04_02
- 목록 SearchKindOpenapiList.do (pageUnit, pageIndex, ccbaCtcd 시도, ccbaCncl 지정해제 여부) returns longitude/latitude.
- 상세 SearchKindOpenapiDt.do (ccbaKdcd, ccbaAsno, ccbaCtcd) returns the description (content) and 분류(gcodeName).
The guide states no text license for descriptions, so they are marked unconfirmed and not paraphrased by a model.
"""
import time
from urllib.parse import urlencode
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener
import ssl
import xml.etree.ElementTree as ElementTree

from ..model import License, Place, SourceDocument
from . import text

BASE = "https://www.khs.go.kr/cha/"
GUIDE_URL = "https://www.khs.go.kr/html/HtmlPage.do?pg=/publicinfo/pbinfo3_0202.jsp&mn=NS_04_04_02"
PROVIDER = "국가유산청 국가유산 검색 OPEN API"
LICENSE = License("KHS-OPENAPI-UNCONFIRMED", GUIDE_URL, False,
                  "국가유산청 OPEN API 안내에 설명문 이용 조건이 명시되지 않음. 원문 인용만 하고 모델 각색 금지, 사용 전 확인 필요")
# 종목코드(ccbaKdcd) -> grade label. Intangible heritage has no place to see from a road.
KINDS = {"11": "국보", "12": "보물", "13": "사적", "15": "명승", "16": "천연기념물", "18": "국가민속문화유산",
         "21": "시도유형문화유산", "23": "시도기념물", "24": "시도민속문화유산", "25": "시도등록유산", "31": "문화유산자료",
         "55": "시도자연유산", "66": "시도자연유산자료", "79": "국가등록유산"}
MAX_BYTES = 4 * 1024 * 1024
PAGE = 500


def _parse(raw):
    if len(raw) > MAX_BYTES or b"<!DOCTYPE" in raw[:2048] or b"<!ENTITY" in raw:
        raise ValueError("INVALID_RESPONSE")
    return ElementTree.fromstring(raw)


class HeritageAdapter:
    name = "heritage"

    def __init__(self, cache, opener=None):
        self.cache = cache
        self.opener = opener or build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()))

    def _get(self, operation, parameters):
        request = Request(BASE + operation + "?" + urlencode(parameters), headers={"User-Agent": "PetasosPipeline/1"})
        with self.opener.open(request, timeout=20) as response:
            return _parse(response.read(MAX_BYTES + 1))

    def province(self, province_code):
        """All designated (not cancelled) heritage of one 시도, cached per page."""
        rows, page = [], 1
        while True:
            def fetch(page=page):
                root = self._get("SearchKindOpenapiList.do", {"pageUnit": PAGE, "pageIndex": page,
                                                              "ccbaCtcd": province_code, "ccbaCncl": "N"})
                items = [{child.tag: (child.text or "").strip() for child in item} for item in root.findall("item")]
                return {"total": int(root.findtext("totalCnt") or 0), "items": items, "fetchedAt": int(time.time() * 1000)}
            result = self.cache.get_or_fetch(self.name, f"list:{province_code}:{PAGE}:{page}", fetch)
            rows += [(item, result["fetchedAt"]) for item in result["items"]]
            if page * PAGE >= result["total"] or not result["items"]:
                return [p for p in (self._place(item, fetched) for item, fetched in rows) if p is not None]
            page += 1

    def _place(self, item, fetched_at):
        kind = item.get("ccbaKdcd") or ""
        if kind not in KINDS or item.get("ccbaCncl") == "Y":
            return None
        try:
            lon, lat = float(item.get("longitude") or 0), float(item.get("latitude") or 0)
        except ValueError:
            return None
        if not (124 < lon < 132 and 33 < lat < 39) or not item.get("ccbaMnm1"):
            return None
        key = {"ccbaKdcd": kind, "ccbaAsno": item.get("ccbaAsno", ""), "ccbaCtcd": item.get("ccbaCtcd", "")}
        return Place(
            place_id=f"heritage:{item.get('ccbaCpno') or kind + item.get('ccbaAsno', '') + item.get('ccbaCtcd', '')}",
            name=text.clean(item["ccbaMnm1"]), lat=lat, lng=lon, region_code=None,
            category="nature" if kind in ("15", "16", "55", "66") else "heritage", source=self.name, license=LICENSE,
            source_url=BASE + "SearchKindOpenapiDt.do?" + urlencode(key), fetched_at=fetched_at,
            attributes={"grade": KINDS[kind], "kind": kind, "detailKey": key, "sido": item.get("ccbaCtcdNm", ""),
                        "sigungu": item.get("ccsiName", "")})

    def details(self, place):
        def fetch():
            root = self._get("SearchKindOpenapiDt.do", place.attributes["detailKey"])
            item = root.find("item")
            values = {child.tag: (child.text or "").strip() for child in item} if item is not None else {}
            return {"content": values.get("content", ""), "gcodeName": values.get("gcodeName", ""),
                    "bcodeName": values.get("bcodeName", ""), "mcodeName": values.get("mcodeName", ""),
                    "scodeName": values.get("scodeName", ""), "ccbaQuan": values.get("ccbaQuan", ""),
                    "fetchedAt": int(time.time() * 1000)}
        return self.cache.get_or_fetch(self.name, f"detail:{place.place_id}", fetch)

    def document(self, place):
        detail = self.details(place)
        content = text.clean(detail["content"])
        if not content:
            return None
        return SourceDocument(
            source_id=place.place_id, provider=PROVIDER, url=place.source_url, title=place.name, text=content,
            license=LICENSE, fetched_at=detail["fetchedAt"])
