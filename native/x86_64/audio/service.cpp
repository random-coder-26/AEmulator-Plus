// android.hardware.audio@2.0-service for the x86-64 engine: the AOSP audio service (hardware/interfaces/audio/common/all-versions/
// default/service/service.cpp) reduced to the 2.0 core and effect factories, which are what the engine's impl libraries
// provide. libaudiohal of Android 10/11 finds them after trying 6.0, 5.0 and 4.0.
#define LOG_TAG "audiohalservice"
#include <android/hardware/audio/2.0/IDevicesFactory.h>
#include <android/hardware/audio/effect/2.0/IEffectsFactory.h>
#include <hidl/HidlTransportSupport.h>
#include <hidl/LegacySupport.h>
#include <log/log.h>

using namespace android::hardware;
using android::OK;

int main() {
    configureRpcThreadpool(16, true /*callerWillJoin*/);
    LOG_ALWAYS_FATAL_IF(registerPassthroughServiceImplementation<audio::V2_0::IDevicesFactory>() != OK,
                        "Could not register Audio Core API 2.0");
    LOG_ALWAYS_FATAL_IF(registerPassthroughServiceImplementation<audio::effect::V2_0::IEffectsFactory>() != OK,
                        "Could not register Audio Effect API 2.0");
    joinRpcThreadpool();
    return 0;
}
