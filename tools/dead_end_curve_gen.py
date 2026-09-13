#!/usr/bin/env python3
"""
dead_end_curve_gen.py — design, validate, and WRITE the Dead End profile.

Run it any time to regenerate the config from the numbers below (one command,
no hand-editing). It writes app/profiles/dead-end-run-gun-precision.json.

DESIGN (Sniper + Shotgun run-and-gun, 1200 DPI):
  HORIZONTAL (yaw)  = true ACCELERATOR.
      Accurate 1.0 floor for tracking, ramps to a 2.5 ceiling so fast turns
      and 180s carry much farther. This is the "do anything / turn farther"
      axis.
  VERTICAL (pitch)  = TARGET-LOCK (deceleration).
      ~2.0 at micro-speed so the crosshair locks onto the zombie's head with
      a tiny nudge (the Sniper headshot feel), smoothly decaying to a 1.45
      ceiling so fast vertical stays controlled. This is the headshot axis.
"""

import json

# ---------------------------------------------------------------- targets
def smoothstep(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)

# HORIZONTAL accelerator: 1.0 (floor) -> 2.7 (ceiling) over [5, 70] in/s
H_V0, H_V1, H_GMIN, H_GMAX = 5.0, 70.0, 1.0, 2.7
def h_gain(v):
    return H_GMIN + (H_GMAX - H_GMIN) * smoothstep((v - H_V0) / (H_V1 - H_V0))

# VERTICAL target-lock: 2.2 (floor, headshot lock) -> 1.5 (ceiling, controlled)
# decays over [3, 45] in/s  (high at low speed, lower at high speed)
V_V0, V_V1, V_GLOW, V_GHIGH = 3.0, 45.0, 2.2, 1.5
def v_gain(v):
    return V_GHIGH + (V_GLOW - V_GHIGH) * (1.0 - smoothstep((v - V_V0) / (V_V1 - V_V0)))

H_POINTS = [0, 2.5, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 85, 100, 150]
V_POINTS = [0, 1.5, 3, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 80, 100, 150]

def sample(gainfn, xs):
    return [(float(x), round(gainfn(x) * x, 2)) for x in xs]

# ---------------------------------------------------------------- driver math mirror
def lerp(a, b, t):
    x = a + t * (b - a)
    if (t > 1) == (a < b):
        return max(x, b)
    return min(x, b)

def lookup_gain(points, velocity_mode, x):
    """Exact port of rawaccel::lookup::operator() (velocity mode)."""
    n = len(points)
    if x <= 0:
        return 0.0
    lo, hi = 0, n - 2
    if hi < 256:
        while lo <= hi:
            mid = (lo + hi) // 2
            px = points[mid][0]
            if x < px:
                hi = mid - 1
            elif x > px:
                lo = mid + 1
            else:
                y = points[mid][1]
                return y / x if velocity_mode else y
        if lo > 0:
            a = points[lo - 1]
            b = points[lo]
            t = (x - a[0]) / (b[0] - a[0])
            y = lerp(a[1], b[1], t)
            return y / x if velocity_mode else y
    y = points[0][1]
    return y / points[0][0] if velocity_mode else y

# ---------------------------------------------------------------- validation
def validate(name, pts, target, direction):
    """direction: 'up' (accelerator, gain non-decreasing) or 'down'
    (target-lock, gain non-increasing)."""
    raw = [c for p in pts for c in p]
    assert len(raw) % 2 == 0 and 4 <= len(raw) <= 514, f"{name}: bad LUT size"
    assert all(c == c for c in raw), f"{name}: non-finite value"
    assert pts[0] == (0.0, 0.0), f"{name}: must start at (0,0)"
    for i in range(1, len(pts)):
        assert pts[i][0] > pts[i - 1][0], f"{name}: x not strictly increasing"

    prev, worst_err, worst_at, bad = None, 0.0, 0.0, 0
    v = 0.1
    while v <= 300.0 + 1e-9:
        g = lookup_gain(pts, True, v)
        if prev is not None:
            if direction == "up" and g < prev - 1e-9:
                bad += 1
                print(f"  !! {name} gain DROPS at v={v:.2f}: {prev:.4f}->{g:.4f}")
            if direction == "down" and g > prev + 1e-9:
                bad += 1
                print(f"  !! {name} gain JUMPS at v={v:.2f}: {prev:.4f}->{g:.4f}")
        prev = g
        if v <= 150.0:
            err = abs(g - target(v))
            if err > worst_err:
                worst_err, worst_at = err, v
        v += 0.05
    assert bad == 0, f"{name}: not smoothly {direction}"
    g_past = lookup_gain(pts, True, 1000.0)
    assert abs(g_past - target(150.0)) < 0.005, f"{name}: plateau broken ({g_past})"
    print(f"{name}: {len(pts)} pts, smooth {direction}, max err {worst_err:.4f} @ "
          f"{worst_at:.1f}, plateau {g_past:.3f} OK")
    return raw

