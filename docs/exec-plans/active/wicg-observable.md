# WICG Observable Migration

Status: active
Last reviewed: 2026-07-11
Owner: theMackabu

## Goal

Replace Ant's implementation of the withdrawn TC39 Observable proposal with
the current WICG Observable API, including AbortSignal-based cancellation,
conversion from asynchronous sources, standard operators, and
`EventTarget.prototype.when()`.

The target is behavior compatible with the WICG draft and its applicable Web
Platform Tests, not compatibility with RxJS or automatic reactive dependency
tracking.

## Reference Baseline

- WICG specification repository:
  `https://github.com/WICG/observable.git`
- Specification revision: `d74bace7cf80200a01c81cfe20961e29ac7fa3d8`
  (2025-11-21)
- WPT repository revision:
  `ba458f52c8a848bbd9c525ac98fb1f7b30e41dc2`
- WPT directory: `dom/observable/tentative/`

Recheck both revisions before implementation begins. If the draft has moved,
record whether the implementation remains pinned or advances to the newer
revision before changing behavior.

## Existing Implementation

- `src/modules/observable.c` implements the older TC39 constructor,
  `Subscription`, `SubscriptionObserver`, `Observable.of()`,
  `Observable.from()`, and `Symbol.observable` protocol.
- `examples/spec/observable.js` tests the older API and assumes that
  `subscribe()` returns an object with `unsubscribe()` and `closed`.
- `src/modules/abort.c` already provides AbortController, AbortSignal,
  dependent signals, abort listeners, and abort reasons.
- `src/modules/events.c` already provides EventTarget and AbortSignal-aware
  listeners, but does not yet expose `EventTarget.prototype.when()` and its
  event-listener option handling must be checked for `passive` conformance.
- Promise construction/reaction and synchronous/async iterator machinery
  already exist elsewhere in the runtime and should be reused rather than
  reimplemented locally.

## Compatibility Breaks To Make Explicit

The WICG API deliberately differs from the current Ant API:

- `subscribe(observer, { signal })` returns `undefined`, not a Subscription.
- Cancellation is controlled by AbortSignal.
- The producer callback receives a `Subscriber` with `next()`, `error()`,
  `complete()`, `addTeardown()`, `active`, and `signal`.
- Producer callback return values no longer register cleanup; cleanup is
  registered with `addTeardown()`.
- `Observable.of()`, `Symbol.observable`, observer `start()`, Subscription
  objects, and the three-callback `subscribe(next, error, complete)` overload
  are not in the WICG interface.
- Multiple active consumers of one Observable share the active producer until
  the final observer aborts or the producer closes.

Before deleting the older surface, decide whether Ant will make a clean
breaking replacement or provide a temporary compatibility path. Do not mix
the two contracts invisibly because their subscription and teardown semantics
conflict.

## Constraints

- Keep lifecycle, cancellation, GC roots, and EventTarget integration in
  native runtime-owned code.
- Reuse the existing AbortSignal, promise, iterator, error-reporting, and event
  listener helpers. Extend their shared APIs when needed instead of embedding
  duplicate implementations in `observable.c`.
- Preserve synchronous delivery for synchronous sources.
- Preserve microtask timing for Promise and async-iterator sources.
- Handle reentrant completion, error, abort, teardown registration, and nested
  subscription without use-after-free or duplicate teardown.
- Do not implement signals-style automatic capture of variables read in a
  scope; that is outside the Observable proposal.
- Keep the target revision visible in tests and update this plan when following
  later draft changes.

## Task List

### 0. Freeze the contract and testing route

- [ ] Compare the pinned `spec.bs` IDL and algorithms with the latest WICG
      revision and record whether to stay pinned or update.
- [ ] Inventory current Ant users of `Observable`, `Symbol.observable`,
      `Observable.of()`, `subscribe()` return values, and observer `start()`.
- [ ] Decide clean replacement versus an explicitly named/flagged temporary
      compatibility layer.
- [ ] Determine how `dom/observable/tentative/` tests will run under Ant's WPT
      harness and list tests that require Window/Document behavior Ant does not
      expose.
- [ ] Add a small initial regression file for the new IDL shape before changing
      the implementation.

### 1. Replace Subscription with the WICG Subscriber core

- [ ] Define native Subscriber state:
      - ordered internal observers;
      - teardown callback stack;
      - internal AbortController and exposed signal;
      - active flag;
      - safe ownership/GC marking.
