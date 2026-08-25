# Static c-ares Release Portability

Status: completed
Completed: 2026-07-11

## Problem

The macOS release builder resolved `libcares` from Homebrew, so the shipped
`ant` binary referenced `/opt/homebrew/opt/c-ares/lib/libcares.2.dylib` and
failed at startup on machines without that package.

## Decision

- Build the vendored c-ares subproject as a static library with Meson's
  size-optimization level.
- Patch the vendored static build through `c-ares.wrap`, not the extracted
  subproject, so static API symbols use hidden visibility. This prevents Ant's
  required `export_dynamic` setting from exporting and retaining c-ares.
- Reject macOS release binaries that reference libraries outside `/usr/lib`
  and `/System/Library` before artifact upload.

## Size Investigation

Matched local release builds used Clang 21, LTO, PGO, identical Meson options,
and `strip -x`. Commit `094641eb4f25fdf92beb16936aa0ebd12d9dffff`
produced 9,744,864 bytes; current `46da87b6` produced 9,483,840 bytes with the
hidden static c-ares build. The source revision did not cause the reported
half-megabyte increase. Archived Blacksmith artifacts agreed: the `094641eb`
binary was 9,698,272 bytes and the later `8f8451f4` binary was 9,437,728 bytes.

The earlier size report therefore compared builds with different provenance,
configuration, or intermediate state. The static c-ares linker map accounted
for about 52 KiB of live mapped code after visibility hiding.

## Validation

- Purged and downloaded c-ares again to prove the tracked wrap patch applies.
- Reconfigured and compiled the release build successfully.
- Confirmed c-ares compiles with `-Oz` and `-fvisibility=hidden`.
- Confirmed `nm -gU build/ant` exposes no `ares_*` symbols.
- Confirmed `otool -L build/ant` lists only Apple system libraries.
- Resolved an external A record through `dns.promises.resolve` successfully.
- Ran `maid preflight`.
