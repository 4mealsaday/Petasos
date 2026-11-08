"""Bounded metadata/semantic rejection checks; does not load geometry or call providers."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("traces", ROOT / "scripts/build-region-policy-traces.py")
traces = importlib.util.module_from_spec(spec)
try:
    spec.loader.exec_module(traces)
    HAS_GIS = True
except ModuleNotFoundError:
    HAS_GIS = False
FIXTURE = ROOT / "app/src/test/resources/regions/ngii-policy-crossings-v1.json"


@unittest.skipUnless(HAS_GIS, "Run with the isolated scripts/requirements-regions.txt environment")
class PublicTraceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = traces.strict_json(FIXTURE.read_bytes())

    def rejects(self, mutate):
        value = copy.deepcopy(self.original)
        mutate(value)
        with self.assertRaises((ValueError, TypeError, KeyError)):
            traces.validate(value)

    def test_generated_fixture_has_exact_public_scope_and_current_generator_hash(self):
        traces.validate(self.original)
        self.assertEqual(traces.digest(Path(traces.__file__).read_bytes()), self.original["generatorSha256"])
        self.assertEqual(243, sum(len(region["traces"]) for region in self.original["regions"]))

    def test_unknown_root_keys_are_not_ignored(self):
        self.rejects(lambda value: value.update(approved=True))

    def test_changed_manifest_or_hierarchy_pin_is_rejected(self):
        for key in ("manifestSha256", "hierarchySha256"):
            self.rejects(lambda value: value.update({key: "0" * 64}))

    def test_no_authority_or_observed_location_claim_can_be_added(self):
        for key in ("networkUsed", "deviceLocationUsed", "naverResponseStored"):
            self.rejects(lambda value: value.update({key: True}))
        self.rejects(lambda value: value.update(approvedPolicyCount=1))
        self.rejects(lambda value: value.update(approvedPolicyCount=False))

    def test_scope_units_and_oracle_model_are_pinned(self):
        self.rejects(lambda value: value.update(scope="OBSERVED_DRIVING"))
        self.rejects(lambda value: value.update(meterCrs="EPSG:4326"))
        self.rejects(lambda value: value["syntheticModel"].update(errorPeriodStepSamples=2))
        self.rejects(lambda value: value["libraries"].update(extra="unsupported"))

    def test_resource_caps_are_not_editable_metadata(self):
        self.rejects(lambda value: value["limits"].update(maxResidentPoints=1_000_000))
        self.rejects(lambda value: value.update(peakResidentGeometryPoints=100_001))
        self.rejects(lambda value: value.update(loadedGeometryPoints=750_001))

    def test_numeric_coordinates_and_nonfinite_coordinates_are_rejected(self):
        self.rejects(lambda value: value["coordinates"][0].__setitem__(0, 127.0))
        self.rejects(lambda value: value["coordinates"][0].__setitem__(0, "NaN"))
        self.rejects(lambda value: value["coordinates"][0].__setitem__(0, "140.000000000"))

    def test_missing_oracle_and_unknown_oracle_units_are_rejected(self):
        self.rejects(lambda value: value["oracle"].pop())
        self.rejects(lambda value: value["oracle"][0].__setitem__(0, "용인시"))
        self.rejects(lambda value: value["oracle"][0].__setitem__(1, -1))

    def test_region_order_and_exact_neighbor_are_required(self):
        self.rejects(lambda value: value["regions"].reverse())
        self.rejects(lambda value: value["regions"][0].update(neighborCode=value["regions"][0]["code"]))
        self.rejects(lambda value: value["regions"][0].update(neighborCode="99999"))

    def test_missing_sampling_rate_or_added_trace_field_is_rejected(self):
        self.rejects(lambda value: value["regions"][0]["traces"].pop())
        self.rejects(lambda value: value["regions"][0]["traces"][0].update(source="device"))

    def test_time_gap_backtracking_and_hidden_sampling_are_rejected(self):
        self.rejects(lambda value: value["regions"][0]["traces"][0]["samples"].pop(2))
        self.rejects(lambda value: value["regions"][0]["traces"][0]["samples"][2].__setitem__(0, 0))
        self.rejects(lambda value: value["regions"][0]["traces"][0]["samples"][2].append(9))
        self.rejects(lambda value: value["regions"][0]["traces"][0]["samples"][2].__setitem__(2, 8192))

    def test_json_duplicates_nonfinite_and_boolean_timestamp_are_rejected(self):
        with self.assertRaises(ValueError):
            traces.strict_json(b'{"formatVersion":1,"formatVersion":1}')
        with self.assertRaises(ValueError):
            traces.strict_json(b'{"value":NaN}')
        self.rejects(lambda value: value["regions"][0]["traces"][0]["samples"][0].__setitem__(0, False))
        self.rejects(lambda value: value["ratesHz"].__setitem__(0, True))
        self.rejects(lambda value: value["regions"][0]["traces"][0].update(rateHz=True))

    def test_every_region_rate_accuracy_bounce_observes_both_sides_without_true_entry(self):
        oracle = self.original["oracle"]
        count = 0
        for region in self.original["regions"]:
            for trace in region["traces"]:
                if trace["kind"] != "outside_boundary_bounce":
                    continue
                self.assertEqual({region["neighborCode"]}, {oracle[row[1]][0] for row in trace["samples"]})
                measured = {oracle[row[2]][0] for row in trace["samples"]}
                self.assertIn(region["code"], measured)
                self.assertIn(region["neighborCode"], measured)
                count += 1
        self.assertEqual(81, count)

    def test_removing_bounce_inside_observations_is_rejected_as_aliased_scenario(self):
        def suppress(value):
            for trace in value["regions"][0]["traces"]:
                if trace["kind"] == "outside_boundary_bounce":
                    for row in trace["samples"]:
                        row[2] = row[1]
        self.rejects(suppress)

    def test_true_trajectory_bounce_never_enters_target(self):
        self.assertTrue(all(traces.true_offset("outside_boundary_bounce", time) < 0 for time in range(0, 6001, 200)))
        self.assertEqual(0, traces.true_offset("moving_dwell", 4000))
        self.assertEqual(80, traces.true_offset("moving_dwell", 8000))
        self.assertEqual(80, traces.true_offset("moving_dwell", 14000))
        self.assertEqual(-80, traces.true_offset("moving_dwell", 22000))
        self.assertEqual(30, traces.true_offset("short_pass", 4000))


if __name__ == "__main__":
    unittest.main()
