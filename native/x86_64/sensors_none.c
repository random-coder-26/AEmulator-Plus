/* sensors.default.so for an x86-64 guest that has no sensors module of its own (the emulator's sensors.ranchu.so needs the emulator host
 * and is parked): a legacy sensors HAL (sensors_module_t, sensors_poll_device_1_t, API 1.3) that reports no sensors.
 *
 * Why: android.hardware.sensors@1.0-service is a passthrough HAL. It calls hw_get_module("sensors"), opens the poll device and only then
 * registers ISensors/default. Without a module it logs "Couldn't load sensors module" and exits with code 1 on every start (init restarts
 * it 12 times). With a module that lists zero sensors the service registers and sensorservice sees an empty list, which apps handle.
 * poll() blocks (the HAL's poll thread calls it in a loop and would spin on an immediate return). */
#include <stdint.h>
#include <stddef.h>
#include <hardware/hardware.h>

#define SENSORS_HARDWARE_MODULE_ID "sensors"
#define SENSORS_HARDWARE_POLL "poll"
#define SENSORS_DEVICE_API_VERSION_1_3 HARDWARE_MAKE_API_VERSION_2(1, 3, 0x0100)
#define SENSORS_MODULE_API_VERSION_0_1 HARDWARE_MAKE_API_VERSION(0, 1)

typedef struct sensors_event_t { int32_t version; int32_t sensor; int32_t type; int32_t reserved0; int64_t timestamp; uint8_t data[64]; uint32_t flags; uint32_t reserved1[3]; } sensors_event_t;
struct sensor_t;
struct sensors_direct_mem_t; struct sensors_direct_cfg_t;
struct sensors_poll_device_1;
typedef struct sensors_poll_device_1 {
    struct hw_device_t common;
    int (*activate)(struct sensors_poll_device_1 *dev, int handle, int enabled);
    int (*setDelay)(struct sensors_poll_device_1 *dev, int handle, int64_t ns);
    int (*poll)(struct sensors_poll_device_1 *dev, sensors_event_t *data, int count);
    int (*batch)(struct sensors_poll_device_1 *dev, int handle, int flags, int64_t period_ns, int64_t max_latency_ns);
    int (*flush)(struct sensors_poll_device_1 *dev, int handle);
    int (*inject_sensor_data)(struct sensors_poll_device_1 *dev, const sensors_event_t *data);
    int (*register_direct_channel)(struct sensors_poll_device_1 *dev, const struct sensors_direct_mem_t *mem, int channel_handle);
    int (*config_direct_report)(struct sensors_poll_device_1 *dev, int handle, int channel_handle, const struct sensors_direct_cfg_t *cfg);
    void (*reserved_procs[5])(void);
} sensors_poll_device_1_t;
typedef struct sensors_module_t {
    struct hw_module_t common;
    int (*get_sensors_list)(struct sensors_module_t *module, const struct sensor_t **list);
    int (*set_operation_mode)(unsigned int mode);
} sensors_module_t;

extern unsigned int sleep(unsigned int);
#define EINVAL_ 22
#define ENOSYS_ 38

static int s_list(struct sensors_module_t *m, const struct sensor_t **list) { (void)m; *list = NULL; return 0; }
static int s_mode(unsigned int mode) { (void)mode; return 0; }
static int d_close(struct hw_device_t *d) { (void)d; return 0; }
static int d_activate(struct sensors_poll_device_1 *d, int h, int on) { (void)d; (void)h; (void)on; return -EINVAL_; }
static int d_delay(struct sensors_poll_device_1 *d, int h, int64_t ns) { (void)d; (void)h; (void)ns; return -EINVAL_; }
static int d_poll(struct sensors_poll_device_1 *d, sensors_event_t *e, int n) { (void)d; (void)e; (void)n; for (;;) sleep(3600); }
static int d_batch(struct sensors_poll_device_1 *d, int h, int f, int64_t p, int64_t l) { (void)d; (void)h; (void)f; (void)p; (void)l; return -EINVAL_; }
static int d_flush(struct sensors_poll_device_1 *d, int h) { (void)d; (void)h; return -EINVAL_; }
static int d_inject(struct sensors_poll_device_1 *d, const sensors_event_t *e) { (void)d; (void)e; return -ENOSYS_; }

static sensors_poll_device_1_t g_dev;

static int s_open(const struct hw_module_t *module, const char *id, struct hw_device_t **device)
{
    const char *want = SENSORS_HARDWARE_POLL; const char *p = id;
    if (!id) return -EINVAL_;
    while (*want && *p == *want) { want++; p++; }
    if (*want || *p) return -EINVAL_;
    g_dev.common.tag = HARDWARE_DEVICE_TAG;
    g_dev.common.version = SENSORS_DEVICE_API_VERSION_1_3;
    g_dev.common.module = (struct hw_module_t *)module;
    g_dev.common.close = d_close;
    g_dev.activate = d_activate; g_dev.setDelay = d_delay; g_dev.poll = d_poll;
    g_dev.batch = d_batch; g_dev.flush = d_flush; g_dev.inject_sensor_data = d_inject;
    *device = &g_dev.common;
    return 0;
}

static struct hw_module_methods_t s_methods = { .open = s_open };

__attribute__((visibility("default"))) sensors_module_t HAL_MODULE_INFO_SYM = {
    .common = { .tag = HARDWARE_MODULE_TAG, .module_api_version = SENSORS_MODULE_API_VERSION_0_1, .hal_api_version = 0,
                .id = SENSORS_HARDWARE_MODULE_ID, .name = "AEmulator no-sensors HAL", .author = "AEmulator Plus", .methods = &s_methods },
    .get_sensors_list = s_list,
    .set_operation_mode = s_mode,
};
