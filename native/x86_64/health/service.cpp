// android.hardware.health@2.0-service for the x86-64 engine: a stand-in IHealth for a system-only x86-64 image that has no
// health HAL (the Android 11 SDK image: /vendor is empty). Why: system_server's BatteryService looks IHealth up as "default"
// and then "backup" and, when neither answers, throws NoSuchElementException ("No IHealth service instance among [default,
// backup] is available") from onStart; the exception ends system_server and zygote follows (killed by signal 9, restarted
// for ever). The stand-in registers IHealth/default and serves the state of /sys/class/power_supply, which the app's host
// battery bridge (HostBatteryBridge.writeSysfs) keeps current: the same nodes AOSP's healthd BatteryMonitor reads.
//
// Behaviour follows hardware/interfaces/health/2.0/default (Health.cpp):
//  * registerCallback() links to the callback's death and answers the new callback with the current HealthInfo right away
//    (BatteryService relies on that first push: its isPowered() would otherwise see a never-filled HealthInfo);
//  * update() re-reads the nodes and notifies every callback;
//  * a node that does not exist is NOT_SUPPORTED for the getters;
//  * a poll thread re-reads the nodes every 10 s and notifies only when something changed (healthd's periodic chores).
#define LOG_TAG "healthhalservice"
#include <android/hardware/health/2.0/IHealth.h>
#include <android/hardware/health/2.0/IHealthInfoCallback.h>
#include <android/hardware/health/2.0/types.h>
#include <hidl/HidlTransportSupport.h>
#include <hidl/Status.h>
#include <log/log.h>

#include "sysfs.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using ::android::OK;
using ::android::sp;
using ::android::hardware::hidl_death_recipient;
using ::android::hardware::hidl_vec;
using ::android::hardware::interfacesEqual;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::configureRpcThreadpool;
using ::android::hardware::joinRpcThreadpool;
using ::android::hidl::base::V1_0::IBase;
using ::android::hardware::health::V1_0::BatteryHealth;
using ::android::hardware::health::V1_0::BatteryStatus;
using ::android::hardware::health::V2_0::DiskStats;
using ::android::hardware::health::V2_0::HealthInfo;
using ::android::hardware::health::V2_0::IHealth;
using ::android::hardware::health::V2_0::IHealthInfoCallback;
using ::android::hardware::health::V2_0::Result;
using ::android::hardware::health::V2_0::StorageInfo;

namespace {

const char kPs[] = "/sys/class/power_supply";

using namespace health_sysfs;

bool readInt(const std::string& rel, int64_t* v) { return health_sysfs::readInt(kPs, rel, v); }
bool readNode(const std::string& rel, std::string* s) { return health_sysfs::readNode(kPs, rel, s); }

// the 2.0 HealthInfo of the current sysfs state
HealthInfo readHealthInfo() {
    Reading r = health_sysfs::read(kPs);
    HealthInfo hi{};
    auto& l = hi.legacy;
    l.chargerAcOnline = r.ac;
    l.chargerUsbOnline = r.usb;
    l.chargerWirelessOnline = r.wireless;
    l.maxChargingCurrent = 0;
    l.maxChargingVoltage = 0;
    l.batteryStatus = (BatteryStatus)r.status;
    l.batteryHealth = (BatteryHealth)r.health;
    l.batteryPresent = r.present;
    l.batteryLevel = r.level;
    l.batteryVoltage = r.voltageMv;
    l.batteryTemperature = r.tempTenths;
    l.batteryCurrent = r.currentNow;
    l.batteryCycleCount = r.cycles;
    l.batteryFullCharge = r.fullCharge;
    l.batteryChargeCounter = r.chargeCounter;
    l.batteryTechnology = r.technology.c_str();
    hi.batteryCurrentAverage = r.currentAvg;
    // no storage device to report on (the guest's disk is a file of the phone)
    hi.diskStats = hidl_vec<DiskStats>();
    hi.storageInfos = hidl_vec<StorageInfo>();
    return hi;
}

bool sameInfo(const HealthInfo& a, const HealthInfo& b) {
    const auto& x = a.legacy; const auto& y = b.legacy;
    return x.chargerAcOnline == y.chargerAcOnline && x.chargerUsbOnline == y.chargerUsbOnline &&
           x.chargerWirelessOnline == y.chargerWirelessOnline && x.batteryStatus == y.batteryStatus &&
           x.batteryHealth == y.batteryHealth && x.batteryPresent == y.batteryPresent && x.batteryLevel == y.batteryLevel &&
           x.batteryVoltage == y.batteryVoltage && x.batteryTemperature == y.batteryTemperature &&
           x.batteryCurrent == y.batteryCurrent && x.batteryCycleCount == y.batteryCycleCount &&
           x.batteryFullCharge == y.batteryFullCharge && x.batteryChargeCounter == y.batteryChargeCounter &&
           x.batteryTechnology == y.batteryTechnology && a.batteryCurrentAverage == b.batteryCurrentAverage;
}

struct Health : public IHealth, public hidl_death_recipient {
    std::mutex mLock;
    std::vector<sp<IHealthInfoCallback>> mCallbacks;
    HealthInfo mLast = readHealthInfo();

