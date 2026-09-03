#!/usr/bin/env python3
"""
lut_check.py — verify a Raw Accel LUT profile the same way the driver sees it.

This mirrors the exact math in Raw Accel's `common/accel-lookup.hpp`
(`lookup::operator()`) plus the wrapper in `common/rawaccel.hpp`
(`modifier::callback_template`):

    scale = 1 + (gain - 1) * range_weight        (range_weight defaults to 1)
    gain  = lerp(LUT, input_speed) / input_speed (in "Gain / Velocity" mode)

It prints the effective sensitivity (gain) across input speeds and flags any
non-monotonic behaviour, which is the #1 cause of "it feels off."

Usage:
    python lut_check.py settings.json [axis]
    axis = "h" (default, "Whole or horizontal accel parameters") or "v"
"""

import json
import sys

def lerp(a, b, t):
    # matches rawaccel::lerp in accel-lookup.hpp
    x = a + t * (b - a)
    if (t > 1) == (a < b):
        return max(x, b)
    return min(x, b)

def lookup_gain(points, velocity_mode, x):
    # points: list of (x, y); mirrors lookup::operator()
    n = len(points)
    if x <= 0:
        return 0.0
    lo, hi = 0, n - 2
    if hi < 256:  # capacity - 1
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

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)

    path = sys.argv[1]
    axis = sys.argv[2].lower() if len(sys.argv) > 2 else "h"

    with open(path) as f:
        cfg = json.load(f)

    prof = cfg["profiles"][0]
    key = ("Whole or horizontal accel parameters" if axis == "h"
           else "Vertical accel parameters")
    acc = prof[key]
    mode = acc["mode"]
    velocity = acc.get("Gain / Velocity", True)
    sp = prof["Input speed calculation parameters"]
    whole = sp.get("Whole/combined accel (set false for 'by component' mode)", True)

    print(f"profile: {prof['name']}")
    print(f"axis:    {key}")
    print(f"mode:    {mode}   gain/velocity = {velocity}")
    print(f"whole/combined = {whole}")
    print()

    if mode != "lut":
        print("(not a LUT profile — nothing to check)")
        return

    raw = acc["data"]
    points = [(raw[i], raw[i + 1]) for i in range(0, len(raw), 2)]
    print(f"{len(points)} LUT points")

    # scan finely across the input-speed range
    max_in = points[-1][0]
    prev = None
    problems = 0
    print(f"\n{'input (in/s)':>12}  {'gain':>8}   flag")
    for v in [0.0, 0.5, 1, 2, 5, 10, 20, 30, 40, 48, 50, 60, 80, 100,
              120, 140, 160, 180, 200, 220, 240, 250, 256, 300, 400,
              512, 768, 1024, 1536, 2048]:
        g = lookup_gain(points, velocity, v)
        flag = ""
        if prev is not None and g < prev - 1e-6:
            flag = "  <-- gain DECREASES (non-monotonic!)"
            problems += 1
        print(f"{v:12.1f}  {g:8.4f}   {flag}")
        prev = g

    print()
    if problems:
        print(f"WARNING: {problems} non-monotonic gain points found. "
              "Sensitivity rises AND falls with speed -> feels inconsistent.")
        sys.exit(1)
    print("OK: gain is monotonic non-decreasing across the scan range.")

if __name__ == "__main__":
    main()
