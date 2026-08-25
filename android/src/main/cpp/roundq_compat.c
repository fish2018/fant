#include <math.h>

// Android's x86_64 bionic exports roundl for its 128-bit long double ABI, but
// Zig's package manager references the GNU-compatible roundq spelling.
typedef __float128 ant_float128;

ant_float128 roundq(ant_float128 value) {
  return (ant_float128)roundl((long double)value);
}
