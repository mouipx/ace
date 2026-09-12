# Contributing To Ace

## Development Rules

- Keep the official signed `rawaccel.sys` as the production engine.
- Do not install experimental kernel drivers on a physical Windows machine.
- Keep driver I/O behind `DriverClient` and the native bridge.
- Validate profiles before Apply and verify the live driver state after Apply.
- Preserve existing profile curves unless a change explicitly targets curve data.
- Add a focused regression test for behavior changes.

## Local Validation

From `app/` on Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat packageDistributionForCurrentOS
```

The native bridge requires Visual Studio C++ tools and a Windows SDK. Live
driver tests additionally require the official Raw Accel driver to be installed
and running. Offline profile and bridge validation should remain usable without
changing the installed driver.

## Change Areas

- Kotlin UI and orchestration: `app/src/main/kotlin/rawaccel/app/`
- Kotlin tests: `app/src/test/kotlin/`
- Native bridge: `bridge/`
- Bundled profiles: `app/profiles/`
- Architecture and audit notes: `ARCHITECTURE.md`, `AUDIT.md`

Do not commit generated `build/` directories, downloaded SDK installers,
private recovery files, driver backups, or machine-specific secrets.