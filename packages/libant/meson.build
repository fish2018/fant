project('libant', ['c', 'cpp'], default_options: [
  'buildtype=release',
  'optimization=3',
  'c_std=gnu23',
  'cpp_std=gnu++23',
  'default_library=static',
  'b_lto=false',
  'b_lto_threads=8',
  'strip=true',
  'warning_level=2'
], subproject_dir: 'vendor')

python = find_program('python3')
fs = import('fs')
is_static = true

use_lto = get_option('lto')
src_root = meson.project_source_root() / '../..'
include = include_directories('../../include')
sandbox_backend_include = include_directories(
  '../../src/sandbox/backends',
  '../../src/sandbox/backends/shared/include',
)

sandbox_shared_sources = files(run_command(
  python, files('meson/sources.py'), src_root / 'sources.json',
  'sandbox_backend:shared', src_root,
  check: true
).stdout().strip().split())

sandbox_backend_sources = []
darwin_hvf_supported = host_machine.system() == 'darwin' and host_machine.cpu_family() == 'aarch64'
if host_machine.system() == 'darwin'
  sandbox_backend_sources += files(run_command(
    python, files('meson/sources.py'), src_root / 'sources.json',
    'sandbox_backend:darwin', src_root,
    check: true
  ).stdout().strip().split())
  if darwin_hvf_supported
    sandbox_backend_sources += files(run_command(
      python, files('meson/sources.py'), src_root / 'sources.json',
      'sandbox_backend:darwin_hvf', src_root,
      check: true
    ).stdout().strip().split())
    sandbox_backend_sources += sandbox_shared_sources
  endif
elif host_machine.system() == 'linux'
  sandbox_backend_sources += files(run_command(
    python, files('meson/sources.py'), src_root / 'sources.json',
    'sandbox_backend:linux', src_root,
    check: true
  ).stdout().strip().split())
  sandbox_backend_sources += sandbox_shared_sources
elif host_machine.system() == 'windows'
  sandbox_backend_sources += files(run_command(
    python, files('meson/sources.py'), src_root / 'sources.json',
    'sandbox_backend:windows', src_root,
    check: true
  ).stdout().strip().split())
endif

subdir('meson')

messages_h = custom_target('messages_h',
  input: '../../src/core/messages.toml',
  output: 'messages.h',
  command: [python, files('../../meson/messages.py'), '@INPUT@'],
  capture: true,
)

theme_h = custom_target('theme_h',
  input: '../../src/highlight/theme.toml',
  output: 'theme.h',
  command: [python, files('../../meson/theme.py'), '@INPUT@'],
  capture: true,
)

source_group = host_machine.system() == 'android' ? 'library_android' : 'library'
lib_sources = files(run_command(
  python, files('meson/sources.py'), src_root / 'sources.json', source_group, src_root,
  check: true
).stdout().strip().split())

libant_core = static_library(
  'ant_core',
  lib_sources + sandbox_backend_sources + [builtin_bundle_h, snapshot_h, messages_h, theme_h],
  include_directories: [include, sandbox_backend_include],
  dependencies: ant_deps,
)

if host_machine.system() == 'android'
pkg_sources = files(
  '../../src/pkg/build.zig',
  '../../src/pkg/cache.zig',
  '../../src/pkg/c_bridge.c',
  '../../src/pkg/c_bridge.h',
  '../../src/pkg/cli.zig',
  '../../src/pkg/debug.zig',
  '../../src/pkg/extractor.zig',
  '../../src/pkg/fetcher.zig',
  '../../src/pkg/intern.zig',
  '../../src/pkg/json.zig',
  '../../src/pkg/linker.zig',
  '../../src/pkg/lockfile.zig',
  '../../src/pkg/metadata.c',
  '../../src/pkg/nghttp2.zig',
  '../../src/pkg/platform.zig',
  '../../src/pkg/process_env.zig',
  '../../src/pkg/resolver.zig',
  '../../src/pkg/root.zig',
  '../../src/pkg/storage.zig',
  '../../src/pkg/tlsuv.zig',
  '../../src/pkg/uv.zig',
)