def table(name, pts):
    print(f"\n{name}")
    print(f"{'in/s':>7} | {'gain':>8}")
    for v in [1, 2, 3, 5, 8, 10, 15, 20, 30, 40, 50, 60, 70, 100]:
        print(f"{v:7.1f} | {lookup_gain(pts, True, v):8.3f}")

# ---------------------------------------------------------------- build curves
h_pts = sample(h_gain, H_POINTS)
v_pts = sample(v_gain, V_POINTS)
h_raw = validate("HORIZONTAL (accelerator)", h_pts, h_gain, "up")
v_raw = validate("VERTICAL  (target-lock) ", v_pts, v_gain, "down")
table("HORIZONTAL (yaw): turn / switch", h_pts)
table("VERTICAL (pitch): headshot target-lock", v_pts)

# ---------------------------------------------------------------- write profile
def fnum(c):
    r = repr(float(c))
    return r if ("." in r or "e" in r) else r + ".0"

def arr(raw):
    return "[ " + ", ".join(fnum(c) for c in raw) + " ]"

NAME = "Dead End | Leaderboard Target-Lock | 1200 DPI"

# Preset cycle embedded in the profile JSON (app-only section — the Raw Accel
# driver never sees it). Must mirror HotkeyManager.deadEndHotkeyPresetCycle so
# the profile is self-contained and tunable without rebuilding the app.
PRESET_STAGES = [
    {"label": "PISTOL",                          "h": ("SMOOTH", 8, 60, 1.9, 0.0),
     "v": ("TARGET_LOCK", 3, 35, 1.7, 1.25), "yx": 0.95},
    {"label": "PISTOL + SHOTGUN",                "h": ("SMOOTH", 10, 65, 2.0, 0.0),
     "v": ("TARGET_LOCK", 3, 40, 1.8, 1.3), "yx": 0.93},
    {"label": "SNIPER + SHOTGUN",                "h": ("SMOOTH", 5, 70, 2.7, 0.0),
     "v": ("TARGET_LOCK", 3, 45, 2.2, 1.5), "yx": 1.0},
    {"label": "ZAPPER + ELDER GUN + GOLD DIGGER","h": ("FAST_RAMP", 8, 55, 2.2, 0.0),
     "v": ("TARGET_LOCK", 3, 40, 2.0, 1.4), "yx": 0.95},
    {"label": "SNIPER FALLBACK (3-SLOT)",        "h": ("SMOOTH", 5, 75, 2.5, 0.0),
     "v": ("TARGET_LOCK", 3, 45, 2.3, 1.5), "yx": 1.0},
]

def stage_block(s):
    hs, hv = s["h"], s["v"]
    return (
        '      {\n'
        f'        "label" : "{s["label"]}",\n'
        f'        "startIn" : {fnum(hs[1])},\n'
        f'        "endIn" : {fnum(hs[2])},\n'
        f'        "peak" : {fnum(hs[3])},\n'
        f'        "yx" : {fnum(s["yx"])},\n'
        '        "whole" : false,\n'
        f'        "shape" : "{hs[0]}",\n'
        f'        "verticalStartIn" : {fnum(hv[1])},\n'
        f'        "verticalEndIn" : {fnum(hv[2])},\n'
        f'        "verticalPeak" : {fnum(hv[3])},\n'
        f'        "verticalShape" : "{hv[0]}",\n'
        f'        "ceil" : {fnum(hs[4])},\n'
        f'        "verticalCeil" : {fnum(hv[4])}\n'
        '      }'
    )

def preset_cycle_block():
    inner = ",\n".join(stage_block(s) for s in PRESET_STAGES)
    return '    "Preset cycle" : [\n' + inner + '\n    ]'

