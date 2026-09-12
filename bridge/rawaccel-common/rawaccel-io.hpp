#pragma once

#include "rawaccel-io-def.h"
#include "rawaccel-version.h"
#include "rawaccel-capabilities.h"
#include "rawaccel-error.hpp"
#include "rawaccel.hpp"

#include <memory>

namespace rawaccel {

    constexpr DWORD io_timeout_ms = 5000;

    inline DWORD io_control(DWORD code, void* in, DWORD in_size, void* out, DWORD out_size)
    {
        HANDLE ra_handle = INVALID_HANDLE_VALUE;

        ra_handle = CreateFileW(L"\\\\.\\rawaccel", 0, 0, 0, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, 0);

        if (ra_handle == INVALID_HANDLE_VALUE) {
            throw install_error();
        }

        HANDLE event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (event == NULL) {
            DWORD code = GetLastError();
            CloseHandle(ra_handle);
            SetLastError(code);
            throw sys_error("CreateEventW failed");
        }

        OVERLAPPED overlapped{};
        overlapped.hEvent = event;
        DWORD returned = 0;

        BOOL success = DeviceIoControl(
            ra_handle,
            code,
            in,
            in_size,
            out,
            out_size,
            &returned,
            &overlapped
        );

        if (!success) {
            DWORD error = GetLastError();
            if (error != ERROR_IO_PENDING) {
                CloseHandle(event);
                CloseHandle(ra_handle);
                SetLastError(error);
                throw sys_error("DeviceIoControl failed");
            }

            DWORD wait_result = WaitForSingleObject(event, io_timeout_ms);
            if (wait_result == WAIT_TIMEOUT) {
                CancelIoEx(ra_handle, &overlapped);
                WaitForSingleObject(event, 1000);
                CloseHandle(event);
                CloseHandle(ra_handle);
                SetLastError(ERROR_TIMEOUT);
                throw sys_error("DeviceIoControl timed out");
            }
            if (wait_result != WAIT_OBJECT_0) {
                DWORD wait_error = GetLastError();
                CloseHandle(event);
                CloseHandle(ra_handle);
                SetLastError(wait_error);
                throw sys_error("DeviceIoControl wait failed");
            }
            if (!GetOverlappedResult(ra_handle, &overlapped, &returned, FALSE)) {
                DWORD result_error = GetLastError();
                CloseHandle(event);
                CloseHandle(ra_handle);
                SetLastError(result_error);
                throw sys_error("DeviceIoControl completion failed");
            }
        }

        CloseHandle(event);
        CloseHandle(ra_handle);

        return returned;

        if (!success) {
            throw sys_error("DeviceIoControl failed");
        }
    }

    inline size_t checked_io_size(unsigned modifier_count, unsigned device_count)
    {
        constexpr size_t max_io_size = 16u * 1024u * 1024u;

        if (modifier_count > (max_io_size - sizeof(io_base)) / sizeof(modifier_settings)) {
            throw io_error("modifier data is too large");
        }

        size_t size = sizeof(io_base) + modifier_count * sizeof(modifier_settings);
        if (device_count > (max_io_size - size) / sizeof(device_settings)) {
            throw io_error("device data is too large");
        }

        return size + device_count * sizeof(device_settings);
    }

    inline std::unique_ptr<std::byte[]> read()
    {
        io_base base_data;

        if (io_control(READ, NULL, 0, &base_data, sizeof(io_base)) < sizeof(io_base)) {
            throw io_error("driver returned an incomplete settings header");
        }

        size_t size = sizeof(base_data);

        if (base_data.modifier_data_size == 0) {
            // driver has no data, but it's more useful to return something, 
            // so return a default modifier_settings object along with base data
             
            size += sizeof(modifier_settings);
            base_data.modifier_data_size = 1;
            auto bytes = std::make_unique<std::byte[]>(size);
            *reinterpret_cast<io_base*>(bytes.get()) = base_data;
            *reinterpret_cast<modifier_settings*>(bytes.get() + sizeof(io_base)) = {};
            return bytes;
        }
        else {
            size = checked_io_size(base_data.modifier_data_size, base_data.device_data_size);
            auto bytes = std::make_unique<std::byte[]>(size);
            if (io_control(READ, NULL, 0, bytes.get(), DWORD(size)) < size) {
                throw io_error("driver returned an incomplete settings buffer");
            }
            return bytes;
        }
    }

    // buffer must point to at least sizeof(io_base) bytes
    inline void write(const void* buffer)
    {
        if (buffer == nullptr) throw io_error("write buffer is null");

        auto* base_ptr = static_cast<const io_base*>(buffer);
        auto size = checked_io_size(base_ptr->modifier_data_size, base_ptr->device_data_size);

        io_control(WRITE, const_cast<void*>(buffer), DWORD(size), NULL, 0);
    }

    inline void reset()
    {
        io_base base_data{};
        // all modifier/device data is cleared when a default io_base is passed
        io_control(WRITE, &base_data, sizeof(io_base), NULL, 0);
    }

    inline version_t get_version() 
    {
        version_t v;

        try {
            if (io_control(GET_VERSION, NULL, 0, &v, sizeof(version_t)) < sizeof(version_t)) {
                throw io_error("driver returned an incomplete version");
            }
        }
        catch (const sys_error&) {
            // assume request is not implemented (< 1.3)
            v = { 0 }; 
        }

        return v;
    }

    inline version_t valid_version_or_throw()
    {
        auto v = get_version();

        if (v < min_driver_version) {
            throw error("reinstallation required");
        }

        if (version < v) {
            throw error("newer driver is installed");
        }

        return v;
    }

}
