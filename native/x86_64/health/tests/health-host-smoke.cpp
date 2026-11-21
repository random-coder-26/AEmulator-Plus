#include "sysfs.h"
#include <assert.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
using namespace health_sysfs;

static std::string root;
static void put(const std::string& rel, const std::string& v) {
    std::string p = root + "/" + rel;
    for (size_t i = root.size() + 1; i < p.size(); i++) if (p[i] == '/') mkdir(p.substr(0, i).c_str(), 0755);
    FILE* f = fopen(p.c_str(), "w"); assert(f); fputs(v.c_str(), f); fclose(f);
}
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main() {
    char tmpl[] = "/tmp/health-smoke-XXXXXX";
    root = mkdtemp(tmpl);
    // what TreeFixer.makeSysNodes writes, with the host bridge's values on top (a charging phone at 44 %)
    put("battery/type", "Battery\n"); put("battery/status", "Charging\n"); put("battery/health", "Good\n");
    put("battery/present", "1\n"); put("battery/capacity", "44\n"); put("battery/voltage_now", "4200000\n");
    put("battery/temp", "250\n"); put("battery/technology", "Li-ion\n"); put("battery/charge_counter", "3000000\n");
    put("battery/charge_full", "4000000\n"); put("battery/current_now", "-120000\n"); put("battery/current_avg", "-100000\n");
    put("battery/cycle_count", "1\n"); put("ac/online", "1\n"); put("usb/online", "0\n");
    Reading r = read(root);
    CHECK(r.status == STATUS_CHARGING); CHECK(r.health == HEALTH_GOOD); CHECK(r.level == 44); CHECK(r.ac && !r.usb && !r.wireless);
    CHECK(r.voltageMv == 4200); CHECK(r.tempTenths == 250); CHECK(r.chargeCounter == 3000000); CHECK(r.fullCharge == 4000000);
    CHECK(r.currentNow == -120000); CHECK(r.currentAvg == -100000); CHECK(r.cycles == 1); CHECK(r.technology == "Li-ion"); CHECK(r.present);
    // the host bridge's other states
    put("battery/status", "Discharging\n"); CHECK(read(root).status == STATUS_DISCHARGING);
    put("battery/status", "Not charging\n"); CHECK(read(root).status == STATUS_NOT_CHARGING);
    put("battery/status", "Full"); CHECK(read(root).status == STATUS_FULL);        // no trailing newline
    put("battery/status", "bogus\n"); CHECK(read(root).status == STATUS_UNKNOWN);
    put("battery/health", "Overheat\n"); CHECK(read(root).health == HEALTH_OVERHEAT);
    put("battery/capacity", "100\n"); CHECK(read(root).level == 100);
    // Samsung-style nodes: millivolts in batt_vol, temperature in batt_temp
    unlink((root + "/battery/voltage_now").c_str()); unlink((root + "/battery/temp").c_str());
    put("battery/batt_vol", "4150\n"); put("battery/batt_temp", "310\n");
    r = read(root); CHECK(r.voltageMv == 4150); CHECK(r.tempTenths == 310);
    // a missing node keeps its default, and the getters see it as absent
    unlink((root + "/battery/cycle_count").c_str());
    int64_t v = 0; CHECK(!readInt(root, "battery/cycle_count", &v)); CHECK(read(root).cycles == 0);
    put("battery/current_now", "garbage\n"); CHECK(!readInt(root, "battery/current_now", &v));
    // an empty tree (no power_supply at all) still yields a usable reading
    std::string empty = root + "/nowhere";
    r = read(empty); CHECK(r.level == 100 && r.present && r.status == STATUS_UNKNOWN && !r.ac);
    CHECK(clampI32(1LL << 40) == INT32_MAX); CHECK(clampI32(-(1LL << 40)) == INT32_MIN);
    printf("health sysfs smoke: ok\n");
    return 0;
}