def accel_block(data_raw):
    return (
        '      "mode" : "lut",\n'
        '      "Gain / Velocity" : true,\n'
        '      "inputOffset" : 0.0,\n'
        '      "outputOffset" : 0.0,\n'
        '      "acceleration" : 0.005,\n'
        '      "decayRate" : 0.1,\n'
        '      "gamma" : 1.0,\n'
        '      "motivity" : 1.5,\n'
        '      "exponentClassic" : 2.0,\n'
        '      "scale" : 1.0,\n'
        '      "exponentPower" : 0.05,\n'
        '      "limit" : 1.5,\n'
        '      "syncSpeed" : 5.0,\n'
        '      "smooth" : 0.5,\n'
        '      "Cap / Jump" : {\n'
        '        "x" : 15.0,\n'
        '        "y" : 1.5\n'
        '      },\n'
        '      "Cap mode" : "output",\n'
        f'      "data" : {arr(data_raw)}'
    )

text = (
'{\n'
'  "version" : "1.7.0",\n'
'  "defaultDeviceConfig" : {\n'
'    "disable" : true,\n'
'    "setExtraInfo" : false,\n'
'    "Use constant time interval based on polling rate" : false,\n'
'    "DPI (normalizes input speed unit: counts/ms -> in/s)" : 1200,\n'
'    "Polling rate Hz (keep at 0 for automatic adjustment)" : 1000,\n'
'    "minimumTime" : 0.0625,\n'
'    "maximumTime" : 100.0\n'
'  },\n'
'  "profiles" : [ {\n'
f'    "name" : "{NAME}",\n'
'    "Stretches domain for horizontal vs vertical inputs" : {\n'
'      "x" : 1.0,\n'
'      "y" : 1.0\n'
'    },\n'
'    "Stretches accel range for horizontal vs vertical inputs" : {\n'
'      "x" : 1.0,\n'
'      "y" : 1.0\n'
'    },\n'
'    "Whole or horizontal accel parameters" : {\n' + accel_block(h_raw) + '\n'
'    },\n'
'    "Vertical accel parameters" : {\n' + accel_block(v_raw) + '\n'
'    },\n'
'    "Input speed calculation parameters" : {\n'
'      "Whole/combined accel (set false for \'by component\' mode)" : false,\n'
'      "lpNorm" : 2.0,\n'
'      "Time in ms after which an input is weighted at half its original value." : 2.0,\n'
'      "Time in ms after which scale is weighted at half its original value." : 0.0,\n'
'      "Time in ms after which an output is weighted at half its original value." : 0.0\n'
'    },\n'
'    "Output DPI" : 1200.0,\n'
'    "Y/X output DPI ratio (vertical sens multiplier)" : 1.0,\n'
'    "L/R output DPI ratio (left sens multiplier)" : 1.0,\n'
'    "U/D output DPI ratio (up sens multiplier)" : 1.0,\n'
'    "Degrees of rotation" : 0.0,\n'
'    "Degrees of angle snapping" : 0.0,\n'
'    "Input Speed Cap" : 0.0,\n'
+ preset_cycle_block() + '\n'
'  } ],\n'
'  "devices" : [ {\n'
'    "name" : "Logitech USB Receiver",\n'
f'    "profile" : "{NAME}",\n'
'    "id" : "HID\\\\VID_046D&PID_C539&MI_01&Col01",\n'
'    "config" : {\n'
'      "disable" : false,\n'
'      "setExtraInfo" : false,\n'
'      "Use constant time interval based on polling rate" : false,\n'
'      "DPI (normalizes input speed unit: counts/ms -> in/s)" : 1200,\n'
'      "Polling rate Hz (keep at 0 for automatic adjustment)" : 1000,\n'
'      "minimumTime" : 0.0625,\n'
'      "maximumTime" : 100.0\n'
'    }\n'
'  } ]\n'
'}\n'
)

out = "/home/user/ace/app/profiles/dead-end-run-gun-precision.json"
with open(out, "w") as f:
    f.write(text)
json.loads(text)  # strict parse
print("\nwrote", out)

# ---------------------------------------------------------------- hotkey simulation
# Mirror HotkeyManager exactly:
#   clutch  -> every LUT y-value (odd index) * m, outputDpi * m
#   toggle  -> both LUTs -> noaccel (raw 1:1 at outputDpi)
def pts(raw):
    return [(raw[i], raw[i + 1]) for i in range(0, len(raw), 2)]

def sim_profile(h_raw, v_raw, out_dpi, scale):
    """Apply a clutch-style uniform scale m to the whole profile."""
    sh = [c if i % 2 == 0 else c * scale for i, c in enumerate(h_raw)]
    sv = [c if i % 2 == 0 else c * scale for i, c in enumerate(v_raw)]
    return pts(sh), pts(sv), out_dpi * scale