pkg_zig_dir = src_root / 'src' / 'pkg'
pkg_build_dir = meson.current_build_dir()
pkg_target = (
  ['arm', 'armv7-a'].contains(host_machine.cpu_family())
  ? 'arm-linux-androideabi'
  : host_machine.cpu_family() + '-linux-android'
)
pkg_android_api = get_option('android_api')
android_sysroot = run_command('sh', '-c', 'printf %s "$ANDROID_SYSROOT"', check: true).stdout().strip()
pkg_lib = custom_target(
  'pkg_zig',
  input: pkg_sources,
  output: 'libpkg.a',
  command: [
    'sh', '-c',
    '"$ZIG" build --build-file "$PKG_ZIG_DIR/build.zig" --prefix "$PKG_BUILD_DIR" && cp "$PKG_BUILD_DIR/lib/$PKG_LIB_NAME" "@OUTPUT@"',
  ],
  env: {
    'ZIG': zig.full_path(),
    'ANT_VERSION': ant_version,
    'PKG_ZIG_DIR': pkg_zig_dir,
    'PKG_BUILD_DIR': pkg_build_dir,
    'PKG_TARGET': pkg_target,
    'PKG_ANDROID_API': pkg_android_api,
    'ANDROID_SYSROOT': android_sysroot,
    'LMDB_INCLUDE': subprojects_src / deps_info['lmdb']['dir'] / deps_info['lmdb']['inc'],
    'ZLIB_INCLUDE': subprojects_build / deps_info['zlib-ng']['dir'] / deps_info['zlib-ng']['inc'],
    'LIBUV_INCLUDE': subprojects_src / deps_info['libuv']['dir'] / deps_info['libuv']['inc'],
    'YYJSON_INCLUDE': subprojects_src / deps_info['yyjson']['dir'] / deps_info['yyjson']['inc'],
    'ANT_INCLUDE': src_root / 'include',
    'PKG_LIB_NAME': 'libpkg.a',
    'ZIG_GLOBAL_CACHE_DIR': pkg_build_dir / '.zig-global-cache',
    'ZIG_LOCAL_CACHE_DIR': pkg_build_dir / '.zig-local-cache',
  },
  build_by_default: true,
)
endif

if use_lto
  libant_core_lto = static_library(
    'ant_core_lto',
    lib_sources + sandbox_backend_sources + [builtin_bundle_h, snapshot_h, messages_h, theme_h],
    include_directories: [include, sandbox_backend_include],
    dependencies: ant_deps,
    override_options: ['b_lto=true'],
  )
endif

pkg = import('pkgconfig')

if host_machine.system() == 'darwin'
  pkg_libs = ['-framework Security', '-framework CoreFoundation', '-framework Hypervisor', '-lpthread']
elif host_machine.system() == 'linux'
  pkg_libs = ['-lpthread', '-ldl', '-lm']
elif host_machine.system() == 'android'
  pkg_libs = ['-llog', '-landroid', '-ldl', '-lm', '-lz', '-latomic', '-pthread']
elif host_machine.system() == 'windows'
  pkg_libs = ['-lws2_32', '-lrpcrt4', '-lsecur32', '-lntdll', '-lcrypt32', '-luserenv']
else
  pkg_libs = ['-lpthread']
endif

pkg.generate(
  name: 'libant',
  version: ant_version,
  description: 'javascript for 🐜\'s',
  extra_cflags: ['-I${includedir}/ant'],
  libraries: ['-L${libdir}', '-lant'] + pkg_libs
)

custom_target(
  'ant_header',
  output: 'libant.h',
  command: [
    'bash', meson.project_source_root() / 'scripts' / 'header.sh',
    '@OUTPUT@', ant_version, timestamp, git_hash, git_longhash, target_triple,
  ],
  build_by_default: true,
  build_always_stale: true
)
