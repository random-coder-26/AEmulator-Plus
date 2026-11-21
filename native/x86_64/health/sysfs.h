// /sys/class/power_supply reader of the stand-in health service (service.cpp), free of HIDL types so that
// tests/health-host-smoke.sh can build it on the host. The enum values are those of health@1.0 BatteryStatus / BatteryHealth.
#pragma once
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <initializer_list>
#include <string>

namespace health_sysfs {

enum { STATUS_UNKNOWN = 1, STATUS_CHARGING = 2, STATUS_DISCHARGING = 3, STATUS_NOT_CHARGING = 4, STATUS_FULL = 5 };
enum { HEALTH_UNKNOWN = 1, HEALTH_GOOD = 2, HEALTH_OVERHEAT = 3, HEALTH_DEAD = 4, HEALTH_OVER_VOLTAGE = 5, HEALTH_UNSPECIFIED = 6, HEALTH_COLD = 7 };

// the first line of a node, without the newline; false when the node cannot be read
inline bool readNode(const std::string& root, const std::string& rel, std::string* out) {
    std::string path = root + "/" + rel;
    FILE* f = fopen(path.c_str(), "re");
    if (!f) return false;
    char buf[128];
    bool ok = fgets(buf, sizeof buf, f) != nullptr;
    fclose(f);
    if (!ok) return false;
    size_t n = strlen(buf);
    while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r' || buf[n - 1] == ' ')) buf[--n] = 0;
    *out = buf;
    return true;
}

// an integer node; false when it is missing or not a number (the getters answer NOT_SUPPORTED then)
inline bool readInt(const std::string& root, const std::string& rel, int64_t* v) {
    std::string s;
    if (!readNode(root, rel, &s) || s.empty()) return false;
    char* end = nullptr;
    long long x = strtoll(s.c_str(), &end, 10);
    if (end == s.c_str()) return false;
    *v = x;
    return true;
}

inline int32_t clampI32(int64_t v) { return v > INT32_MAX ? INT32_MAX : (v < INT32_MIN ? INT32_MIN : (int32_t)v); }

// the first of several nodes that exists (kernels name one value differently: temp / batt_temp)
inline bool readIntAny(const std::string& root, std::initializer_list<const char*> rels, int64_t* v) {
    for (const char* r : rels) if (readInt(root, r, v)) return true;
    return false;
}

inline int parseStatus(const std::string& s) {
    if (s == "Charging") return STATUS_CHARGING;
    if (s == "Discharging") return STATUS_DISCHARGING;
    if (s == "Not charging") return STATUS_NOT_CHARGING;
    if (s == "Full") return STATUS_FULL;
    return STATUS_UNKNOWN;
}

inline int parseHealth(const std::string& s) {
    if (s == "Good") return HEALTH_GOOD;
    if (s == "Overheat") return HEALTH_OVERHEAT;
    if (s == "Dead") return HEALTH_DEAD;
    if (s == "Over voltage") return HEALTH_OVER_VOLTAGE;
    if (s == "Unspecified failure") return HEALTH_UNSPECIFIED;
    if (s == "Cold") return HEALTH_COLD;
    return HEALTH_UNKNOWN;
}

inline bool online(const std::string& root, const char* supply) {
    int64_t v = 0;
    return readInt(root, std::string(supply) + "/online", &v) && v != 0;
}

struct Reading {
    bool ac = false, usb = false, wireless = false, present = true;
    int status = STATUS_UNKNOWN, health = HEALTH_UNKNOWN;
    int32_t level = 100, voltageMv = 0, tempTenths = 250, currentNow = 0, cycles = 0, fullCharge = 0, chargeCounter = 0, currentAvg = 0;
    std::string technology = "Li-ion";
};

// BatteryMonitor::updateValues, on the nodes the guest has
inline Reading read(const std::string& root) {
    Reading r;
    int64_t v = 0;
    std::string s;
    r.ac = online(root, "ac");
    r.usb = online(root, "usb");
    r.wireless = online(root, "wireless");
    if (readNode(root, "battery/status", &s)) r.status = parseStatus(s);
    if (readNode(root, "battery/health", &s)) r.health = parseHealth(s);
    if (readInt(root, "battery/present", &v)) r.present = v != 0;
    if (readInt(root, "battery/capacity", &v)) r.level = clampI32(v);
    // voltage_now is in microvolts, the HAL reports millivolts; batt_vol (Samsung) is already millivolts
    if (readInt(root, "battery/voltage_now", &v)) r.voltageMv = clampI32(v / 1000);
    else if (readInt(root, "battery/batt_vol", &v)) r.voltageMv = clampI32(v);
    if (readIntAny(root, {"battery/temp", "battery/batt_temp"}, &v)) r.tempTenths = clampI32(v);
    if (readInt(root, "battery/current_now", &v)) r.currentNow = clampI32(v);
    if (readInt(root, "battery/cycle_count", &v)) r.cycles = clampI32(v);
    if (readInt(root, "battery/charge_full", &v)) r.fullCharge = clampI32(v);
    if (readInt(root, "battery/charge_counter", &v)) r.chargeCounter = clampI32(v);
    if (readInt(root, "battery/current_avg", &v)) r.currentAvg = clampI32(v);
    if (readNode(root, "battery/technology", &s) && !s.empty()) r.technology = s;
    return r;
}

}  // namespace health_sysfs