def show(label, hp, vp, out_dpi):
    def g(p, x):
        return lookup_gain(p, True, x)
    row = []
    for x in [2, 10, 25, 50]:
        row.append(f"  H@{x:<3}={g(hp,x):5.2f}  V@{x:<3}={g(vp,x):5.2f}")
    print(f"\n{label}  (outputDpi={out_dpi:.0f})")
    print("\n".join(row))

base_out = 1200.0
print("\n" + "=" * 70)
print("HOTKEY SIMULATION — how the app transforms THIS baseline:")
print("=" * 70)
show("BASELINE (what you load / restore on disarm)", pts(h_raw), pts(v_raw), base_out)
hp, vp, od = sim_profile(h_raw, v_raw, base_out, 0.75)
show("CTRL+F1 CLUTCH LOW (x0.75)", hp, vp, od)
hp, vp, od = sim_profile(h_raw, v_raw, base_out, 1.35)
show("CTRL+F2 CLUTCH HIGH (x1.35)", hp, vp, od)
print("\nCTRL+F3 TOGGLE ACCEL  ->  both LUTs = noaccel = RAW 1:1 at 1200 DPI")
print("CTRL+SHIFT+F1 PRESET  ->  cycles the profile's embedded 'Preset cycle'")

# Simulate the app's generateFor() for each embedded stage and verify the
# SNIPER + SHOTGUN stage reproduces this profile's baseline curves.
def gen_for(shape, start, end, peak, ceil=1.0):
    pts = [(0.0, 0.0)]
    if start > 1.0:
        first = start / 2.0
        fg = peak if shape == "TARGET_LOCK" else 1.0
        pts.append((first, first * fg))
    pts.append((start, start * (peak if shape == "TARGET_LOCK" else 1.0)))
    N = 25
    for i in range(1, N):
        t = i / (N - 1)
        if shape == "TARGET_LOCK":
            g = ceil + (peak - ceil) * (1.0 - smoothstep(t))
        elif shape == "FAST_RAMP":
            g = 1.0 + (peak - 1.0) * (1.0 - (1.0 - t) ** 2)
        else:  # SMOOTH
            g = 1.0 + (peak - 1.0) * smoothstep(t)
        pts.append((start + (end - start) * t, (start + (end - start) * t) * g))
    pts.append((2048.0, 2048.0 * (ceil if shape == "TARGET_LOCK" else peak)))
    return pts

print("\nEMBEDDED PRESET STAGES (what Ctrl+Shift+F1 generates):")
for s in PRESET_STAGES:
    hs, hv = s["h"], s["v"]
    hp, vp = gen_for(*hs), gen_for(*hv)
    print(f"  {s['label']:<32} H {lookup_gain(hp,True,2):4.2f}->{lookup_gain(hp,True,100):4.2f}   "
          f"V {lookup_gain(vp,True,2):4.2f}->{lookup_gain(vp,True,100):4.2f}")

sniper = [s for s in PRESET_STAGES if s["label"] == "SNIPER + SHOTGUN"][0]
hp = gen_for(*sniper["h"])
vp = gen_for(*sniper["v"])
base_h = pts(h_raw)
base_v = pts(v_raw)
for x in [2, 10, 30, 70]:
    assert abs(lookup_gain(hp, True, x) - lookup_gain(base_h, True, x)) < 0.02, \
        f"preset H gain != baseline at {x} in/s"
    assert abs(lookup_gain(vp, True, x) - lookup_gain(base_v, True, x)) < 0.02, \
        f"preset V gain != baseline at {x} in/s"
print("\nSNIPER + SHOTGUN stage matches the baseline profile curves  OK")

# confirm clutch preserves the curve SHAPES (target-lock stays target-lock)
for m, name in [(0.75, "clutch low"), (1.35, "clutch high")]:
    hp, vp, _ = sim_profile(h_raw, v_raw, base_out, m)
    vmicro = lookup_gain(vp, True, 1.0)
    vpeak = lookup_gain(vp, True, 200.0)
    hfloor = lookup_gain(hp, True, 1.0)
    hceil = lookup_gain(hp, True, 200.0)
    assert vmicro > vpeak, f"{name}: vertical lost its target-lock shape"
    assert hceil > hfloor, f"{name}: horizontal lost its accelerator shape"
    print(f"\n{name}: vertical stays target-lock ({vmicro:.2f}->{vpeak:.2f}), "
          f"horizontal stays accelerator ({hfloor:.2f}->{hceil:.2f})  OK")
