# Raw Accel — Diagnosis & Fix

I cloned `RawAccelOfficial/rawaccel` and traced the exact path your `settings.json`
takes through `writer.exe` → `wrapper` → the kernel driver. Here's what's actually
wrong, in order of how much it affects your aim.

---

## 1. Your LUT curve is non-monotonic — this is the "feels off" cause

Your symptom was *"it somewhat works but not really / feels inconsistent."*
That's not the driver dropping the profile. It's the curve itself.

`"Gain / Velocity": true` means your LUT data is a **velocity curve** — the pairs are
`(input speed in in/s, output speed in in/s)`, and the driver computes the sensitivity
multiplier as `output / input` (`common/accel-lookup.hpp → lookup::operator()`).

When you do that division on your horizontal LUT, the sensitivity **rises AND falls**
as you move faster:

| input speed (in/s) | sensitivity (gain) |
|---|---|
| 0 – 50  | 1.000 (flat — your intended "deadzone") |
| 60      | 1.018 |
| 80      | 1.131 |
| **100** | **1.292  ← peak** |
| 120     | 1.256 |
| **160** | **1.155  ← valley** |
| 200     | 1.205 |
| **240** | **1.192  ← valley** |
| **256** | **1.400  ← jump of +0.208 in 16 in/s** |

So a medium-speed flick gets *less* sensitivity than a slow one, and there's a harsh
sensitivity spike right at ~250 in/s. Your hand can't feel "where" you are on the curve,
so it feels like the profile is coming and going. That's the "not holding."

### The fix (already done for you)

`app/profiles/zombies_deadend.json` replaces the horizontal LUT with a smooth,
monotonic curve that keeps your intent: **1:1 up to 50 in/s → smooth rise to 1.4× →
flat at 1.4×.**

```
old:  1.00 ─ 1.29 ╲ 1.16 ─ 1.20 ╲ 1.19 ╱╱ 1.40   (bumpy, has a cliff)
new:  1.00 ──────────────────────╱ 1.40         (smooth, always rising)
```

You can verify any future curve yourself with:

```
python tools/lut_check.py app/profiles/zombies_deadend.json h
```

It re-implements the driver's exact interpolation and prints the gain table, flagging
anything non-monotonic. **The rule: sensitivity must never decrease as input speed
increases.** Every dip = that "off" feeling.

---

## 2. Your "Vertical accel parameters" LUT is dead code

You set `"Whole/combined accel …": true` (your profile is named "Unified", so this is
intentional). In whole/combined mode the driver computes **one** combined speed and
applies **only** the horizontal LUT to both axes (`rawaccel.hpp → modifier::modify`).
The vertical LUT is never read — it's even skipped during validation
(`rawaccel-validate.hpp`).

- Vertical feel is currently controlled **only** by `Y/X output DPI ratio = 0.90`.
- In `v2` I set vertical to `"noaccel"` with an empty table, so it's honest about
  being unused (and it can't accidentally drift).

If you ever want *different* H vs V curves, you must set that flag to `false`
("by component" mode) and re-tune both LUTs — but for a unified feel, keep it `true`.

---

## 3. `writer.exe` is a GUI app, so your `.bat` can't see its errors

`writer/Program.cs` calls `MessageBox.Show()` on every failure and exits 1. In your
launcher, a validation error pops a dialog box that **blocks the bat waiting for a
click you can't see**, and success prints nothing.

This is why the new app talks to the driver directly instead of shelling out to
`writer.exe`: it prints errors to the UI log and returns real status, and it adds
two things `writer.exe` can't do at all — **Query** (read back the *actual* driver
state) and a real **Verify** path (compare a profile against live driver state).

---

## 4. Your auto-heal / monitor use `fc /b` on files, not on driver state

Your watchdogs decide "the profile dropped" by byte-comparing `settings.json` to your
source file. That comparison has nothing to do with what the driver is doing:

- OneDrive touching the file, a line-ending change, or the Raw Accel GUI rewriting
  `settings.json` all read as a "DROP".
- Every false positive triggers a re-write, and every `writer.exe` write costs a
  **mandatory 1-second `WRITE_DELAY`** (`rawaccel-base.hpp`) *and* resets the driver's
  stateful smoothers. So false alarms every 30s = constant churn and state resets.

The app's **Watchdog** fixes this: it reads the real driver state and only
re-applies when the driver actually drifted. Its comparison is tolerant of the
small rounding caused by Raw Accel storing LUT entries as 32-bit floats; a raw
JSON comparison would otherwise falsely detect drift every 15 seconds. (This is
exactly what the old `StartZombies.ps1` launcher's watchdog was trying to do, but
against file hashes instead of driver state.)

---

## 5. Smaller things (not bugs, but cleanup)

- **Dead caps removed.** `"Cap / Jump"` and `"Cap mode"` only apply to
  `classic`/`power`/`jump`/`natural` — in `lut` they're ignored. Removed in v2.
- **`"version": "1.7.0"` is correct** — the driver is 1.7.0 (`common/rawaccel-version.h`).
  The `RawAccel_v1.7.1` folder name is just the GitHub release tag; no mismatch.
- **DPI mismatch is the real "drop" risk.** Your JSON declares `1200` DPI, and that
  number scales counts→in/s. If G HUB / OnboardMemoryManager / a DPI-slot hotkey ever
  changes the actual DPI to anything else (e.g. 6400 on slot 5, which your own header
  mentions), the *entire curve shifts* and it looks exactly like Raw Accel "dropped" —
  but the driver is fine, the normalization is just wrong. Keep DPI locked to 1200 (or
  whatever you declare) for the whole session, or the LUT will feel wrong no matter
  what you do.
- **Device matching is by hardware ID.** Your `"id": "HID\\VID_046D&PID_C539&MI_01&Col01"`
  is a specific HID collection on the G Pro Wireless receiver. If the mouse is on a
  different receiver, wired, or G HUB re-exposes it as a virtual device, that ID can
  stop matching and accel silently stops. The app's **Query** button will show which
  devices the driver is currently tracking, which makes this easy to diagnose.

---

## What's in this workspace

| file | what it is |
|---|---|
| `app/profiles/zombies_deadend.json` | corrected profile — **use this** |
| `app/profiles/zombies_deadend_original.json` | supported original/experimental non-monotonic curve |
| `tools/lut_check.py` | driver-faithful LUT checker (no Windows needed) |
| `app/` + `bridge/` | the new **Ace** app (see `README.md`) |
| `legacy/` | old script launcher + watchdog (superseded by the app) |
