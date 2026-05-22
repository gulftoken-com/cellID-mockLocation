# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.0 (current) | ✅ |
| < 1.0 | ❌ |

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report privately through one of:

- GitHub **Security Advisories** — *Security → Report a vulnerability* on this
  repository (private to maintainers).
- Email the maintainers at `security@gulftoken.com`.

Please include: affected component, reproduction steps, impact assessment, and
any suggested remediation. Expect an acknowledgement within a few working days.

## Scope

This project is a development and diagnostic tool. Security-relevant areas:

- **API key handling.** The Google Geolocation API key is stored only in the
  app's private `SharedPreferences`. It must never be compiled into the APK,
  logged, or committed to source. Report any code path that exposes it.
- **Network transmission.** The optional online resolver sends cell
  identifiers (MCC/MNC/TAC/CID + signal strength) to the Google Geolocation
  API over HTTPS, and only on an offline-database miss.
- **Mock location.** Injected fixes are intentionally flagged as mock
  (`Location.isMock() == true`). Mock injection requires the user to select
  the app under Developer Options; it cannot be enabled silently.

## Out of scope

- The fact that mock locations are detectable by consumer apps — this is by
  design, not a vulnerability.
- Accuracy limitations of cell-based positioning — see
  [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md).

## Operator responsibilities

- Restrict the Google API key (Geolocation API only; Android app restriction
  for package `com.example.celltrajectorymock`).
- Rotate the key if it is ever exposed.
