const std = @import("std");
const storage = @import("storage.zig");
const io = std.Io.Threaded.global_single_threaded.io();

pub const Entry = struct {
  integrity: [64]u8,
  path: []const u8,
  unpacked_size: u64,
  file_count: u32,
  cached_at: i64,
  allocator: ?std.mem.Allocator = null,

  pub fn deinit(self: *Entry) void {
    if (self.allocator) |allocator| allocator.free(self.path);
  }
};

const Record = struct {
  unpacked_size: u64,
  file_count: u32,
  cached_at: i64,
};

pub const PortableCache = struct {
  context: *const storage.Context,
  allocator: std.mem.Allocator,

  pub const METADATA_TTL_SECS: i64 = 24 * 60 * 60;
  pub const Stats = struct {
    entries: usize,
    db_size: usize,
    cache_size: usize,
  };

  pub fn open(context: *const storage.Context) !*PortableCache {
    const allocator = std.heap.c_allocator;
    try storage.mkdirs(context, "packages");
    try storage.mkdirs(context, "entries");
    try storage.mkdirs(context, "names");
    try storage.mkdirs(context, "metadata");

    const self = try allocator.create(PortableCache);
    self.* = .{ .context = context, .allocator = allocator };
    return self;
  }

  pub fn close(self: *PortableCache) void {
    self.allocator.destroy(self);
  }

  fn integrityHex(integrity: *const [64]u8) [128]u8 {
    return std.fmt.bytesToHex(integrity.*, .lower);
  }

  fn packagePath(
    allocator: std.mem.Allocator,
    integrity: *const [64]u8,
  ) ![]u8 {
    const hex = integrityHex(integrity);
    return std.fmt.allocPrint(allocator, "packages/{s}", .{&hex});
  }

  fn entryPath(
    allocator: std.mem.Allocator,
    integrity: *const [64]u8,
  ) ![]u8 {
    const hex = integrityHex(integrity);
    return std.fmt.allocPrint(allocator, "entries/{s}.json", .{&hex});
  }

  fn keyPath(
    allocator: std.mem.Allocator,
    directory: []const u8,
    first: []const u8,
    second: []const u8,
  ) ![]u8 {
    var first_hasher = std.hash.Wyhash.init(0x41_4e_54_31);
    first_hasher.update(first);
    first_hasher.update(&[_]u8{0});
    first_hasher.update(second);
    var second_hasher = std.hash.Wyhash.init(0x41_4e_54_32);
    second_hasher.update(second);
    second_hasher.update(&[_]u8{0});
    second_hasher.update(first);
    return std.fmt.allocPrint(
      allocator,
      "{s}/{x}-{x}.cache",
      .{ directory, first_hasher.final(), second_hasher.final() },
    );
  }

  fn parseNibble(value: u8) ?u8 {
    return switch (value) {
      '0'...'9' => value - '0',
      'a'...'f' => value - 'a' + 10,
      'A'...'F' => value - 'A' + 10,
      else => null,
    };
  }

  fn parseIntegrity(data: []const u8) ?[64]u8 {
    if (data.len != 128) return null;
    var integrity: [64]u8 = undefined;
    for (0..64) |index| {
      const high = parseNibble(data[index * 2]) orelse return null;
      const low = parseNibble(data[index * 2 + 1]) orelse return null;
      integrity[index] = (high << 4) | low;
    }
    return integrity;
  }

  fn readRecord(self: *PortableCache, integrity: *const [64]u8) ?Record {
    const path = entryPath(self.allocator, integrity) catch return null;
    defer self.allocator.free(path);
    const data = storage.read(self.context, path, self.allocator) catch return null;
    defer self.allocator.free(data);
    const parsed = std.json.parseFromSlice(Record, self.allocator, data, .{}) catch return null;
    defer parsed.deinit();
    return parsed.value;
  }

  pub fn lookup(self: *PortableCache, integrity: *const [64]u8) ?Entry {
    const record = self.readRecord(integrity) orelse return null;
    const path = packagePath(self.allocator, integrity) catch return null;
    const info = storage.stat(self.context, path) catch {
      self.allocator.free(path);
      return null;
    };
    if (!info.exists or !info.is_directory) {
      self.allocator.free(path);
      return null;
    }
    return .{
      .integrity = integrity.*,
      .path = path,
      .unpacked_size = record.unpacked_size,
      .file_count = record.file_count,
      .cached_at = record.cached_at,
      .allocator = self.allocator,
    };
  }

  pub fn hasIntegrity(self: *PortableCache, integrity: *const [64]u8) bool {
    var entry = self.lookup(integrity) orelse return false;
    entry.deinit();
    return true;
  }

  pub fn lookupByName(
    self: *PortableCache,
    name: []const u8,
    version: []const u8,
  ) ?Entry {
    const path = keyPath(self.allocator, "names", name, version) catch return null;
    defer self.allocator.free(path);
    const data = storage.read(self.context, path, self.allocator) catch return null;
    defer self.allocator.free(data);
    const integrity = parseIntegrity(data) orelse return null;
    return self.lookup(&integrity);
  }

  pub fn getPackagePath(
    _: *PortableCache,
    integrity: *const [64]u8,
    allocator: std.mem.Allocator,
  ) ![]u8 {
    return packagePath(allocator, integrity);
  }

  pub fn insert(
    self: *PortableCache,
    entry: anytype,
    name: ?[]const u8,
    version: ?[]const u8,
  ) !void {
    const path = try entryPath(self.allocator, &entry.integrity);
    defer self.allocator.free(path);
    const record = try std.fmt.allocPrint(
      self.allocator,
      "{{\"unpacked_size\":{d},\"file_count\":{d},\"cached_at\":{d}}}",
      .{ entry.unpacked_size, entry.file_count, entry.cached_at },
    );
    defer self.allocator.free(record);
    try storage.atomicWrite(self.context, path, record, self.allocator);

    if (name != null and version != null) {
      const name_path = try keyPath(self.allocator, "names", name.?, version.?);
      defer self.allocator.free(name_path);
      const hex = integrityHex(&entry.integrity);
      try storage.atomicWrite(self.context, name_path, &hex, self.allocator);
    }
  }

  pub fn delete(self: *PortableCache, integrity: *const [64]u8) !void {
    const package_path = try packagePath(self.allocator, integrity);
    defer self.allocator.free(package_path);
    const marker_path = try entryPath(self.allocator, integrity);
    defer self.allocator.free(marker_path);
    storage.remove(self.context, package_path, true) catch {};
    storage.remove(self.context, marker_path, false) catch {};

    // Remove name aliases that still point at this package. Leaving these
    // aliases behind makes a physically deleted package look cached on the
    // next lookup and causes repeated failed installs.
    const names = storage.list(self.context, "names", self.allocator) catch return;
    defer storage.freeEntries(self.allocator, names);
    const expected = integrityHex(integrity);
    for (names) |entry| {
      if (entry.is_directory) continue;
      const name_path = storage.join(self.allocator, "names", entry.name) catch continue;
      defer self.allocator.free(name_path);
      const data = storage.read(self.context, name_path, self.allocator) catch continue;
      defer self.allocator.free(data);
      if (std.mem.eql(u8, data, &expected)) {
        storage.remove(self.context, name_path, false) catch {};
      }
    }
  }

  pub fn lookupMetadata(
    self: *PortableCache,
    registry_host: []const u8,
    name: []const u8,
    allocator: std.mem.Allocator,
  ) ?[]u8 {
    const path = keyPath(self.allocator, "metadata", registry_host, name) catch return null;
    defer self.allocator.free(path);
    const data = storage.read(self.context, path, allocator) catch return null;
    errdefer allocator.free(data);
    const newline = std.mem.indexOfScalar(u8, data, '\n') orelse {
      allocator.free(data);
      return null;
    };
    const cached_at = std.fmt.parseInt(i64, data[0..newline], 10) catch {
      allocator.free(data);
      return null;
    };
    const now = std.Io.Timestamp.now(io, .real).toSeconds();
    if (now - cached_at > METADATA_TTL_SECS) {
      allocator.free(data);
      return null;
    }
    const result = allocator.dupe(u8, data[newline + 1 ..]) catch {
      allocator.free(data);
      return null;
    };
    allocator.free(data);
    return result;
  }

  pub fn insertMetadata(
    self: *PortableCache,
    registry_host: []const u8,
    name: []const u8,
    json_data: []const u8,
  ) !void {
    const path = try keyPath(self.allocator, "metadata", registry_host, name);
    defer self.allocator.free(path);
    const now = std.Io.Timestamp.now(io, .real).toSeconds();
    const data = try std.fmt.allocPrint(self.allocator, "{d}\n{s}", .{ now, json_data });
    defer self.allocator.free(data);
    try storage.atomicWrite(self.context, path, data, self.allocator);
  }

  pub fn stats(self: *PortableCache) !Stats {
    const marker_entries = try storage.list(self.context, "entries", self.allocator);
    defer storage.freeEntries(self.allocator, marker_entries);
    const package_size = try storage.treeSize(self.context, "packages", self.allocator);
    const entry_size = try storage.treeSize(self.context, "entries", self.allocator);
    const name_size = try storage.treeSize(self.context, "names", self.allocator);
    const metadata_size = try storage.treeSize(self.context, "metadata", self.allocator);
    return .{
      .entries = marker_entries.len,
      .db_size = @intCast(entry_size + name_size + metadata_size),
      .cache_size = @intCast(package_size),
    };
  }

  pub fn prune(self: *PortableCache, max_age_days: u32) !u32 {
    const now = std.Io.Timestamp.now(io, .real).toSeconds();
    const cutoff = now - @as(i64, max_age_days) * 24 * 60 * 60;
    const entries = try storage.list(self.context, "entries", self.allocator);
    defer storage.freeEntries(self.allocator, entries);
    var removed: u32 = 0;

    for (entries) |entry| {
      if (entry.is_directory or !std.mem.endsWith(u8, entry.name, ".json")) continue;
      const marker_path = try storage.join(self.allocator, "entries", entry.name);
      defer self.allocator.free(marker_path);
      const data = storage.read(self.context, marker_path, self.allocator) catch continue;
      defer self.allocator.free(data);
      const parsed = std.json.parseFromSlice(Record, self.allocator, data, .{}) catch continue;
      defer parsed.deinit();
      if (parsed.value.cached_at >= cutoff) continue;

      const hex = entry.name[0 .. entry.name.len - ".json".len];
      const integrity = parseIntegrity(hex) orelse continue;
      try self.delete(&integrity);
      removed += 1;
    }
    return removed;
  }
};
