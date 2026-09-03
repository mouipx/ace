/*
 * rawaccel_bridge.cpp — implementation.
 *
 * Reuses Raw Accel's own common/ structs and io helpers so the binary layout
 * (padding, the accel_union, fixed-size arrays) is compiler-guaranteed to
 * match the kernel driver. JSON <-> struct mapping mirrors the C# wrapper's
 * property names exactly, so it reads the same settings.json files.
 *
 * NOTE: RA_BRIDGE_EXPORTS is defined on the compiler command line (build.bat),
 * not here, to avoid a macro-redefinition warning.
 */

#include "rawaccel_bridge.h"

#include <cstddef>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <string>
#include <vector>
#include <sstream>
#include <stdexcept>
#include <unordered_set>

#include "rawaccel-common/rawaccel-io.hpp"
#include "rawaccel-common/rawaccel-validate.hpp"
#include "json.hpp"

using json = nlohmann::json;
namespace ra = rawaccel;

/* ------------------------------------------------------------------ */
/* UTF-8 <-> UTF-16 (native structs store names as wchar_t)            */
/* ------------------------------------------------------------------ */

static std::wstring utf8_to_wide(const std::string& s)
{
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (n <= 0) return L"";
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}

static std::string wide_to_utf8(const wchar_t* w)
{
    if (!w || !w[0]) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 0) return "";
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    while (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

static void set_wstr(wchar_t* dst, size_t cap, const std::wstring& s)
{
    size_t n = s.size() < cap ? s.size() : cap - 1;
    if (n) std::memcpy(dst, s.c_str(), n * sizeof(wchar_t));
    dst[n] = 0;
}

/* ------------------------------------------------------------------ */
/* enum string mapping (matches C# StringEnumConverter)                */
/* ------------------------------------------------------------------ */

static const char* accel_mode_str(ra::accel_mode m)
{
    switch (m) {
        case ra::accel_mode::classic:     return "classic";
        case ra::accel_mode::jump:        return "jump";
        case ra::accel_mode::natural:     return "natural";
        case ra::accel_mode::synchronous: return "synchronous";
        case ra::accel_mode::power:       return "power";
        case ra::accel_mode::lookup:      return "lut";
        case ra::accel_mode::noaccel:     return "noaccel";
    }
    return "noaccel";
}

static bool accel_mode_parse(const std::string& s, ra::accel_mode& out)
{
    if (s == "classic")            out = ra::accel_mode::classic;
    else if (s == "jump")          out = ra::accel_mode::jump;
    else if (s == "natural")       out = ra::accel_mode::natural;
    else if (s == "synchronous")   out = ra::accel_mode::synchronous;
    else if (s == "power")         out = ra::accel_mode::power;
    else if (s == "lut" || s == "lookup") out = ra::accel_mode::lookup;
    else if (s == "noaccel")       out = ra::accel_mode::noaccel;
    else return false;
    return true;
}

static const char* cap_mode_str(ra::cap_mode m)
{
    switch (m) {
        case ra::cap_mode::io:  return "in_out";
        case ra::cap_mode::in:  return "input";
        case ra::cap_mode::out: return "output";
    }
    return "output";
}

static bool cap_mode_parse(const std::string& s, ra::cap_mode& out)
{
    if (s == "in_out")            out = ra::cap_mode::io;
    else if (s == "input")        out = ra::cap_mode::in;
    else if (s == "output")       out = ra::cap_mode::out;
    else return false;
    return true;
}

/* ------------------------------------------------------------------ */
/* accel_args                                                          */
/* ------------------------------------------------------------------ */

static json accel_to_json(const ra::accel_args& a)
{
    json j;
    j["mode"]            = accel_mode_str(a.mode);
    j["Gain / Velocity"] = a.gain;
    j["inputOffset"]     = a.input_offset;
    j["outputOffset"]    = a.output_offset;
    j["acceleration"]    = a.acceleration;
    j["decayRate"]       = a.decay_rate;
    j["gamma"]           = a.gamma;
    j["motivity"]        = a.motivity;
    j["exponentClassic"] = a.exponent_classic;
    j["scale"]           = a.scale;
    j["exponentPower"]   = a.exponent_power;
    j["limit"]           = a.limit;
    j["syncSpeed"]       = a.sync_speed;
    j["smooth"]          = a.smooth;
    j["Cap / Jump"]      = { {"x", a.cap.x}, {"y", a.cap.y} };
    j["Cap mode"]        = cap_mode_str(a.cap_mode);

    json d = json::array();
    if (a.mode == ra::accel_mode::lookup) {
        for (int i = 0; i < a.length && i < (int)ra::LUT_RAW_DATA_CAPACITY; i++) {
            d.push_back((double)a.data[i]);
        }
    }
    j["data"] = d;
    return j;
}

static void accel_from_json(const json& j, ra::accel_args& a)
{
    a = ra::accel_args{};   // reset to the same defaults as the C++ header

    std::string mode = j.value("mode", "noaccel");
    ra::accel_mode m;
    if (!accel_mode_parse(mode, m)) {
        throw std::runtime_error("unknown acceleration mode: " + mode);
    }
    a.mode = m;

    a.gain             = j.value("Gain / Velocity", true);
    a.input_offset     = j.value("inputOffset", 0.0);
    a.output_offset    = j.value("outputOffset", 0.0);
    a.acceleration     = j.value("acceleration", 0.005);
    a.decay_rate       = j.value("decayRate", 0.1);
    a.gamma            = j.value("gamma", 1.0);
    a.motivity         = j.value("motivity", 1.5);
    a.exponent_classic = j.value("exponentClassic", 2.0);
    a.scale            = j.value("scale", 1.0);
    a.exponent_power   = j.value("exponentPower", 0.05);
    a.limit            = j.value("limit", 1.5);
    a.sync_speed       = j.value("syncSpeed", 5.0);
    a.smooth           = j.value("smooth", 0.5);

    if (j.contains("Cap / Jump")) {
        a.cap.x = j["Cap / Jump"].value("x", 15.0);
        a.cap.y = j["Cap / Jump"].value("y", 1.5);
    }

    std::string cm = j.value("Cap mode", "output");
    ra::cap_mode c;
    if (!cap_mode_parse(cm, c)) {
        throw std::runtime_error("unknown cap mode: " + cm);
    }
    a.cap_mode = c;

    if (j.contains("data") && !j["data"].is_array()) {
        throw std::runtime_error("acceleration data must be an array");
    }
    if (j.contains("data") && j["data"].is_array()) {
        if (j["data"].size() > ra::LUT_RAW_DATA_CAPACITY) {
            throw std::runtime_error("too many LUT values (maximum is 514 / 257 points)");
        }
        int n = 0;
        for (auto& v : j["data"]) {
            const double value = v.get<double>();
            if (!std::isfinite(value)) throw std::runtime_error("LUT values must be finite numbers");
            a.data[n++] = (float)value;
        }
        a.length = n;
    }

    if (a.mode == ra::accel_mode::lookup) {
        if (a.length < 4 || (a.length % 2) != 0) {
            throw std::runtime_error("lookup mode requires an even number of LUT values and at least 2 points");
        }
        float previous_x = a.data[0];
        if (previous_x < 0) throw std::runtime_error("LUT input speeds can not be negative");
        for (int i = 2; i < a.length; i += 2) {
            const float x = a.data[i];
            if (x <= previous_x) throw std::runtime_error("LUT input speeds must be strictly increasing");
            previous_x = x;
        }
    }
}

/* ------------------------------------------------------------------ */
/* speed_args / profile / device                                       */
/* ------------------------------------------------------------------ */

static json speed_to_json(const ra::speed_args& s)
{
    json j;
    j["Whole/combined accel (set false for 'by component' mode)"] = s.whole;
    j["lpNorm"] = s.lp_norm;
    j["Time in ms after which an input is weighted at half its original value."]  = s.input_speed_smooth_halflife;
    j["Time in ms after which scale is weighted at half its original value."]     = s.scale_smooth_halflife;
    j["Time in ms after which an output is weighted at half its original value."] = s.output_speed_smooth_halflife;
    return j;
}

static void speed_from_json(const json& j, ra::speed_args& s)
{
    s = ra::speed_args{};
    s.whole = j.value("Whole/combined accel (set false for 'by component' mode)", true);
    s.lp_norm = j.value("lpNorm", 2.0);
    s.input_speed_smooth_halflife  = j.value("Time in ms after which an input is weighted at half its original value.", 0.0);
    s.scale_smooth_halflife        = j.value("Time in ms after which scale is weighted at half its original value.", 0.0);
    s.output_speed_smooth_halflife = j.value("Time in ms after which an output is weighted at half its original value.", 0.0);
}

static json profile_to_json(const ra::profile& p)
{
    json j;
    j["name"] = wide_to_utf8(p.name);
    j["Stretches domain for horizontal vs vertical inputs"] = { {"x", p.domain_weights.x}, {"y", p.domain_weights.y} };
    j["Stretches accel range for horizontal vs vertical inputs"] = { {"x", p.range_weights.x}, {"y", p.range_weights.y} };
    j["Whole or horizontal accel parameters"] = accel_to_json(p.accel_x);
    j["Vertical accel parameters"] = accel_to_json(p.accel_y);
    j["Input speed calculation parameters"] = speed_to_json(p.speed_processor_args);
    j["Output DPI"] = p.output_dpi;
    j["Y/X output DPI ratio (vertical sens multiplier)"] = p.yx_output_dpi_ratio;
    j["L/R output DPI ratio (left sens multiplier)"] = p.lr_output_dpi_ratio;
    j["U/D output DPI ratio (up sens multiplier)"] = p.ud_output_dpi_ratio;
    j["Degrees of rotation"] = p.degrees_rotation;
    j["Degrees of angle snapping"] = p.degrees_snap;
    j["Input Speed Cap"] = p.speed_max;
    return j;
}

static void profile_from_json(const json& j, ra::profile& p)
{
    p = ra::profile{};
    const auto profile_name = utf8_to_wide(j.value("name", "default"));
    if (profile_name.size() >= ra::MAX_NAME_LEN) {
        throw std::runtime_error("profile name is too long");
    }
    set_wstr(p.name, ra::MAX_NAME_LEN, profile_name);

    if (j.contains("Stretches domain for horizontal vs vertical inputs")) {
        p.domain_weights.x = j["Stretches domain for horizontal vs vertical inputs"].value("x", 1.0);
        p.domain_weights.y = j["Stretches domain for horizontal vs vertical inputs"].value("y", 1.0);
    }
    if (j.contains("Stretches accel range for horizontal vs vertical inputs")) {
        p.range_weights.x = j["Stretches accel range for horizontal vs vertical inputs"].value("x", 1.0);
        p.range_weights.y = j["Stretches accel range for horizontal vs vertical inputs"].value("y", 1.0);
    }

    accel_from_json(j.value("Whole or horizontal accel parameters", json::object()), p.accel_x);
    accel_from_json(j.value("Vertical accel parameters", json::object()), p.accel_y);
    speed_from_json(j.value("Input speed calculation parameters", json::object()), p.speed_processor_args);

    p.output_dpi          = j.value("Output DPI", (double)ra::NORMALIZED_DPI);
    p.yx_output_dpi_ratio = j.value("Y/X output DPI ratio (vertical sens multiplier)", 1.0);
    p.lr_output_dpi_ratio = j.value("L/R output DPI ratio (left sens multiplier)", 1.0);
    p.ud_output_dpi_ratio = j.value("U/D output DPI ratio (up sens multiplier)", 1.0);
    p.degrees_rotation    = j.value("Degrees of rotation", 0.0);
    p.degrees_snap        = j.value("Degrees of angle snapping", 0.0);
    p.speed_max           = j.value("Input Speed Cap", 0.0);
    p.speed_min           = 0.0;
}

static json devcfg_to_json(const ra::device_config& c)
{
    json j;
    j["disable"] = c.disable;
    j["setExtraInfo"] = c.set_extra_info;
    j["Use constant time interval based on polling rate"] = c.poll_time_lock;
    j["DPI (normalizes input speed unit: counts/ms -> in/s)"] = c.dpi;
    j["Polling rate Hz (keep at 0 for automatic adjustment)"] = c.polling_rate;
    j["minimumTime"] = c.clamp.min;
    j["maximumTime"] = c.clamp.max;
    return j;
}

static void devcfg_from_json(const json& j, ra::device_config& c)
{
    c = ra::device_config{};
    c.disable        = j.value("disable", false);
    c.set_extra_info = j.value("setExtraInfo", false);
    c.poll_time_lock = j.value("Use constant time interval based on polling rate", false);
    c.dpi            = j.value("DPI (normalizes input speed unit: counts/ms -> in/s)", 0);
    c.polling_rate   = j.value("Polling rate Hz (keep at 0 for automatic adjustment)", 0);
    c.clamp.min      = j.value("minimumTime", (double)ra::DEFAULT_TIME_MIN);
    c.clamp.max      = j.value("maximumTime", (double)ra::DEFAULT_TIME_MAX);
}

static json device_to_json(const ra::device_settings& d)
{
    json j;
    j["name"]    = wide_to_utf8(d.name);
    j["profile"] = wide_to_utf8(d.profile);
    j["id"]      = wide_to_utf8(d.id);
    j["config"]  = devcfg_to_json(d.config);
    return j;
}

static void device_from_json(const json& j, ra::device_settings& d)
{
    d = ra::device_settings{};
    const auto name = utf8_to_wide(j.value("name", ""));
    const auto profile = utf8_to_wide(j.value("profile", ""));
    const auto id = utf8_to_wide(j.value("id", ""));
    if (name.size() >= ra::MAX_NAME_LEN) throw std::runtime_error("device name is too long");
    if (profile.size() >= ra::MAX_NAME_LEN) throw std::runtime_error("device profile name is too long");
    if (id.size() >= ra::MAX_DEV_ID_LEN) throw std::runtime_error("device id is too long");
    set_wstr(d.name,    ra::MAX_NAME_LEN,  name);
    set_wstr(d.profile, ra::MAX_NAME_LEN,  profile);
    set_wstr(d.id,      ra::MAX_DEV_ID_LEN, id);
    devcfg_from_json(j.value("config", json::object()), d.config);
}

static void validate_settings(
    const ra::device_config& default_cfg,
    const std::vector<ra::modifier_settings>& mods,
    const std::vector<ra::device_settings>& devs)
{
    std::ostringstream errors;
    int error_count = 0;
    auto add_error = [&](const std::string& context, const char* message) {
        if (error_count++) errors << "; ";
        errors << context << ": " << message;
    };

    ra::device_settings default_device{};
    default_device.config = default_cfg;
    ra::valid(default_device, [&](const char* message) { add_error("default device", message); });

    std::unordered_set<std::wstring> profile_names;
    for (size_t i = 0; i < mods.size(); ++i) {
        const auto& profile = mods[i].prof;
        const std::string context = "profile " + std::to_string(i + 1);
        ra::valid(profile, [&](const char* message) { add_error(context, message); });
        if (!profile_names.insert(profile.name).second) {
            add_error(context, "duplicate profile name");
        }
    }

    for (size_t i = 0; i < devs.size(); ++i) {
        const auto& device = devs[i];
        const std::string context = "device " + std::to_string(i + 1);
        ra::valid(device, [&](const char* message) { add_error(context, message); });
        if (device.profile[0] == L'\0' || profile_names.count(device.profile) == 0) {
            add_error(context, "references a profile name that does not exist");
        }
        if (device.id[0] == L'\0') {
            add_error(context, "device id can not be empty");
        }
    }

    if (error_count) {
        throw std::runtime_error(errors.str());
    }
}

/* ------------------------------------------------------------------ */
/* exported C API                                                      */
/* ------------------------------------------------------------------ */

extern "C" RA_API int ra_apply_json(const char* json_text, char* err, int err_cap)
{
    try {
        json j = json::parse(json_text ? json_text : "{}");

        ra::device_config default_cfg{};
        if (j.contains("defaultDeviceConfig")) {
            devcfg_from_json(j["defaultDeviceConfig"], default_cfg);
        }

        std::vector<ra::modifier_settings> mods;
        if (j.contains("profiles") && j["profiles"].is_array()) {
            for (auto& pj : j["profiles"]) {
                ra::modifier_settings ms{};
                profile_from_json(pj, ms.prof);
                ra::init_data(ms);   // pre-compute the accel_union data the driver executes
                mods.push_back(ms);
            }
        }
        if (mods.empty()) {
            throw std::runtime_error("settings must contain at least one profile");
        }

        std::vector<ra::device_settings> devs;
        if (j.contains("devices") && j["devices"].is_array()) {
            for (auto& dj : j["devices"]) {
                ra::device_settings ds{};
                device_from_json(dj, ds);
                devs.push_back(ds);
            }
        }

        validate_settings(default_cfg, mods, devs);
        ra::valid_version_or_throw();

        size_t mod_bytes = mods.size() * sizeof(ra::modifier_settings);
        size_t dev_bytes = devs.size() * sizeof(ra::device_settings);
        std::vector<std::byte> buf(sizeof(ra::io_base) + mod_bytes + dev_bytes);

        auto* base = reinterpret_cast<ra::io_base*>(buf.data());
        base->default_dev_cfg   = default_cfg;
        base->modifier_data_size = (unsigned)mods.size();
        base->device_data_size   = (unsigned)devs.size();

        std::byte* p = buf.data() + sizeof(ra::io_base);
        std::memcpy(p, mods.data(), mod_bytes);
        p += mod_bytes;
        if (dev_bytes) std::memcpy(p, devs.data(), dev_bytes);

        ra::write(buf.data());
        return 0;
    } catch (const std::exception& e) {
        if (err && err_cap > 0) std::snprintf(err, err_cap, "%s", e.what());
        return 1;
    }
}

extern "C" RA_API int ra_read_json(char* out, int out_cap, char* err, int err_cap)
{
    try {
        auto bytes = ra::read();
        auto* base = reinterpret_cast<ra::io_base*>(bytes.get());
        auto* byte_ptr = bytes.get() + sizeof(ra::io_base);

        json j;
        j["version"] = RA_VER_STRING;
        j["defaultDeviceConfig"] = devcfg_to_json(base->default_dev_cfg);

        json profs = json::array();
        auto* mods = reinterpret_cast<ra::modifier_settings*>(byte_ptr);
        for (unsigned i = 0; i < base->modifier_data_size; i++) {
            profs.push_back(profile_to_json(mods[i].prof));
        }
        byte_ptr += base->modifier_data_size * sizeof(ra::modifier_settings);
        j["profiles"] = profs;

        json devs = json::array();
        auto* dev = reinterpret_cast<ra::device_settings*>(byte_ptr);
        for (unsigned i = 0; i < base->device_data_size; i++) {
            devs.push_back(device_to_json(dev[i]));
        }
        j["devices"] = devs;

        std::string s = j.dump(4);
        if (!out || out_cap <= 0) {
            throw std::runtime_error("read output buffer is missing");
        }
        if (s.size() + 1 > (size_t)out_cap) {
            throw std::runtime_error("read output buffer is too small");
        }
        std::memcpy(out, s.c_str(), s.size());
        out[s.size()] = 0;
        return 0;
    } catch (const std::exception& e) {
        if (err && err_cap > 0) std::snprintf(err, err_cap, "%s", e.what());
        return 1;
    }
}

extern "C" RA_API int ra_reset(char* err, int err_cap)
{
    try {
        ra::reset();
        return 0;
    } catch (const std::exception& e) {
        if (err && err_cap > 0) std::snprintf(err, err_cap, "%s", e.what());
        return 1;
    }
}

extern "C" RA_API int ra_version(int* maj, int* min, int* patch, char* err, int err_cap)
{
    try {
        ra::version_t v = ra::get_version();
        if (maj)   *maj   = v.major;
        if (min)   *min   = v.minor;
        if (patch) *patch = v.patch;
        return 0;
    } catch (const std::exception& e) {
        if (err && err_cap > 0) std::snprintf(err, err_cap, "%s", e.what());
        return 1;
    }
}

extern "C" RA_API int ra_present(void)
{
    HANDLE h = CreateFileW(L"\\\\.\\rawaccel", 0, 0, 0, OPEN_EXISTING, 0, 0);
    if (h == INVALID_HANDLE_VALUE) return 0;
    CloseHandle(h);
    return 1;
}
