# Dynamic Property Performance Extras

Status: active
Last reviewed: 2026-07-04
Owner: theMackabu

## Purpose

This file captures related optimization ideas that are adjacent to
[Dynamic Property Performance](dynamic-property-perf.md), but are not the two
primary tracks in that plan.

The main plan focuses on:

- computed-property inline caches for `obj[key]`
- numeric-index storage for plain objects used like sparse arrays or heaps

Those two tracks are the core architectural pieces. The ideas below are
supporting or follow-on optimizations.

## Extra Optimization Buckets

### Key Conversion Fast Paths

Reduce repeated property-key conversion overhead before or alongside larger
storage changes.

Candidate improvements:

- specialize small non-negative integer keys before generic `ToPropertyKey`
- cache canonical string forms for hot numeric keys
- avoid duplicate key string creation between `GET_ELEM` / `PUT_ELEM` and
  proxy dispatch
- preserve symbol and object-key coercion semantics exactly

This can help both plain objects and proxies, but it will not remove the need
for numeric-index object storage when workloads touch many distinct integer
keys.

### Proxy Dispatch Fast Paths

Proxies cannot skip user traps when traps exist, but Ant can reduce the setup
around trap calls.

Candidate improvements:

- cache handler `get` / `set` trap lookups while handler shape is stable
- reuse normalized property keys when entering proxy code
- avoid repeated allocation of short property-key strings where possible
- skip expensive invariant checks only when target shape proves no relevant
  non-configurable property can exist
- keep revoked-proxy and missing-trap semantics unchanged

This is especially relevant to `examples/demo/c/da.ts`, where every `mem[...]`
operation goes through a proxy trap.

### Megamorphic Or Dictionary Mode

For objects with many changing keys, shape transitions and shape-based slot
layouts become less useful.

Candidate improvements:

- detect high-cardinality add/delete patterns
- switch plain objects to dictionary-mode property storage after a threshold
- make deletes cheaper for dictionary-mode objects
- keep own-key order and descriptor behavior consistent with normal objects

This is more general than numeric-index storage. It helps workloads that use
many different string keys, not only integer-like keys.

### Trap-Aware Benchmarks

Keep benchmarks that separate these costs:

- plain numeric computed property access
- plain string computed property access
- pass-through proxy access
- demo-shaped proxy access with `Number(prop)`, `isNaN`, and `Set.has`
- high-cardinality add/delete workloads for dictionary-mode investigation

`tests/bench_numeric_object_proxy.js` already covers the first four categories.
Add a separate dictionary-mode bench if that track moves from idea to active
implementation.

## Priority Notes

For the fake C heap demo:

1. numeric-index object storage
2. proxy dispatch cleanup
3. computed-property inline caches
4. key conversion fast paths where they support the above

For broad JavaScript workloads:

1. computed-property inline caches
2. megamorphic/dictionary mode
3. numeric-index object storage
4. proxy dispatch cleanup

## Decision Log

- 2026-07-04: Keep these ideas separate from the main dynamic-property plan so
  the primary implementation tracks remain easy to reason about.
- 2026-07-04: Treat key conversion and proxy dispatch improvements as
  supporting work, not replacements for computed-property ICs or indexed object
  storage.