- [ ] Implement `Subscriber.next()`, `error()`, `complete()`, `addTeardown()`,
      `active`, and `signal` with brand checks.
- [ ] Implement the shared close operation:
      - set inactive before callbacks;
      - abort the internal signal with the correct reason;
      - execute teardowns once in reverse insertion order;
      - invoke a teardown added after closure immediately;
      - remain safe under reentrant close/abort calls.
- [ ] Route observer callback exceptions through Ant's host exception reporting
      path without turning them into producer errors.
- [ ] Report `subscriber.error(value)` after closure as an unhandled host error;
      ignore late `next()` and `complete()` calls.
- [ ] Store the Observable's active Subscriber weakly and establish GC-safe
      promotion/clearing behavior.
- [ ] Implement shared-producer semantics: a second active subscription joins
      the existing Subscriber rather than rerunning the producer callback.
- [ ] Implement per-observer `{ signal }` cancellation, removing only that
      observer and closing the producer after the final observer leaves.
- [ ] Make an already-aborted input signal prevent producer startup.
- [ ] Change `subscribe()` to return `undefined`.
- [ ] Stop interpreting the producer callback's return value as cleanup.
- [ ] Add focused tests for close ordering, LIFO teardown, reentrancy, shared
      producers, partial cancellation, final cancellation, and GC.

### 2. Remove or isolate the old TC39 surface

- [ ] Remove or isolate Subscription and SubscriptionObserver objects.
- [ ] Remove or isolate `Observable.of()`.
- [ ] Remove or isolate `Symbol.observable` and the corresponding Observable
      protocol conversion.
- [ ] Remove or isolate observer `start()` and callback-triplet subscription.
- [ ] Update `examples/spec/observable.js` so it no longer asserts the old
      contract.
- [ ] Add a migration note for code using returned subscriptions or producer
      return-value cleanup.

### 3. Implement `Observable.from()` conversion

- [ ] Reject primitives instead of coercing them into iterable values.
- [ ] Return an Observable unchanged when it is already a native Observable.
- [ ] Probe conversion protocols in WICG order: async iterable, iterable,
      Promise.
- [ ] Implement synchronous iterable delivery with iterator closing on abort,
      completion, and abrupt iteration failure.
- [ ] Implement Promise fulfillment as `next(value)` followed by `complete()`,
      and rejection as `error(reason)` with correct microtask timing.
- [ ] Implement async-iterator pulling one step at a time through promise
      reactions.
- [ ] Stop pulling and perform AsyncIteratorClose when the subscription signal
      aborts.
- [ ] Cover throwing iterator getters, throwing `next()`, malformed iterator
      results, rejected `next()` promises, and throwing `return()` methods.
- [ ] Adapt and run the pinned `observable-from.any.js` cases.

### 4. Add reusable derived-Observable infrastructure

- [ ] Add an internal observer representation usable by both script observers
      and native operator algorithms.
- [ ] Add a native helper for constructing derived Observables without exposing
      implementation callbacks to JavaScript.
- [ ] Add helpers for subscribing upstream with a dependent AbortSignal and
      propagating downstream closure exactly once.
- [ ] Add consistent callback invocation helpers that pass `(value, index)`,
      report or route exceptions per the operator algorithm, and avoid
      unnecessary argument/property allocation.
- [ ] Test cancellation propagation through two or more chained operators.

### 5. Implement Observable-returning operators

- [ ] `map()` including mapper exceptions and index values.
- [ ] `filter()` including predicate exceptions and index values.
- [ ] `take()` including zero, early completion, and upstream cancellation.
- [ ] `drop()` including zero and amounts larger than the stream.
- [ ] `takeUntil()` including synchronous notifier emission, Promise and
      iterable conversion, notifier errors, and dual-signal cancellation.
- [ ] `flatMap()` including multiple concurrent inner Observables and closure
      only after source and all active inners complete.
- [ ] `switchMap()` including immediate cancellation of the previous inner
      Observable and stale-emission suppression.
- [ ] `inspect()` including subscribe, next, error, complete, and abort hooks.
- [ ] `catch()` including conversion of the callback result and callback
      exceptions.
- [ ] `finally()` for complete, error, and consumer abort without duplicate
      invocation.
- [ ] Adapt and run each pinned operator WPT file as its operator lands.

### 6. Implement Promise-returning operators

