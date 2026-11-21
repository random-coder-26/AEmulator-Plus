/* AEmulator Plus 2026-10-27: stand-in for Intel CE4100 libgdl/libgma (Logitech Revue). There is no /dev/gdl here, so every call
 * reports GDL_SUCCESS (0). Freestanding i386, no libc. */
typedef unsigned int u32;
int gdl_plane_set_rect(void){ return 0; }
int gdl_check_tvmode(void){ return 0; }
int gdl_plane_capabilities(void){ return 0; }
int gdl_map_surface(void){ return 0; }
int gdl_unmap_surface(void){ return 0; }
/* gdl_port_recv(port, cmd, buf, size), as av_settings calls it (port is always 2 = HDMI):
 *  cmd 2, size 0x81: one 128-byte EDID block, buf[0] = block number. Report an all-zero block (extension count 0), success:
 *      leaving it untouched gave a random extension count and a random CEA block, and a failure crashes av_settings;
 *  cmd 0, size 0x18: HDMI audio capability entry number buf[1]; it ends the list on the first failure, so report one at once.
 *      Success with the buffer untouched made it print "HDMIAudioFmt[n] ... Channels 1082132168" over a million times;
 *  anything else (cmd 5, ...): success with a zeroed buffer. */
int gdl_port_recv(int port, int cmd, unsigned char* buf, u32 size){
    u32 i;
    if(cmd==0) return 1;
    if(buf) for(i=(cmd==2?1:0); i<size; i++) buf[i]=0;
    return 0;
}
int gdl_dbg_dump_csc_info(void){ return 0; }
int gdl_plane_set_uint(void){ return 0; }
int gdl_clear_surface(void){ return 0; }
int gdl_closed_caption_source(void){ return 0; }
int gdl_plane_get_uint(void){ return 0; }
int gdl_event_register(void){ return 0; }
const char* gdl_dbg_string_attribute(int v){ return ""; }
int gdl_plane_get_rect(void){ return 0; }
int gdl_set_display_info(void){ return 0; }
int gdl_plane_set_attr(void){ return 0; }
const char* gdl_dbg_string_audio_format(int v){ return ""; }
int gdl_get_palette(void){ return 0; }
int gdl_plane_get_attr(void){ return 0; }
const char* gdl_dbg_string_pixel_format(int v){ return ""; }
const char* gdl_dbg_string_speaker_map(int v){ return ""; }
int gdl_free_surface(void){ return 0; }
int gdl_debug_log_start(void){ return 0; }
int gdl_set_palette(void){ return 0; }
const char* gdl_get_error_string(int e){ return "ok"; }
int gdl_plane_config_begin(void){ return 0; }
int gdl_plane_reset(void){ return 0; }
int gdl_wait_for_vblank(void){ return 0; }
int gdl_plane_config_end(void){ return 0; }
int gdl_get_display_info(int id,u32*info){ if(info){info[0]=id;info[1]=1280;info[2]=720;info[3]=0;info[4]=0;} return 0; }
int gdl_close(void){ return 0; }
int gdl_flush_surface(void){ return 0; }
int gdl_flip(void){ return 0; }
int gdl_create_surface(void){ return 0; }
int gdl_get_surface_info(void){ return 0; }
int _gdl_init(void){ return 0; }
int gdl_get_upp_zorder(void){ return 0; }
int gdl_heap_in_use(void){ return 0; }
int gdl_dbg_dump_display_info(void){ return 0; }
int gdl_get(void){ return 0; }
int gdl_alloc_surface(void){ return 0; }
int gdl_dbg_dump_surface_info(void){ return 0; }
/* gdl_port_get_attr(port, attr, buf): av_settings asks port 2 (HDMI), attr 8 for the cable status; the "connected" flag is the
 * word at buf+0x24. Failing here logged "Failed to read HDMI cable status (gdl_ret 1)" for every poll (a million lines per boot)
 * and answered "connected" anyway, so report success and "connected". Any other attribute: not available, as before. */
int gdl_port_get_attr(int port, int attr, u32* buf){
    if(port==2 && attr==8 && buf){ buf[9]=1; return 0; }
    return 1;
}
int gdl_port_set_attr(void){ return 0; }
int gdl_plane_get_int(void){ return 0; }
int gdl_set_upp_zorder(void){ return 0; }
int gdl_debug_log_stop(void){ return 0; }
const char* gdl_dbg_string_plane_id(int v){ return ""; }
int gdl_attach_heap(void){ return 0; }
const char* gdl_dbg_string_sample_size(int v){ return ""; }
int gdl_plane_reset_all(void){ return 0; }
int gdl_get_driver_info(void){ return 0; }
int gdl_get_display_polarity(void){ return 0; }
int gdl_port_pd_unload(void){ return 0; }
int gdl_get_port_tvmode_by_index(void){ return 1; } /* end of the mode list at once: av_settings falls back to its built-in modes */
const char* gdl_dbg_string_refresh(int v){ return ""; }
int gdl_port_send(void){ return 0; }
const char* gdl_dbg_string_sampling_rate(int v){ return ""; }
int gdl_put(void){ return 0; }
int gdl_plane_set_int(void){ return 0; }
int gdl_event_unregister(void){ return 0; }
int gdl_port_pd_load(void){ return 0; }

