# Raw Accel Capability IOCTL Contract

This contract is for a future driver/bridge pair. The currently installed signed driver does not implement this IOCTL, so the bridge must treat `ERROR_INVALID_FUNCTION` and `ERROR_INVALID_PARAMETER` as `capability query unavailable` and continue using the existing version gate.

## IOCTL

```cpp
CAPABILITIES = CTL_CODE(0x8888u, 0x88b, METHOD_BUFFERED, FILE_ANY_ACCESS)
```

The request has no input buffer. The driver returns one `capability_info` structure in the output buffer.

## Response ABI

The response is a fixed 64-byte structure with 32-bit fields:

```cpp
struct capability_info {
    uint32_t struct_size;
    uint32_t protocol_major;
    uint32_t protocol_minor;
    uint32_t driver_version_packed;
    uint32_t feature_flags;
    uint32_t max_modifier_count;
    uint32_t max_device_count;
    uint32_t max_io_bytes;
    uint32_t reserved[8];
};
```

`struct_size` is the number of bytes the driver initialized. The client must require at least the first eight fields and ignore fields beyond the client-known size. The driver must zero all reserved fields and return `sizeof(capability_info)` when the output buffer is large enough.

## Feature flags

| Bit | Name | Meaning |
| ---: | --- | --- |
| `0x0001` | `READ` | The driver accepts the existing READ IOCTL. |
| `0x0002` | `WRITE` | The driver accepts the existing WRITE IOCTL. |
| `0x0004` | `LUT` | Lookup-table acceleration fields are supported. |
| `0x0008` | `DEVICE_MAPPING` | Per-device profile mappings are supported. |
| `0x0010` | `RESET` | An empty WRITE performs the documented reset operation. |
| `0x0020` | `FLOAT32_SETTINGS` | Numeric settings use the current float32 wire representation. |

Unknown bits must be ignored by clients. A driver must not advertise a feature unless it implements the corresponding behavior.

## Compatibility rules

1. `protocol_major` must equal the bridge-supported major version.
2. `protocol_minor` may be newer only when the client knows all required fields; otherwise the client must refuse Apply and explain the mismatch.
3. `driver_version_packed` is informational and remains separately checked against the minimum supported Raw Accel version.
4. `max_io_bytes` must be nonzero and no larger than the bridge hard limit of 16 MiB.
5. `max_modifier_count` and `max_device_count` must fit within `max_io_bytes` using the shared struct sizes.
6. Missing required flags cause a clear unsupported-feature error before opening a write transaction.
7. A short response, invalid `struct_size`, impossible limits, or nonzero reserved fields is a malformed capability response.

## Failure behavior

- `ERROR_INVALID_FUNCTION`: older driver; capability query is unavailable.
- `ERROR_ACCESS_DENIED`: report access denied and do not retry Apply.
- `ERROR_IO_PENDING` followed by timeout: report timeout and cancel the request.
- Short or inconsistent response: report malformed capability data and refuse Apply.
- Capability query failure must never be interpreted as full feature support.

## Rollout order

1. Add the shared IOCTL and response definition to the driver and bridge headers.
2. Implement and test the driver response without changing existing READ/WRITE behavior.
3. Add bridge parsing and strict response validation.
4. Add app-side feature gating and diagnostics.
5. Sign and test the candidate driver in an isolated rollback environment.
6. Keep the current signed production driver as the fallback until the complete pair passes validation.
