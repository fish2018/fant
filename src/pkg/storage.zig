const std = @import("std");
const io = std.Io.Threaded.global_single_threaded.io();

const c = @cImport({
  @cInclude("c_bridge.h");
});

pub const Context = c.ant_storage_context_t;

pub const Error = error{
  OutOfMemory,
  InvalidArgument,
  NotFound,
  PermissionDenied,
  IoError,
  Unsupported,
  Conflict,
};

fn mapError(code: c_int) Error!void {
  if (code == 0) return;
  return switch (code) {
    -1 => error.InvalidArgument,
    -2 => error.NotFound,
    -3 => error.PermissionDenied,
    -5 => error.Unsupported,
    -6 => error.Conflict,
    else => error.IoError,
  };
}

pub fn isVirtual(path: []const u8) bool {
  const z = std.heap.c_allocator.dupeZ(u8, path) catch return false;
  defer std.heap.c_allocator.free(z);
  return c.ant_pkg_storage_is_virtual_path(z.ptr);
}

pub fn virtualPath(
  context: *const Context,
  relative_path: []const u8,
  allocator: std.mem.Allocator,
) ![]u8 {
  const relative_z = try allocator.dupeZ(u8, relative_path);
  defer allocator.free(relative_z);
  const ptr = c.ant_pkg_storage_virtual_path(context, relative_z.ptr) orelse
    return error.IoError;
  defer std.c.free(ptr);
  return allocator.dupe(u8, std.mem.span(ptr));
}

pub fn read(
  context: *const Context,
  path: []const u8,
  allocator: std.mem.Allocator,
) ![]u8 {
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  var data: ?[*]u8 = null;
  var len: usize = 0;
  try mapError(c.ant_pkg_storage_read_file(context, path_z.ptr, &data, &len));
  const ptr = data orelse return error.IoError;
  defer c.ant_pkg_storage_free_data(context, ptr);
  return allocator.dupe(u8, ptr[0..len]);
}

pub fn write(
  context: *const Context,
  path: []const u8,
  data: []const u8,
  truncate: bool,
) !void {
  const allocator = std.heap.c_allocator;
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  try mapError(c.ant_pkg_storage_write_file(
    context,
    path_z.ptr,
    if (data.len == 0) null else data.ptr,
    data.len,
    truncate,
  ));
}

pub fn mkdirs(context: *const Context, path: []const u8) !void {
  const allocator = std.heap.c_allocator;
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  try mapError(c.ant_pkg_storage_mkdirs(context, path_z.ptr));
}

pub const Stat = struct {
  size: u64,
  is_directory: bool,
  exists: bool,
};

pub fn stat(context: *const Context, path: []const u8) !Stat {
  const allocator = std.heap.c_allocator;
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  var size: u64 = 0;
  var is_directory = false;
  var exists = false;
  try mapError(c.ant_pkg_storage_stat(
    context, path_z.ptr, &size, &is_directory, &exists,
  ));
  return .{ .size = size, .is_directory = is_directory, .exists = exists };
}

pub const Entry = struct {
  name: []const u8,
  is_directory: bool,
};

const ListState = struct {
  allocator: std.mem.Allocator,
  entries: *std.ArrayListUnmanaged(Entry),
  failed: bool = false,
};

fn listVisitor(
  name: [*c]const u8,
  is_directory: bool,
  data: ?*anyopaque,
) callconv(.c) bool {
  const state: *ListState = @ptrCast(@alignCast(data.?));
  if (name == null) {
    state.failed = true;
    return false;
  }
  const name_z: [*:0]const u8 = @ptrCast(name);
  const name_slice = std.mem.span(name_z);
  const name_copy = state.allocator.dupe(u8, name_slice) catch {
    state.failed = true;
    return false;
  };
  state.entries.append(state.allocator, .{
    .name = name_copy,
    .is_directory = is_directory,
  }) catch {
    state.allocator.free(name_copy);
    state.failed = true;
    return false;
  };
  return true;
}

