# Contributing

This is a proprietary project (see [LICENSE](LICENSE)). Contributions are
limited to authorised collaborators. This guide describes the development
workflow for those collaborators.

## Getting set up

1. Install **JDK 17** and the **Android SDK** (platform 35).
2. Create `local.properties` in the project root with `sdk.dir=/path/to/sdk`
   (this file is git-ignored — never commit it).
3. Build and test:

   ```sh
   ./gradlew assembleDebug
   ./gradlew test
   ```

Full instructions: [docs/BUILD-AND-CONFIGURE.md](docs/BUILD-AND-CONFIGURE.md).

## Before you start

Read [CLAUDE.md](CLAUDE.md) and [docs/DESIGN.md](docs/DESIGN.md) to understand
the pipeline architecture.

## Branching & commits

- Branch off `main`; never commit directly to `main`.
- Branch names: `feature/<short-name>`, `fix/<short-name>`, `docs/<short-name>`.
- Write focused commits with clear messages explaining *why*.

## Pull requests

- Open a PR against `main`. CI (`.github/workflows/ci.yml`) must pass —
  it builds the debug APK and runs the unit tests.
- Keep PRs small and single-purpose.
- Update the relevant `docs/` file in the same PR when behaviour changes.

## Code standards

- **Kotlin**, official code style (`kotlin.code.style=official`).
- **Never block the UI thread** — radio, database and network work belongs on
  background executors.
- **Keep pure-logic classes Android-free.** `TrajectoryEngine`, `CellNode`,
  `CellSample` and `HandoverStateMachine` must compile and test on a plain JVM.
  Do not add Android imports to them.
- Comments explain *why*, not *what*; match the surrounding density and tone.
- Add a unit test for new non-trivial logic wherever it is JVM-testable.

## Security

- Never hard-code, log, or commit API keys or other secrets.
- Do not commit `local.properties`, build outputs, or IDE files.
- For vulnerabilities, follow [SECURITY.md](SECURITY.md) — do not open a public
  issue.

## Testing

```sh
./gradlew test
```

Android-dependent components (telephony, SQLite, mock injection, networking)
are verified on a physical device — see
[docs/BUILD-AND-CONFIGURE.md §8](docs/BUILD-AND-CONFIGURE.md#8-verifying-it-works).
Emulators have no modem and cannot exercise the cell pipeline.
