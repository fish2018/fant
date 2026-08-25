const std = @import("std");

const bridge = @cImport({
  @cInclude("c_bridge.h");
});

pub const MutableValue = bridge.ant_pkg_json_mut_value;

pub const JsonError = error{
  ParseError,
  OutOfMemory,
  InvalidType,
  KeyNotFound,
  IoError,
};

pub const JsonDoc = struct {
  doc: *bridge.ant_pkg_json_doc,

  pub fn parse(data: []const u8) !JsonDoc {
    const doc = bridge.ant_pkg_json_read(data.ptr, data.len);
    if (doc == null) return error.ParseError;
    return JsonDoc{ .doc = doc.? };
  }

  pub fn parseFile(path: [:0]const u8) !JsonDoc {
    const doc = bridge.ant_pkg_json_read_file(path.ptr);
    if (doc == null) return error.ParseError;
    return JsonDoc{ .doc = doc.? };
  }

  pub fn deinit(self: *JsonDoc) void {
    bridge.ant_pkg_json_doc_free(self.doc);
  }

  pub fn root(self: *JsonDoc) JsonValue {
    return JsonValue{ .val = bridge.ant_pkg_json_doc_root(self.doc).? };
  }
};

pub const JsonValue = struct {
  val: *bridge.ant_pkg_json_value,

  pub fn getString(self: JsonValue, key: [:0]const u8) ?[]const u8 {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_string(obj)) return null;
    var len: usize = 0;
    const ptr = bridge.ant_pkg_json_get_string(obj, &len) orelse return null;
    return ptr[0..len];
  }

  pub fn getInt(self: JsonValue, key: [:0]const u8) ?i64 {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_int(obj)) return null;
    return bridge.ant_pkg_json_get_sint(obj);
  }

  pub fn getUint(self: JsonValue, key: [:0]const u8) ?u64 {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_uint(obj)) return null;
    return bridge.ant_pkg_json_get_uint(obj);
  }

  pub fn getDouble(self: JsonValue, key: [:0]const u8) ?f64 {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_real(obj)) return null;
    return bridge.ant_pkg_json_get_real(obj);
  }

  pub fn getBool(self: JsonValue, key: [:0]const u8) ?bool {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_bool(obj)) return null;
    return bridge.ant_pkg_json_get_bool(obj);
  }

  pub fn getObject(self: JsonValue, key: [:0]const u8) ?JsonValue {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_object(obj)) return null;
    return JsonValue{ .val = obj };
  }

  pub fn getArray(self: JsonValue, key: [:0]const u8) ?JsonValue {
    const obj = bridge.ant_pkg_json_object_get(self.val, key.ptr) orelse return null;
    if (!bridge.ant_pkg_json_is_array(obj)) return null;
    return JsonValue{ .val = obj };
  }

  pub fn isNull(self: JsonValue) bool {
    return bridge.ant_pkg_json_is_null(self.val);
  }

  pub fn isArray(self: JsonValue) bool {
    return bridge.ant_pkg_json_is_array(self.val);
  }

  pub fn isObject(self: JsonValue) bool {
    return bridge.ant_pkg_json_is_object(self.val);
  }

  pub fn arrayLen(self: JsonValue) usize {
    return bridge.ant_pkg_json_array_size(self.val);
  }

  pub fn arrayGet(self: JsonValue, index: usize) ?JsonValue {
    const elem = bridge.ant_pkg_json_array_get(self.val, index) orelse return null;
    return JsonValue{ .val = elem };
  }

  pub fn asString(self: JsonValue) ?[]const u8 {
    if (!bridge.ant_pkg_json_is_string(self.val)) return null;
    var len: usize = 0;
    const ptr = bridge.ant_pkg_json_get_string(self.val, &len) orelse return null;
    return ptr[0..len];
  }

  pub const ObjectIterator = struct {
    iter: bridge.ant_pkg_json_iter,

    pub fn next(self: *ObjectIterator) ?struct { key: []const u8, value: JsonValue } {
      var key_ptr: [*c]const u8 = null;
      var key_len: usize = 0;
      var val: ?*bridge.ant_pkg_json_value = null;
      if (!bridge.ant_pkg_json_object_iter_next(
        &self.iter, &key_ptr, &key_len, &val,
      )) return null;
      const value = val orelse return null;

      return .{
        .key = key_ptr[0..key_len],
        .value = JsonValue{ .val = value },
      };
    }

    pub fn deinit(_: *ObjectIterator) void {}
  };

  pub fn objectIterator(self: JsonValue) ?ObjectIterator {
    if (!bridge.ant_pkg_json_is_object(self.val)) return null;
    var iter: bridge.ant_pkg_json_iter = undefined;
    if (!bridge.ant_pkg_json_object_iter_init(self.val, &iter)) return null;
    return ObjectIterator{ .iter = iter };
  }

  pub const ArrayIterator = struct {
    iter: bridge.ant_pkg_json_iter,

    pub fn next(self: *ArrayIterator) ?JsonValue {
      const val = bridge.ant_pkg_json_array_iter_next(&self.iter) orelse return null;
      return JsonValue{ .val = val };
    }

    pub fn deinit(_: *ArrayIterator) void {}
  };

  pub fn arrayIterator(self: JsonValue) ?ArrayIterator {
    if (!bridge.ant_pkg_json_is_array(self.val)) return null;
    var iter: bridge.ant_pkg_json_iter = undefined;
    if (!bridge.ant_pkg_json_array_iter_init(self.val, &iter)) return null;
    return ArrayIterator{ .iter = iter };
  }
};

