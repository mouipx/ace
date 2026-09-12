#pragma once
/*
 * rawaccel_bridge.h — C ABI for talking to the Raw Accel driver.
 *
 * This tiny native DLL is the ONLY place that touches the driver's binary
 * struct layout. It reuses the exact structs from Raw Accel's `common/`
 * headers, so the marshalling is guaranteed by the C++ compiler to match
 * what rawaccel.sys expects. The app (Kotlin/Compose) only ever passes JSON
 * strings and plain ints, so it can never corrupt driver state.
 */

#ifdef RA_BRIDGE_EXPORTS
#  define RA_API __declspec(dllexport)
#else
#  define RA_API __declspec(dllimport)
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Apply a settings JSON document (Raw Accel settings.json format) to the
 * driver. Returns 0 on success, non-zero on failure (message in `err`). */
RA_API int ra_apply_json(const char* json, char* err, int err_cap);

/* Validate a settings JSON document without opening or writing the driver. */
RA_API int ra_validate_json(const char* json, char* err, int err_cap);

/* Read the ACTIVE driver config and serialize it back to a JSON document.
 * Returns 0 on success (JSON in `out`), non-zero on failure (message in `err`). */
RA_API int ra_read_json(char* out, int out_cap, char* err, int err_cap);

/* Clear all acceleration (equivalent to the GUI "disable" / writer reset).
 * Returns 0 on success, non-zero on failure. */
RA_API int ra_reset(char* err, int err_cap);

/* Installed driver version. Returns 0 on success, non-zero on failure. */
RA_API int ra_version(int* maj, int* min, int* patch, char* err, int err_cap);

/* Returns 1 if the \\.\rawaccel device is present/openable, else 0. */
RA_API int ra_present(void);

#ifdef __cplusplus
}
#endif
