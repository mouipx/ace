# Architecture decisions

## 1. Keep the signed driver as the engine (don't rebuild it)

Raw Accel is two halves:

- `rawaccel.sys` — a **kernel-mode** driver. It's the acceleration engine *and*
  it's **digitally signed**, which is the entire reason Raw Accel is anti-cheat
  friendly. Rebuilding it would (a) require the MSVC **WDK**, and (b) destroy the
  signed status — you'd have to self-sign, which anti-cheats flag. Strict downgrade.
- The GUI (`grapher`, `writer`) — a ~2020 C# WinForms app. This is what's dated.

So the app is a **new front-end over the existing driver**. The driver exposes a
clean interface (`\\.\rawaccel` + three IOCTLs: READ / WRITE / GET_VERSION), so a
new app can do everything the old GUI did — and more.

## 2. A C++ bridge DLL does the marshalling (not JNA structs)

This is the load-bearing decision. Inspecting `driver/driver.cpp` shows the driver
does a raw `RtlCopyMemory` of the incoming `modifier_settings` buffer and executes
it as-is. That buffer contains:

- a `profile` (~5 KB) with fixed-size arrays and a `wchar_t[256]` name,
- an `accel_union` — a union of ~7 accel implementations (classic, jump, natural,
  synchronous, power, lookup, noaccel) whose exact size depends on the largest
  member and whose padding is compiler-defined.

Getting any of that wrong by even one byte would silently corrupt driver state.
Hand-deriving those layouts in JVM code (JNA `Structure`s) is exactly the kind of
thing that can't be tested off-Windows and would break subtly.

So the marshalling lives in a tiny **C++ DLL that includes Raw Accel's own
`common/` headers**. The C++ compiler guarantees `sizeof`, padding, and union
layout match the driver, because it's the *same* code the driver was compiled
from. The Kotlin app never touches a raw struct byte — it only passes JSON strings
and ints across JNA, which is inherently safe.

```
Kotlin/Compose  ──JNA (JSON strings)──▶  rawaccel_bridge.dll  ──IOCTL──▶  rawaccel.sys
        (new)                            (reuses common/ structs)        (unchanged)
```

## 3. JSON schema is shared with Raw Accel

The bridge reads and writes the **same `settings.json` format** the GUI/writer use
(property names like `"Whole or horizontal accel parameters"`, `"Gain / Velocity"`,
`"Cap / Jump"`, etc.). This means:

- your existing profiles load unchanged,
- the old GUI and the new app can both read the same files,
- `app/profiles/zombies_deadend.json` (the corrected curve) works as-is.

## 4. What "new features" means on top of the driver

The driver fixes the *acceleration math*. Everything else is fair game for the app:

| feature | status in v1 |
|---|---|
| Profile manager (list / apply / persist) | ✅ |
| Live curve preview + monotonicity check | ✅ (LUT; more modes next) |
| Query the *actual* driver state | ✅ |
| Real-drift watchdog (auto-heal) | ✅ |
| One-click session (Bitsum power plan) | ✅ |
| DPI-change warnings / device list | next |
| Profile editor + curve painter | next |
| Per-game hotkeys / auto-apply on device connect | next |

## 5. Why Kotlin + Compose (not C#)

You asked to use IntelliJ IDEA, which is a JVM IDE. Kotlin is JetBrains' own
language and Compose for Desktop gives a modern, native-window UI (dark theme,
Canvas charts). JNA is the standard way to call a native DLL from the JVM, so the
whole stack fits IntelliJ naturally. (A C#/.NET rebuild would be JetBrains Rider,
not IntelliJ — but the bridge pattern would be identical.)
