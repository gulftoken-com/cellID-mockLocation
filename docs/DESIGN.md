# Design — CellTrajectoryMock

**Document type:** Architecture & design
**Version:** 1.0

---

## 1. Overview

CellTrajectoryMock is a single-process Android app. A persistent foreground
service hosts a linear data pipeline that converts raw modem cell observations
into injected mock location fixes.

```
 ┌──────────────────┐   CellSample    ┌─────────────────────┐
 │ CellInfoCollector │ ──────────────▶ │ HandoverStateMachine │
 │  (modem callback) │                 │  (debounce / guard)  │
 └──────────────────┘                  └──────────┬──────────┘
                                                   │ (previous, current)
                                                   ▼
 ┌──────────────────┐   CellNode      ┌─────────────────────┐
 │   CellResolver    │ ◀────────────── │   TrackingService    │
 │ DB → cache → API  │ ──────────────▶ │  (pipeline owner)    │
 └──────────────────┘                  └──────────┬──────────┘
                                                   │ Fix (lat, lon, ±acc)
                                                   ▼
                                        ┌─────────────────────┐
                                        │  TrajectoryEngine    │  great-circle
                                        │  (estimate)          │  interpolation
                                        └──────────┬──────────┘
                                                   ▼
                                        ┌─────────────────────┐
                                        │ MockInjectionFabric  │ ─▶ Android
                                        │ (test providers)     │    LocationManager
                                        └─────────────────────┘
```

## 2. Components

| Component | Responsibility |
|-----------|----------------|
| `MainActivity` | Control panel: permissions, mock-app selection, start/stop, API-key entry. |
| `TrackingService` | Foreground service. Owns and wires the pipeline; computes speed/bearing; publishes status. |
| `CellInfoCollector` | Subscribes to modem `CellInfo` callbacks; extracts the registered serving cell as a `CellSample`. |
| `CellSample` | Immutable value type — one serving-cell observation (RAT, MCC/MNC/LAC-TAC/CID, dBm, timestamp). |
| `HandoverStateMachine` | Debounces raw observations into committed `(previous, current)` handover events; rejects ping-pong. |
| `CellResolver` | Resolves a `CellSample` to a `CellNode` via the cheapest available source. |
| `CellDatabase` | Read-only access to the embedded OpenCellID SQLite database. |
| `ResolvedCellCache` | On-device cache of online-resolved cells (memory + `SharedPreferences`). |
| `GeolocationClient` | Optional online fallback — one HTTPS POST to the Google Geolocation API. |
| `CellNode` | Immutable value type — resolved coordinates (lat, lon, range). |
| `TrajectoryEngine` | Pure math: great-circle interpolation, haversine distance, bearing, RSS weighting. |
| `MockInjectionFabric` | Registers the GPS/NETWORK test providers and injects `Location` objects. |
| `LastFixStore` | Persists the most recent fix across process restarts. |
| `ApiKeyStore` | Stores the user's Google API key in private `SharedPreferences`. |

## 3. Data flow

1. **Observe** — `CellInfoCollector` receives a `CellInfo` list from the modem
   (event-driven plus a 15 s timer nudge). It keeps the first *registered*
   cell it understands and emits a `CellSample`.
2. **Debounce** — `HandoverStateMachine` decides whether the sample represents
   a genuine handover. A new cell becomes a *candidate*; it is only *committed*
   after it has been the serving cell for the guard window (default 5 s). The
   first ever sample is committed immediately so there is an initial fix.
3. **Resolve** — for the previous and current cells, `CellResolver` returns a
   `CellNode` using the resolution cascade (§5).
4. **Estimate** — `TrajectoryEngine.estimate()` produces a `Fix`: a great-circle
   point between the two towers, biased by signal strength.
5. **Inject** — `TrackingService` derives speed/bearing from the displacement
   since the last fix, then `MockInjectionFabric.push()` writes a `Location`
   into every test provider.
6. **Persist & repeat** — the fix is saved by `LastFixStore`; a 10 s timer
   re-pushes it so consumers keep seeing a fresh location between handovers.

## 4. Threading model

The app is strictly off-main-thread for all radio, I/O and network work.

| Thread | Owner | Work |
|--------|-------|------|
| Main / UI | `MainActivity` | Buttons, status text, permission dialogs only. |
| Service worker (single) | `TrackingService` | One-shot pipeline initialisation. |
| Collector callback (single) | `CellInfoCollector` | `CellInfo` parsing → state machine → handover → resolve → estimate → inject. The online network call (if reached) blocks this thread briefly; it is serialised and rare. |
| Nudge timer (single) | `CellInfoCollector` | Periodic `requestCellInfoUpdate`. |
| Re-push timer (single) | `TrackingService` | Periodic re-injection of the last fix. |

