# CLAUDE.md — Project knowledge for AI assistants

Orientation for Claude Code (and similar tools) working in this repository.
Read this first, then the `docs/` set for depth.

## What this is

CellTrajectoryMock — an Android app that estimates a coarse position from
cellular tower identifiers and injects it into Android's location framework as
a mock location. It is a fallback positioning source for GPS-denied
environments. See [docs/SPECIFICATION.md](docs/SPECIFICATION.md).

## Build & test commands

```sh
./gradlew assembleDebug     # build the debug APK -> app/build/outputs/apk/debug/
./gradlew test              # run pure-JVM unit tests
./gradlew testDebugUnitTest # same, debug variant only
```

CLI builds need `local.properties` with `sdk.dir=...` (git-ignored).
Rebuild the cell database with `python3 tools/build_cell_db.py <csv> app/src/main/assets/cells.db`.

## Architecture in one line

`CellInfoCollector → HandoverStateMachine → CellResolver → TrajectoryEngine → MockInjectionFabric`,
all owned by the `TrackingService` foreground service.
Full detail: [docs/DESIGN.md](docs/DESIGN.md).

## Layout

- `app/src/main/java/com/example/celltrajectorymock/` — Kotlin source, by package:
  `telephony/`, `state/`, `db/`, `trajectory/`, `net/`, `mock/`, `persist/`.
- `app/src/main/assets/cells.db` — embedded OpenCellID database (binary).
- `app/src/test/` — JUnit 4 unit tests (`TrajectoryEngine`, `HandoverStateMachine`).
- `tools/build_cell_db.py` — OpenCellID CSV → `cells.db` compiler.
- `docs/` — specification, design, technical notes, solution map, build guide.

## Conventions

- **Kotlin**, `minSdk` 31, `compileSdk`/`targetSdk` 35, JDK 17.
- **Nothing blocks the UI thread.** Radio parsing, DB I/O and the network call
  all run on `java.util.concurrent` executors. Coroutines are used only for the
  UI status `StateFlow`.
- **Pure-logic classes stay Android-free** (`TrajectoryEngine`, `CellNode`,
  `HandoverStateMachine`, `CellSample`) so they unit-test on a plain JVM. Keep
  it that way — do not add Android imports to them.
- Comments explain *why*, not *what*; match the existing density and tone.
- New non-trivial logic should come with a unit test where it is JVM-testable.

## Gotchas (see docs/TECHNICAL-NOTES.md for the full list)

- `CellInfo` delivery is throttled to tens of seconds — this is not real-time.
- LTE/NR **neighbour** cells expose only a PCI, not a global ID — they cannot
  be resolved. Only the serving cell is.
- Mock injection is authorised by the user selecting the app under Developer
  Options, **not** by a manifest permission.
- The Google API key lives only in `SharedPreferences` — never hard-code it,
  log it, or commit it.
- `cells.db` is re-extracted automatically on an asset size change; no manual
  "clear app data" is needed.

## Constraints

- Keep the offline path dependency-free of Google Play Services.
- Do not commit secrets, `local.properties`, or build outputs.
- `cells.db` derives from OpenCellID (CC BY-SA 4.0) — preserve `NOTICE`.
