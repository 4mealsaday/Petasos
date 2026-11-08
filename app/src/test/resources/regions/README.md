# NGII packed boundary regression oracle

`ngii-d7a3751bd89f-oracle.tsv` contains the 264 public administrative codes,
statuses, ring/point counts, and GEOS interior representative points from
`artifacts/milestone-18/region-dataset-audit.json`. The header pins the original
ZIP and compiled manifest. Coordinates are public source geometry, not sampled
device locations. Quarantined representative points only exercise withholding;
they are never treated as valid containment results.

`NgiiPackedRegionDatasetTest` independently checks Kotlin containment against
these GEOS points and streams every ready asset completely. This catches
compatibility problems in the actual coastal rings while keeping the test JVM
heap bounded. The TSV is small; no binary geometry is copied into test resources.

To regenerate after reviewing a new compiler audit, run from the project root:

```sh
python3 - <<'PY'
import json
from pathlib import Path

audit = json.loads(Path('artifacts/milestone-18/region-dataset-audit.json').read_text())
lines = [
    '# Independent geometry oracle from artifacts/milestone-18/region-dataset-audit.json.',
    '# Original ZIP SHA-256: ' + audit['zipSha256'],
    '# Compiled manifest SHA-256: ' + audit['manifestSha256'],
    '# Shapely ' + audit['libraries']['shapely'] + ' / GEOS ' + audit['libraries']['geos']
        + ' representative_point() on EPSG:4326 transformed geometry.',
    '# Public administrative geometry only; not a user/device location or a visit policy.',
    '# Quarantined rows are retained for inventory, never an accepted containment oracle.',
    '# code\tstatus\tclassification\tringCount\tpointCount\tlongitude\tlatitude',
]
for region in audit['regions']:
    longitude, latitude = region['representativePoint']
    lines.append('\t'.join(map(str, [region['code'], region['status'], region['classification'],
        region['ringCount'], region['pointCount'], longitude, latitude])))
Path('app/src/test/resources/regions/ngii-d7a3751bd89f-oracle.tsv').write_text('\n'.join(lines) + '\n')
PY
```

Review any changed quarantine list, counts, source identity, and manifest digest
before updating the explicit expectations in `NgiiPackedRegionDatasetTest` and
the production manifest pin. Regenerating an oracle does not establish source
rights, effective dates, region selection policy, or location quality semantics.