`@Volatile` fields and `ConcurrentHashMap` guard the few values shared across
these threads. The `HandoverStateMachine` is deliberately **not** thread-safe —
it is only ever called from the collector callback thread.

## 5. Resolution cascade

`CellResolver` tries sources cheapest-first and stops at the first hit:

| Step | Source | Cost | Notes |
|------|--------|------|-------|
| 1 | `CellDatabase` (offline OpenCellID) | instant, free | Primary path. |
| 2 | `ResolvedCellCache` | instant, free | Past online hits, persisted. |
| 3 | `GeolocationClient` (Google API) | one network request | Only if an API key is set. |

A cell that **all three** fail to resolve is added to a session-scoped
"unresolvable" set, so the network is not retried for it on every handover.
With no API key entered, steps 2–3 never run and the app is pure-offline.

## 6. Handover state machine

The machine exists to suppress **ping-pong** — rapid bouncing between two
towers near a cell boundary — which would otherwise teleport the injected fix
back and forth.

- **First sample** → committed immediately (`previous = null`). Guarantees an
  initial fix without waiting for a handover.
- **Same cell as committed** → no event; any pending candidate is dropped.
- **New cell** → becomes the *candidate*, timestamped.
- **Candidate re-seen after the guard window** → committed as a handover.
- **A different cell before the candidate ages** → replaces the candidate.
  This is precisely how a bounce back to the previous tower is silently
  rejected.
- **Sparse updates** — an aged candidate is promoted even if the *next* sample
  is a third cell, so genuine handovers are not lost when updates arrive slowly.

## 7. Trajectory estimation

`TrajectoryEngine` is a pure-JVM `object` with no Android dependencies, so it
is fully unit-testable.

- **`interpolate`** — the general great-circle intermediate-point formula. At
  `fraction = 0.5` it is the geometric midpoint; other fractions allow a
  signal-weighted blend. Coincident points fall back to a linear blend to
  avoid a divide-by-zero.
- **`rssFraction`** — converts two dBm readings into a blend fraction. dBm is
  negative and logarithmic, so each reading is shifted above an assumed noise
  floor (−140 dBm) to a positive score before normalising. A heuristic, not a
  calibrated path-loss inversion — but directionally correct.
- **`distanceMeters` / `bearingDegrees`** — haversine distance and initial
  great-circle bearing, used to derive speed and heading.
- **`estimate`** — orchestrates the above and degrades gracefully: two towers →
  interpolated fix; one tower → that tower; zero → `null`.

## 8. Persistence

| Store | Backing | Contents |
|-------|---------|----------|
| `LastFixStore` | `SharedPreferences` ("last_fix") | Latest fix; lat/lon as raw IEEE-754 bits (no double accessor exists). Re-injected on restart. |
| `ResolvedCellCache` | `SharedPreferences` ("resolved_cells") | Online-resolved cells, keyed by cell identity string. |
| `ApiKeyStore` | `SharedPreferences` ("settings") | Google Geolocation API key. |
| `CellDatabase` | App-private SQLite file | The cell database, extracted from the bundled asset. |

The bundled `cells.db` asset is re-extracted automatically whenever its byte
size differs from the extracted copy — so shipping a new database needs only a
reinstall, never a manual "clear app data".

## 9. Error handling and degradation

| Failure | Behaviour |
|---------|-----------|
| Not selected as mock-location app | `MockInjectionFabric.setUp()` returns failure; status reports it; no crash. |
| `cells.db` fails to open | Logged; resolver falls through to cache/online; no crash. |
| Cell absent from all sources | Handover reported as "tower not found"; previous fix kept alive by the re-push timer. |
| Network down / API key rejected | `GeolocationClient` returns `null`; treated as a normal miss. |
| Process killed by the OS | `START_STICKY` restart; `LastFixStore` re-injects the last fix immediately. |

## 10. Security & privacy

- The Google API key is entered on-device and stored only in private
  `SharedPreferences`. It is never compiled into the APK or committed.
- The online path transmits only cell identifiers (MCC/MNC/TAC/CID + signal
  strength) to Google, and only on an offline-database miss.
- Injected fixes are honestly flagged as mock (`Location.isMock() == true`).
- Mock injection is authorised by the user explicitly selecting the app under
  Developer Options — it cannot be enabled silently.
