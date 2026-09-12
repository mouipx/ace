# RawAccel Studio

A modern Kotlin / Compose Desktop front-end for the Raw Accel mouse acceleration
driver. The signed `rawaccel.sys` driver stays as the engine, and this app owns
the desktop UI, profile manager, curve preview, session tools, watchdog, and
native bridge.

This project is also called **Ace** in the packaged application. It is a
user-mode desktop application and native bridge around the official Raw Accel
driver; it is not a replacement kernel driver.

## Start Here

Read these files in order:

1. `ARCHITECTURE.md` — why the app, bridge, and driver are separated.
2. `TESTING.md` — local build and validation commands.
3. `AUDIT.md` — known risks, safeguards, and verification history.
4. `bridge/CAPABILITIES.md` — the proposed capability protocol, currently dormant.

The main runtime path is:

```text
Compose UI -> service layer -> DriverClient -> JNA -> rawaccel_bridge.dll
  -> Windows DeviceIoControl -> official signed rawaccel.sys
```

## Core Functions

| Area | Responsibility | Starting point |
|---|---|---|
| UI | Dashboard, editor, curve preview, devices, session controls | `app/src/main/kotlin/rawaccel/app/ui/App.kt` |
| Driver client | Serialized Apply, Query, Reset, version and health checks | `app/src/main/kotlin/rawaccel/app/driver/DriverClient.kt` |
| Native bridge | ABI-correct JSON-to-Raw-Accel marshalling and IOCTL calls | `bridge/rawaccel_bridge.cpp` |
| Profiles | Load, validate, import, export, backup, and recover JSON profiles | `app/src/main/kotlin/rawaccel/app/service/ProfileManager.kt` |
| Device matching | Detect connected mice and bind profiles to hardware IDs | `app/src/main/kotlin/rawaccel/app/service/DeviceInspector.kt` |
| Auto-switch | Apply a game profile when Minecraft or another known game is focused | `app/src/main/kotlin/rawaccel/app/service/ForegroundWatcher.kt` |
| Reliability | Read-back verification, drift detection, watchdog recovery | `app/src/main/kotlin/rawaccel/app/service/Watchdog.kt` |
| Validation | Profile, LUT, device, version, and numeric safety checks | `app/src/main/kotlin/rawaccel/app/service/ConfigDoctor.kt` |

## Safety Boundary

The official signed Raw Accel driver is the production dependency. Do not
replace `rawaccel.sys` on a physical machine with an experimental build. Kernel
driver testing belongs in a disposable VM with a known-good snapshot. Changes
to the Kotlin app, bridge, profiles, and offline validation can be tested on the
normal development machine.

The repository intentionally excludes local SDK downloads, private recovery
records, driver backups, and test artifacts. Build outputs are also ignored.

## Helping With The Project

Good first tasks are improvements to profile validation, device matching,
read-back diagnostics, tests, and documentation. Before changing the driver
protocol, read `ARCHITECTURE.md` and confirm the matching kernel-driver source
and a rollback-capable test environment exist.

## Project Layout

| path | what it is |
|---|---|
| `app/` | Kotlin / Compose Desktop app. Open this folder in IntelliJ. |
| `app/profiles/` | Raw Accel settings JSON profiles. |
| `bridge/` | C++ native bridge built into `rawaccel_bridge.dll`. |
| `bridge/rawaccel-common/` | Vendored Raw Accel common headers used for driver struct layout. |
| `tools/lut_check.py` | Standalone LUT curve checker. |
| `legacy/` | Old script workflow, kept for reference only. |
| `TESTING.md` | Build and test steps. |

## Backend

RawAccel Studio now talks to the driver through its own native bridge:

```text
Kotlin/Compose app -> JNA -> rawaccel_bridge.dll -> IOCTL -> rawaccel.sys
```

The app no longer shells out to Raw Accel's `writer.exe`. Gradle builds
`bridge/rawaccel_bridge.cpp`, copies the DLL into JNA's `win32-x86-64/` resource
folder, and packages it with the app.

## Prerequisites

- Windows 10/11 x64.
- Raw Accel driver already installed and running.
- IntelliJ IDEA with the Kotlin plugin.
- JDK 17-21.
- Visual Studio Build Tools with Desktop development with C++ and a Windows 10/11 SDK.

## Build And Run

1. Open the `app/` folder in IntelliJ.
2. Let Gradle sync finish.
3. Run the app through Gradle or run `Main.kt` after the Gradle build has
   produced the bridge DLL.

From a terminal:

```text
cd app
.\gradlew.bat build
```

During the Gradle build, `buildRawAccelBridge` runs `bridge/build.ps1` and
produces:

```text
app/build/native/bridge/rawaccel_bridge.dll
app/build/generated/nativeResources/win32-x86-64/rawaccel_bridge.dll
```

At startup the console should say:

```text
driver: using bundled native bridge (rawaccel_bridge.dll)
```

To package the Windows installer:

```text
.\gradlew.bat packageDistributionForCurrentOS
```

The generated `build\compose\binaries\main\exe\Ace-<version>.exe` file is the
installer. After running that installer, the app executable is installed under
`%LOCALAPPDATA%\Ace\Ace.exe`. If you rebuild but keep the same package version,
Windows may keep the previous installed app, so `gradlew run` can be newer than
the EXE you launch from Start Menu/Desktop. Bump `ace.version` or pass
`-Pace.version=x.y.z` before packaging a new test build.

The build also runs JVM tests for profile loading, curve math, device ID
normalization, and bundled native bridge loading.

## What Replaced The Old Workflow

| old workflow | RawAccel Studio |
|---|---|
| `writer.exe <profile>` | Apply Selected validates, writes, reads back, and verifies the complete profile through the bundled bridge. |
| `fc /b` file-compare watchdog | Watchdog uses float-tolerant live-state comparison and re-applies only on true drift. |
| power plan / USB suspend / G HUB checks | Start Session runs those checks from the app. |
| guessing whether settings applied | Query reads the live driver config through the bridge. |
| hand-edited LUT checks | Curve Preview includes a monotonicity check. |

## Notes

- The bridge is compiled against the vendored Raw Accel common headers.
- Keep your mouse at the DPI your profile declares; DPI changes shift the whole
  curve.
- Apply, Query, Reset, and Watchdog all require the native bridge to load.
