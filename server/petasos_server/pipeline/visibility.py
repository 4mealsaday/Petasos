"""Can a passenger see this place from the road, and from how far?  (CONTENT_PIPELINE.md 2장 visibility)

large  (~1 km): tall or wide structures and terrain -- towers/observatories, stadiums, big bridges, dams,
                fortress walls, and named mountains/peaks.
small  (~150 m): everything else that stands at street level -- gates, station buildings, churches, temples,
                 parks, monuments, museums.
None   : not a thing you can see from a car -- movable artifacts, documents, intangible heritage, animals,
         and places whose official description says nothing (no facts to tell).
The radius is the largest lateral distance from the travel line at which the app may announce it (6장).
"""
import re

from .sources.text import spoken_name

RADIUS_M = {"small": 150.0, "large": 1000.0}
LARGE_WORDS = ("타워", "전망대", "한빛탑", "경기장", "스타디움", "야구장", "댐", "산성", "도성", "읍성", "성곽")
# "대교" is a big bridge only as a word ending ("반포대교"), never inside "초대교회".
BRIDGE = re.compile(r"대교(?![가-힣])")
MOUNTAIN = re.compile(r"(산|봉)$")
# 국가유산 분류(gcodeName) that stand somewhere visible. 유물·기록유산·무형유산 do not.
VISIBLE_HERITAGE_GROUPS = ("유적건조물", "자연유산")
# 등록문화유산 mixes buildings (종교·상업·업무시설 …) with movable objects (동산); only buildings count.
REGISTERED_GROUP = "등록문화유산"
# TourAPI 신분류(lclsSystm3) prefixes that are not a sight: activities, events, food, shopping, stays,
# themed streets/alleys (VE04), libraries (VE09), shops/brand spaces (VE12).
INVISIBLE_TOURAPI_PREFIXES = ("EX", "EV", "FD", "SH", "AC", "LS02", "LS03", "VE04", "VE09", "VE12")
INVISIBLE_NAME_WORDS = ("골목", "거리", "둘레길", "놀이터", "반려동물", "관광특구", "지하")


def visibility(place, heritage_group=None, heritage_kind=None):
    """heritage_group = gcodeName, heritage_kind = mcodeName from the 국가유산청 detail."""
    name = spoken_name(place.name)
    if place.source == "heritage" and heritage_group is not None:
        if heritage_group == REGISTERED_GROUP:
            if (heritage_kind or "").startswith("동산") or not heritage_kind:
                return None
        elif heritage_group not in VISIBLE_HERITAGE_GROUPS:
            return None
    if any(word in name for word in INVISIBLE_NAME_WORDS):
        return None
    if place.source == "tourapi" and place.attributes.get("lclsSystm", "").startswith(INVISIBLE_TOURAPI_PREFIXES):
        return None
    if any(word in name for word in LARGE_WORDS) or BRIDGE.search(name):
        return "large"
    if place.category == "nature" and MOUNTAIN.search(name):
        return "large"
    return "small"