pub const JsonWriter = struct {
  doc: *bridge.ant_pkg_json_mut_doc,

  pub fn init() !JsonWriter {
    const doc = bridge.ant_pkg_json_mut_doc_new();
    if (doc == null) return error.OutOfMemory;
    return JsonWriter{ .doc = doc.? };
  }

  pub fn deinit(self: *JsonWriter) void {
    bridge.ant_pkg_json_mut_doc_free(self.doc);
  }

  pub fn createObject(self: *JsonWriter) *MutableValue {
    return bridge.ant_pkg_json_mut_object(self.doc).?;
  }

  pub fn createArray(self: *JsonWriter) *MutableValue {
    return bridge.ant_pkg_json_mut_array(self.doc).?;
  }

  pub fn createString(self: *JsonWriter, str: []const u8) *MutableValue {
    return bridge.ant_pkg_json_mut_string(self.doc, str.ptr, str.len).?;
  }

  pub fn createInt(self: *JsonWriter, val: i64) *MutableValue {
    return bridge.ant_pkg_json_mut_sint(self.doc, val).?;
  }

  pub fn createUint(self: *JsonWriter, val: u64) *MutableValue {
    return bridge.ant_pkg_json_mut_uint(self.doc, val).?;
  }

  pub fn createBool(self: *JsonWriter, val: bool) *MutableValue {
    return bridge.ant_pkg_json_mut_bool(self.doc, val).?;
  }

  pub fn createReal(self: *JsonWriter, val: f64) *MutableValue {
    return bridge.ant_pkg_json_mut_real(self.doc, val).?;
  }

  pub fn createNull(self: *JsonWriter) *MutableValue {
    return bridge.ant_pkg_json_mut_null(self.doc).?;
  }

  pub fn objectAdd(self: *JsonWriter, obj: *MutableValue, key: []const u8, val: *MutableValue) void {
    _ = bridge.ant_pkg_json_mut_object_add(
      self.doc, obj, key.ptr, key.len, val,
    );
  }

  pub fn arrayAppend(_: *JsonWriter, arr: *MutableValue, val: *MutableValue) void {
    _ = bridge.ant_pkg_json_mut_array_append(arr, val);
  }

  pub fn setRoot(self: *JsonWriter, val: *MutableValue) void {
    bridge.ant_pkg_json_mut_doc_set_root(self.doc, val);
  }

  pub fn write(self: *JsonWriter, allocator: std.mem.Allocator) ![]u8 {
    var len: usize = 0;
    const ptr = bridge.ant_pkg_json_mut_write(self.doc, &len);
    if (ptr == null) return error.OutOfMemory;
    defer bridge.ant_pkg_json_string_free(ptr);

    const result = try allocator.alloc(u8, len);
    @memcpy(result, ptr[0..len]);
    return result;
  }

  pub fn writeToFile(self: *JsonWriter, path: [:0]const u8) !void {
    const success = bridge.ant_pkg_json_mut_write_file(path.ptr, self.doc, false);
    if (!success) return error.IoError;
  }

  pub fn writeData(self: *JsonWriter, allocator: std.mem.Allocator) ![]u8 {
    return self.write(allocator);
  }
};

pub fn appendUniqueStringsToArrayFile(
  path: [:0]const u8,
  key: [:0]const u8,
  values: []const []const u8,
) !u32 {
  var doc = try JsonDoc.parseFile(path);
  defer doc.deinit();

  const mutable_doc = bridge.ant_pkg_json_doc_mut_copy(doc.doc) orelse
    return error.OutOfMemory;
  defer bridge.ant_pkg_json_mut_doc_free(mutable_doc);

  const root = bridge.ant_pkg_json_mut_doc_root(mutable_doc) orelse
    return error.InvalidType;
  var array = bridge.ant_pkg_json_mut_object_get(root, key.ptr);
  if (array) |existing| {
    if (!bridge.ant_pkg_json_mut_is_array(existing)) return error.InvalidType;
  } else {
    array = bridge.ant_pkg_json_mut_array(mutable_doc) orelse
      return error.OutOfMemory;
    if (!bridge.ant_pkg_json_mut_object_add(
      mutable_doc, root, key.ptr, key.len, array.?,
    )) return error.OutOfMemory;
  }

  var added: u32 = 0;
  for (values) |candidate| {
    var iter: bridge.ant_pkg_json_iter = undefined;
    if (!bridge.ant_pkg_json_mut_array_iter_init(array.?, &iter))
      return error.InvalidType;

    var exists = false;
    while (bridge.ant_pkg_json_mut_array_iter_next(&iter)) |item| {
      if (!bridge.ant_pkg_json_mut_is_string(item)) continue;
      var length: usize = 0;
      const ptr = bridge.ant_pkg_json_mut_get_string(item, &length) orelse continue;
      if (std.mem.eql(u8, ptr[0..length], candidate)) {
        exists = true;
        break;
      }
    }
    if (exists) continue;

    const value = bridge.ant_pkg_json_mut_string(
      mutable_doc, candidate.ptr, candidate.len,
    ) orelse return error.OutOfMemory;
    if (!bridge.ant_pkg_json_mut_array_append(array.?, value))
      return error.OutOfMemory;
    added += 1;
  }

  if (!bridge.ant_pkg_json_mut_write_file(path.ptr, mutable_doc, true))
    return error.IoError;
  return added;
}

