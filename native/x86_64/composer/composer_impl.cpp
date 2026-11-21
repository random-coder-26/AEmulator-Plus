/*
 * android.hardware.graphics.composer@2.1-impl.so for an x86-64 firmware (added for AEmulator Plus, 2026-11-14).
 *
 * AOSP's passthrough implementation (hardware/interfaces/graphics/composer/2.1/default/passthrough.cpp, HwcLoader::load()),
 * minus the adapters: HwcLoader wraps an HWC 1.x module in HWC2On1Adapter and a gralloc module in HWC2OnFbAdapter, which is
 * 5000 lines of libhwc2on1adapter that the x86-64 engine does not need, because its own module (native/hwcomposer, built by
 * ../build-hwcomposer.sh as hwcomposer.default.so) already is an HWC2 device. Anything else is refused.
 */
#define LOG_TAG "composer@2.1-impl"

#include <android/hardware/graphics/composer/2.1/IComposer.h>
#include <composer-hal/2.1/Composer.h>
#include <composer-passthrough/2.1/HwcHal.h>
#include <hardware/hardware.h>
#include <hardware/hwcomposer.h>
#include <hardware/hwcomposer2.h>
#include <log/log.h>
#include <string.h>

using android::hardware::graphics::composer::V2_1::IComposer;
using android::hardware::graphics::composer::V2_1::passthrough::HwcHal;
namespace hal = android::hardware::graphics::composer::V2_1::hal;

extern "C" IComposer* HIDL_FETCH_IComposer(const char* /* name */) {
    const hw_module_t* module = nullptr;
    int error = hw_get_module(HWC_HARDWARE_MODULE_ID, &module);
    if (error || !module) {
        ALOGE("failed to get the hwcomposer module");
        return nullptr;
    }
    hw_device_t* device = nullptr;
    error = module->methods->open(module, HWC_HARDWARE_COMPOSER, &device);
    if (error || !device) {
        ALOGE("failed to open hwcomposer device: %s", strerror(-error));
        return nullptr;
    }
    if (((device->version >> 24) & 0xf) != 2) {
        ALOGE("the hwcomposer module is not an HWC2 device (version 0x%x), no adapter in this build", device->version);
        return nullptr;
    }
    auto hal = std::make_unique<HwcHal>();
    // true: the module's present fences are not trusted to be reliable, as for a device that is not adapted
    if (!hal->initWithDevice(reinterpret_cast<hwc2_device_t*>(device), true)) return nullptr;
    return hal::Composer::create(std::move(hal)).release();
}
