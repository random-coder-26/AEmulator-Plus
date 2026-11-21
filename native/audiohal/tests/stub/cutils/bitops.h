#ifndef STUB_CUTILS_BITOPS_H
#define STUB_CUTILS_BITOPS_H
#include <stdint.h>
#include <stddef.h>
static inline int popcount(unsigned int x) { return __builtin_popcount(x); }
static inline int popcountl(unsigned long x) { return __builtin_popcountl(x); }
static inline int popcountll(unsigned long long x) { return __builtin_popcountll(x); }
#endif
