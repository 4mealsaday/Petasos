"""TourAPI 4.0 (한국관광공사 국문 관광정보 KorService2) adapter, through the existing contract-checked TourismClient.

locationBasedList2 (mapX, mapY, radius) lists places near route samples; detailCommon2 gives the overview.
"""
import time

from ...tourism_api import TourismClient
from ..model import License, Place, SourceDocument
from . import text

PROVIDER = "한국관광공사 TourAPI"
CATALOG_URL = "https://www.data.go.kr/data/15101578/openapi.do"
CONTENT_TYPES = ("12", "14")  # 관광지, 문화시설. Food/stay/shopping/event types are never landmarks.
# 공공누리 types reported per item in cpyrhtDivCd. Type3 forbids modification, so a model may not paraphrase it.
LICENSES = {
    "Type1": License("KOGL-1", "https://www.kogl.or.kr/info/license.do", True, "TourAPI cpyrhtDivCd=Type1: 공공누리 제1유형(출처표시)"),
    "Type3": License("KOGL-3", "https://www.kogl.or.kr/info/license.do", False, "TourAPI cpyrhtDivCd=Type3: 공공누리 제3유형(출처표시·변경금지)"),
}
# lclsSystm1 (신분류체계 대분류) -> pipeline category.
CATEGORIES = {"HS": "heritage", "VE": "culture", "NA": "nature", "LS": "leisure", "EX": "culture"}


def _license(code):
    return LICENSES.get(code) or License(f"TOURAPI-{code or 'UNKNOWN'}", "https://www.kogl.or.kr/info/license.do", False,
                                         f"TourAPI cpyrhtDivCd={code or '-'}: 유형 미확인, 생성 근거로 쓰지 않음")


class TourApiAdapter:
    name = "tourapi"

    def __init__(self, key, cache, client=None):
        self.client = client or TourismClient("tourapi", key)
        self.cache = cache

    def _request(self, operation, parameters):
        parameters = {"MobileOS": "ETC", "MobileApp": "Petasos", **parameters}
        return [dict(item) for item in self.client.request(operation, parameters).items]

    def places_near(self, lon, lat, radius_m):
        places = []
        for content_type in CONTENT_TYPES:
            key = f"locationBasedList2:{content_type}:{lon:.4f}:{lat:.4f}:{int(radius_m)}"

            def fetch():
                rows, page = [], 1
                while True:
                    batch = self._request("locationBasedList2", {"mapX": f"{lon:.6f}", "mapY": f"{lat:.6f}",
                                          "radius": str(int(radius_m)), "contentTypeId": content_type, "arrange": "E",
                                          "numOfRows": "100", "pageNo": str(page)})
                    rows += batch
                    if len(batch) < 100 or page >= 10:
                        return {"items": rows, "fetchedAt": int(time.time() * 1000)}
                    page += 1
            result = self.cache.get_or_fetch(self.name, key, fetch)
            for row in result["items"]:
                place = self._place(row, result["fetchedAt"])
                if place is not None:
                    places.append(place)
        return places

    def places_in_area(self, region_code):
        """Every 관광지/문화시설 of one si/gun/gu (areaBasedList2 by 법정동 code). The location index is sparse
        outside Seoul (반경 10 km around 대전엑스포 listed 3 places), so route regions use this list."""
        places = []
        for content_type in CONTENT_TYPES:
            key = f"areaBasedList2:{content_type}:{region_code}"

            def fetch():
                rows, page = [], 1
                while True:
                    batch = self._request("areaBasedList2", {"lDongRegnCd": region_code[:2], "lDongSignguCd": region_code[2:],
                                          "contentTypeId": content_type, "arrange": "A", "numOfRows": "100", "pageNo": str(page)})
                    rows += batch
                    if len(batch) < 100 or page >= 20:
                        return {"items": rows, "fetchedAt": int(time.time() * 1000)}
                    page += 1
            result = self.cache.get_or_fetch(self.name, key, fetch)
            places += [p for p in (self._place(row, result["fetchedAt"]) for row in result["items"]) if p is not None]
        return places

    def _place(self, row, fetched_at):
        try:
            lon, lat = float(row.get("mapx") or 0), float(row.get("mapy") or 0)
        except ValueError:
            return None
        if not (124 < lon < 132 and 33 < lat < 39) or not row.get("contentid") or not row.get("title"):
            return None
        region, sigungu = row.get("lDongRegnCd") or "", row.get("lDongSignguCd") or ""
        code = region + sigungu if len(region) == 2 and len(sigungu) == 3 else None
        return Place(
            place_id=f"tourapi:{row['contentid']}", name=text.clean(row["title"]), lat=lat, lng=lon, region_code=code,
            category=CATEGORIES.get(row.get("lclsSystm1") or "", "other"), source=self.name,
            license=_license(row.get("cpyrhtDivCd")), source_url=f"{CATALOG_URL}#contentId={row['contentid']}",
            fetched_at=fetched_at,
            attributes={"contentTypeId": row.get("contenttypeid"), "lclsSystm": row.get("lclsSystm3") or "",
                        "cat3": row.get("cat3") or "", "contentId": row["contentid"]})

    def document(self, place):
        content_id = place.attributes["contentId"]

        def fetch():
            rows = self._request("detailCommon2", {"contentId": content_id})
            row = rows[0] if rows else {}
            return {"overview": row.get("overview") or "", "modifiedtime": row.get("modifiedtime"),
                    "addr1": row.get("addr1") or "", "fetchedAt": int(time.time() * 1000)}
        detail = self.cache.get_or_fetch(self.name, f"detailCommon2:{content_id}", fetch)
        overview = text.clean(detail["overview"])
        if not overview:
            return None
        return SourceDocument(
            source_id=f"tourapi:{content_id}", provider=PROVIDER, url=place.source_url, title=place.name,
            text=overview, license=place.license, fetched_at=detail["fetchedAt"], updated_raw=detail.get("modifiedtime"))
