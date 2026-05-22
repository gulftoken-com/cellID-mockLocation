# Build & Configure — CellTrajectoryMock

**Document type:** Build, configuration and operations guide
**Version:** 1.0

How to build the app, compile the cell database, install it, configure it on a
device, and verify it is working.

---

## 1. Prerequisites

| Requirement | Notes |
|-------------|-------|
| JDK 17 | Required by AGP 8.6. `java -version` must report 17. |
| Android SDK | Platform 35 + build-tools. Android Studio installs these automatically. |
| `adb` | Android Platform Tools, for installing on a device. |
| Python 3 | Standard library only — needed only to rebuild `cells.db`. |
| A physical Android device | Android 12+ (API 31), with a SIM and a real modem. **Emulators are not supported** — they have no modem. |

The Gradle wrapper (`./gradlew`) pins Gradle 8.7; no separate Gradle install is
needed.

### SDK location

CLI builds need `local.properties` in the project root pointing at the SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

This file is **git-ignored** and must never be committed. Android Studio
creates it automatically.

## 2. Building the APK

From the project root:

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

For a release build, `./gradlew assembleRelease` (configure signing first).

## 3. Running the tests

```sh
./gradlew test
```

This runs the pure-JVM unit tests for `TrajectoryEngine` and
`HandoverStateMachine`. No device or SDK emulator is required.

## 4. Installing on a device

1. Enable **USB debugging** on the device (Settings → Developer Options).
2. Connect it and confirm it is visible:

   ```sh
   adb devices
   ```

   If `adb` is not on your `PATH`, invoke it by full path — on macOS with the
   Homebrew command-line tools it is at
   `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`.

3. Install (the `-r` flag reinstalls over an existing copy):

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## 5. Rebuilding the cell database

The bundled `app/src/main/assets/cells.db` is compiled from an
[OpenCellID](https://opencellid.org) CSV export.

1. Create a free OpenCellID account and download the per-country (per-MCC) CSV
   for your region — e.g. `424.csv` for the UAE.
2. Compile it:

   ```sh
   python3 tools/build_cell_db.py /path/to/424.csv app/src/main/assets/cells.db
   ```

   The script prints the number of cell nodes written. A count of **0** means
   the input format was not recognised — verify it is an OpenCellID CSV.
3. Rebuild and reinstall the APK (steps 2 and 4).

No "clear app data" is needed: `CellDatabase` detects the asset's size change
and re-extracts it automatically on the next service start.

> **Licensing.** `cells.db` is a derivative of OpenCellID data, licensed
> CC BY-SA 4.0. See [`NOTICE`](../NOTICE).

## 6. On-device configuration

Open the **CellTrajectoryMock** app and complete the numbered steps:

### Step 1 — Grant permissions
Tap **1 · Grant permissions** and allow location, phone, and notifications.
`ACCESS_FINE_LOCATION` is mandatory — without it the modem will not return cell
info.

### Step 2 — Select as the mock-location app
Tap **2 · Open Developer Options**, then **Select mock location app** →
**CellTrajectoryMock**.

- If Developer Options is hidden: Settings → About phone → tap **Build number**
  seven times.
- This selection — **not** any manifest permission — is what authorises mock
  injection.

### Step 3 (optional) — Online lookup key
To resolve towers missing from the offline database, paste a **Google
Geolocation API key** into the *Online lookup* field and tap **Save API key**.
See §7. Leave it blank to run pure-offline.

### Step 4 — Start
Tap **3 · Start tracking**. A persistent notification appears and the on-screen
status updates with each fix.

## 7. Obtaining a Google Geolocation API key

The online fallback uses the **Google Geolocation API**. Its key is a Google
Cloud key — it is **not** the same as an OpenCellID `pk.` token.

1. Go to <https://console.cloud.google.com> and create or select a project.
2. Enable the **Geolocation API** for that project.
3. **APIs & Services → Credentials → Create credentials → API key**. The key
   looks like `AIzaSy…`.
4. Enable **billing** on the project. The API is pay-as-you-go (~$5 per 1000
   requests) with a recurring monthly free allowance. On-device caching keeps
   real usage to a handful of requests.
5. **Restrict the key** — Application restrictions → Android apps (package
   `com.example.celltrajectorymock`), and API restrictions → Geolocation API
   only.

The key is stored only in the app's private `SharedPreferences`. It is never
compiled into the APK or committed to source.

## 8. Verifying it works

A successful fix shows in the status line and notification, e.g.:

```
Fix 25.07792, 55.14304  ±1114m  7m/s  320°  [LTE/424-2-10120-11701010] online
```

The trailing `online` indicates the cell was resolved via the API (absent ⇒
resolved from the offline database).

Confirm the mock is reaching the location framework:

```sh
adb shell dumpsys location | grep -A2 "mock"
```

Both `gps provider [mock]` and `network provider [mock]` should show the
injected coordinates with `identity=com.example.celltrajectorymock`.

## 9. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Status: "not the selected mock app" | Complete §6 Step 2 — select the app under Developer Options. |
| "tower not found (offline DB or online)" | The cell is in neither source. Add a Google API key (§7), or move to a mapped area. |
| No fix at all, stationary | Wait — `CellInfo` is throttled; the first lock may take tens of seconds. Check location permission is granted. |
| Online lookup fails — HTTP 400/403 | Bad key, billing not enabled, or Geolocation API not enabled on the project. |
| Online lookup fails — HTTP 404 | Google has no record of that cell either. |
| Google Maps still shows real location | The Fused Location Provider prefers the more accurate real GNSS fix. Expected — see [TECHNICAL-NOTES.md §4](TECHNICAL-NOTES.md#4-the-fused-location-provider-may-bypass-the-mock). |
| `build_cell_db.py` writes 0 rows | The input is not an OpenCellID CSV, or is empty. |
| `adb: command not found` | Platform Tools not on `PATH` — use the full path (§4). |

## 10. CI

`/.github/workflows/ci.yml` builds the debug APK and runs the unit tests on
every push and pull request, and uploads the APK as a build artifact.
