# Testing Ace

Project path:

```text
C:\Users\sarah\OneDrive\Desktop\review
```

The app now requires its own bundled `rawaccel_bridge.dll`. It does not fall back
to Raw Accel's `writer.exe`.

## Before You Start

1. Confirm the Raw Accel driver is installed and running:

   ```text
   sc query rawaccel
   ```

   The service should say `RUNNING`.

2. Confirm Visual Studio Build Tools are installed with:

   - Desktop development with C++
   - MSVC x64 build tools
   - Windows 10/11 SDK

3. Open this folder in IntelliJ:

   ```text
   C:\Users\sarah\OneDrive\Desktop\review\app
   ```

## Build And Run

1. Let Gradle sync finish.
2. From `app/`, run:

   ```text
   .\gradlew.bat build
   ```

3. Run `Main.kt` or the Gradle run task after the build completes.
4. Gradle should build the native bridge before processing resources.

To test the packaged app layout before installing:

```text
.\gradlew.bat createDistributable
.\build\compose\binaries\main\app\Ace\Ace.exe
```

To create the installer:

```text
.\gradlew.bat packageDistributionForCurrentOS
```

`build\compose\binaries\main\exe\Ace-<version>.exe` is the installer, not the
installed app. The installed app executable is `%LOCALAPPDATA%\Ace\Ace.exe`.
If you rebuild a new installer with the same package version, Windows can leave
the old installed app in place. Bump `ace.version` in `build.gradle.kts`, or use
`.\gradlew.bat packageDistributionForCurrentOS -Pace.version=x.y.z`, before
installing a new test build.

Expected generated files:

```text
app\build\native\bridge\rawaccel_bridge.dll
app\build\generated\nativeResources\win32-x86-64\rawaccel_bridge.dll
```

Expected run console line:

```text
driver: using bundled native bridge (rawaccel_bridge.dll)
```

Expected test result:

```text
22 tests, 0 failures
```

If the bridge cannot load, the app reports `bridge unavailable` and Apply,
Query, Reset, and Watchdog will fail with a bridge load error instead of using
`writer.exe`.

## What To Click

1. Select `zombies_deadend` in Profiles.
2. Click Apply Selected. The log should show `applied profile: zombies_deadend`.
3. Click Query. The log should show the live driver profile count.
4. Click Prepare Session if you want to activate the Bitsum Highest Performance plan.
5. Click Reset to clear acceleration through the bridge.

## Admin Rights

Prepare Session uses `powercfg`, which needs admin rights. Apply and Reset may also
need admin depending on your driver's device ACL. If those actions fail with
permission errors, run IntelliJ as administrator.

## Troubleshooting

| symptom | fix |
|---|---|
| `Visual Studio Installer was not found` | Install Visual Studio Build Tools. |
| `cl.exe (x64) was not found` | Add MSVC x64 build tools in Visual Studio Installer. |
| `No complete Windows SDK was found` | Reinstall a Windows 10/11 SDK from Visual Studio Installer. |
| `bridge unavailable` | Run the Gradle build again and check the native DLL paths above. |
| `driver not detected` | Start or reinstall the Raw Accel driver, then run `sc query rawaccel`. |
| permission errors on Apply/Reset/Prepare Session | Run IntelliJ as administrator. |
