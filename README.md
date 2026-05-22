# CellTrajectoryMock

![CI](https://github.com/gulftoken-com/cellID-mockLocation/actions/workflows/ci.yml/badge.svg)

An offline Android app that estimates a coarse position from cellular tower
hand-overs and injects it into Android's location framework as a mock GPS fix.
Intended as a **fallback positioning source when GPS is jammed or unavailable**.

- **Offline-first** — an embedded OpenCellID database resolves towers with no
  network. An optional online fallback covers towers the offline data lacks.
- **No Google Play Services SDK** — the online fallback is one plain HTTPS call.
- minSdk 31 (Android 12) · targetSdk 35 · Kotlin · Gradle 8.7 / AGP 8.6 · JDK 17.

> Proprietary software — see [LICENSE](LICENSE). The bundled cell database
> derives from OpenCellID (CC BY-SA 4.0) — see [NOTICE](NOTICE).

---

## Quick start

```sh
./gradlew assembleDebug                                   # build
./gradlew test                                            # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk  # install
```

Then on the device: grant permissions → select the app under **Developer
Options → Select mock location app** → **Start tracking**. Full steps:
[docs/BUILD-AND-CONFIGURE.md](docs/BUILD-AND-CONFIGURE.md).

A physical device with a SIM is required — emulators have no modem.

## How it works

```
CellInfoCollector → HandoverStateMachine → CellResolver → TrajectoryEngine → MockInjectionFabric
```

The modem's serving-cell identity is read, debounced into handover events,
resolved to coordinates (offline database → on-device cache → optional online
API), interpolated along the great-circle arc between towers, and injected into
the `GPS_PROVIDER` and `NETWORK_PROVIDER` test providers.

## Documentation

| Document | Contents |
|----------|----------|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | High-level spec — purpose, requirements, scope. |
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture, pipeline, components, data flow, threading. |
| [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md) | Platform constraints, cell-ID details, spec corrections, gotchas. |
| [docs/SOLUTION.md](docs/SOLUTION.md) | Implementation reference — module-by-module map. |
| [docs/BUILD-AND-CONFIGURE.md](docs/BUILD-AND-CONFIGURE.md) | Build, rebuild the database, install, configure, troubleshoot. |
| [CLAUDE.md](CLAUDE.md) | Orientation for AI coding assistants. |
| [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) | Development workflow · vulnerability reporting. |

## Accuracy expectations

This is a **coarse fallback, not a GPS replacement.** A fix is a point on the
arc between transmitter masts, not your true location. Realistic error is the
cell range itself — a few hundred metres in dense urban areas, kilometres
rural. Updates lag because the OS throttles cell-info delivery. Every fix is a
zone; that is why the injected accuracy radius is deliberately large.

## Project status

Implemented and verified on a physical device. The five defects in the original
specification were corrected — see
[docs/TECHNICAL-NOTES.md §7](docs/TECHNICAL-NOTES.md#7-corrections-to-the-original-specification).
Mock fixes are honestly flagged (`Location.isMock()`); this is a development and
diagnostic tool by design.
