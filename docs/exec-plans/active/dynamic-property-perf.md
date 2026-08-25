# Dynamic Property Performance

Status: active
Last reviewed: 2026-07-04
Owner: theMackabu

## Goal

Reduce Ant's overhead for computed object property access, especially workloads
that repeatedly use `obj[key]` and plain objects as numeric-index stores.

The motivating repro is `examples/demo/c/da.ts`, where the fake C heap uses a
proxied plain object with numeric addresses:

```ts
mem[p + offset] = value;
const value = mem[p + offset];
```

Focused measurements in `tests/bench_numeric_object_proxy.js` show that Ant is
behind Bun both for plain computed properties and for proxy traps. The largest
semantic hot spots are:

- computed numeric keys on plain objects
- computed string keys on plain objects
- proxy `get`/`set` trap dispatch
- demo-shaped traps that also run `Number(prop)`, `isNaN`, and `Set.has`

## Current Findings

- Static property access such as `obj.x` uses `GET_FIELD` / `PUT_FIELD` and has
  inline-cache support.
- Computed property access such as `obj[key]` uses `GET_ELEM` / `PUT_ELEM`.
  This path currently coerces keys to property keys and then falls into generic
  lookup/set behavior.
- Numeric computed keys on plain objects pay for number-to-string conversion
  because JavaScript object keys are semantically strings or symbols.
- Plain objects used as numeric address spaces create many array-index-like
  string properties instead of using array-style indexed storage.
- Proxy access must still call the user trap when a trap exists, but the
  surrounding work may be reducible: handler trap lookup, key string creation,
  argument setup, and invariant checks.

## Scope

- `src/silver/ops/property.h`
- `src/silver/glue.c`
- `src/ant.c`
- `include/object.h`
- object/shape helpers under `src/gc/`, `src/shapes.c`, and related headers
- focused benchmarks/tests under `tests/`

Avoid changing observable JavaScript semantics for property keys, property
ordering, descriptors, proxies, arrays, or `delete`.

## Optimization Tracks

Related supporting ideas are tracked in
[Dynamic Property Performance Extras](dynamic-property-perf-extras.md).

### Computed-Property Inline Caches

Add IC support for `GET_ELEM` / `PUT_ELEM` to optimize repeated dynamic keys.

This targets code shaped like:

```js
const key = chooseField();
for (...) total += obj[key];
```

Possible cache contents:

- receiver shape
- key identity or normalized property key
- cached slot and holder
- receiver prototype guard for inherited data properties
- miss counters so unstable sites fall back cheaply

This track helps repeated or low-cardinality dynamic keys. It is not enough for
workloads that write thousands of different numeric keys, because a normal
monomorphic or small polymorphic IC will not remember every numeric address.

### Numeric-Index Storage For Plain Objects

Add a separate indexed-property backing store for plain objects that receive
array-index-like keys.

This targets code shaped like:

```js
const heap = {};
heap[0] = value;
heap[1024] = other;
for (let i = 0; i < n; i++) sum += heap[i];
```

The fast path should avoid repeated:

```text
number -> string -> intern string -> shape lookup -> property slot
```

and instead route valid uint32-style keys through indexed storage. This is the
track most likely to help the fake C heap demo.

Required semantic checks:

- `Object.keys` and own-property order must still list integer indices in the
  required order before ordinary string keys.
- `delete obj[i]` must create a hole/missing indexed property, not leave a stale
  value.
- `in`, `hasOwnProperty`, `Reflect.ownKeys`, descriptors, freezing/sealing, and
  non-configurable properties must keep correct behavior.
- Proxies cannot skip traps; indexed storage only applies to the proxy target
  or to non-proxy plain objects.
- Arrays already have dense/sparse element behavior; avoid merging this work
  with array semantics unless deliberately scoped.

## Sequencing

1. Keep `tests/bench_numeric_object_proxy.js` as the tracking benchmark.
   - Compare Bun, installed Ant, and local `./build/ant` while iterating.
   - Keep benchmark output wall-clock only; do not make normal tests assert
     timing thresholds.

2. Add minimal computed-property IC coverage.
   - Start with `GET_ELEM` for string keys on non-exotic plain objects.
   - Extend to `PUT_ELEM` only after get-side behavior is stable.
   - Add correctness tests for accessors, prototype changes, deleted props, and
     changing receiver shapes.

3. Design numeric-index object storage separately.
   - Document object layout changes before implementation.
   - Decide whether indexed entries live in a dense buffer, sparse hash table,
     or hybrid backing store.
   - Keep named shape properties and indexed properties distinct.

4. Implement numeric-index get/set/delete for plain objects.
   - Add fast paths before key stringification in `GET_ELEM` / `PUT_ELEM`.
   - Preserve fallback to generic string-key properties when keys are not valid
     array indices or when descriptors/prototype semantics require it.

5. Integrate enumeration and descriptor semantics.
   - Update own-key collection and property descriptor lookup paths.
   - Add tests for ordering and deletion behavior.

6. Investigate proxy overhead after plain-object paths improve.
   - Cache handler `get`/`set` trap lookups when the handler shape is stable.
   - Avoid duplicate key string creation where possible.
   - Keep invariant checks correct for non-configurable target properties.

7. Validate before landing broad runtime changes.
   - Run focused runtime tests for property/proxy behavior.
   - Run `tests/bench_numeric_object_proxy.js` for perf tracking.
   - Run `maid preflight` and follow any recommended validation.
   - Run relevant spec files or document why targeted validation is sufficient.

## Decision Log

- 2026-07-04: Split the work into two independent tracks. Computed-property ICs
  help repeated dynamic keys, while numeric-index storage targets many distinct
  integer-like keys on plain objects.
- 2026-07-04: Numeric-index storage is not a replacement for computed-property
  ICs. It only covers array-index-like keys; general string dynamic keys still
  need IC work.
- 2026-07-04: Proxy traps cannot be skipped when present. Proxy optimization
  should focus on reducing dispatch/setup overhead while preserving trap calls
  and invariant checks.

## Validation Status

- Added `tests/bench_numeric_object_proxy.js` for plain numeric/string object
  access and proxy numeric/string access.
- Initial local samples showed Ant behind Bun for both plain computed
  properties and proxies, with demo-shaped proxy traps slower still.
- No runtime optimization from this plan has been implemented yet.

## Follow-Ups

- Add a smaller correctness test suite for computed-property edge cases before
  changing `GET_ELEM` / `PUT_ELEM`.
- Decide whether numeric-index plain-object storage should reuse any existing
  dense array helpers or use a new object-specific backing store.
- Revisit the experimental collection inline-key change separately; it did not
  clearly address the dominant property-access bottleneck.