    // ---- IHealth ----
    Return<Result> registerCallback(const sp<IHealthInfoCallback>& cb) override {
        if (cb == nullptr) return Result::SUCCESS;
        {
            std::lock_guard<std::mutex> g(mLock);
            mCallbacks.push_back(cb);
        }
        // AOSP's HealthImpl::registerCallback does the same: a new listener gets the state at once
        auto linked = cb->linkToDeath(this, 0 /*cookie*/);
        if (!linked.isOk() || !linked) {
            ALOGW("registerCallback: linkToDeath failed, the callback is dropped");
            std::lock_guard<std::mutex> g(mLock);
            removeLocked(cb);
            return Result::UNKNOWN;
        }
        update();
        return Result::SUCCESS;
    }

    Return<Result> unregisterCallback(const sp<IHealthInfoCallback>& cb) override {
        std::lock_guard<std::mutex> g(mLock);
        return removeLocked(cb) ? Result::SUCCESS : Result::NOT_FOUND;
    }

    Return<Result> update() override {
        notify(readHealthInfo(), true);
        return Result::SUCCESS;
    }

    Return<void> getChargeCounter(getChargeCounter_cb cb) override { return intProp("battery/charge_counter", cb); }
    Return<void> getCurrentNow(getCurrentNow_cb cb) override { return intProp("battery/current_now", cb); }
    Return<void> getCurrentAverage(getCurrentAverage_cb cb) override { return intProp("battery/current_avg", cb); }
    Return<void> getCapacity(getCapacity_cb cb) override { return intProp("battery/capacity", cb); }

    Return<void> getEnergyCounter(getEnergyCounter_cb cb) override {
        int64_t v = 0;
        if (readInt("battery/energy_now", &v)) cb(Result::SUCCESS, v); else cb(Result::NOT_SUPPORTED, 0);
        return Void();
    }

    Return<void> getChargeStatus(getChargeStatus_cb cb) override {
        std::string s;
        if (readNode("battery/status", &s)) cb(Result::SUCCESS, (BatteryStatus)parseStatus(s)); else cb(Result::NOT_SUPPORTED, BatteryStatus::UNKNOWN);
        return Void();
    }

    Return<void> getStorageInfo(getStorageInfo_cb cb) override { cb(Result::SUCCESS, hidl_vec<StorageInfo>()); return Void(); }
    Return<void> getDiskStats(getDiskStats_cb cb) override { cb(Result::SUCCESS, hidl_vec<DiskStats>()); return Void(); }
    Return<void> getHealthInfo(getHealthInfo_cb cb) override { cb(Result::SUCCESS, readHealthInfo()); return Void(); }

    // ---- death of a callback ----
    void serviceDied(uint64_t /*cookie*/, const android::wp<IBase>& who) override {
        sp<IBase> dead = who.promote();
        std::lock_guard<std::mutex> g(mLock);
        auto it = mCallbacks.begin();
        while (it != mCallbacks.end()) {
            if (dead == nullptr || interfacesEqual(*it, dead)) it = mCallbacks.erase(it); else ++it;
        }
    }

    // ---- polling ----
    void poll() {
        for (;;) {
            sleep(10);
            notify(readHealthInfo(), false);
        }
    }

private:
    template <typename CB>
    Return<void> intProp(const char* node, CB cb) {
        int64_t v = 0;
        if (readInt(node, &v)) cb(Result::SUCCESS, clampI32(v)); else cb(Result::NOT_SUPPORTED, 0);
        return Void();
    }

    bool removeLocked(const sp<IHealthInfoCallback>& cb) {
        for (auto it = mCallbacks.begin(); it != mCallbacks.end(); ++it) {
            if (interfacesEqual(*it, cb)) { (*it)->unlinkToDeath(this); mCallbacks.erase(it); return true; }
        }
        return false;
    }

    // forced: update() always notifies; the poll only when the values moved
    void notify(const HealthInfo& hi, bool forced) {
        std::vector<sp<IHealthInfoCallback>> cbs;
        {
            std::lock_guard<std::mutex> g(mLock);
            if (!forced && sameInfo(hi, mLast)) return;
            mLast = hi;
            cbs = mCallbacks;
        }
        // outside the lock: a callback may call back into the service
        for (auto& cb : cbs) {
            auto r = cb->healthInfoChanged(hi);
            if (!r.isOk()) ALOGW("healthInfoChanged failed: %s", r.description().c_str());
        }
    }
};

}  // namespace

int main() {
    configureRpcThreadpool(2, false /*callerWillJoin: the poll thread below keeps main busy*/);
    sp<Health> health = new Health();
    if (health->registerAsService("default") != OK) {
        ALOGE("could not register android.hardware.health@2.0::IHealth/default");
        return 1;
    }
    ALOGI("android.hardware.health@2.0::IHealth/default registered (sysfs %s)", kPs);
    std::thread([health] { health->poll(); }).detach();
    joinRpcThreadpool();
    return 1;  // joinRpcThreadpool never returns
}
