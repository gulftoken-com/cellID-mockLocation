# Technical Notes — CellTrajectoryMock

**Document type:** Technical notes, platform constraints, decisions
**Version:** 1.0

This document records the non-obvious engineering realities behind the
implementation — the things that are not visible from the code alone.

---

## 1. Android cell-info delivery is throttled

The original concept assumed millisecond-resolution, real-time handover
capture. Android does not provide this. `TelephonyManager` delivers `CellInfo`
updates on the order of **tens of seconds**, and far more slowly with the
screen off. `requestCellInfoUpdate()` can nudge the modem, but it is itself
rate-limited by the framework.

Consequences for the design:

- The `HandoverStateMachine` guard window (5 s) is generous because the OS has
  usually already smoothed away sub-second jitter before the app sees it.
- The re-push timer (10 s) keeps the last fix "fresh" for consumers between the
  genuinely infrequent handover events.
- The app is a **coarse fallback**, never a real-time tracker.

## 2. Neighbour cells expose only a PCI

A natural idea is multi-cell positioning: resolve every cell the phone can see
and trilaterate. **This does not work on Android for LTE/NR.**

`getAllCellInfo()` returns the serving cell *with* its full global identity
(MCC/MNC/TAC/ECI), but neighbour cells typically carry only a **Physical Cell
Identity (PCI)** — a 0–503 value that is *not* globally unique and cannot be
looked up in any cell database. Observed on the test device: ~23 visible cells,
but only **4** with a full identity (all serving-capable, same Tracking Area).

The app therefore resolves the **serving cell only**. Multi-cell positioning
would require the neighbour global IDs the platform does not provide.

## 3. The mock-location authorisation model

`ACCESS_MOCK_LOCATION` has been a **no-op since Android 6.0 (API 23)**. It is
kept in the manifest, commented, purely for traceability to the spec.

Real authorisation: the user must select the app under **Settings → Developer
Options → Select mock location app**. While selected, `addTestProvider`,
`setTestProviderEnabled` and `setTestProviderLocation` succeed; otherwise they
throw `SecurityException`, which `MockInjectionFabric.setUp()` reports as a
failed `Result`.

## 4. The Fused Location Provider may bypass the mock

The app injects into `GPS_PROVIDER` and `NETWORK_PROVIDER`. Apps that read
those providers directly receive the mock fix.

However, the **Fused Location Provider** (used by Google Maps and most modern
apps) blends sources and prefers the most accurate one. When real GNSS is
available (e.g. ±16 m) it can outrank the mock (e.g. ±1200 m), so a fused-API
consumer may still show the true location. This is expected and acceptable:
in the **GPS-denied** scenario the app targets, there is no competing accurate
fix, so the fuser falls back to the mock. Verified via `dumpsys location`:
both test providers carry the injected fix; the fused provider holds the real
fix only while genuine GNSS is present.

## 5. Cell identity arithmetic

### LTE — ECI decomposition

The LTE E-UTRAN Cell Identity (ECI) is 28 bits and decomposes as:

```
ECI = (eNodeB ID << 8) | Sector ID        # Sector is the low 8 bits
eNodeB ID = ECI / 256
Sector ID = ECI % 256
```

All sectors of one eNodeB are **the same physical tower**. Example from the
test device: serving cell ECI `11700992` = eNodeB `45707`, sector `0`; a later
handover to ECI `11701010` = the *same* eNodeB `45707`, sector `18`.

### NR — NCI

The 5G New Radio Cell Identity (NCI) is **36 bits** and does not fit in an
`Int`. `CellSample.cid` is therefore a `Long`, and the Google Geolocation API
request uses the dedicated `newRadioCellId` field for NR.

### LAC vs TAC

GSM/WCDMA report a Location Area Code; LTE/NR report a Tracking Area Code.
They occupy the same `lacTac` field in `CellSample` and the same `lac_tac`
column in the database — the RAT determines which it is.

### API naming trap

`CellIdentityLte.getCid()` does **not** exist. The accessor is `getCi()`
(returning the ECI). The original spec referenced the non-existent method.

## 6. The cell database

### Schema

