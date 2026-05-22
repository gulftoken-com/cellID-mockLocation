# Software Solution — CellTrajectoryMock

**Document type:** Implementation reference
**Version:** 1.0

A module-by-module map of the codebase. Pair this with [DESIGN.md](DESIGN.md)
(why) — this document is the *what and where*.

---

## 1. Technology stack

| Aspect | Choice |
|--------|--------|
| Language | Kotlin |
| Build | Gradle 8.7 (wrapper) · Android Gradle Plugin 8.6.0 · Kotlin 2.0.21 |
| JDK | 17 (`sourceCompatibility`/`targetCompatibility` = 17, `jvmTarget` = 17) |
| Platform | `minSdk` 31 (Android 12) · `targetSdk`/`compileSdk` 35 |
| App ID | `com.example.celltrajectorymock` · `versionCode` 1 · `versionName` 1.0 |
| Async | `java.util.concurrent` executors; coroutines only for the UI status flow |
| Tests | JUnit 4 |
| Database | SQLite (`android.database.sqlite`), read-only |
| JSON / HTTP | `org.json` + `HttpsURLConnection` — both AOSP built-ins, zero extra deps |

### Dependencies (`app/build.gradle.kts`)

```
androidx.core:core-ktx                    1.13.1
androidx.appcompat:appcompat              1.7.0
com.google.android.material:material      1.12.0
androidx.lifecycle:lifecycle-runtime-ktx  2.8.4
org.jetbrains.kotlinx:kotlinx-coroutines-android 1.8.1
junit:junit (test)                        4.13.2
```

There is intentionally **no Google Play Services / location-services
dependency** — the online fallback is a hand-rolled HTTPS call.

## 2. Repository layout

```
cellID-mockLocation/
├── app/
│   ├── build.gradle.kts                 module build config
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/cells.db          embedded OpenCellID database
│       │   ├── java/com/example/celltrajectorymock/
│       │   │   ├── MainActivity.kt
│       │   │   ├── TrackingService.kt
│       │   │   ├── db/                  CellDatabase, CellNode, CellResolver
│       │   │   ├── net/                 GeolocationClient
│       │   │   ├── persist/             ApiKeyStore, LastFixStore, ResolvedCellCache
│       │   │   ├── state/               HandoverStateMachine
│       │   │   ├── telephony/           CellInfoCollector, CellSample
│       │   │   └── trajectory/          TrajectoryEngine
│       │   └── res/                     layout, values, drawable, mipmap
│       └── test/java/.../               unit tests (pure JVM)
├── tools/build_cell_db.py               OpenCellID CSV → cells.db compiler
├── docs/                                this documentation set
├── gradle/ · gradlew · gradlew.bat      Gradle wrapper
└── build.gradle.kts · settings.gradle.kts · gradle.properties
```

## 3. Source modules

### Entry points

#### `MainActivity.kt`
Single-screen control panel. Requests runtime permissions
(`ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`), opens
Developer Options for mock-app selection, starts/stops `TrackingService`, and
hosts the Google API-key entry field (saved via `ApiKeyStore`). Mirrors
`TrackingService.status` into the UI with `repeatOnLifecycle`.

#### `TrackingService.kt`
Persistent foreground service (`foregroundServiceType="location"`,
`START_STICKY`). On start it builds the pipeline on a worker thread:
constructs `CellDatabase`, `CellResolver`, `MockInjectionFabric`,
`HandoverStateMachine` and `CellInfoCollector`; restores the last fix; and
starts the re-push timer. `onValidHandover()` is the pipeline core — it
resolves both towers, calls `TrajectoryEngine.estimate()`, derives speed and
bearing, injects via `MockInjectionFabric`, persists via `LastFixStore`, and
publishes a human-readable status. Exposes a `StateFlow<String>` for the UI.

### `telephony/` — radio input

#### `CellInfoCollector.kt`
Wraps `TelephonyManager`. Registers a `TelephonyCallback.CellInfoListener` and
runs a 15 s `requestCellInfoUpdate` nudge timer. `toSample()` converts a
`CellInfoLte` / `CellInfoNr` / `CellInfoWcdma` / `CellInfoGsm` into a
`CellSample`, rejecting entries with unavailable identity fields. `dispatch()`
keeps the first *registered* cell and emits it.

#### `CellSample.kt`
Immutable data class: one serving-cell observation (`rat`, `mcc`, `mnc`,
`lacTac`, `cid: Long`, `dbm`, `timestampMillis`). `sameCellAs()` compares
identity; `key()` returns the compact string `"$rat/$mcc-$mnc-$lacTac-$cid"`
used for logs, notifications and cache keys.

### `state/` — debounce