- [ ] Add a shared promise/operator subscription helper that aborts upstream
      after an early result or callback failure.
- [ ] `toArray()`.
- [ ] `forEach()`.
- [ ] `every()` with early false resolution.
- [ ] `first()` with empty-stream rejection.
- [ ] `last()` with empty-stream rejection.
- [ ] `find()` with early match resolution.
- [ ] `some()` with early true resolution.
- [ ] `reduce()` with and without an explicit initial value.
- [ ] Verify resolve/reject microtask timing and AbortSignal rejection reasons.
- [ ] Adapt and run each pinned Promise-operator WPT file as it lands.

### 7. Integrate EventTarget

- [ ] Add `EventTarget.prototype.when(type, options)` in `src/modules/events.c`
      using the EventTarget's native listener registry.
- [ ] Keep the EventTarget weakly reachable from the returned Observable where
      required by the WICG lifetime rules.
- [ ] Use the Subscriber's signal to remove the event listener on completion,
      error, or final consumer cancellation.
- [ ] Support `capture` and determine the correct Ant behavior for `passive`.
- [ ] Ensure `when()` does not accidentally inherit `once` behavior.
- [ ] Add tests for repeated subscriptions, shared producer/listener behavior,
      cancellation, dispatch reentrancy, and EventTarget collection.
- [ ] Adapt and run the applicable pinned EventTarget WPT cases.

### 8. Conformance and hardening

- [ ] Track every pinned WPT file as pass, expected skip, harness limitation, or
      known failure with a reason.
- [ ] Run the Observable GC/crashtests and add Ant-specific stress tests for
      weak Subscriber and EventTarget ownership.
- [ ] Exercise nested abort, abort during `next()`, completion during `next()`,
      teardown-added-from-teardown, and observer mutation while dispatching.
- [ ] Exercise long synchronous firehoses and ensure abort can stop production
      without unbounded allocation.
- [ ] Check repeated operator chains for leaked observers, abort listeners,
      promises, and iterator records.
- [ ] Review hot paths for avoidable object creation and repeated property
      lookup after semantics are stable.
- [ ] Update any global/API documentation and compatibility tables.

## Validation

Run focused validation after each phase rather than waiting for the entire API:

- `meson compile -C build`
- `./build/ant examples/spec/run.js observable`
- focused new tests under `tests/`
- applicable tests from pinned `dom/observable/tentative/`

Before finalizing the complete migration:

- `maid preflight`
- any build/spec commands recommended by preflight
- `./build/ant examples/spec/run.js --all`
- the full applicable Observable WPT set
- GC/crashtest coverage under both normal and stress-GC configurations, if
  available

Record skipped Window/Document-only WPT cases explicitly instead of counting
them as implementation passes.

## Milestones

1. Core Subscriber and AbortSignal cancellation pass focused tests.
2. `Observable.from()` passes synchronous, Promise, and async-iterator tests.
3. All Observable-returning operators pass focused tests.
4. All Promise-returning operators pass focused tests.
5. `EventTarget.prototype.when()` passes applicable event tests.
6. Applicable pinned WPT coverage is green and the old API migration is
   documented.

## Decision Log

- 2026-07-11: Use a versioned execution plan because this migration crosses
  Observable, AbortSignal, EventTarget, promises, iterators, GC, and WPT
  integration.
- 2026-07-11: Initial analysis is pinned to WICG revision `d74bace` and WPT
  revision `ba458f52` so later proposal drift is distinguishable from Ant bugs.
- 2026-07-11: Automatic capture of values read in lexical or async scopes is
  explicitly out of scope; WICG Observable is an explicit push-stream API.
- 2026-07-11: Prefer runtime-owned lifecycle and cancellation state over a
  compatibility wrapper around the old Subscription implementation.

## Validation Status

- The WICG repository was cloned and its current IDL and algorithms inspected.
- The existing Ant Observable, AbortSignal, EventTarget, Promise, and iterator
  support was inventoried at a high level.
- No implementation or behavior changes have been made.

## Follow-Ups

- Decide whether the old TC39 API receives a deprecation window.
- Decide whether applicable WPT files are vendored, fetched by the existing WPT
  tooling, or represented by smaller Ant-owned regression tests plus an
  external conformance job.
- Re-estimate remaining work after the core Subscriber milestone; operator work
  depends heavily on how much reusable native subscription infrastructure that
  phase establishes.
