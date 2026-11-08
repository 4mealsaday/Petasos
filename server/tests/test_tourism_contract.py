from dataclasses import FrozenInstanceError
import json
from pathlib import Path
import unittest

from petasos_server.tourism_contract import (
    TourismContractError, operation, operations, prepare_parameters,
)


class TourismContractTests(unittest.TestCase):
    def assert_error(self, code, service, name, parameters=None, parameter=None, **kwargs):
        with self.assertRaises(TourismContractError) as raised:
            prepare_parameters(service, name, parameters, **kwargs)
        self.assertEqual(raised.exception.code, code)
        self.assertEqual(raised.exception.parameter, parameter)
        return raised.exception

    def test_all_23_operations_match_pinned_query_contracts(self):
        self.assertEqual(len(operations()), 23)
        self.assertEqual(len(operations("odii")), 8)
        self.assertEqual(len(operations("tourapi")), 15)
        directory = Path(__file__).resolve().parents[2] / "docs/reference/public-data"
        for service in ("odii", "tourapi"):
            raw = json.loads((directory / f"{service}.openapi.json").read_text())
            for path, entry in raw["paths"].items():
                selected = operation(service, entry["get"]["operationId"])
                self.assertEqual(selected.path, path)
                self.assertEqual(selected.parameters, tuple(entry["parameters"]))
                self.assertEqual(selected.description, entry["get"]["description"])
                self.assertEqual(selected.item_schema["type"], "object")
                self.assertTrue(selected.item_schema["properties"])
                self.assertNotIn('"$ref"', json.dumps(selected.item_schema))

    def test_minimal_queries_cover_every_operation_without_invented_defaults(self):
        minimal = {
            "langCode": "ko", "mapX": "127.0", "mapY": "37.0", "radius": "100",
            "keyword": "서울", "contentId": "00042", "contentTypeId": "12",
            "eventStartDate": "20261107", "lDongRegnCd": "11",
        }
        for selected in operations():
            with self.subTest(service=selected.service, name=selected.name):
                required = {p["name"] for p in selected.parameters if p.get("required")}
                inputs = {key: value for key, value in minimal.items() if key in required}
                result = prepare_parameters(selected.service, selected.name, inputs)
                self.assertEqual(set(result), required - {"serviceKey"})
                self.assertEqual(result["MobileOS"], "ETC")
                self.assertEqual(result["MobileApp"], "Petasos")
                self.assertNotIn("_type", result)
                self.assertNotIn("serviceKey", result)

    def test_exposed_raw_schemas_are_detached_from_validation_cache(self):
        first = operation("odii", "themeBasedList")
        with self.assertRaises(FrozenInstanceError):
            first.name = "changed"
        first.parameters[0]["name"] = "modified"
        first.item_schema["properties"].clear()
        next_copy = operation("odii", "themeBasedList")
        self.assertEqual(next_copy.parameters[0]["name"], "numOfRows")
        self.assertIn("tid", next_copy.item_schema["properties"])
        inventory = operations("odii")
        inventory[0].parameters[0]["required"] = True
        self.assertNotIn("numOfRows", prepare_parameters("odii", "themeBasedList", {"langCode": "ko"}))

    def test_no_language_or_format_default_and_custom_mobile_settings(self):
        self.assert_error("MISSING_PARAMETER", "odii", "themeBasedList", parameter="langCode")
        self.assertEqual(prepare_parameters("tourapi", "areaBasedList2"),
                         {"MobileOS": "ETC", "MobileApp": "Petasos"})
        result = prepare_parameters("tourapi", "areaBasedList2", {"MobileApp": "내 서비스", "_type": "xml"},
                                    mobile_os="WEB", mobile_app="unused")
        self.assertEqual(result, {"MobileApp": "내 서비스", "_type": "xml", "MobileOS": "WEB"})

    def test_unknown_service_operation_and_input_names_do_not_escape(self):
        for service, name, expected in (("secret-key", "x", "UNKNOWN_SERVICE"),
                                        ("odii", "secret-key", "UNKNOWN_OPERATION")):
            error = self.assert_error(expected, service, name)
            self.assertNotIn("secret-key", str(error))
        for params in ({"secret-key": "private"}, {17: "private"}, [], "private"):
            error = self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", params)
            self.assertNotIn("private", repr(error))
        with self.assertRaisesRegex(TourismContractError, "^UNKNOWN_SERVICE$"):
            operations("secret-key")
        error = TourismContractError("secret-value", "secret-key")
        self.assertEqual(error.code, "INVALID_PARAMETER")
        self.assertIsNone(error.parameter)

    def test_service_key_is_rejected_even_when_empty(self):
        for key in ("private", "", None):
            error = self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2",
                                      {"serviceKey": key}, parameter="serviceKey")
            self.assertNotIn("private", str(error))

    def test_required_blank_and_wrong_scalar_types_are_distinct(self):
        for content_id in ("", "  "):
            self.assert_error("MISSING_PARAMETER", "tourapi", "detailCommon2",
                              {"contentId": content_id}, parameter="contentId")
        for content_id in (12, True, None, ["12"], {"id": "12"}):
            self.assert_error("INVALID_PARAMETER", "tourapi", "detailCommon2",
                              {"contentId": content_id}, parameter="contentId")
        self.assert_error("INVALID_PARAMETER", "tourapi", "detailCommon2",
                          {"contentId": "12\nsecret"}, parameter="contentId")
        self.assert_error("INVALID_PARAMETER", "tourapi", "searchKeyword2",
                          {"keyword": "\ud800"}, parameter="keyword")

    def test_identifiers_and_codes_keep_leading_zeroes(self):
        result = prepare_parameters("odii", "storyBasedList",
                                    {"langCode": "ko", "tid": "0001", "tlid": "0002"})
        self.assertEqual((result["tid"], result["tlid"]), ("0001", "0002"))
        result = prepare_parameters("tourapi", "areaBasedList2",
                                    {"lDongRegnCd": "01", "lDongSignguCd": "001", "areaCode": "001"})
        self.assertEqual(result["lDongSignguCd"], "001")
        self.assertEqual(result["areaCode"], "001")
        self.assertEqual(prepare_parameters("tourapi", "detailCommon2", {"contentId": "000123"})["contentId"], "000123")

    def test_numeric_paging_strings_and_numbers_are_whole_positive_digits(self):
        for service, name, extras in (("odii", "themeBasedList", {"langCode": "ko"}),
                                     ("odii", "storySearchList", {"langCode": "ko", "keyword": "서울"}),
                                     ("tourapi", "areaBasedList2", {}),
                                     ("tourapi", "categoryCode2", {})):
            for value in (1, 1.0, "01", "1e0"):
                with self.subTest(service=service, name=name, value=value):
                    result = prepare_parameters(service, name, {**extras, "pageNo": value, "numOfRows": value})
                    self.assertEqual(result["pageNo"], "1")
                    self.assertEqual(result["numOfRows"], "1")
        # This layer does not invent a provider maximum or the client's 1,000-row budget.
        self.assertEqual(prepare_parameters("tourapi", "areaBasedList2", {"numOfRows": 1001})["numOfRows"], "1001")

    def test_invalid_numbers_booleans_nonfinite_and_fractional_pages_rejected(self):
        for value in (True, False, 0, -1, 1.1, "", "NaN", "Infinity", "1e999999", "０１",
                      float("nan"), float("inf"), float("-inf"), None, [], "1" * 129):
            with self.subTest(value=value):
                self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", {"pageNo": value}, parameter="pageNo")

    def test_location_coordinates_and_documented_radius_limit(self):
        base = {"mapX": 127.5, "mapY": "37.5", "radius": 20000}
        result = prepare_parameters("tourapi", "locationBasedList2", base)
        self.assertEqual(result["mapX"], "127.5")
        self.assertEqual(result["radius"], "20000")
        for field, value in (("mapX", 181), ("mapY", -91), ("radius", 20001), ("radius", 0),
                             ("mapX", True), ("mapY", float("nan"))):
            self.assert_error("INVALID_PARAMETER", "tourapi", "locationBasedList2",
                              {**base, field: value}, parameter=field)
        self.assert_error("MISSING_PARAMETER", "odii", "storyLocationBasedList", {"langCode": "ko"}, parameter="mapX")

    def test_mobile_os_choices_are_service_specific(self):
        self.assertEqual(prepare_parameters("odii", "themeBasedList", {"langCode": "ko", "MobileOS": "WIN"})["MobileOS"], "WIN")
        self.assertEqual(prepare_parameters("tourapi", "areaBasedList2", {"MobileOS": "WEB"})["MobileOS"], "WEB")
        self.assert_error("INVALID_PARAMETER", "odii", "themeBasedList", {"langCode": "ko", "MobileOS": "WEB"}, parameter="MobileOS")
        self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", {"MobileOS": "WIN"}, parameter="MobileOS")

    def test_language_and_sync_state_enumerations(self):
        for language in ("ko", "en", "cn1", "jp"):
            self.assertEqual(prepare_parameters("odii", "themeBasedList", {"langCode": language})["langCode"], language)
        self.assert_error("INVALID_PARAMETER", "odii", "themeBasedList", {"langCode": "zh"}, parameter="langCode")
        for state in ("A", "U", "D"):
            self.assertEqual(prepare_parameters("odii", "themeBasedSyncList", {"langCode": "ko", "syncStatus": state})["syncStatus"], state)
        self.assert_error("INVALID_PARAMETER", "odii", "storyBasedSyncList", {"langCode": "ko", "syncStatus": "deleted"}, parameter="syncStatus")

    def test_arrange_choices_do_not_leak_between_endpoints(self):
        for choice in ("A", "C", "D", "O", "Q", "R"):
            self.assertEqual(prepare_parameters("tourapi", "areaBasedList2", {"arrange": choice})["arrange"], choice)
        for choice in ("E", "S"):
            self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", {"arrange": choice}, parameter="arrange")
            self.assertEqual(prepare_parameters("tourapi", "locationBasedList2",
                                               {"arrange": choice, "mapX": 127, "mapY": 37, "radius": 1})["arrange"], choice)

    def test_content_type_codes_are_per_operation_and_stay_required_region(self):
        self.assertEqual(prepare_parameters("tourapi", "detailIntro2", {"contentId": "123", "contentTypeId": "25"})["contentTypeId"], "25")
        self.assert_error("INVALID_PARAMETER", "tourapi", "detailInfo2", {"contentId": "123", "contentTypeId": "25"}, parameter="contentTypeId")
        self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", {"contentTypeId": 12}, parameter="contentTypeId")
        self.assert_error("MISSING_PARAMETER", "tourapi", "searchStay2", parameter="lDongRegnCd")
        self.assertEqual(prepare_parameters("tourapi", "searchStay2", {"lDongRegnCd": "11"})["lDongRegnCd"], "11")

    def test_flags_and_format_reject_undocumented_values(self):
        cases = (("detailImage2", "imageYN", {"contentId": "1"}),
                 ("ldongCode2", "lDongListYn", {}), ("lclsSystmCode2", "lclsSystmListYn", {}))
        for name, field, extra in cases:
            for value in ("Y", "N"):
                self.assertEqual(prepare_parameters("tourapi", name, {**extra, field: value})[field], value)
            self.assert_error("INVALID_PARAMETER", "tourapi", name, {**extra, field: "true"}, parameter=field)
        for value in ("0", "1"):
            self.assertEqual(prepare_parameters("tourapi", "areaBasedSyncList2", {"showflag": value})["showflag"], value)
        self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedSyncList2", {"showflag": "2"}, parameter="showflag")
        self.assert_error("INVALID_PARAMETER", "tourapi", "areaBasedList2", {"_type": "yaml"}, parameter="_type")

    def test_dates_are_real_calendar_dates_and_range_ordered(self):
        result = prepare_parameters("tourapi", "searchFestival2", {"eventStartDate": "20240229", "eventEndDate": "20240301"})
        self.assertEqual(result["eventStartDate"], "20240229")
        for value in ("20230229", "20261301", "20260001", "20260132", "00000101", "2026-11-07", "２０２６１１０７"):
            self.assert_error("INVALID_PARAMETER", "tourapi", "searchFestival2", {"eventStartDate": value}, parameter="eventStartDate")
        self.assert_error("INVALID_PARAMETER", "tourapi", "searchFestival2",
                          {"eventStartDate": "20261107", "eventEndDate": "20261106"}, parameter="eventEndDate")
        self.assert_error("INVALID_PARAMETER", "odii", "themeBasedSyncList", {"langCode": "ko", "modifiedtime": "20260230"}, parameter="modifiedtime")

    def test_tid_tlid_and_hierarchy_dependencies_follow_descriptions(self):
        for name in ("storyBasedList", "storyBasedSyncList"):
            self.assert_error("MISSING_PARAMETER", "odii", name, {"langCode": "ko", "tid": "1"}, parameter="tlid")
            self.assert_error("MISSING_PARAMETER", "odii", name, {"langCode": "ko", "tlid": "2"}, parameter="tid")
            self.assertEqual(prepare_parameters("odii", name, {"langCode": "ko", "tid": "", "tlid": ""})["tid"], "")
        for name in ("areaBasedList2", "locationBasedList2", "searchKeyword2", "searchFestival2",
                     "searchStay2", "areaBasedSyncList2"):
            extra = {"mapX": 127, "mapY": 37, "radius": 1} if name == "locationBasedList2" else {}
            extra.update({"keyword": "서울"} if name == "searchKeyword2" else {})
            extra.update({"eventStartDate": "20261107"} if name == "searchFestival2" else {})
            extra.update({"lDongRegnCd": "11"} if name == "searchStay2" else {})
            self.assert_error("MISSING_PARAMETER", "tourapi", name, {**extra, "lclsSystm2": "A01"}, parameter="lclsSystm1")
        self.assert_error("MISSING_PARAMETER", "tourapi", "areaBasedList2", {"lDongSignguCd": "001"}, parameter="lDongRegnCd")
        self.assert_error("MISSING_PARAMETER", "tourapi", "lclsSystmCode2",
                          {"lclsSystm1": "A", "lclsSystm3": "A0101"}, parameter="lclsSystm2")

    def test_deprecated_operations_and_parameters_remain_available(self):
        self.assertEqual({o.name for o in operations() if o.deprecated}, {"areaCode2", "categoryCode2"})
        self.assertEqual(prepare_parameters("tourapi", "areaCode2", {"areaCode": "1"})["areaCode"], "1")
        self.assertEqual(prepare_parameters("tourapi", "categoryCode2", {"cat1": "A01", "cat2": "A0101"})["cat2"], "A0101")
        self.assertEqual(prepare_parameters("tourapi", "areaBasedList2", {"areaCode": "1", "cat1": "A01"})["cat1"], "A01")
        self.assert_error("INVALID_PARAMETER", "tourapi", "detailCommon2", {"contentId": "1", "defaultYN": "Y"})

    def test_output_fields_keep_official_types_without_numeric_id_coercion(self):
        fields = operation("odii", "storyBasedList").item_schema["properties"]
        self.assertEqual(set(fields), {"tid", "tlid", "stid", "stlid", "title", "mapX", "mapY",
                                       "audioTitle", "script", "playTime", "audioUrl", "langCode",
                                       "imageUrl", "createdtime", "modifiedtime"})
        self.assertTrue(all(value["type"] == "string" for value in fields.values()))
        self.assertEqual(len(operation("tourapi", "detailIntro2").item_schema["properties"]), 125)
        self.assertEqual(len(operation("tourapi", "detailInfo2").item_schema["properties"]), 53)
        self.assertNotIn("required", operation("odii", "storyBasedList").item_schema)


if __name__ == "__main__":
    unittest.main()