#### `HandoverStateMachine.kt`
Converts a stream of `CellSample`s into committed `(previous, current)`
handover events. Dwell-time debounce with a tunable guard window (default
5 s); the first sample commits immediately. Rejects ping-pong. Not
thread-safe by design — single-threaded caller only. See
[DESIGN.md §6](DESIGN.md#6-handover-state-machine).

### `db/` — resolution

#### `CellNode.kt`
Immutable data class: resolved coordinates (`latitude`, `longitude`,
`rangeMeters`). Deliberately Android-free so it compiles and unit-tests on a
plain JVM.

#### `CellDatabase.kt`
Read-only access to the embedded SQLite database. `open()` extracts the
`cells.db` asset to app-private storage (and re-extracts on a size change —
see [TECHNICAL-NOTES.md §6](TECHNICAL-NOTES.md#6-the-cell-database)).
`lookup()` performs the composite-key query and returns a `CellNode` or `null`.

#### `CellResolver.kt`
The resolution cascade: offline DB → on-device cache → online API. Returns a
`Resolved(node, fromOnline)`. Maintains a session-scoped "unresolvable" set so
a cell that all sources miss is not retried on every handover. Online steps are
skipped entirely when no API key is present.

### `net/` — online fallback

#### `GeolocationClient.kt`
One plain HTTPS `POST` to the Google Geolocation API. Builds the cell-tower
JSON request (`org.json`), uses the `newRadioCellId` field for NR, includes
signal strength only when plausible, and forces `considerIp: false` so a miss
never degrades to an IP-based guess. Parses `location`/`accuracy` into a
`CellNode`. Any failure (network, bad key, 404) returns `null`.

### `trajectory/` — math

#### `TrajectoryEngine.kt`
Pure-JVM `object`. `interpolate()` — great-circle intermediate point;
`distanceMeters()` — haversine distance; `bearingDegrees()` — initial bearing;
`rssFraction()` — dBm → blend fraction (noise-floor shifted); `estimate()` —
orchestrates them into a `Fix`, degrading gracefully. Fully unit-tested.

### `mock/` — output

#### `MockInjectionFabric.kt`
Registers the `GPS_PROVIDER` and `NETWORK_PROVIDER` test providers using the
`ProviderProperties.Builder` overload (API 31+). `push()` builds a fully
populated `Location` (including `elapsedRealtimeNanos`, which the framework
requires for freshness) and injects it into both providers. `setUp()` reports
missing mock authorisation as a failed `Result`.

### `persist/` — state

| File | `SharedPreferences` file | Stores |
|------|--------------------------|--------|
| `LastFixStore.kt` | `last_fix` | Last fix (lat/lon as raw IEEE-754 bits). |
| `ResolvedCellCache.kt` | `resolved_cells` | Online-resolved cells (memory + disk). |
| `ApiKeyStore.kt` | `settings` | Google Geolocation API key. |

## 4. Resources

- `AndroidManifest.xml` — permissions, the foreground service, the launcher
  activity. `INTERNET` is declared for the online fallback.
- `res/layout/activity_main.xml` — the control panel (buttons, status text,
  API-key field).
- `res/values/` — `strings.xml`, `colors.xml`, `themes.xml`.
- `res/drawable/`, `res/mipmap-anydpi-v26/` — launcher icon.

## 5. Tests

Pure-logic unit tests under `app/src/test/` (no instrumentation, run on the
JVM):

| Test class | Coverage |
|------------|----------|
| `TrajectoryEngineTest` | Interpolation endpoints/midpoints, great-circle bulge, coincident points, RSS weighting, distance, bearing (4 cardinals), `estimate()` degradation paths. |
| `HandoverStateMachineTest` | First-sample commit, same-cell no-op, ping-pong rejection, candidate promotion after the guard window, aged-candidate promotion under sparse updates. |

Run with `./gradlew test`. The Android-dependent components (`CellDatabase`,
`MockInjectionFabric`, telephony, network) are not unit-tested — they are
verified on-device (see [BUILD-AND-CONFIGURE.md](BUILD-AND-CONFIGURE.md)).

## 6. Tooling — `tools/build_cell_db.py`

A standalone Python 3 script (standard library only) that compiles an
OpenCellID CSV export into `cells.db`. It reads columns positionally, keeps
GSM/UMTS/LTE/NR rows, creates the `WITHOUT ROWID` schema and bulk-inserts in
batches. Usage:

```sh
python3 tools/build_cell_db.py <opencellid.csv> app/src/main/assets/cells.db
```

See [BUILD-AND-CONFIGURE.md §5](BUILD-AND-CONFIGURE.md#5-rebuilding-the-cell-database).
