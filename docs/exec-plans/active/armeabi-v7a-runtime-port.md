# Armeabi-v7a Runtime Port

## Goal

Make the Android runtime build and package a functional `armeabi-v7a` ABI while
preserving the existing 64-bit value representation and Android API surface.

## Constraints

- Keep `ant_value_t` as a 64-bit NaN-boxed value on every Android ABI.
- Do not pack native pointers or 32-bit identities into fields that are only
  wide enough on 64-bit hosts.
- Keep Android minimum API 24 and the existing Java/JNI interfaces unchanged.
- Do not enable the Silver MIR JIT on 32-bit ARM until a separate codegen port
  exists; the interpreter remains the supported execution path.

## Task List

- [x] Add 32-bit-safe inline-cache metadata and compile-time layout checks.
- [x] Add `armeabi-v7a` to Android/Meson/Zig target selection and packaging.
- [x] Add focused runtime/build coverage for 32-bit pointer-width behavior.
- [x] Update Android/build documentation and validation records.
- [x] Run syntax checks and the available cross-build validations.

## Decision Log

- The 64-bit NaN-box is retained because its payload is already a 47-bit heap
  address and remains valid for 32-bit pointers.
- 32-bit ICs store prototype identity and primitive negative-cache shape in
  dedicated fields instead of `uintptr_t` bit packing.
- 32-bit ARM uses the interpreter (`jit=false`) because the existing MIR JIT
  emits 64-bit register operations and has no ARM32 backend contract.

## Validation Status

The ARMv7 Android build has completed through the JNI shared-library and AAR
packaging stages with NDK 29.0.14206865, Zig 0.16.0, and minSdk 24. The
published native library was verified as ELF32, `Machine: ARM`, and its
`libpkg.a` includes the target compiler-rt helpers required by Zig's
soft-float/quad-precision code. The final AAR was also consumed by the Demo's
isolated Gradle build, which produced a debug APK containing
`lib/armeabi-v7a/libant_android.so`.

No Android device or emulator was connected during validation, so launch, GC,
property IC, npm installation, storage, and lifecycle behavior remain pending
on an actual 32-bit ARM runtime.

## Follow-ups

- Consider adding an ARM32 MIR backend only if 32-bit JIT performance becomes a
  product requirement.