/* AEmulator Plus 2026-11-17: eglCreateWindowSurface() interposer.
 * The Revue's SurfaceFlinger (DisplayHardware::init) does not build a FramebufferNativeWindow; it hands eglCreateWindowSurface() the
 * id of the ISMD UI plane (7: "SurfaceFlinger using 7 for ISMD UIPlane") and Intel's PowerVR EGL turns that into a GDL plane. The
 * software EGL that replaces it (the firmware's own libGLES_android.so) reads the argument as an ANDROID_NATIVE_WINDOW_MAGIC
 * object (cmp [window], 'dnw_'), so window = 7 faults at address 7 and system_server dies with signal 11 right after that log line.
 * libgdl is NEEDED by libandroid_runtime, so it sits in the global scope ahead of libEGL (which only libui, libopengl_bridge and
 * libsurfaceflinger need) and this definition is the one every egl call site binds to. A small integer window is replaced by one
 * FramebufferNativeWindow (libui, gralloc framebuffer /dev/graphics/fb0) made on first use; anything else, and every other process, goes
 * straight to the real function. Only a process that has libsurfaceflinger loaded, found with dlopen(RTLD_NOLOAD) since it is loaded locally (system_server) makes the window, so mediaserver and
 * the others never open the framebuffer a second time. All libc/libui references are weak, so processes that load libgdl without
 * libui still start. Checked natively against the Revue's own libEGL/libui/gralloc (480x800 emulated fbdev): without this
 * eglCreateWindowSurface(.., 7, ..) faults at address 7; with it the surface is made, eglMakeCurrent succeeds, EGL_WIDTH/HEIGHT
 * read 480x800 and a swap posts the cleared frame to the framebuffer. Build (byte-identical recipe of the other stand-ins):
 * gcc -m32 -O2 -ffreestanding -fno-stack-protector -nostdlib -shared -fPIC -Wl,-soname,libgdl.so -Wl,--hash-style=both */
extern void *dlsym(void *, const char *) __attribute__((weak));
extern void *dlopen(const char *, int) __attribute__((weak));
extern void *_Znwj(u32) __attribute__((weak));
extern void _ZN7android23FramebufferNativeWindowC1Ev(void *) __attribute__((weak));
static void *aemu_fbwin;
static volatile int aemu_fbwin_lock;
#define AEMU_FBWIN_BYTES 0x400 /* the object is below 0x100 bytes; no code here depends on the exact size */
void *eglCreateWindowSurface(void *dpy, void *cfg, void *win, const int *attr) {
    void *(*real)(void *, void *, void *, const int *) = 0;
    if (dlsym) real = dlsym((void *)-1 /* RTLD_NEXT */, "eglCreateWindowSurface");
    if (!real && dlopen && dlsym) { void *h = dlopen("libEGL.so", 2 /* RTLD_NOW */); if (h) real = dlsym(h, "eglCreateWindowSurface"); }
    if (!real) return 0;
    if ((u32)win != 0 && (u32)win < 0x10000 && _Znwj && _ZN7android23FramebufferNativeWindowC1Ev && dlopen &&
        /* libsurfaceflinger is dlopen()ed RTLD_LOCAL by libsystem_server, so RTLD_DEFAULT cannot see it; RTLD_NOLOAD can */
        (dlopen("libsurfaceflinger.so", 1 | 4 /* RTLD_LAZY | RTLD_NOLOAD */) || dlopen("/system/lib/libsurfaceflinger.so", 1 | 4))) {
        while (__sync_lock_test_and_set(&aemu_fbwin_lock, 1)) { }
        if (!aemu_fbwin) {
            unsigned char *p = (unsigned char *)_Znwj(AEMU_FBWIN_BYTES);
            if (p) { u32 i; for (i = 0; i < AEMU_FBWIN_BYTES; i++) p[i] = 0; _ZN7android23FramebufferNativeWindowC1Ev(p); aemu_fbwin = p; }
        }
        __sync_lock_release(&aemu_fbwin_lock);
        if (aemu_fbwin) win = aemu_fbwin;
    }
    return real(dpy, cfg, win, attr);
}