pub fn list(
  context: *const Context,
  path: []const u8,
  allocator: std.mem.Allocator,
) ![]Entry {
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  var result = std.ArrayListUnmanaged(Entry).empty;
  var state = ListState{ .allocator = allocator, .entries = &result };
  mapError(c.ant_pkg_storage_list(
    context, path_z.ptr, listVisitor, &state,
  )) catch |err| {
    for (result.items) |entry| allocator.free(entry.name);
    result.deinit(allocator);
    return err;
  };
  if (state.failed) {
    for (result.items) |entry| allocator.free(entry.name);
    result.deinit(allocator);
    return error.OutOfMemory;
  }
  return result.toOwnedSlice(allocator) catch {
    for (result.items) |entry| allocator.free(entry.name);
    result.deinit(allocator);
    return error.IoError;
  };
}

pub fn freeEntries(allocator: std.mem.Allocator, entries: []Entry) void {
  for (entries) |entry| allocator.free(entry.name);
  allocator.free(entries);
}

pub fn remove(context: *const Context, path: []const u8, recursive: bool) !void {
  const allocator = std.heap.c_allocator;
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  try mapError(c.ant_pkg_storage_remove(context, path_z.ptr, recursive));
}

pub fn rename(context: *const Context, from: []const u8, to: []const u8) !void {
  const allocator = std.heap.c_allocator;
  const from_z = try allocator.dupeZ(u8, from);
  defer allocator.free(from_z);
  const to_z = try allocator.dupeZ(u8, to);
  defer allocator.free(to_z);
  try mapError(c.ant_pkg_storage_rename(context, from_z.ptr, to_z.ptr));
}

pub fn copy(context: *const Context, from: []const u8, to: []const u8) !void {
  const allocator = std.heap.c_allocator;
  const from_z = try allocator.dupeZ(u8, from);
  defer allocator.free(from_z);
  const to_z = try allocator.dupeZ(u8, to);
  defer allocator.free(to_z);
  try mapError(c.ant_pkg_storage_copy(context, from_z.ptr, to_z.ptr));
}

pub fn copyBetween(
  source_context: *const Context,
  source_path: []const u8,
  destination_context: *const Context,
  destination_path: []const u8,
) !void {
  const allocator = std.heap.c_allocator;
  const source_z = try allocator.dupeZ(u8, source_path);
  defer allocator.free(source_z);
  const destination_z = try allocator.dupeZ(u8, destination_path);
  defer allocator.free(destination_z);
  try mapError(c.ant_pkg_storage_copy_between(
    source_context, source_z.ptr, destination_context, destination_z.ptr,
  ));
}

pub fn atomicReplace(context: *const Context, from: []const u8, to: []const u8) !void {
  const allocator = std.heap.c_allocator;
  const from_z = try allocator.dupeZ(u8, from);
  defer allocator.free(from_z);
  const to_z = try allocator.dupeZ(u8, to);
  defer allocator.free(to_z);
  try mapError(c.ant_pkg_storage_atomic_replace(context, from_z.ptr, to_z.ptr));
}

pub fn join(allocator: std.mem.Allocator, base: []const u8, child: []const u8) ![]u8 {
  if (base.len == 0) return allocator.dupe(u8, child);
  if (child.len == 0) return allocator.dupe(u8, base);
  if (base[base.len - 1] == '/') {
    return std.fmt.allocPrint(allocator, "{s}{s}", .{ base, child });
  }
  return std.fmt.allocPrint(allocator, "{s}/{s}", .{ base, child });
}

pub fn isPortableContext(context: ?*anyopaque) bool {
  return context != null;
}

pub fn kind(context: *const Context) c_int {
  return c.ant_pkg_storage_kind(context);
}

pub fn relativePath(
  context: *const Context,
  path: []const u8,
  allocator: std.mem.Allocator,
) ![]u8 {
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  const relative = c.ant_pkg_storage_relative_path(context, path_z.ptr) orelse
    return error.InvalidArgument;
  defer std.c.free(relative);
  return allocator.dupe(u8, std.mem.span(relative));
}

