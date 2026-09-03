# Ace audit — 2026-08-17

## Main issue found

The watchdog compared the requested Kotlin/JSON settings to the driver read-back
as exact serialized JSON. Raw Accel stores LUT entries as 32-bit floats, so normal
values such as `55.04` come back as approximately `55.0400009155`. Exact JSON
comparison treated this harmless rounding as drift.

Result: after Apply, the watchdog could falsely re-apply the profile every 15
seconds. A write also resets stateful smoothing and introduces driver write delay,
which can feel like acceleration is stopping, changing, or never settling.

## Fixes made

1. Added `DriverStateComparator`, which compares all structure, strings, flags,
   and arrays strictly while allowing only native float-level numeric rounding.
2. Changed the watchdog to use that comparator and report `watching` immediately.
3. Apply now performs write **and live read-back verification**. The UI only says
   `APPLIED + VERIFIED` when the driver returns equivalent settings.
4. Added native bridge validation before writing:
    - supported acceleration and cap modes;
    - LUT size, finite values, even pairs, and strictly increasing input speeds;
    - Raw Accel's own profile/device validation;
    - duplicate profile names;
    - missing device-to-profile references;
    - empty/overlong device IDs and names;
    - installed driver/version compatibility.
5. The bridge now reports a too-small read buffer instead of returning truncated
   JSON as a successful read.
6. Apply warns when default devices are disabled but none of the configured mouse
   targets are currently connected. In that situation the profile is loaded but
   does not affect the mouse.
7. Query now logs driver device-to-profile mappings as well as profile names.
8. Added comparator regression tests and a Windows GitHub Actions build workflow.
9. Serialized all native driver operations so Apply, Query, Reset, and Watchdog
   cannot overlap and race each other.
10. Profile saves are now atomic, verified after serialization, and retain the
    previous version as `<profile>.json.bak`—important for OneDrive folders.
11. Invalid JSON profiles are reported in the app log instead of disappearing
    silently from the profile list.
12. Added profile import, export, clone, rename, recoverable delete, and backup
    restore controls.
13. Device retargeting now preserves existing device mappings instead of replacing
    the entire list, and multi-profile banks let the user choose the target profile.
14. Curve telemetry now lets the user switch between profiles in a multi-profile
    settings bank.
15. Added a themed Editor tab with guided DPI, polling, directional ratio,
    smoothing, rotation, snapping, and speed-cap controls.
16. Added safe LUT generation (smooth, linear, soft-start, and fast-ramp), an
    advanced point table, live draft preview, strict validation, and Revert.
17. Saving an editor draft remains separate from Apply, so editing never changes
    the live driver unexpectedly.
18. Session commands now have timeouts and failure containment, activate Bitsum
    Highest Performance by display name, and explicitly use Windows `.exe` commands.
19. Blank device IDs can no longer appear connected by accidentally matching every
    mouse, large driver reads support multi-profile banks up to 8 MiB, and shutdown
    now stops the watchdog and cancels its coroutine scope cleanly.
20. Packaged-runtime diagnostics found continuous decorative animation consuming
    substantial CPU. All infinite UI animation is now removed so Ace remains idle.
21. The original non-monotonic curve remains available as a supported dual-axis
    profile with a distinct driver name. Its horizontal and vertical LUTs are both
    active, visible, editable, serialized, and previewable. Gain dips are warnings
    rather than blockers, with optional repair in the Editor.

## Profile review

- `zombies_deadend.json`: LUT check passes; gain is monotonic and reaches 1.4x.
- `zombies_deadend_original.json`: intentionally retained as a reference; its gain
  is non-monotonic and should not be used for normal play.
- The active profile has `defaultDeviceConfig.disable = true`, so its configured
  hardware ID must match the connected mouse. If it does not, open **DEVICES** and
  choose **USE THIS MOUSE**.
- The configured DPI is 1200. The physical mouse DPI must also remain at 1200 or
  the acceleration curve will activate at the wrong hand speeds.

## Verification status

- `tools/lut_check.py app/profiles/zombies_deadend.json h`: passed.
- Repository diff/whitespace checks: passed.
- Full local Gradle test execution in the Linux audit sandbox was blocked while
  resolving Kotlin dependencies by a Java 11 TLS handshake failure. This was an
  environment/network failure before project compilation, not a test failure.
- Native bridge and live-driver behavior require Windows. The new
  `.github/workflows/windows-build.yml` runs the complete Gradle build and all 22
  tests on `windows-latest` with JDK 21 after these changes are pushed.

## Windows checks to perform

1. Push or copy these changes, then confirm the GitHub **Windows build** action is green.
2. Run `app\\gradlew.bat build` on Windows.
3. Start Ace and select the corrected `zombies_deadend` profile.
4. Open **DEVICES**. If the target is not connected, click **USE THIS MOUSE** for
   the correct Logitech mouse/receiver.
5. Confirm the mouse is locked to 1200 DPI.
6. Click **APPLY PROFILE** and confirm the log says `APPLIED + VERIFIED`.
7. Leave the watchdog running for at least 30 seconds. It should remain
   `WATCHING (OK)` and must not report repeated drift/re-apply events.