```sql
CREATE TABLE cell_nodes (
    mcc          INTEGER NOT NULL,
    mnc          INTEGER NOT NULL,
    lac_tac      INTEGER NOT NULL,
    cid          INTEGER NOT NULL,
    latitude     REAL    NOT NULL,
    longitude    REAL    NOT NULL,
    range_meters INTEGER DEFAULT 1000,
    PRIMARY KEY (mcc, mnc, lac_tac, cid)
) WITHOUT ROWID;
```

`WITHOUT ROWID` + a composite primary key makes the table index-organised: a
lookup is a single B-Tree descent with no secondary index, which matters on
low-spec hardware.

### OpenCellID CSV format

The compiler (`tools/build_cell_db.py`) ingests an OpenCellID CSV export.
Column order:

```
radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
  0    1   2   3    4    5    6   7    8      9        10       11      12        13
```

**The per-MCC country files ship without a header row** — line 1 is already
data. The compiler reads columns *positionally* with `csv.reader` and skips any
row whose first field is not a known RAT, which transparently tolerates both a
headerless file and a full export that does carry a header. (An earlier
`csv.DictReader` version silently produced zero rows on the headerless files.)

### Asset auto-refresh

`CellDatabase` extracts the bundled asset to app-private storage on first run.
It also re-extracts whenever the asset's byte size differs from the extracted
copy (`AssetInputStream.available()` reports the full uncompressed length; a
full copy makes the target exactly that size). Shipping a rebuilt database
therefore needs only a reinstall — never a manual "clear app data".

### `radio` column omitted

The schema has no `radio` column, so in theory a GSM cell id could collide with
an LTE/NR id sharing the same MCC/MNC/LAC-TAC. The risk is low. For a large
multi-RAT database, add a `radio` column to the schema and key before
compiling to eliminate it.

## 7. Corrections to the original specification

The supplied specification had five defects; all are corrected in this
implementation.

| # | Spec said | Reality | Fix |
|---|-----------|---------|-----|
| 1 | `xmlns:android="http://google.com"` | Build-breaking — must be the AOSP schema URI. | Correct URI in `AndroidManifest.xml`. |
| 2 | `ACCESS_MOCK_LOCATION` grants spoofing | No-op since Android 6.0. | Authorisation via Developer Options; permission kept only as a commented spec trace. |
| 3 | `addTestProvider(...)` 11-arg boolean form | Deprecated since API 30. | `MockInjectionFabric` uses the `ProviderProperties.Builder` overload (API 31+). |
| 4 | Millisecond real-time handover capture | `CellInfo` delivery is throttled to tens of seconds. | Documented; debounce guard window made tunable. See §1. |
| 5 | RSS weight `\|P_A\| / (\|P_A\|+\|P_B\|)` | Mishandles the dBm sign (stronger signal → smaller `\|dBm\|`) and treats a logarithmic quantity as linear. | `TrajectoryEngine.rssFraction()` shifts each reading above a noise floor first. |

Also corrected: the spec's "kernel/HAL-level injection" description — the
Android Mock Provider API is an ordinary framework API, not a HAL hook.

## 8. The OpenCellID coverage gap (and why an online fallback exists)

OpenCellID's free crowdsourced data is **incomplete**. Its whole-UAE export
(`424.csv`) contained ~26,000 cells; the test device's actual serving tower —
and every sector of its eNodeB — was absent. Combined with the PCI-only
neighbour limitation (§2), the device could see no resolvable cell at all at
its location.

A Tracking-Area-centroid fallback was evaluated and rejected: UAE Tracking
Areas in the data span 30–60 km, so a centroid "fix" would be tens of km off.

The chosen solution (with the project owner's agreement) was an **optional
online fallback** to the Google Geolocation API, whose cell database is far
denser. It is reached only on an offline-database miss, results are cached
on-device, and the app remains pure-offline when no API key is configured.

## 9. Known limitations / rough edges

- **Speed/bearing noise.** Speed and heading are derived from the displacement
  between two coarse cell fixes over the handover interval. Far-apart
  consecutive handovers can produce a large apparent speed; it is clamped at
  100 m/s. Treat injected speed/bearing as indicative only.
- **No multi-cell trilateration** — see §2.
- **Online accuracy** — a single-cell Google lookup yields a few hundred
  metres to ~1–2 km accuracy. Multi-tower requests would be tighter but require
  neighbour global IDs the platform withholds.
- **Emulators unsupported** — no real modem; a physical device with a SIM is
  required.
