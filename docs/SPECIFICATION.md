# Specification — CellTrajectoryMock

**Document type:** High-level specification
**Project:** CellTrajectoryMock (Cell-ID Trajectory-Based Mock Location Provider)
**Version:** 1.0
**Status:** Implemented

---

## 1. Purpose

CellTrajectoryMock is an Android application that estimates a coarse
geographic position from cellular network identifiers and publishes it to the
Android location framework as a **mock location**. It is designed as a
**fallback positioning source for environments where GNSS (GPS) is jammed,
denied, or otherwise unavailable**.

The device's modem always knows which cell tower it is attached to, even when
satellite positioning fails. By mapping that tower identity to known
coordinates, the app can supply a usable — if coarse — location to any consumer
application on the device.

## 2. Problem statement

GNSS is the default positioning source on Android, but it fails in:

- indoor and underground environments,
- urban canyons with poor sky view,
- areas subject to deliberate GPS jamming or spoofing.

In these conditions the cellular radio remains connected. A cell tower has a
fixed, known location; the serving cell therefore constrains the device to a
bounded geographic zone. This project turns that constraint into an injected
location fix.

## 3. Goals

| ID | Goal |
|----|------|
| G1 | Resolve the serving cell tower to coordinates without GNSS. |
| G2 | Operate **offline-first** — no network dependency for the primary path. |
| G3 | Inject the estimate into Android's location framework as a mock fix. |
| G4 | Survive long screen-off periods and process restarts. |
| G5 | Degrade gracefully — partial data must still yield a usable fix. |

## 4. Functional requirements

| ID | Requirement |
|----|-------------|
| FR-1 | Read the registered serving cell's identity (MCC, MNC, LAC/TAC, CID) and signal strength from the modem. |
| FR-2 | Support GSM (2G), WCDMA/UMTS (3G), LTE (4G) and NR (5G) radio access technologies. |
| FR-3 | Resolve a cell identity to latitude/longitude/range via an embedded SQLite database compiled from [OpenCellID](https://opencellid.org) data. |
| FR-4 | Detect cell **handover** events and debounce ping-pong jitter before acting on them. |
| FR-5 | Estimate position by great-circle interpolation between the previous and current towers, biased by relative signal strength. |
| FR-6 | Derive speed and bearing from the displacement between successive fixes. |
| FR-7 | Inject the estimate into the `GPS_PROVIDER` and `NETWORK_PROVIDER` test providers. |
| FR-8 | Persist the most recent fix and re-inject it immediately after a process restart. |
| FR-9 | Run as a persistent foreground service so tracking continues with the screen off. |
| FR-10 | *(Optional)* When a cell is absent from the offline database, resolve it via the Google Geolocation API and cache the result on-device. |

## 5. Non-functional requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | **Offline-first.** The primary resolution path must require no network. |
| NFR-2 | No Google Play Services SDK dependency. The optional online path is a single plain HTTPS request. |
| NFR-3 | Minimum platform: Android 12 (API 31). Target/compile: API 35. |
| NFR-4 | All radio parsing, database I/O and network calls run off the UI thread. |
| NFR-5 | Database lookups must be single index-organised B-Tree descents (suitable for low-spec hardware). |
| NFR-6 | No secret (API key) is ever compiled into the APK or committed to source. |

## 6. Out of scope

- CDMA — retired on modern networks; not resolved.
- Sub-cell trilateration or RF fingerprinting — the estimate is tower-based.
- Hiding the mock nature of the fix — `Location.isMock()` is intentionally
  honest; this is a diagnostic/fallback tool, not a spoofing utility.
- Emulator support — a physical device with a SIM and a real modem is required.

## 7. Resolution model

The position estimate is a **trajectory interpolation**, not a point lookup:

1. Each committed handover yields a *previous* and a *current* tower.
2. The fix is a point on the great-circle arc between them.
3. The interpolation fraction is biased by the two towers' signal strengths
   (stronger tower pulls the fix toward itself).
4. With only one tower resolvable, the fix collapses to that tower's location.
5. With neither resolvable, no fix is produced for that handover.

Accuracy is reported honestly: the injected accuracy radius is the towers'
nominal range (hundreds of metres urban, kilometres rural). **Every fix is a
zone, not a point.**

## 8. Deviations from the original specification

The supplied specification contained five defects that would prevent the app
from building or functioning on current Android. All were corrected; see
[TECHNICAL-NOTES.md §7](TECHNICAL-NOTES.md#7-corrections-to-the-original-specification)
for the full detail. Summary:

1. Wrong XML namespace URI (build-breaking).
2. Reliance on `ACCESS_MOCK_LOCATION` (a no-op since Android 6.0).
3. Deprecated `addTestProvider` overload.
4. Unrealistic "millisecond" real-time handover capture.
5. Mathematically incorrect signal-strength weighting formula.

One scope change was made during implementation, with the project owner's
agreement: the strict-offline rule (NFR-1) was **relaxed to add an optional
online fallback** (FR-10), because the free OpenCellID dataset proved too
sparse to cover the test location's serving cell. The offline path remains
primary and the default.

## 9. Glossary

| Term | Meaning |
|------|---------|
| MCC | Mobile Country Code (e.g. 424 = UAE). |
| MNC | Mobile Network Code (identifies the carrier within a country). |
| LAC | Location Area Code (GSM/WCDMA). |
| TAC | Tracking Area Code (LTE/NR) — the LAC equivalent. |
| CID / CI | Cell Identity. For LTE this is the 28-bit ECI; for NR the 36-bit NCI. |
| RAT | Radio Access Technology (GSM, WCDMA, LTE, NR). |
| eNodeB | LTE base station. Its ID is the upper bits of the ECI; sectors share it. |
| Handover | The device changing its serving cell. |
| Mock provider | An Android test location provider fed synthetic fixes. |
| RSS | Received Signal Strength (reported in dBm). |
