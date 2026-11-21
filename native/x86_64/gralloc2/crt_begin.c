/* _start of a dynamic executable for bionic on x86-64 (zig has no bionic crt): the same as bionic's
 * arch-common/bionic/crtbegin.c, which hands the kernel's argument block to __libc_init. Link this object FIRST and
 * crt_end.o LAST: the sentinel -1 in front and the NULL behind the constructor arrays are what __libc_init walks. */
#include <stddef.h>
typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
typedef struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } structors_array_t;
extern int main(int, char **, char **);
extern void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **), structors_array_t *const structors);

#define SECTION(name) __attribute__((__section__(name)))
SECTION(".preinit_array") init_func_t *__PREINIT_ARRAY__ = (init_func_t *)-1;
SECTION(".init_array.0") init_func_t *__INIT_ARRAY__ = (init_func_t *)-1;
SECTION(".fini_array.0") fini_func_t *__FINI_ARRAY__ = (fini_func_t *)-1;

__attribute__((used)) static void _start_main(void *raw_args) {
    structors_array_t array;
    array.preinit_array = &__PREINIT_ARRAY__;
    array.init_array = &__INIT_ARRAY__;
    array.fini_array = &__FINI_ARRAY__;
    __libc_init(raw_args, NULL, &main, &array);
}
__asm__(".text; .global _start; .type _start,%function; _start:"
        "xorl %ebp, %ebp; movq %rsp,%rdi; andq $~0xf,%rsp; callq _start_main; .size _start, .-_start");

/* what bionic's __dso_handle.h gives every module (hidden: the module's own handle for __cxa_atexit) */
__attribute__((visibility("hidden"))) void *__dso_handle = (void *)0;