pub fn fromOpaque(context: ?*anyopaque) ?*const Context {
  const ptr = context orelse return null;
  return @ptrCast(@alignCast(ptr));
}

pub fn lock(context: *const Context, path: []const u8) !u64 {
  const allocator = std.heap.c_allocator;
  const path_z = try allocator.dupeZ(u8, path);
  defer allocator.free(path_z);
  var token: u64 = 0;
  try mapError(c.ant_pkg_storage_lock(context, path_z.ptr, &token));
  return token;
}

pub fn unlock(context: *const Context, token: u64) void {
  c.ant_pkg_storage_unlock(context, token);
}

fn temporarySibling(
  allocator: std.mem.Allocator,
  path: []const u8,
) ![]u8 {
  const nonce = std.Io.Timestamp.now(io, .boot).toNanoseconds();
  const slash = std.mem.lastIndexOfScalar(u8, path, '/');
  if (slash) |index| {
    return std.fmt.allocPrint(
      allocator,
      "{s}/.{s}.ant-tmp-{d}",
      .{ path[0..index], path[index + 1 ..], nonce },
    );
  }
  return std.fmt.allocPrint(allocator, ".{s}.ant-tmp-{d}", .{ path, nonce });
}

pub fn atomicWrite(
  context: *const Context,
  path: []const u8,
  data: []const u8,
  allocator: std.mem.Allocator,
) !void {
  const token = try lock(context, path);
  defer unlock(context, token);

  const temporary = try temporarySibling(allocator, path);
  defer allocator.free(temporary);
  remove(context, temporary, true) catch {};
  errdefer remove(context, temporary, true) catch {};

  try write(context, temporary, data, true);
  try atomicReplace(context, temporary, path);
}

pub fn copyTree(
  source_context: *const Context,
  source_path: []const u8,
  destination_context: *const Context,
  destination_path: []const u8,
  allocator: std.mem.Allocator,
) !void {
  if (source_context != destination_context) {
    return copyBetween(
      source_context, source_path, destination_context, destination_path,
    );
  }
  const info = try stat(source_context, source_path);
  if (!info.exists) return error.NotFound;

  if (!info.is_directory) {
    const data = try read(source_context, source_path, allocator);
    defer allocator.free(data);
    try write(destination_context, destination_path, data, true);
    return;
  }

  try mkdirs(destination_context, destination_path);
  const entries = try list(source_context, source_path, allocator);
  defer freeEntries(allocator, entries);
  for (entries) |entry| {
    const child_source = try join(allocator, source_path, entry.name);
    defer allocator.free(child_source);
    const child_destination = try join(allocator, destination_path, entry.name);
    defer allocator.free(child_destination);
    try copyTree(
      source_context,
      child_source,
      destination_context,
      child_destination,
      allocator,
    );
  }
}

pub fn countFiles(
  context: *const Context,
  path: []const u8,
  allocator: std.mem.Allocator,
) !u32 {
  const info = try stat(context, path);
  if (!info.exists) return 0;
  if (!info.is_directory) return 1;

  var total: u32 = 0;
  const entries = try list(context, path, allocator);
  defer freeEntries(allocator, entries);
  for (entries) |entry| {
    const child = try join(allocator, path, entry.name);
    defer allocator.free(child);
    total +|= try countFiles(context, child, allocator);
  }
  return total;
}

pub fn treeSize(
  context: *const Context,
  path: []const u8,
  allocator: std.mem.Allocator,
) !u64 {
  const info = try stat(context, path);
  if (!info.exists) return 0;
  if (!info.is_directory) return info.size;

  var total: u64 = 0;
  const entries = try list(context, path, allocator);
  defer freeEntries(allocator, entries);
  for (entries) |entry| {
    const child = try join(allocator, path, entry.name);
    defer allocator.free(child);
    total +|= try treeSize(context, child, allocator);
  }
  return total;
}