pub const PackageJson = struct {
  name: []const u8,
  version: []const u8,
  dependencies: std.StringHashMap([]const u8),
  dev_dependencies: std.StringHashMap([]const u8),
  peer_dependencies: std.StringHashMap([]const u8),
  optional_dependencies: std.StringHashMap([]const u8),
  trusted_dependencies: std.StringHashMap(void),

  pub fn parse(allocator: std.mem.Allocator, path: [:0]const u8) !PackageJson {
    var doc = try JsonDoc.parseFile(path);
    defer doc.deinit();

    return parseDoc(allocator, &doc);
  }

  pub fn parseData(allocator: std.mem.Allocator, data: []const u8) !PackageJson {
    var doc = try JsonDoc.parse(data);
    defer doc.deinit();

    return parseDoc(allocator, &doc);
  }

  fn parseDoc(allocator: std.mem.Allocator, doc: *JsonDoc) !PackageJson {

    const root_val = doc.root();

    var pkg = PackageJson{
      .name = "",
      .version = "",
      .dependencies = std.StringHashMap([]const u8).init(allocator),
      .dev_dependencies = std.StringHashMap([]const u8).init(allocator),
      .peer_dependencies = std.StringHashMap([]const u8).init(allocator),
      .optional_dependencies = std.StringHashMap([]const u8).init(allocator),
      .trusted_dependencies = std.StringHashMap(void).init(allocator),
    };

    if (root_val.getString("name")) |s| {
      pkg.name = try allocator.dupe(u8, s);
    }

    if (root_val.getString("version")) |s| {
      pkg.version = try allocator.dupe(u8, s);
    }

    try parseDeps(allocator, root_val, "dependencies", &pkg.dependencies);
    try parseDeps(allocator, root_val, "devDependencies", &pkg.dev_dependencies);
    try parseDeps(allocator, root_val, "peerDependencies", &pkg.peer_dependencies);
    try parseDeps(allocator, root_val, "optionalDependencies", &pkg.optional_dependencies);

    if (root_val.getArray("trustedDependencies")) |arr| {
      for (0..arr.arrayLen()) |i| {
        const name = (arr.arrayGet(i) orelse continue).asString() orelse continue;
        try pkg.trusted_dependencies.put(try allocator.dupe(u8, name), {});
      }
    }

    return pkg;
  }

  fn parseDeps(
    allocator: std.mem.Allocator,
    root_val: JsonValue,
    key: [:0]const u8,
    map: *std.StringHashMap([]const u8),
  ) !void {
    if (root_val.getObject(key)) |deps| {
      var iter = deps.objectIterator() orelse return;
      defer iter.deinit();
      while (iter.next()) |entry| {
        const version = entry.value.asString() orelse continue;
        try map.put(try allocator.dupe(u8, entry.key), try allocator.dupe(u8, version));
      }
    }
  }

  pub fn deinit(self: *PackageJson, allocator: std.mem.Allocator) void {
    if (self.name.len > 0) allocator.free(self.name);
    if (self.version.len > 0) allocator.free(self.version);

    var iter = self.dependencies.iterator();
    while (iter.next()) |entry| {
      allocator.free(entry.key_ptr.*);
      allocator.free(entry.value_ptr.*);
    }
    self.dependencies.deinit();

    iter = self.dev_dependencies.iterator();
    while (iter.next()) |entry| {
      allocator.free(entry.key_ptr.*);
      allocator.free(entry.value_ptr.*);
    }
    self.dev_dependencies.deinit();

    iter = self.peer_dependencies.iterator();
    while (iter.next()) |entry| {
      allocator.free(entry.key_ptr.*);
      allocator.free(entry.value_ptr.*);
    }
    self.peer_dependencies.deinit();

    iter = self.optional_dependencies.iterator();
    while (iter.next()) |entry| {
      allocator.free(entry.key_ptr.*);
      allocator.free(entry.value_ptr.*);
    }
    self.optional_dependencies.deinit();

    var trusted_iter = self.trusted_dependencies.keyIterator();
    while (trusted_iter.next()) |key| {
      allocator.free(key.*);
    }
    self.trusted_dependencies.deinit();
  }
};
