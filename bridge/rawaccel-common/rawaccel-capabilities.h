#pragma once

#include "rawaccel-io-def.h"

namespace rawaccel {
    constexpr ULONG CAPABILITIES = (ULONG)CTL_CODE(0x8888u, 0x88b, METHOD_BUFFERED, FILE_ANY_ACCESS);

    constexpr ULONG CAPABILITY_READ = 0x0001u;
    constexpr ULONG CAPABILITY_WRITE = 0x0002u;
    constexpr ULONG CAPABILITY_LUT = 0x0004u;
    constexpr ULONG CAPABILITY_DEVICE_MAPPING = 0x0008u;
    constexpr ULONG CAPABILITY_RESET = 0x0010u;
    constexpr ULONG CAPABILITY_FLOAT32_SETTINGS = 0x0020u;

    constexpr ULONG CAPABILITY_PROTOCOL_MAJOR = 1u;
    constexpr ULONG CAPABILITY_PROTOCOL_MINOR = 0u;

    struct capability_info {
        ULONG struct_size = sizeof(capability_info);
        ULONG protocol_major = CAPABILITY_PROTOCOL_MAJOR;
        ULONG protocol_minor = CAPABILITY_PROTOCOL_MINOR;
        ULONG driver_version_packed = 0;
        ULONG feature_flags = 0;
        ULONG max_modifier_count = 0;
        ULONG max_device_count = 0;
        ULONG max_io_bytes = 0;
        ULONG reserved[8] = {};
    };

    static_assert(sizeof(capability_info) == 64, "capability_info ABI must remain 64 bytes");
}
