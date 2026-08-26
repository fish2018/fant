const std = @import("std");

fn darwinMinVersion(os_tag: ?std.Target.Os.Tag) ?std.Target.Query.OsVersion {
  const tag = os_tag orelse return null;
  if (tag != .macos) return null;
  return .{ .semver = .{ .major = 15, .minor = 0, .patch = 0 } };
}

fn parseTargetOs(value: []const u8) ?std.Target.Os.Tag {
  if (std.mem.eql(u8, value, "darwin")) return .macos;
  return std.meta.stringToEnum(std.Target.Os.Tag, value);
}

fn parseTargetAbi(value: []const u8) ?std.Target.Abi {
  if (value.len == 0) return null;
  return std.meta.stringToEnum(std.Target.Abi, value);
}

pub fn build(b: *std.Build) void {
  if (b.graph.environ_map.get("ANDROID_SYSROOT")) |sysroot| {
    b.sysroot = sysroot;
  }
  const resolved_target = blk: {
    const target_str = b.graph.environ_map.get("PKG_TARGET") 
    orelse break :blk b.standardTargetOptions(.{});
    var it = std.mem.splitScalar(u8, target_str, '-');
    
    const cpu_arch = if (it.next()) |a| 
      std.meta.stringToEnum(std.Target.Cpu.Arch, a) else null;

    const os_tag = if (it.next()) |o| parseTargetOs(o) else null;
    const abi = if (it.next()) |a| parseTargetAbi(a) else null;

    std.debug.print("[zig.build] cpu_arch: {?}\n", .{cpu_arch});
    std.debug.print("[zig.build] os_tag: {?}\n", .{os_tag});
    std.debug.print("[zig.build] abi: {?}\n", .{abi});

    const android_api_level = if (b.graph.environ_map.get("PKG_ANDROID_API")) |api|
      std.fmt.parseUnsigned(u32, api, 10) catch null
    else
      null;

    break :blk b.resolveTargetQuery(.{
      .cpu_arch = cpu_arch,
      .os_tag = os_tag,
      .abi = abi,
      .android_api_level = android_api_level,
      .os_version_min = darwinMinVersion(os_tag),
      .cpu_model = .baseline,
    });
  };
  if (resolved_target.result.abi.isAndroid()) {
    std.debug.print("[zig.build] android_api: {d}\n", .{
      resolved_target.result.os.version_range.linux.android,
    });
  }

  const lmdb_include = b.graph.environ_map.get("LMDB_INCLUDE");
  const zlib_include = b.graph.environ_map.get("ZLIB_INCLUDE");
  const libuv_include = b.graph.environ_map.get("LIBUV_INCLUDE");
  const yyjson_include = b.graph.environ_map.get("YYJSON_INCLUDE");

  const lib = b.addLibrary(.{
    .name = "pkg",
    .root_module = b.createModule(.{
      .root_source_file = b.path("root.zig"),
      .target = resolved_target,
      .optimize = .ReleaseSmall,
      .link_libc = true,
      .link_libcpp = true,
      // Android embeds this archive in libant_android.so, so its TLS and
      // other relocations must be position-independent.
      .pic = if (resolved_target.result.abi.isAndroid()) true else null,
      .omit_frame_pointer = true,
      .unwind_tables = .none,
      .strip = true,
    }),
  });

  lib.use_llvm = true;
  if (!resolved_target.result.os.tag.isDarwin()) lib.use_lld = true;
  // Static libraries do not bundle compiler-rt by default. Android embeds
  // libpkg.a with CMake instead of Zig's linker, so keep the target runtime
  // helpers in the archive (notably ARM32 soft-float and 128-bit routines).
  if (resolved_target.result.abi.isAndroid()) lib.bundle_compiler_rt = true;

  lib.root_module.addCSourceFile(.{
    .file = b.path("metadata.c"),
    .flags = &.{ "-O3", "-DNDEBUG" },
  });
  lib.root_module.addCSourceFile(.{
    .file = b.path("c_bridge.c"),
    .flags = &.{ "-O3", "-DNDEBUG" },
  });
  
  const version = b.graph.environ_map.get("ANT_VERSION") orelse "unknown";
  const options = b.addOptions();
  options.addOption([]const u8, "version", version);
  
  lib.root_module.addOptions("config", options);
  lib.root_module.addCMacro("NDEBUG", "1");
  lib.root_module.addCMacro("YYJSON_DISABLE_UTILS", "1");
  if (resolved_target.result.abi.isAndroid()) {
    if (b.graph.environ_map.get("ANDROID_SYSROOT")) |sysroot| {
      lib.root_module.addSystemIncludePath(.{
        .cwd_relative = b.pathJoin(&.{ sysroot, "usr/include" }),
      });
      const target_include = switch (resolved_target.result.cpu.arch) {
        .aarch64 => "aarch64-linux-android",
        .arm => "arm-linux-androideabi",
        .x86_64 => "x86_64-linux-android",
        else => null,
      };
      if (target_include) |triple| {
        lib.root_module.addSystemIncludePath(.{
          .cwd_relative = b.pathJoin(&.{ sysroot, "usr/include", triple }),
        });
      }
    }
  }
  lib.root_module.addIncludePath(b.path("."));
  if (b.graph.environ_map.get("ANT_INCLUDE")) |include_path| {
    lib.root_module.addIncludePath(.{ .cwd_relative = include_path });
  } else {
    lib.root_module.addIncludePath(b.path("../../include"));
  }

  if (lmdb_include) |p| lib.root_module.addIncludePath(.{ .cwd_relative = p });
  if (zlib_include) |p| lib.root_module.addIncludePath(.{ .cwd_relative = p });
  if (libuv_include) |p| lib.root_module.addIncludePath(.{ .cwd_relative = p });
  if (yyjson_include) |p| lib.root_module.addIncludePath(.{ .cwd_relative = p });

  b.installArtifact(lib);
}
