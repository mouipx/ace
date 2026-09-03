# Legacy — script-based workflow (superseded)

These files are your **old** approach — the PowerShell launcher + watchdog that
replaced `StartZombies.bat`. They work, but the **RawAccel Studio app** (the `app/`
folder) does everything they do, with a GUI, real driver-state checking, and curve
preview. Use the app instead.

| file | what it did |
|---|---|
| `StartZombies.ps1` | elevated launcher: apply profile, power plan, USB suspend, G HUB check |
| `zombies-watchdog.ps1` | background loop re-applying the profile on drift |

Kept only for reference / in case you want a lightweight script option. The
corrected profile they reference lives in `../app/profiles/zombies_deadend.json`.
