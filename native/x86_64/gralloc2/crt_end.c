/* the NULL terminators behind the constructor arrays (bionic's crtend.S); see crt_begin.c */
#define SECTION(name) __attribute__((__section__(name)))
SECTION(".preinit_array") void *__PREINIT_ARRAY_END__ = (void *)0;
SECTION(".init_array") void *__INIT_ARRAY_END__ = (void *)0;
SECTION(".fini_array") void *__FINI_ARRAY_END__ = (void *)0;
